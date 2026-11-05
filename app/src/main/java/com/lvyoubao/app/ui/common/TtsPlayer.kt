package com.lvyoubao.app.ui.common

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 回答朗读（老人/骑行/走路场景解放双眼）。进程级单例。
 * 模型（v0.8.2 恢复整段式）：全文合成音频 → 整段播放 →（长文才）合成下一段 → 播放，
 * 严格串行、不并发不预取——杜绝乱序与拼接破音。
 * 引擎链：Edge 云端合成（免费无 Key）→ 硅基流动 CosyVoice2（配 Key 才启用）→ 系统 TTS；
 * 全部失败才报"不可用"，落到 [state] 由界面明示。
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
        // 两个页面（提问页/长辈模式）同时挂着自动播报、或开关翻转导致重复触发时，
        // 若各自重启一遍，旧句被拦腰打断、新句从头读，听感就是“话赶话”。
        if ((_state.value is State.Loading || _state.value is State.Speaking) && body == currentText) return
        stop()
        val myGen = ++generation
        currentText = body
        _state.value = State.Loading
        job = scope.launch {
            val parts = buildParts(body)
            if (parts.isEmpty()) {
                if (myGen == generation) _state.value = State.Idle
                return@launch
            }
            val dir = File(context.cacheDir, "tts")
            // 恢复最初方案的模型：合成完整音频 → 整段播放 → 再合成下一段。
            // 严格串行，不并发、不预取、不交错——常见回答（≤800字）就是一整段音频一次播完，
            // 从结构上杜绝"有的句子先读、有的后读"和分段拼接处的破音。
            for (i in parts.indices) {
                val file = synthesizeWithRetry(parts[i], dir)
                // 不能把"被停止的取消信号"当成合成失败吞掉，否则旧流水线会走系统 TTS 兜底叠音
                if (myGen != generation) return@launch
                if (file == null || file.length() == 0L) {
                    // 两级云端合成（Edge→CosyVoice2）都失败：剩余文本整体交给系统 TTS
                    speakWithSystem(context, parts.drop(i).joinToString(""), myGen)
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
        runCatching { player?.let { if (it.isPlaying) it.stop() } }
        runCatching { player?.release() }
        player = null
        runCatching { systemTts?.stop() }
        currentText = null
        if (_state.value !is State.Idle) _state.value = State.Idle
    }

    /**
     * 全文分段（v0.8.2 恢复"整段合成整段播"）：短回答就是一整段、合成一次播一次；
     * 超过 800 字的长文才按句切大段，播完一段再合成下一段（严格串行，不预取不并发）。
     * internal 是为了单元测试可直接调用。
     */
    internal fun buildParts(text: String): List<String> {
        val sentences = splitForSpeech(text)
        if (sentences.isEmpty()) return emptyList()
        if (sentences.joinToString("").length <= SEGMENT_CHARS) return listOf(sentences.joinToString(""))
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > SEGMENT_CHARS) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 单段合成引擎链：Edge（免费无 Key，主力）→ 硅基流动 CosyVoice2（配了语音识别 Key 才启用，
     * 普通 HTTP 比 WebSocket 抗抖动，Edge 协议再变动时也顶得住）→ 返回 null 交给系统 TTS。
     * 每级自带重试；"播几句就断"多因单引擎中途失败，两级接力后剩余段落不再陪葬。
     */
    private suspend fun synthesizeWithRetry(text: String, dir: File): File? {
        repeat(SYNTH_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(500L * attempt)
            val file = runCatching { EdgeTts.synthesize(text, dir) }.getOrNull()
            if (file != null && file.length() > 0) return file
        }
        val key = cloudKey
        if (key != null) {
            repeat(2) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(400L)
                val file = runCatching { CloudTts.synthesize(text, dir, key) }.getOrNull()
                if (file != null && file.length() > 0) return file
            }
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

    /** 系统 TTS 兜底：初始化（带超时）→ speak → 等待完成 */
    private suspend fun speakWithSystem(context: Context, text: String, gen: Int) {
        if (gen != generation) return
        val engine = ensureSystemTts(context)
        if (engine == null) {
            if (gen == generation) {
                _state.value = State.Unavailable(
                    "朗读不可用：免费朗读和云端兜底都失败了，这台手机也没有可用的语音引擎。请检查网络后重试；可在设置里填「语音识别 Key」启用云端兜底",
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
        return withTimeoutOrNull(8_000) {
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
    }
}
