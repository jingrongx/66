package com.lvyoubao.app.ui.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 朗读链路诊断日志：写入运行日志（LogStore，设置页可导出），也打 logcat（tag=lvyoubao.tts）。
 * 每一级引擎的成败与原因都记录在案——「为什么不播」不再靠猜。JVM 单测里
 * android.util.Log 不可用，LogStore 内部 runCatching 兜底，println 恒输出。
 */
internal fun ttsLog(msg: String) {
    com.lvyoubao.app.data.LogStore.log("tts", msg)
}

/**
 * Edge 朗读：微软 Edge 浏览器的云端 TTS 接口。免费、无需 Key、零 APK 体积，
 * 中文用晓晓神经语音，效果远好于多数手机自带引擎。失败时上层回退系统 TTS。
 * DRM 令牌算法（v2）见 [EdgeTtsDrm]，已于 2026-11 用参考向量+实网合成验证。
 */
object EdgeTts {

    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val EDGE_VERSION = "2-143.0.3650.75"
    private const val VOICE = "zh-CN-XiaoxiaoNeural"
    private const val MAX_CHARS = 3000
    /** 流式单段上限（字）：2000 汉字 ≈ 6KB SSML，稳在微软 8KB 限制内（对齐 edge-tts 分会话阈值） */
    private const val STREAM_MAX_CHARS = 2000

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** 最近一次合成失败的具体原因（供界面明示「为什么读不出来」），成功后清空 */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 设备时钟 vs 微软服务器的偏差秒数。Sec-MS-GEC 令牌按 5 分钟时间窗校验，
     * 手动对时/时钟漂移的手机偏差一大就次次 403——参照 edge-tts 7.x 的做法，
     * 从失败响应的 Date 头学习偏差（[noteFailure]），后续请求一律用校正后的时间生成令牌。
     */
    @Volatile
    private var clockSkewSeconds = 0L

    private fun nowSec(): Long = System.currentTimeMillis() / 1000 + clockSkewSeconds

    /** 失败记账：把可读原因写进 [lastFailure]；403/401 时顺手校正时钟偏差（下次请求即用新令牌） */
    private fun noteFailure(t: Throwable, response: Response?, where: String) {
        val reason = when {
            response != null && (response.code == 401 || response.code == 403) -> {
                response.header("Date")?.let { dateHeader ->
                    runCatching {
                        val parsed = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            .parse(dateHeader)
                        if (parsed != null) {
                            clockSkewSeconds = parsed.time / 1000 - System.currentTimeMillis() / 1000
                        }
                    }
                }
                "HTTP ${response.code} 微软令牌被拒（已按服务器时间自动校正并重试）"
            }
            t is javax.net.ssl.SSLException ->
                "SSL 握手被断开（当前网络到微软的连接被重置——此网络环境连不上微软朗读，属常见网络拦截）"
            t is java.net.ConnectException ->
                "无法建立连接（此网络环境连不上微软朗读）"
            t is java.net.UnknownHostException ->
                "微软域名解析失败（DNS 异常或无网络）"
            t is java.io.IOException && t.message?.contains("timeout", true) == true ->
                "连接微软服务超时"
            else -> "${t.javaClass.simpleName}：${t.message ?: "未知错误"}"
        }
        lastFailure = reason
        ttsLog("EdgeTts $where 失败：$reason（时钟偏差 ${clockSkewSeconds}s）")
    }

    /** 合成语音写到 cacheDir，返回 mp3 文件；任何失败返回 null（上层走回退） */
    suspend fun synthesize(text: String, cacheDir: File): File? {
        val body = text.trim().take(MAX_CHARS)
        if (body.isEmpty()) return null
        cacheDir.mkdirs()
        val out = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        var audioBytes = 0L
        val result = suspendCancellableCoroutine { cont ->
            val ws = client.newWebSocket(buildRequest(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(configMessage())
                    webSocket.send(ssmlMessage(body))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size <= headerLen + 2) return
                    val header = bytes.substring(2, 2 + headerLen).utf8()
                    if (header.contains("Path:audio")) {
                        val payload = bytes.substring(2 + headerLen).toByteArray()
                        runCatching {
                            out.appendBytes(payload)
                            audioBytes += payload.size
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, "done")
                        if (cont.isActive) cont.resume(if (audioBytes > 0) out else null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // turn.end 之后我们主动 close()，OkHttp 还会补一次 onFailure（关闭噪声）。
                    // 那次如果走到下面删文件，删的就是**已经交给播放器、正在播的那个 mp3**——
                    // 播放器随后 setDataSource failed，听感就是「播到一半少了一句」。
                    // 所以：已 settle 过就只当噪声，既不记账也不删文件。
                    if (!cont.isActive) return
                    out.delete()
                    noteFailure(t, response, "整段合成")
                    cont.resume(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resume(if (audioBytes > 0) out else null)
                }
            })
            cont.invokeOnCancellation {
                runCatching { ws.cancel() }
                out.delete()
            }
        }
        if (result != null) lastFailure = null
        return result
    }

    /**
     * 预热到微软朗读服务的连接（DNS + TCP + TLS）。
     * 唤醒命中那一刻调用：用户还在说问题，握手已经在后台跑完；等首句文本出来时
     * WSS 握手直接复用连接池里的现成连接（实测首帧慢 0.6~0.9 秒主要就慢在这次握手）。
     * 失败无所谓，正式合成时照旧新建连接。
     *
     * **body 必须读完**：OkHttp 只有在响应体被完整读取后才把连接还回池子；
     * 老实现 `execute().close()` 没读就关，连接直接被丢弃——等于只预热了 DNS，
     * 日志里「EdgeTts 流式已连接」每次仍要 800~900ms 就是它。
     */
    fun prewarm() {
        thread(isDaemon = true, name = "tts-prewarm") {
            runCatching {
                val req = Request.Builder()
                    .url("https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.3650.75",
                    )
                    .get()
                    .build()
                // 读完（不是 close）：这个 GET 正常回 404/400 + 一小段 body，读完连接才会回池
                client.newCall(req).execute().use { resp ->
                    runCatching { resp.body?.bytes() }
                    ttsLog("TTS 连接预热完成：HTTP ${resp.code}（连接已回池，等待复用）")
                }
            }
        }
    }

    /**
     * 流式合成（秒播核心）：WSS 每收到一帧 Path:audio 音频立即回调 onAudio，不等整段合成完——
     * 上层边收边解码边播，首声 ≈ 微软返回首帧的耗时，与文本长度无关。
     * 返回 true = turn.end 正常收尾且至少收到过一帧音频；false = 连接/协议失败（上层走回退链）。
     * 失败自动重试一次（403 时钟偏差经 [noteFailure] 校正后重试即生效）；
     * 已收到过音频帧就不重试——重试会从头读一遍，播出去就是复读。
     * onAudio 在 OkHttp 回调线程上执行，必须非阻塞（入队即返回）。
     */

    /**
     * 预连接好的 WebSocket：在「用户刚问完、模型还在出字」的空档里就把 WSS 握手做完，
     * 首句一到直接发 SSML。实测握手 1.0~1.2 秒，这一下能省掉大半。
     *
     * 单条连接只做一轮合成（发完 ssml、收到 turn.end 就关），不做多轮复用——
     * 微软那边对同一连接的第二个 turn 不保证支持，宁可保守。
     */
    private class Prepared(val createdAt: Long) {
        val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        @Volatile var ws: okhttp3.WebSocket? = null
        @Volatile var failed = false
        @Volatile var gotAudio = false
        @Volatile var sink: ((ByteArray) -> Unit)? = null
    }

    /** 复用长连接的开关：出问题可以在设置里一键退回「每句都重新握手」的老链路 */
    @Volatile
    var reuseSocket: Boolean = true

    @Volatile
    private var prepared: Prepared? = null

    private fun preparedListener(p: Prepared) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 只发 config，不发 ssml —— 文字还没到
            runCatching { webSocket.send(configMessage()) }
            p.opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size < 2) return
            val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (bytes.size <= headerLen + 2) return
            val header = bytes.substring(2, 2 + headerLen).utf8()
            if (header.contains("Path:audio")) {
                p.gotAudio = true
                runCatching { p.sink?.invoke(bytes.substring(2 + headerLen).toByteArray()) }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:turn.end")) {
                runCatching { webSocket.close(1000, "done") }
                p.done.complete(true)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            p.failed = true
            p.opened.complete(false)
            p.done.complete(false)
            noteFailure(t, response, "预连接合成")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!p.gotAudio) {
                p.failed = true
                p.opened.complete(false)
            }
            p.done.complete(p.gotAudio)
        }
    }

    /** 预热：先开一条 WebSocket 并发出 config，等首句 */
    fun prewarmSocket() {
        if (!reuseSocket) return
        val cur = prepared
        if (cur != null && !cur.failed && System.currentTimeMillis() - cur.createdAt < 20_000L) return
        val p = Prepared(System.currentTimeMillis())
        prepared = p
        runCatching { p.ws = client.newWebSocket(buildRequest(), preparedListener(p)) }
            .onFailure { p.failed = true; p.opened.complete(false) }
        // 3 秒还没连上就作废，别让首句等它
        kotlin.concurrent.thread(isDaemon = true, name = "tts-warm-watch") {
            Thread.sleep(CONNECT_WATCHDOG_MS + 200)
            if (!p.opened.isCompleted) {
                p.failed = true
                p.opened.complete(false)
                runCatching { p.ws?.cancel() }
                if (prepared === p) prepared = null
            }
        }
    }

    @Volatile
    private var lastWarmAt = 0L

    /**
     * 预热到微软 TTS 主机的连接：模型还在出第一个字的时候把 DNS/TCP/TLS 建好，
     * 等首句切出来直接做 WSS 升级。实测握手 0.9 秒，和模型首字延迟重叠掉能省掉大半。
     */
    fun warmUp() {
        // 真正的加速来自预连接 WebSocket（省 1 秒握手），TLS 预热只当兜底
        prewarmSocket()
        val now = System.currentTimeMillis()
        if (now - lastWarmAt < 60_000L) return
        lastWarmAt = now
        kotlin.concurrent.thread(isDaemon = true, name = "tts-warm") {
            runCatching {
                client.newCall(
                    Request.Builder().url("https://speech.platform.bing.com/").head().build(),
                ).execute().close()
            }
        }
    }

    suspend fun streamSynthesize(text: String, onAudio: (ByteArray) -> Unit): Boolean {
        val body = text.trim().take(STREAM_MAX_CHARS)
        if (body.isEmpty()) return false

        // 先试预连接：命中就直接发 SSML，省掉 1 秒出头的握手
        val p = prepared
        prepared = null
        if (p != null && !p.failed) {
            val opened = kotlinx.coroutines.withTimeoutOrNull(900) { p.opened.await() } == true
            if (opened) {
                p.sink = onAudio
                val sent = runCatching { p.ws?.send(ssmlMessage(body)) }.getOrNull() == true
                if (sent) {
                    val finished = kotlinx.coroutines.withTimeoutOrNull(STREAM_TURN_TIMEOUT_MS) { p.done.await() }
                    if (finished == true && p.gotAudio) {
                        ttsLog("EdgeTts 复用预连接成功（" + body.length + " 字）")
                        lastFailure = null
                        return true
                    }
                    ttsLog("EdgeTts 预连接合成未出音频（got=" + p.gotAudio + "），回落重连")
                }
            }
            runCatching { p.ws?.cancel() }
        }
        var gotAudio = false
        var ok = false
        repeat(2) { attempt ->
            if (attempt > 0 && gotAudio) return@repeat
            if (attempt > 0) ttsLog("EdgeTts 流式重试（第2次，时钟偏差已按服务器时间校正）")
            val once = streamOnce(body) { gotAudio = true; onAudio(it) }
            if (once) {
                ok = true
                return@repeat
            }
            if (gotAudio) ttsLog("EdgeTts 流式中断（已收到过音频帧，不重试防复读）")
        }
        if (ok) lastFailure = null
        return ok && gotAudio
    }

    private val CONNECT_WATCHDOG_MS = 3_000L
    private val STREAM_TURN_TIMEOUT_MS = 12_000L

    private suspend fun streamOnce(body: String, onFirstAudio: (ByteArray) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            var settled = false
            var got = false
            var opened = false
            var sock: WebSocket? = null
            // 握手看门狗：微软那边偶尔会把一次 WSS 握手晾在那儿十秒（实测 8 秒才报 SSL 断开），
            // 期间一个字都不出、用户就干等着。超过 3 秒没 onOpen 直接掐掉，走下一次尝试。
            val watchdog = java.util.Timer(true)
            fun settle(value: Boolean) {
                if (settled) return
                settled = true
                runCatching { watchdog.cancel() }
                if (cont.isActive) cont.resume(value && got)
            }
            val ws = client.newWebSocket(buildRequest(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    ttsLog("EdgeTts 流式已连接，发送 SSML（${body.length} 字）")
                    webSocket.send(configMessage())
                    webSocket.send(ssmlMessage(body))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size <= headerLen + 2) return
                    val header = bytes.substring(2, 2 + headerLen).utf8()
                    if (header.contains("Path:audio")) {
                        if (!got) {
                            got = true
                            ttsLog("EdgeTts 流式收到首帧 ${bytes.size}B")
                        }
                        runCatching { onFirstAudio(bytes.substring(2 + headerLen).toByteArray()) }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, "done")
                        settle(true)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    noteFailure(t, response, "流式合成")
                    settle(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    settle(true)
                }
            })
            sock = ws
            watchdog.schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        if (settled || opened) return
                        ttsLog("EdgeTts 握手超时（3 秒没连上），掐掉重试")
                        runCatching { sock?.cancel() }
                        settle(false)
                    }
                },
                CONNECT_WATCHDOG_MS,
            )
            cont.invokeOnCancellation { runCatching { ws.cancel() } }
        }

    private fun buildRequest(): Request =
        Request.Builder()
            .url(endpoint())
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.3650.75",
            )
            .build()

    private fun endpoint(): String {
        val gec = runCatching {
            EdgeTtsDrm.generateSecMsGecV2(nowSec())
        }.getOrNull()
        return "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_TOKEN" +
            (gec?.let { "&Sec-MS-GEC=$it&Sec-MS-GEC-Version=$EDGE_VERSION" } ?: "")
    }

    private fun timestamp(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'0000", Locale.US).format(Date())

    private fun configMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

    private fun ssmlMessage(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name=\"$VOICE\"><prosody rate='+0%' pitch='+0Hz'>$escaped</prosody></voice></speak>"
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }
}
