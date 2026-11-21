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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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

    /**
     * 整段回答共用的音轨（流水线播报专用）。句与句之间不再「销毁播放器 → 重建播放器」——
     * 旧链每句一个 MediaPlayer，setDataSource+prepare 在这台 MTK 上要 300~600ms，
     * 加上每段 Edge 音频首尾各带一截静音，短句连播时听感就被切成「这，是一个好，东西」。
     */
    private var sink: SpeechSink? = null
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

    /**
     * 朗读链路诊断：逐级实测三级引擎并返回可读报告（设置页「朗读链路诊断」按钮用）。
     * 不依赖播报流程，点一下就知道哪一级通、哪一级挂、挂的原因——一张截图即可定位问题。
     */
    suspend fun diagnose(context: Context, cloudKey: String?): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val testText = "你好，这是旅游宝朗读链路诊断测试。"
        val dir = File(context.cacheDir, "tts")

        // ① 微软流式（秒播主链路）
        val t0 = System.currentTimeMillis()
        var streamBytes = 0L
        val streamOk = runCatching {
            EdgeTts.streamSynthesize(testText) { streamBytes += it.size }
        }.getOrDefault(false)
        lines += if (streamOk) {
            "① 微软流式（秒播）：✅ 正常（收 $streamBytes 字节，耗时 ${System.currentTimeMillis() - t0} 毫秒）"
        } else {
            "① 微软流式（秒播）：❌ ${EdgeTts.lastFailure ?: "失败"}"
        }

        // ② 微软整段文件（兜底第一级）
        val t1 = System.currentTimeMillis()
        val file = runCatching { EdgeTts.synthesize(testText, dir) }.getOrNull()
        lines += if (file != null && file.length() > 0) {
            runCatching { file.delete() }
            "② 微软整段（兜底）：✅ 正常（${file.length()} 字节，耗时 ${System.currentTimeMillis() - t1} 毫秒）"
        } else {
            "② 微软整段（兜底）：❌ ${EdgeTts.lastFailure ?: "失败"}"
        }

        // ③ CosyVoice2 云端兜底（配 Key 才测）
        if (cloudKey.isNullOrBlank()) {
            lines += "③ 云端兜底 CosyVoice：— 未配置语音识别 Key，已跳过（设置里填 Key 可启用）"
        } else {
            val t2 = System.currentTimeMillis()
            val cfile = runCatching { CloudTts.synthesize(testText, dir, cloudKey) }.getOrNull()
            lines += if (cfile != null && cfile.length() > 0) {
                runCatching { cfile.delete() }
                "③ 云端兜底 CosyVoice：✅ 正常（${cfile.length()} 字节，耗时 ${System.currentTimeMillis() - t2} 毫秒）"
            } else {
                "③ 云端兜底 CosyVoice：❌ ${CloudTts.lastFailure ?: "失败"}"
            }
        }

        // ④ 系统语音引擎
        val sysOk = runCatching { systemTtsAvailable(context) }.getOrDefault(false)
        lines += if (sysOk) "④ 系统语音引擎：✅ 可用" else "④ 系统语音引擎：❌ 不可用（这台手机没装语音引擎）"

        val report = lines.joinToString("\n")
        ttsLog("诊断报告 →\n$report")
        report
    }

    /** 系统 TTS 引擎是否可用（初始化一次并缓存） */
    suspend fun systemTtsAvailable(context: Context): Boolean = ensureSystemTts(context) != null

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

    // ───────────────────────── 流水线播报（合成与播放解耦） ─────────────────────────
    //
    // 逐句 speak() 的老链路，每句都要重新建立一次 EdgeTTS 的 WSS 连接（握手约 0.9 秒）
    // 再等首帧（约 0.5 秒），句与句之间就硬生生空出 2~3 秒。改成合成器一路往下把句子
    // 合成为文件、播放器严格按顺序播：合成（约 1.5 秒/句）远快于播放（4~6 秒/句），
    // 队列稳定领先。v0.9.31 起整段回答共用一条 AudioTrack（见 [SpeechSink]），
    // 句间不再重建播放器，段与段真正无缝。

    /** 开一轮流水线播报：打断上一轮并返回新的播放代次 */
    fun startStreamSession(): Int {
        stop()
        val gen = ++generation
        currentText = null
        _state.value = State.Loading
        sink = SpeechSink(
            isCurrent = { gen == generation },
            onStarted = {
                if (gen == generation && _state.value is State.Loading) _state.value = State.Speaking
            },
        )
        return gen
    }

    /** 合成一段文本到临时文件（不播放）。播报失败返回 null，由调用方决定跳过还是兜底 */
    suspend fun synthesizeOnly(text: String, dir: File): File? {
        val body = text.trim()
        if (body.isEmpty()) return null
        return synthesizeWithRetry(body, dir)
    }

    /**
     * 播一个已合成好的文件：解码成 PCM、掐掉首尾静音后**续写进同一个音轨**，
     * 与上一句无缝相接（旧实现每句新建 MediaPlayer，句间固定空出 0.5~1 秒）。
     * 没有流水线会话时（普通 speak() 链路）仍走单文件播放。
     */
    suspend fun playPrepared(file: File, gen: Int): Boolean {
        if (gen != generation) return false
        val s = sink ?: return playFileAwait(file, gen)
        // 解码和阻塞写入都不能占主线程（AudioTrack 缓冲满时 write 会等）
        return withContext(Dispatchers.IO) {
            val clip = decodeMp3File(file)
            if (clip == null) {
                // 解码失败（极少见）：先把共用音轨里排队的音频放完再回退单文件播放，
                // 否则两条音轨会同时出声、叠成一团
                s.awaitDrained()
                return@withContext playFileAwait(file, gen)
            }
            val pcm = trimSilence(clip)
            if (pcm.isEmpty()) {
                ttsLog("整段音频全是静音，已丢弃")
                return@withContext true
            }
            // 段首段尾做几毫秒淡入淡出：句与句是两段独立合成的 PCM，直接首尾相接时波形在
            // 接缝处可能是个陡台阶，放大出来就是一声"啪"——听感上被当成某个字破了。
            applyEdgeFade(pcm, clip.channels, clip.sampleRate, fadeInMs = 4, fadeOutMs = 10)
            s.ensureTrack(clip.sampleRate, clip.channels)
            if (!s.formatMatches(clip.sampleRate, clip.channels)) {
                // 声道数变了：上采样只按声道交错排布，声道对不上会彻底错位（刺耳破音）。
                // 正常不会发生（Edge 固定单声道），真出现就先记清楚，便于定位。
                ttsLog("段声道数与音轨不一致：${clip.channels}ch（轨道 ${s.channelCount}ch），已按轨道格式写入")
            }
            var off = 0
            while (off < pcm.size) {
                if (gen != generation || !s.alive) return@withContext false
                val w = s.writePcm(pcm, off, pcm.size - off, clip.sampleRate)
                if (w <= 0) return@withContext false
                off += w
            }
            // 这一句写完：短句可能没攒到预滚量，兜底起播（否则要等下一句才出声）
            s.ensureStarted()
            s.reportSegment("句")
            true
        }
    }

    /** 等整段回答真正播完（播放头追上写入量）。播放器流水线在收尾时调它 */
    suspend fun awaitPlaybackDone(gen: Int) {
        if (gen != generation) return
        sink?.awaitDrained()
    }

    /**
     * 首句秒播：边收 WSS 音频帧边解码播放，首声 ≈ 微软返回首帧的耗时（约 0.5 秒），
     * 不必等整句合成完（那是每句 1.5~3 秒的整段等待）。
     * 返回 false = 一帧都没解出来（连不上/协议失败），上层退回文件链补这一句。
     */
    suspend fun playStreamedPiece(text: String, gen: Int): Boolean {
        if (gen != generation) return false
        return playStreamed(text, gen)
    }

    /** 该代次是否仍是当前播报：被用户打断/新播报接管后变 false，流水线据此决定是否继续 */
    fun isActive(gen: Int): Boolean = gen == generation

    /** 流水线收尾：只有代次仍然是自己的时候才把状态置回空闲 */
    fun endStreamSession(gen: Int) {
        if (gen == generation) {
            sink?.abort()
            sink = null
            if (_state.value is State.Speaking || _state.value is State.Loading) {
                _state.value = State.Idle
            }
            currentText = null
        }
    }

    fun stop() {
        // 先升代次再停：任何在途流水线此后发现自己代次对不上，一律闭嘴
        generation++
        job?.cancel()
        job = null
        runCatching { sink?.abort() }
        sink = null
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
        val sessionSink = sink
        val player = Mp3StreamPlayer(
            onStarted = {
                if (gen == generation && _state.value is State.Loading) {
                    _state.value = State.Speaking
                    ttsLog("流式起播（距发起 ${System.currentTimeMillis() - t0}ms）")
                }
            },
            isCurrent = { gen == generation },
            sink = sessionSink,
        )
        streamPlayer = player
        // 解码 + 写音轨是阻塞的（缓冲区满时 write 会等），必须离开主线程
        val decoder = async(Dispatchers.IO) { player.run(gen) }
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
     * 流水线会永久挂死（"播几句就不播了"的根因之一）；改为轮询播放头，
     * 播完/出错/被新播报接管都能可靠退出，还顺带消掉了强杀播放器时的"破音"。
     *
     * 起播失败要重试：这台 MTK 设备上 AudioTrack 偶发被占用/焦点切换，第一次 start 抛异常
     * 是常见事；老的实现一次失败就返回 false，上层据此整条流水线 break——听感就是
     * "播完一句突然没声了，后面全哑"。
     * 结束判据也不能看 isPlaying：缓冲/焦点抖动时它会瞬时变 false，一句会被从中间掐断，
     * 同样表现为"播到一半没声音"。改看播放头是否走完，只有连续 1.5 秒不前进才算真卡死。
     */
    private suspend fun playFileAwait(file: File, gen: Int): Boolean {
        return withContext(Dispatchers.Main) {
            // 开播前最后核对一次代次（和 stop()/speak() 同在主线程执行，此检查无竞态窗口）：
            // 已被接管的旧流水线到此为止，一个音都不许出
            if (gen != generation) return@withContext false

            var mp: MediaPlayer? = null
            var started = false
            for (attempt in 0 until 2) {
                if (gen != generation) break
                if (attempt > 0) delay(120)
                val candidate = MediaPlayer()
                mp = candidate
                player = candidate
                started = try {
                    candidate.setDataSource(file.absolutePath)
                    candidate.prepare()
                    if (gen == generation) {
                        _state.value = State.Speaking
                        candidate.start()
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    ttsLog("播放器起播失败（第 ${attempt + 1} 次）：${e.javaClass.simpleName} ${e.message ?: ""}")
                    false
                }
                if (!started) {
                    // 只释放自己这个播放器，不误伤后继流水线
                    runCatching { candidate.release() }
                    if (player === candidate) player = null
                    mp = null
                } else {
                    break
                }
            }
            val active = mp
            if (!started || active == null) return@withContext false

            try {
                var lastPos = -1
                var stuckMs = 0L
                while (true) {
                    if (gen != generation) break
                    val pos = runCatching { active.currentPosition }.getOrDefault(-1)
                    val dur = runCatching { active.duration }.getOrDefault(-1)
                    if (pos < 0) break // 播放器已出错
                    if (dur > 0 && pos >= dur - 30) break // 播到头
                    if (pos == lastPos) {
                        stuckMs += 150
                        if (stuckMs >= 1_500) break // 播放头真的不动了才算卡死
                    } else {
                        lastPos = pos
                        stuckMs = 0
                    }
                    delay(150)
                }
            } finally {
                runCatching { if (active.isPlaying) active.stop() }
                runCatching { active.release() }
                if (player === active) player = null
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

    // ───────────────────── 整段共用一个音轨：句间无缝 ─────────────────────
    //
    // 为什么必须共用：旧链每句话「合成到文件 → new MediaPlayer → setDataSource → prepare → start」，
    // 这台 MTK 上 prepare 就要 300~600ms；再加上 Edge 每段音频首尾自带的 0.2~0.8 秒静音，
    // 一句短话被切成两段播出来就是「这，是一个好，东西」。
    // 现在整段回答只在开头建一条 AudioTrack，后面每句解成 PCM 掐掉首尾静音直接续写，
    // 段与段之间只剩解码耗时（几十毫秒，且被 2 秒的缓冲区完全吃掉）。

    private class PcmClip(val data: ByteArray, val sampleRate: Int, val channels: Int)

    private class SpeechSink(
        private val isCurrent: () -> Boolean,
        private val onStarted: () -> Unit,
    ) {
        @Volatile private var track: AudioTrack? = null
        private var channels = 1
        private var writtenBytes = 0L
        private var started = false

        /** 轨道声道数（判断某一段能不能原样写进去） */
        val channelCount: Int get() = channels

        @Volatile var alive = true
            private set

        private val writtenFrames: Long get() = writtenBytes / (2L * channels)

        /** 上采样跨 buffer 的接续样本（每声道一份）：不接着上一块算，块边界就会出现台阶 */
        private val lastSamples = IntArray(2)

        /** 本段诊断累计：削波 / 突变次数，用来判断破音是"数据本身坏了"还是"播放链路坏了" */
        private var segBytes = 0L
        private var segClip = 0
        private var segJump = 0
        private var segMaxJump = 0

        /**
         * 起播前要攒够的 PCM：300ms。
         * 老实现是「第一次写就 play」，而 MediaCodec 一次只吐 85ms 左右的 PCM——
         * 缓冲里只有几十毫秒就开始放，而 Edge 的 WSS 推流接近实时（实测 4 秒收 3 秒音频），
         * 网络稍抖立刻欠载，听感就是某个字突然「破」了（用户反馈的破音就是这么来的）。
         *
         * 0.9.38 取 450ms 是保守值；实测供给速度约 1.4 倍实时、音轨缓冲 4 秒，
         * 300ms 已能盖住常见抖动，同时把首句出声再往前挪 150ms。
         * 判断有没有退步就看日志这行：`共用音轨起播：预滚 Xms` 之后若出现
         * `流式段统计` 里削波/跳变明显变多，就把它调回 450。
         */
        private val prerollBytes: Long get() = OUT_RATE * 2L * channels * PREROLL_MS / 1000

        /**
         * 建轨：**固定用 48000Hz**（设备 native 输出率），源音频（Edge 固定 24kHz）在写入前
         * 自己线性上采样。
         *
         * 为什么不再直接把 24kHz 交给 AudioTrack：那是让 AudioFlinger 的混音线程替我们重采样，
         * 而这台 MTK 机（Android 8.1）的音频链路在「24kHz 播放 + 同时录音（唤醒词常驻）」
         * 并发时，重采样 + 厂商音效（BesLoudness）会偶发处理出错，听感就是某个字劈开。
         * 自己按整数倍（24k→48k 正好 2 倍）插值，交给系统的永远是它原生的 48kHz，
         * 这一层不确定性就没了。
         */
        fun ensureTrack(rate: Int, ch: Int) {
            if (!alive || track != null) return
            channels = if (ch > 0) ch else 1
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, chMask, AudioFormat.ENCODING_PCM_16BIT)
            // 缓冲 ≈4 秒 @48kHz：解码耗时、合成空档、录音抢麦都靠它吸收
            val bufSize = maxOf(minBuf * 4, 384_000)
            track = runCatching {
                AudioTrack(
                    AudioManager.STREAM_MUSIC, OUT_RATE, chMask, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize, AudioTrack.MODE_STREAM,
                )
            }.getOrNull()
            ttsLog("共用音轨就绪：${OUT_RATE}Hz ${channels}ch 缓冲 ${bufSize}B（源 ${rate}Hz → 线性上采样）")
        }

        /** 这一段能不能原样写进已建的轨道（声道数不一致会彻底错位，直接刺耳破音） */
        fun formatMatches(rate: Int, ch: Int): Boolean =
            track == null || ch == channels

        fun writePcm(buf: java.nio.ByteBuffer, size: Int, srcRate: Int): Int {
            val t = track ?: return -1
            if (!alive) return -1
            val frame = 2 * channels
            val n = (size / frame) * frame // 帧对齐：奇数字节会让后面所有样本高低字节错位
            if (n <= 0) return 0
            val raw = ByteArray(n)
            val pos = buf.position()
            val lim = buf.limit()
            runCatching {
                buf.limit(minOf(lim, pos + n))
                buf.get(raw, 0, n)
            }
            runCatching {
                buf.limit(lim)
                buf.position(pos)
            }
            return push(t, raw, 0, n, srcRate)
        }

        fun writePcm(pcm: ByteArray, offset: Int, len: Int, srcRate: Int): Int {
            val t = track ?: return -1
            if (!alive) return -1
            val frame = 2 * channels
            val n = (len / frame) * frame
            if (n <= 0) return 0
            return push(t, pcm, offset, n, srcRate)
        }

        /** 上采样后整块写进音轨；返回消费掉的**源**字节数（调用方按它推进 offset） */
        private fun push(t: AudioTrack, src: ByteArray, offset: Int, len: Int, srcRate: Int): Int {
            val up = resample(src, offset, len, srcRate)
            var off = 0
            while (off < up.size) {
                if (!alive) return -1
                val w = runCatching {
                    t.write(up, off, up.size - off, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(-1)
                if (w <= 0) return -1
                off += w
            }
            count(up.size)
            return len
        }

        /**
         * 线性上采样到 [OUT_RATE]。24k→48k 是 2 倍：在「上一个样本 → 当前样本」之间插
         * 1 个中点，等效延迟半个源样本（20µs，听不出来），换来的是波形连续、块边界无台阶。
         * 非整数倍（罕见）就原样放行，交给系统重采样——那种情况至少不会因为我们的插值出错。
         */
        private fun resample(src: ByteArray, offset: Int, len: Int, srcRate: Int): ByteArray {
            val frame = 2 * channels
            val ratio = if (srcRate > 0) OUT_RATE / srcRate else 1
            if (ratio <= 1 || ratio * srcRate != OUT_RATE) {
                return if (offset == 0 && len == src.size) src else src.copyOfRange(offset, offset + len)
            }
            val srcFrames = len / frame
            val dst = ByteArray(srcFrames * ratio * frame)
            var di = 0
            for (f in 0 until srcFrames) {
                val base = offset + f * frame
                var jump = 0
                for (k in 1..ratio) {
                    for (c in 0 until channels) {
                        val x = readS16(src, base + c * 2)
                        val p = lastSamples[c]
                        val v = p + ((x - p) * k) / ratio
                        writeS16(dst, di, v)
                        di += 2
                        if (c == 0) {
                            if (v >= CLIP_LEVEL || v <= -CLIP_LEVEL) segClip++
                            val d = if (v >= p) v - p else p - v
                            if (d > jump) jump = d
                        }
                    }
                }
                for (c in 0 until channels) lastSamples[c] = readS16(src, base + c * 2)
                if (jump > JUMP_LEVEL) {
                    segJump++
                    if (jump > segMaxJump) segMaxJump = jump
                }
            }
            segBytes += dst.size
            return dst
        }

        private fun readS16(a: ByteArray, i: Int): Int =
            ((a[i + 1].toInt() shl 8) or (a[i].toInt() and 0xFF)).toShort().toInt()

        private fun writeS16(a: ByteArray, i: Int, v: Int) {
            val s = if (v > 32767) 32767 else if (v < -32768) -32768 else v
            a[i] = (s and 0xFF).toByte()
            a[i + 1] = ((s shr 8) and 0xFF).toByte()
        }

        /**
         * 打一行本段统计再清零。
         * 削波（贴顶的样本）= 源音频本身爆了；跳变（相邻样本差 > 12000 ≈ 满量程 37%）
         * = 波形断裂。两项都接近 0 却仍听到破音，就说明问题不在数据，在播放链路/音量链。
         */
        fun reportSegment(tag: String) {
            if (segBytes <= 0) return
            val ms = segBytes * 1000 / (2L * channels * OUT_RATE)
            ttsLog("${tag}段统计：${ms}ms 削波=${segClip} 跳变=${segJump}(峰${segMaxJump})")
            segBytes = 0
            segClip = 0
            segJump = 0
            segMaxJump = 0
        }

        private fun count(bytes: Int) {
            writtenBytes += bytes
            // 攒够预滚量才起播；不够就先让它攒着（见 prerollBytes 的说明）
            if (started || writtenBytes < prerollBytes) return
            startNow()
        }

        /** 兜底起播：短句可能永远攒不到预滚量，写完就必须放，否则一直不出声 */
        fun ensureStarted() {
            if (started || writtenBytes <= 0) return
            startNow()
        }

        private fun startNow() {
            if (started) return
            val t = track ?: return
            started = true
            // 记下起播时的预滚量：太小 = 后面容易欠载破音，太大 = 首句变慢，调参全靠这行
            val prerollMs = (writtenBytes * 1000 / (2L * channels * OUT_RATE)).toInt()
            ttsLog("共用音轨起播：预滚 ${prerollMs}ms")
            runCatching { t.play() }
            onStarted()
        }

        /** 等播放头追上写入量（= 整段真的播完）；中断时立刻返回 */
        suspend fun awaitDrained() {
            val t = track ?: return
            ensureStarted()
            if (!started) return
            var lastHead = -1L
            var sameMs = 0L
            while (alive && isCurrent()) {
                val head = runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(writtenFrames)
                if (head >= writtenFrames - 2) break
                if (head == lastHead) {
                    sameMs += 40
                    if (sameMs >= 3_000) break // 播放头 3 秒不动 = 真卡死，别把整轮对话挂住
                } else {
                    lastHead = head
                    sameMs = 0
                }
                delay(40)
            }
        }

        fun abort() {
            if (!alive) return
            alive = false
            val t = track
            track = null
            runCatching { t?.pause() }
            runCatching { t?.flush() }
            runCatching { t?.release() }
        }

        companion object {
            /** 设备 native 输出采样率：AudioFlinger 混音线程固定 48kHz，喂它 48k 就不再重采样 */
            private const val OUT_RATE = 48_000
            private const val PREROLL_MS = 300L
            /** 贴顶（≈-0.3dBFS）算削波 */
            private const val CLIP_LEVEL = 32_000
            /** 相邻样本差超过满量程 37% 算一次波形断裂 */
            private const val JUMP_LEVEL = 12_000
        }
    }

    /** 把一个 MP3 文件整段解码成 PCM（几十毫秒，远快于播放；用于共用音轨链） */
    /**
     * 把一个 MP3 文件整段解码成 PCM（几十毫秒，远快于播放；用于共用音轨链）。
     *
     * v0.9.40 改了两处，都是"解码器吐噪"的直接成因：
     * ①**按 MP3 帧边界切片喂**。老实现用 16KB 任意切块，块边界经常落在帧中间；桌面 ffmpeg
     *   遇到半个帧会自己重新同步，但这台 MTK/Android 8.1 的 audio/mpeg 解码器会就此错位，
     *   后面整段都按错位的字节流乱解。实测同一段 MP3：ffmpeg 解出峰值 18316、零削波，
     *   而这里解出来有 2487 个贴顶样本、峰值 32767 —— 听感就是段中/段末突然"破"一下。
     * ②**`want` 的计算顺序**。老代码先读 `buf.remaining()` 再 `buf.clear()`，而
     *   `getInputBuffer` 返回的是复用对象、position 还停在上次的写入处，remaining 会越来越小，
     *   最后算出 0 → `read(.., 0)` 返回 0 → 被当成 EOF 提前发 EOS。现在先 clear 再算。
     */
    private fun decodeMp3File(file: File): PcmClip? {
        var codec: MediaCodec? = null
        return try {
            val data = file.readBytes()
            if (data.isEmpty()) return null
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            codec = c
            c.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 24_000, 1),
                null, null, 0,
            )
            c.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var rate = 24_000
            var ch = 1
            var pos = skipId3(data)
            var eosQueued = false
            var done = false
            var idleAfterEos = 0
            while (!done) {
                if (!eosQueued) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        if (buf == null || pos >= data.size) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            buf.clear()
                            val cap = buf.remaining()
                            val end = frameAlignedEnd(data, pos, cap)
                            val n = end - pos
                            if (n <= 0) {
                                // 尾部凑不出一个完整帧（填充字节）：直接收尾
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            } else {
                                buf.put(data, pos, n)
                                c.queueInputBuffer(inIdx, 0, n, 0, 0)
                                pos += n
                            }
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    idleAfterEos = 0
                    if (info.size > 0) {
                        runCatching {
                            val fmt = c.outputFormat
                            rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        val b = c.getOutputBuffer(outIdx)
                        if (b != null && info.offset >= 0 && info.offset + info.size <= b.capacity()) {
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            val arr = ByteArray(info.size)
                            b.get(arr)
                            out.write(arr)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                } else if (eosQueued) {
                    idleAfterEos++
                    // ~3 秒仍无输出：解码器卡在坏数据上，别把 IO 线程挂住
                    if (idleAfterEos > 300) {
                        ttsLog("整段解码 EOS 后无输出，强制收尾（${data.size}B）")
                        break
                    }
                }
            }
            val pcm = out.toByteArray()
            if (pcm.isEmpty()) return null
            // 兜底：把解码器可能吐出来的短爆音段补掉（见 repairClipped）
            val fixed = repairClipped(pcm, ch)
            if (fixed > 0) ttsLog("整段解码修补爆音 ${fixed} 个样本（${file.length()}B）")
            PcmClip(pcm, rate, ch)
        } catch (e: Exception) {
            ttsLog("整段解码失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /** 跳过 ID3v2 头（Edge 产物没有，别处来的 mp3 可能带） */
    private fun skipId3(d: ByteArray): Int {
        if (d.size < 10) return 0
        if (d[0] != 'I'.code.toByte() || d[1] != 'D'.code.toByte() || d[2] != '3'.code.toByte()) return 0
        val size = ((d[6].toInt() and 0x7F) shl 21) or ((d[7].toInt() and 0x7F) shl 14) or
            ((d[8].toInt() and 0x7F) shl 7) or (d[9].toInt() and 0x7F)
        return minOf(d.size, 10 + size)
    }

    /**
     * 从 [pos] 起、不超过 [pos]+[cap] 的最大帧边界。
     * 往回最多退 1600 字节（24kHz/48kbps 下每帧 144 字节，够退十来帧）找一个可信帧头；
     * 实在找不到就原样切（让解码器自己重新同步，总比卡死强）。
     */
    private fun frameAlignedEnd(d: ByteArray, pos: Int, cap: Int): Int {
        val limit = minOf(d.size, pos + cap)
        if (limit >= d.size) return d.size
        var p = limit
        val floor = maxOf(pos, limit - 1600)
        while (p > floor) {
            if (looksLikeFrameStart(d, p)) return p
            p--
        }
        return limit
    }

    private fun isMp3FrameHeader(d: ByteArray, i: Int): Boolean {
        if (i + 1 >= d.size) return false
        if ((d[i].toInt() and 0xFF) != 0xFF) return false
        return ((d[i + 1].toInt() and 0xFF) and 0xE0) == 0xE0
    }

    /**
     * 可信帧头：0xFFEx 之外，再往后 144 字节（24kHz/48kbps MPEG2-L3 的固定帧长）也必须是帧头。
     * 只看两字节的话，音频数据内部偶然出现的 0xFFE? 会被误判成帧头，切点反而落到帧中间。
     */
    private fun looksLikeFrameStart(d: ByteArray, i: Int): Boolean {
        if (!isMp3FrameHeader(d, i)) return false
        if (i + 288 >= d.size) return true // 临近文件尾，少一重校验
        return isMp3FrameHeader(d, i + 144) && isMp3FrameHeader(d, i + 288)
    }

    /**
     * 修补解码器吐出的爆音：连续 ≥4 个贴顶样本（|s| ≥ 32000）在正常语音里几乎不可能出现，
     * 是解码器在坏帧上填噪声的典型特征（单个样本偶尔贴顶是正常的强瞬态，不动它）。
     * 用爆音段前后的样本做线性插值填掉，听感从"啪"变得几乎察觉不到。
     * 返回修补的样本数（0 = 没发现问题）。
     */
    private fun repairClipped(pcm: ByteArray, channels: Int): Int {
        if (channels != 1) return 0 // 只处理 Edge 的单声道产物；多声道不该被这样填平
        val frame = 2 * channels
        val frames = pcm.size / frame
        if (frames < 16) return 0
        fun at(i: Int): Int = ((pcm[i * frame + 1].toInt() shl 8) or (pcm[i * frame].toInt() and 0xFF)).toShort().toInt()
        fun put(i: Int, v: Int) {
            val s = if (v > 32767) 32767 else if (v < -32768) -32768 else v
            val base = i * frame
            for (c in 0 until channels) {
                pcm[base + c * 2] = (s and 0xFF).toByte()
                pcm[base + c * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
        }
        var i = 0
        var fixed = 0
        while (i < frames) {
            val v = at(i)
            if (v >= 32000 || v <= -32000) {
                var j = i
                while (j < frames) {
                    val w = at(j)
                    if (w >= 32000 || w <= -32000) j++ else break
                }
                if (j - i >= 4) {
                    val before = if (i > 0) at(i - 1) else 0
                    val after = if (j < frames) at(j) else 0
                    val span = j - i + 1
                    for (k in i until j) {
                        val t = (k - i + 1).toLong() * 1000 / span
                        put(k, (before + ((after - before) * t / 1000)).toInt())
                    }
                    fixed += j - i
                }
                i = j
            } else {
                i++
            }
        }
        return fixed
    }

    /**
     * 掐掉一段音频首尾的静音。Edge 每段合成都带一截首尾静音，短句一句一段地拼起来时，
     * 这些静音就是听感里那些莫名其妙的停顿。首留 100ms（别削掉字头）、尾留 240ms（自然收尾）。
     *
     * 阈值从 500(-36dBFS) 降到 260(-42dBFS)：句首的 s/sh/x/zh 这类弱辅音经常就在 -38~-40dBFS，
     * 老阈值会把它们当底噪削掉，"这是一个好东西"的"这"就成了半个音——用户听成"破音"。
     */
    private fun trimSilence(clip: PcmClip): ByteArray {
        val pcm = clip.data
        val frameBytes = 2 * clip.channels
        val frames = pcm.size / frameBytes
        if (frames < 200) return pcm
        val threshold = 260 // ≈ -42dBFS：比这还轻的才是底噪
        fun ampAt(i: Int): Int {
            var m = 0
            val base = i * frameBytes
            for (c in 0 until clip.channels) {
                val lo = pcm[base + c * 2].toInt() and 0xFF
                val hi = pcm[base + c * 2 + 1].toInt()
                val v = ((hi shl 8) or lo).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > m) m = a
            }
            return m
        }
        var first = 0
        while (first < frames && ampAt(first) < threshold) first++
        if (first >= frames) return ByteArray(0) // 整段静音（合成出错），丢掉别占时间
        var last = frames - 1
        while (last > first && ampAt(last) < threshold) last--
        val lead = clip.sampleRate * 100 / 1000
        val tail = clip.sampleRate * 240 / 1000
        val start = maxOf(0, first - lead)
        val end = minOf(frames, last + tail)
        return pcm.copyOfRange(start * frameBytes, end * frameBytes)
    }

    /**
     * 段首段尾做几毫秒线性淡入淡出。句与句是两次独立合成的 PCM，直接首尾相接时，
     * 接缝两端的波形可能相差很大（一个高电平直接跳到另一个高电平），传进扬声器就是一声
     * "啪"，很容易被听成某个字破了。淡入 4ms / 淡出 10ms 在听感上完全无感，但接缝处
     * 一定从 0 起、到 0 落，波形不会再跳。原地改 [pcm]。
     */
    private fun applyEdgeFade(pcm: ByteArray, channels: Int, sampleRate: Int, fadeInMs: Int, fadeOutMs: Int) {
        val frame = 2 * channels
        val total = pcm.size / frame
        if (total < 16) return
        fun applyFade(from: Int, count: Int, gainAt: (Int) -> Int) {
            if (count <= 0) return
            for (k in 0 until count) {
                val base = (from + k) * frame
                val g = gainAt(k)
                for (c in 0 until channels) {
                    val i = base + c * 2
                    val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                    val s = (v.toLong() * g / 1000).toInt()
                    pcm[i] = (s and 0xFF).toByte()
                    pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
                }
            }
        }
        val inFrames = minOf(total, sampleRate * fadeInMs / 1000)
        applyFade(0, inFrames) { k -> ((k + 1).toLong() * 1000 / inFrames).toInt() }
        val outFrames = minOf(total, sampleRate * fadeOutMs / 1000)
        applyFade(total - outFrames, outFrames) { k -> ((outFrames - k).toLong() * 1000 / outFrames).toInt() }
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
        /** 非空则写进整段共用的那条音轨（流水线播报），句间无缝；空则自己建轨（普通 speak 链路） */
        private val sink: SpeechSink? = null,
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
            var pendingEos = false // 收到 EOS 但 leftover 还没喂完：等喂完再发 EOS
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
                            // 老实现是 `val data = leftover ?: polled`——只要 leftover 非空，
                            // 刚从队列里 poll 出来的那一帧就再也没人用了，MP3 字节流中间被挖掉一块，
                            // 解码出来就是某个字突然「破」掉。这里改成「接到 leftover 后面」，
                            // 一个字节都不许丢；EOS 也先挂起，等 leftover 喂完再发。
                            val polled = input.poll()
                            var data = leftover
                            if (polled === EOS) {
                                pendingEos = true
                            } else if (polled != null) {
                                data = if (data == null) polled else data + polled
                                leftover = null
                            }
                            if (pendingEos && data == null) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
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
                            if (sink == null && t == null) {
                                c.releaseOutputBuffer(outIdx, false)
                                break
                            }
                            val out = c.getOutputBuffer(outIdx)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                // 有流水线会话：PCM 续写进共用音轨，段间不留缝
                                val w = if (sink != null) {
                                    sink.writePcm(out, info.size, sampleRate)
                                } else {
                                    t!!.write(out, info.size, AudioTrack.WRITE_BLOCKING)
                                }
                                if (w > 0) {
                                    sawPcm = true
                                    if (sink == null) {
                                        writtenFrames += w / (2L * channels)
                                        writtenBytes += w
                                        if (!started && (writtenBytes >= prerollBytes() || eosQueued)) {
                                            started = true
                                            t!!.play()
                                            onStarted()
                                        }
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
                // 流式段收尾：短句可能一直攒不到预滚量，强制起播，别让这一句憋着不出声
                if (sink != null) {
                    sink.ensureStarted()
                    sink.reportSegment("流式")
                }
                // 等播完：播放头追上写入量（部分 ROM 的回调不可靠，与 playFileAwait 同样用轮询）
                // 共用音轨时不在这里等——整段回答的收尾由 awaitPlaybackDone 统一等
                val t = track
                if (sink == null && t != null && started) {
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
            // 流水线会话：整段回答共用一条音轨，这里只负责把它建起来
            if (sink != null) {
                sink.ensureTrack(sampleRate, channels)
                return
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

/**
 * 边生成边播的播报会话：普通模式（提问页）与大字模式共用。
 *
 * 为什么要有它：灵动模式一直是「AI 一吐字就按句切、首句走流式秒播」，
 * 而普通/大字模式是「等整段回答落地 → 再整段朗读」——同一台机器上两套体验，
 * 用户感受就是「灵动模式按住说完就出声，普通模式要干等好几秒」。
 * 这里把灵动模式那条流水线抽出来复用：
 *
 *   1. [feed] 增量喂 AI 吐出来的文本，遇到句末标点就切一段送合成；
 *   2. 首段走 `playStreamedPiece`（边收 WSS 音频帧边播，首声 ≈ 首帧耗时）；
 *   3. 其余段在后台预取成文件，按顺序续写进同一条 AudioTrack，句间无缝。
 *
 * 失败自愈：首段流式一帧没出来就退回文件链；整条链一声没出（[spoke] 仍为 false），
 * 调用方可以照旧走 [TtsPlayer.speak] 整段兜底。
 */
class LiveTtsSession(context: Context) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private val gen: Int = TtsPlayer.startStreamSession()

    private val buffer = StringBuilder()
    private var spokenUpTo = 0
    private var finished = false

    private val firstPiece = CompletableDeferred<String?>()
    private val pieces = Channel<String>(Channel.UNLIMITED)
    private val audio = Channel<File>(Channel.UNLIMITED)
    private val dir = File(context.applicationContext.cacheDir, "tts_live").apply {
        runCatching { mkdirs() }
        // 上一轮被打断时没播到的文件会留在这里，每轮开播前清一次
        runCatching { listFiles()?.forEach { it.delete() } }
    }

    /** 这一轮到底有没有真的出声（false = 调用方该走整段兜底） */
    @Volatile
    var spoke: Boolean = false
        private set

    /**
     * 第一声真的出来时回调（主线程）。
     * 极短的回答可能在 AI 结束时还没起播，调用方靠它把「已接手」这个状态补上，
     * 否则会误判成没出声、再整段朗读一遍——同一段话放两遍。
     */
    @Volatile
    var onFirstSound: (() -> Unit)? = null

    init {
        // 合成器：句子一到就合成，不等播放（合成约 1.5s/句，播放 4~6s/句，队列会稳定领先）
        scope.launch(Dispatchers.IO) {
            try {
                for (piece in pieces) {
                    if (!TtsPlayer.isActive(gen)) break
                    val f = TtsPlayer.synthesizeOnly(piece, dir)
                    if (f != null) audio.send(f)
                }
            } finally {
                audio.close()
            }
        }
        // 播放器：首句边收边播，之后严格按顺序播预取好的文件
        scope.launch {
            try {
                val head = firstPiece.await()
                if (!head.isNullOrBlank() && TtsPlayer.isActive(gen)) {
                    val streamed = TtsPlayer.playStreamedPiece(head, gen)
                    if (streamed) {
                        markSpoke()
                    } else if (TtsPlayer.isActive(gen)) {
                        // 流式一帧都没出（连不上/协议失败）：这一句退回文件链，别把首句丢了
                        val f = TtsPlayer.synthesizeOnly(head, dir)
                        if (f != null) {
                            if (TtsPlayer.playPrepared(f, gen)) markSpoke()
                            runCatching { f.delete() }
                        }
                    }
                }
                for (f in audio) {
                    if (!TtsPlayer.isActive(gen)) {
                        runCatching { f.delete() }
                        break
                    }
                    if (TtsPlayer.playPrepared(f, gen)) markSpoke()
                    runCatching { f.delete() }
                }
                // 文件都写进音轨不等于播完了，等播放头追上写入量再收尾
                TtsPlayer.awaitPlaybackDone(gen)
            } catch (_: CancellationException) {
                TtsPlayer.stop()
            } finally {
                TtsPlayer.endStreamSession(gen)
            }
        }
    }

    /** 喂一段新吐出来的文本（可在任意线程调用） */
    @Synchronized
    fun feed(chunk: String) {
        if (chunk.isEmpty() || finished) return
        buffer.append(chunk)
        var cut = findCut(spokenUpTo, !firstPiece.isCompleted)
        while (cut >= 0) {
            val isFirst = !firstPiece.isCompleted
            val len = cut + 1 - spokenUpTo
            val minLen = if (isFirst) MIN_FIRST_PIECE else MIN_PIECE
            if (len < minLen) {
                // 太碎的片段不单独成段（「好。」「是的。」各自起播听感像结巴）：
                // 注意这里**不能动 spokenUpTo**，只是跳过这个切点，让它并进下一句
                cut = findCut(cut + 1, false)
                continue
            }
            val piece = buffer.substring(spokenUpTo, cut + 1).trim()
            spokenUpTo = cut + 1
            emit(piece)
            cut = findCut(spokenUpTo, !firstPiece.isCompleted)
        }
        // 长句兜底：一口气说到上限还没标点，先播出去，别让人干等
        val limit = if (!firstPiece.isCompleted) FIRST_TTS_CHARS else 48
        if (buffer.length - spokenUpTo >= limit) {
            val piece = buffer.substring(spokenUpTo).trim()
            spokenUpTo = buffer.length
            emit(piece)
        }
    }

    /** 回答结束：把最后没标点的余量播掉并收尾 */
    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        val tail = buffer.substring(spokenUpTo).trim()
        spokenUpTo = buffer.length
        if (tail.isNotEmpty()) emit(tail)
        // 全文没有可播内容时也要放行首句通道，否则播放器永远等在第一句上
        if (!firstPiece.isCompleted) firstPiece.complete(null)
        pieces.close()
    }

    /** 打断（用户又问了 / 离开页面）：立刻静音并释放 */
    fun cancel() {
        finished = true
        TtsPlayer.stop()
        job.cancel()
        pieces.close()
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun markSpoke() {
        if (spoke) return
        spoke = true
        runCatching { onFirstSound?.invoke() }
    }

    private fun emit(piece: String) {
        if (piece.isBlank()) return
        if (!firstPiece.complete(piece)) pieces.trySend(piece)
    }

    /** 从 [from] 起找句末标点；首句允许在逗号处切（10 字起），后续要求 40 字起 */
    private fun findCut(from: Int, isFirst: Boolean): Int {
        val pauseMin = if (isFirst) 10 else 40
        for (i in from until buffer.length) {
            val c = buffer[i]
            if ((c in "。！？!?；;…" || c.code == 10) && i - from + 1 >= 3) return i
            val isPause = c == '，' || c == ',' || c == '、' || c == '：' || c == ':'
            if (isPause && i - from + 1 >= pauseMin) return i
        }
        return -1
    }

    private companion object {
        const val MIN_FIRST_PIECE = 4
        const val MIN_PIECE = 10
        const val FIRST_TTS_CHARS = 18
    }
}
