package com.lvyoubao.app.ui.common

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 回答朗读（老人/骑行/走路场景解放双眼）。进程级单例。
 * 模型（v0.8.47 秒播流式）：Edge 流式合成——WSS 每到一帧 MP3 立即喂 MediaCodec 解码、
 * PCM 写 AudioTrack，攒够 ~150ms 就起播：首声 ≈ 微软返回首帧的耗时，与文本长度无关
 * （此前整段合成完才开播，长回答要先干等好几秒）。一段一条 WSS 会话，段间严格串行。
 * 流式一次都没出声（连不上/协议失败）才落回文件链：整段合成 → 播放 → 下一段，
 * 再往后 硅基流动 CosyVoice2（配 Key 才启用）→ 系统 TTS；全部失败才报"不可用"，落到 [state] 由界面明示。
 */
object TtsPlayer {

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data object Speaking : State()
        data class Unavailable(val reason: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var streamPlayer: Mp3StreamPlayer? = null
    private var systemTts: TextToSpeech? = null
    private var systemReady = false

    /**
     * 播报代次：每次 speak()/stop() 自增。流水线在出声前后都要核对自己的代次，
     * 对不上就立刻闭嘴——被新播报或手动停止接管的旧流水线（含取消信号被吞、
     * 延迟回调等“僵尸”情况）一律不得再启动任何音频。这是防重叠的硬保证。
     */
    private var generation = 0

    /** 当前正在播的文本：同一段文本重复触发时直接忽略，不再从头重读（防多触发源互相踩踏） */
    private var currentText: String? = null

    /** 硅基流动云端朗读兜底用的 Key（复用语音识别 Key）；由界面层按设置注入，空则跳过该级 */
    @Volatile
    private var cloudKey: String? = null

    /** 界面层在设置变化时注入；Key 只在本机，用于 Edge 失败时的云端合成兜底 */
    fun setCloudKey(key: String?) {
        cloudKey = key?.takeIf { it.isNotBlank() }
    }

    /** 每段的目标长度（字）：常见回答 ≤800 字 = 整篇一段、一次合成一次播完；两家云端引擎单段上限都在此之内 */
    private const val SEGMENT_CHARS = 800
    /** 流式会话单段上限（字）：一段一条 WSS 连接，段越少边界间隙越少；2000 字稳在微软 SSML 8KB 限制内 */
    private const val STREAM_SEGMENT_CHARS = 2000
    /** 单段合成重试次数 */
    private const val SYNTH_ATTEMPTS = 3

    /** 判定"内容字符"时要排除的全角标点（它们码位都在 CJK 区间内） */
    private val FULLWIDTH_PUNCT = "。，、；：！？…—·～「」『』《》〈〉（）【】〔〕“”‘’"

    /** 播放/合成中 → 停止并返回 false；空闲 → 开始并返回 true */
    fun toggle(context: Context, text: String): Boolean {
        return if (_state.value is State.Speaking || _state.value is State.Loading) {
            stop()
            false
        } else {
            speak(context, text)
            true
        }
    }

    fun speak(context: Context, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        // 同一段文本正在播/正在合成时，重复触发直接忽略。
        // 两个页面（提问页/大字模式）同时挂着自动播报、或开关翻转导致重复触发时，
        // 若各自重启一遍，旧句被拦腰打断、新句从头读，听感就是“话赶话”。
        if ((_state.value is State.Loading || _state.value is State.Speaking) && body == currentText) return
        stop()
        val myGen = ++generation
        currentText = body
        _state.value = State.Loading
        job = scope.launch {
            ttsLog("speak 启动（${body.length} 字，流式段 ${STREAM_SEGMENT_CHARS}/文件段 ${SEGMENT_CHARS}）")
            // 流式链路一段上限 2000 字（一条 WSS 会话），比文件链的 800 字少一倍段边界；
            // 常见回答 ≤2000 字 = 一条连接、边收边播一次到底
            val parts = buildParts(body, STREAM_SEGMENT_CHARS)
            if (parts.isEmpty()) {
                if (myGen == generation) _state.value = State.Idle
                return@launch
            }
            val dir = File(context.cacheDir, "tts")
            for (i in parts.indices) {
                // 秒播主链路：流式合成 + 边收边播（首声 ≈ 微软首帧耗时）
                val played = playStreamed(parts[i], myGen)
                // 不能把"被停止的取消信号"当成合成失败吞掉，否则旧流水线会走系统 TTS 兜底叠音
                if (myGen != generation) return@launch
                if (played) continue
                // 流式一次声都没出（连不上/协议失败/收帧为空）：落回文件链，整段合成→播放。
                // 严格串行不交错，杜绝"有的句子先读、有的后读"和拼接处破音。
                val file = synthesizeWithRetry(parts[i], dir)
                if (myGen != generation) return@launch
                if (file == null || file.length() == 0L) {
                    // 两级云端合成（Edge→CosyVoice2）都失败：剩余文本整体交给系统 TTS
                    val reason = buildString {
                        append(EdgeTts.lastFailure ?: "免费朗读未能连上微软服务")
                        append("；")
                        append(
                            CloudTts.lastFailure
                                ?: if (cloudKey == null) "未配置云端兜底（设置里填「语音识别 Key」可启用）" else "云端兜底也失败",
                        )
                    }
                    ttsLog("全链失败原因：$reason")
                    speakWithSystem(context, parts.drop(i).joinToString(""), myGen, reason)
                    return@launch
                }
                val ok = playFileAwait(file, myGen)
                runCatching { file.delete() }
                if (myGen != generation) return@launch
                if (!ok) {
                    speakWithSystem(context, parts.drop(i + 1).joinToString(""), myGen)
                    return@launch
                }
            }
            if (myGen == generation &&
                (_state.value is State.Speaking || _state.value is State.Loading)
            ) {
                _state.value = State.Idle
                currentText = null
            }
        }
    }

    fun stop() {
        // 先升代次再停：任何在途流水线此后发现自己代次对不上，一律闭嘴
        generation++
        job?.cancel()
        job = null
        runCatching { streamPlayer?.abort() }
        streamPlayer = null
        runCatching { player?.let { if (it.isPlaying) it.stop() } }
        runCatching { player?.release() }
        player = null
        runCatching { systemTts?.stop() }
        currentText = null
        if (_state.value !is State.Idle) _state.value = State.Idle
    }

    /**
     * 全文分段：短文就是一整段、一次播完；超过上限的长文按句切大段，播完一段再下一段
     * （严格串行，不预取不并发）。maxChars 默认 800（文件链），流式链传 2000 少一倍段边界。
     * internal 是为了单元测试可直接调用。
     */
    internal fun buildParts(text: String, maxChars: Int = SEGMENT_CHARS): List<String> {
        val sentences = splitForSpeech(text)
        if (sentences.isEmpty()) return emptyList()
        if (sentences.joinToString("").length <= maxChars) return listOf(sentences.joinToString(""))
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > maxChars) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 流式播一段（秒播主链路）：EdgeTts 帧一到就喂 [Mp3StreamPlayer]，解码与 WSS 收帧并发跑。
     * 返回是否解码出过 PCM；false = 一次都没出声，上层落回文件链兜底。
     * 中途断流且已出过声：播到哪算哪，本段视为完成（不回文件链重读造成复读）。
     */
    private suspend fun playStreamed(text: String, gen: Int): Boolean = coroutineScope {
        val t0 = System.currentTimeMillis()
        val player = Mp3StreamPlayer(
            onStarted = {
                if (gen == generation && _state.value is State.Loading) {
                    _state.value = State.Speaking
                    ttsLog("流式起播（距发起 ${System.currentTimeMillis() - t0}ms）")
                }
            },
            isCurrent = { gen == generation },
        )
        streamPlayer = player
        val decoder = async { player.run(gen) }
        val ok = EdgeTts.streamSynthesize(text) { bytes -> player.feed(bytes) }
        player.endInput()
        val sawPcm = decoder.await()
        streamPlayer = null
        ttsLog("流式播段结束：ok=$ok sawPcm=$sawPcm 收帧=${player.receivedBytes}B 用时=${System.currentTimeMillis() - t0}ms")
        sawPcm
    }

    /**
     * 单段合成引擎链：Edge（免费无 Key，主力）→ 硅基流动 CosyVoice2（配了语音识别 Key 才启用，
     * 普通 HTTP 比 WebSocket 抗抖动，Edge 协议再变动时也顶得住）→ 返回 null 交给系统 TTS。
     * 每级自带重试；"播几句就断"多因单引擎中途失败，两级接力后剩余段落不再陪葬。
     * 每次尝试都记日志；全挂时 [EdgeTts.lastFailure]/[CloudTts.lastFailure] 里有具体原因供界面明示。
     */
    private suspend fun synthesizeWithRetry(text: String, dir: File): File? {
        repeat(SYNTH_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(500L * attempt)
            ttsLog("文件链 Edge 尝试 ${attempt + 1}/$SYNTH_ATTEMPTS（${text.length} 字）")
            val file = runCatching { EdgeTts.synthesize(text, dir) }.getOrNull()
            if (file != null && file.length() > 0) {
                ttsLog("文件链 Edge 成功 ${file.length()}B")
                return file
            }
            ttsLog("文件链 Edge 第${attempt + 1}次失败：${EdgeTts.lastFailure ?: "空产物"}")
        }
        val key = cloudKey
        if (key != null) {
            repeat(2) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(400L)
                val file = runCatching { CloudTts.synthesize(text, dir, key) }.getOrNull()
                if (file != null && file.length() > 0) {
                    ttsLog("文件链 CosyVoice 成功 ${file.length()}B")
                    return file
                }
                ttsLog("文件链 CosyVoice 第${attempt + 1}次失败：${CloudTts.lastFailure ?: "未知"}")
            }
        } else {
            ttsLog("未配置语音识别 Key，跳过 CosyVoice 兜底")
        }
        return null
    }

    /** 按句切分：句末标点必断；逗号/顿号超 40 字也断；单段上限约 90 字 */
    private fun splitForSpeech(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            sb.clear()
        }
        for (ch in text) {
            sb.append(ch)
            val len = sb.length
            val isEnd = ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';'
            val isPause = (ch == '，' || ch == ',' || ch == '、' || ch == '：') && len >= 40
            if (isEnd || isPause || len >= 90) flush()
        }
        flush()
        // 过滤纯符号段：全角标点码位也在 0x2E80 以上，不算内容，别为"。，"白跑一次合成
        return out.filter { seg ->
            seg.any { c -> c.isLetterOrDigit() || (c.code > 0x2E80 && c !in FULLWIDTH_PUNCT) }
        }
    }

    /**
     * 播放一个音频文件并挂起直到播完/出错/被取消；返回是否完整播完。
     * 不再依赖 OnCompletionListener——部分 ROM 的播放完成回调不触发，
     * 流水线会永久挂死（"播几句就不播了"的根因之一）；改为轮询 isPlaying，
     * 播完/出错/被新播报接管都能可靠退出，还顺带消掉了强杀播放器时的"破音"。
     */
    private suspend fun playFileAwait(file: File, gen: Int): Boolean {
        return withContext(Dispatchers.Main) {
            // 开播前最后核对一次代次（和 stop()/speak() 同在主线程执行，此检查无竞态窗口）：
            // 已被接管的旧流水线到此为止，一个音都不许出
            if (gen != generation) return@withContext false
            val mp = MediaPlayer()
            player = mp
            var started = false
            try {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                if (gen == generation) {
                    _state.value = State.Speaking
                    mp.start()
                    started = true
                }
            } catch (_: Exception) {
            }
            if (!started) {
                // 创建/预加载失败（或已被接管）：只释放自己这个播放器，不误伤后继流水线
                runCatching { mp.release() }
                if (player === mp) player = null
                return@withContext false
            }
            try {
                while (true) {
                    if (gen != generation) break
                    val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                    if (!playing) break
                    kotlinx.coroutines.delay(150)
                }
            } finally {
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
                if (player === mp) player = null
            }
            gen == generation
        }
    }

    /** 系统 TTS 兜底：初始化（带超时）→ speak → 等待完成。cloudReason=前面两级云端的具体失败原因，随报错透出 */
    private suspend fun speakWithSystem(context: Context, text: String, gen: Int, cloudReason: String? = null) {
        if (gen != generation) return
        ttsLog("落系统 TTS 兜底（${text.length} 字）")
        val engine = ensureSystemTts(context)
        if (engine == null) {
            if (gen == generation) {
                _state.value = State.Unavailable(
                    "朗读不可用：${cloudReason ?: "免费朗读和云端兜底都失败了"}；这台手机也没有可用的语音引擎。请检查网络后重试；可在设置里填「语音识别 Key」启用云端兜底",
                )
            }
            return
        }
        if (gen != generation) {
            runCatching { engine.stop() }
            return
        }
        var speakFailed = false
        withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine { cont ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (gen == generation) _state.value = State.Speaking
                        }

                        override fun onDone(utteranceId: String?) {
                            if (gen == generation) {
                                if (_state.value is State.Speaking) _state.value = State.Idle
                                currentText = null
                            }
                            if (cont.isActive) cont.resume(Unit)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (gen == generation) {
                                if (_state.value is State.Speaking) _state.value = State.Idle
                                currentText = null
                            }
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                    val result = engine.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "lvyoubao_${System.currentTimeMillis()}",
                    )
                    if (result != TextToSpeech.SUCCESS) {
                        speakFailed = true
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation { runCatching { engine.stop() } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        if (gen != generation) return
        if (speakFailed) {
            _state.value = State.Unavailable("这台手机的语音引擎没有响应")
        } else if (_state.value is State.Loading) {
            // 引擎没有回调 onStart（空文本等边界），归位
            _state.value = State.Idle
        }
    }

    private suspend fun ensureSystemTts(context: Context): TextToSpeech? {
        systemTts?.let { return if (systemReady) it else null }
        val engine = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                val created = runCatching {
                    TextToSpeech(context.applicationContext) { status ->
                        if (settled) return@TextToSpeech
                        settled = true
                        if (status == TextToSpeech.SUCCESS) {
                            systemReady = true
                            runCatching { systemTts?.setLanguage(Locale.SIMPLIFIED_CHINESE) }
                            if (cont.isActive) cont.resume(systemTts)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }.getOrNull()
                if (created == null && !settled) {
                    settled = true
                    if (cont.isActive) cont.resume(null)
                }
                systemTts = created
            }
        }
        ttsLog("系统 TTS 引擎：${if (engine != null) "可用" else "不可用（初始化失败或 8 秒超时）"}")
        return engine
    }

    /**
     * 流式播放器（秒播核心，参考实现 MSE 边收边播的原生等价物）：
     * WSS 到来的 MP3 帧经 [feed] 入队，解码循环喂 MediaCodec（audio/mpeg）解出 PCM 写 AudioTrack，
     * 攒够 ~150ms 预滚量就起播（防网络小抖动造成开头顿挫）；EOS 后排空解码器、
     * 等播放头追上写入量才算播完。MediaCodec/AudioTrack 旧构造都是 API 21 方案（minSdk=21 兼容车机）。
     */
    private class Mp3StreamPlayer(
        private val onStarted: () -> Unit,
        private val isCurrent: () -> Boolean, // 播报代次仍有效（未被停止/接管）
    ) {
        private val input = LinkedBlockingQueue<ByteArray>()
        private var codec: MediaCodec? = null
        private var track: AudioTrack? = null
        private var sampleRate = 24_000
        private var channels = 1
        private var writtenFrames = 0L
        private var writtenBytes = 0L
        private var started = false

        /** 累计收到的 MP3 字节数（诊断日志用） */
        var receivedBytes = 0L
            private set

        @Volatile private var aborted = false

        /** OkHttp 回调线程调用：音频帧入队，立即返回 */
        fun feed(bytes: ByteArray) {
            if (!aborted && bytes.isNotEmpty()) {
                receivedBytes += bytes.size
                input.put(bytes)
            }
        }

        /** 输入结束：解码器排空后自然收尾 */
        fun endInput() {
            if (!aborted) input.put(EOS)
        }

        /** 用户停止/被接管：立即静音并解锁阻塞中的 write/dequeue */
        fun abort() {
            if (aborted) return
            aborted = true
            input.clear()
            input.put(EOS)
            runCatching { track?.pause() }
            runCatching { track?.release() }
            runCatching { codec?.stop() }
        }

        /**
         * 解码+播放主循环（挂起直到播完/被接管/中止）。返回是否解码出过 PCM（出过声）。
         * 网络帧不与 MP3 帧对齐、且常大于解码器输入缓冲——放不下的部分记入 [leftover]，
         * 与下一帧拼着喂，绝不静默丢弃（丢了就是吞字/破音）。
         */
        suspend fun run(gen: Int): Boolean {
            var sawPcm = false
            var eosQueued = false
            var drained = false
            var idleAfterEos = 0 // EOS 后解码器一直无输出的空转计数（看门狗：解码器错误卡死时兜底退出）
            var leftover: ByteArray? = null
            try {
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
                codec = c
                c.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 24_000, 1), null, null, 0)
                c.start()
                ttsLog("MediaCodec MP3 解码器就绪")
                val info = MediaCodec.BufferInfo()
                while (!aborted && isCurrent()) {
                    if (!eosQueued) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)
                            val polled = input.poll()
                            val data = leftover ?: polled
                            if (data === EOS) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                                leftover = null
                            } else if (buf != null && data != null) {
                                val cap = buf.capacity()
                                if (data.size <= cap) {
                                    buf.put(data, 0, data.size)
                                    c.queueInputBuffer(inIdx, 0, data.size, 0, 0)
                                    leftover = null
                                } else {
                                    // 放不下：填满缓冲，剩下的与下一帧拼着继续喂
                                    buf.put(data, 0, cap)
                                    c.queueInputBuffer(inIdx, 0, cap, 0, 0)
                                    leftover = data.copyOfRange(cap, data.size)
                                }
                            } else {
                                // 暂无数据：还回空槽，10ms 后再来（leftover 保持待喂）
                                c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        idleAfterEos = 0
                        if (info.size > 0) {
                            ensureTrack(c.outputFormat)
                            val t = track
                            val out = if (t != null) c.getOutputBuffer(outIdx) else null
                            if (t != null && out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val w = t.write(out, info.size, AudioTrack.WRITE_BLOCKING)
                                if (w > 0) {
                                    writtenFrames += w / (2L * channels)
                                    writtenBytes += w
                                    sawPcm = true
                                    if (!started && (writtenBytes >= prerollBytes() || eosQueued)) {
                                        started = true
                                        t.play()
                                        onStarted()
                                    }
                                } else if (w < 0) {
                                    ttsLog("AudioTrack.write 错误码 $w，停止本段")
                                    break
                                }
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
                    } else if (eosQueued) {
                        idleAfterEos++
                        // ~5 秒仍无输出：解码器多半已出错卡死（drained 永远等不到），兜底退出
                        if (idleAfterEos > 300) {
                            ttsLog("解码器 EOS 后持续无输出，看门狗强制收尾")
                            break
                        }
                    }
                    if (eosQueued && drained) break
                }
                // 等播完：播放头追上写入量（部分 ROM 的回调不可靠，与 playFileAwait 同样用轮询）
                val t = track
                if (t != null && started) {
                    while (!aborted && isCurrent() && t.playbackHeadPosition < writtenFrames) {
                        delay(80)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ttsLog("流式解码/播放异常：${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                releaseInternal()
            }
            return sawPcm
        }

        /** 预滚量（字节）：~150ms 的 PCM，起播前攒一点防开头顿挫 */
        private fun prerollBytes(): Long = sampleRate * 2L * channels * 150 / 1000

        private fun ensureTrack(fmt: MediaFormat) {
            if (track != null) return
            runCatching {
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            // 缓冲给足：Edge 下发快于实时，写入端要能吸收突发（64KB ≈ 1.3 秒 @24kHz 单声道）
            val bufSize = maxOf(minBuf * 4, 64_000)
            track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT,
                bufSize, AudioTrack.MODE_STREAM,
            )
        }

        private fun releaseInternal() {
            runCatching { track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() } }
            runCatching { track?.release() }
            track = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }

        companion object {
            /** 空数组哨兵 = 输入流结束（feed 保证不进空数组，不会与真实帧混淆） */
            private val EOS = ByteArray(0)
        }
    }
}
