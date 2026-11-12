package com.lvyoubao.app.ui.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
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
        .connectTimeout(10, TimeUnit.SECONDS)
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
                    noteFailure(t, response, "整段合成")
                    out.delete()
                    if (cont.isActive) cont.resume(null)
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
     * 流式合成（秒播核心）：WSS 每收到一帧 Path:audio 音频立即回调 onAudio，不等整段合成完——
     * 上层边收边解码边播，首声 ≈ 微软返回首帧的耗时，与文本长度无关。
     * 返回 true = turn.end 正常收尾且至少收到过一帧音频；false = 连接/协议失败（上层走回退链）。
     * 失败自动重试一次（403 时钟偏差经 [noteFailure] 校正后重试即生效）；
     * 已收到过音频帧就不重试——重试会从头读一遍，播出去就是复读。
     * onAudio 在 OkHttp 回调线程上执行，必须非阻塞（入队即返回）。
     */
    suspend fun streamSynthesize(text: String, onAudio: (ByteArray) -> Unit): Boolean {
        val body = text.trim().take(STREAM_MAX_CHARS)
        if (body.isEmpty()) return false
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

    private suspend fun streamOnce(body: String, onFirstAudio: (ByteArray) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            var settled = false
            var got = false
            fun settle(value: Boolean) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(value && got)
            }
            val ws = client.newWebSocket(buildRequest(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
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
