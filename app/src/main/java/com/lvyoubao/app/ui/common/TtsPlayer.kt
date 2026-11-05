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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 回答朗读（老人/骑行/走路场景解放双眼）。进程级单例。
 * 策略：优先 Edge 云端合成（效果好、零体积、无需 Key），失败回退系统 TTS；
 * 两路都失败才报"不可用"。所有失败落到 [state] 由界面明示。
 *
 * 长文本按句切分流水线：边合成边播（第 i 句播放时第 i+1 句已在后台合成），
 * 首句秒出，不再等整篇合成完才出声。
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

    /** 第一句之后每段的目标长度（字）：连接少、抗抖动，Edge 单请求上限 3000 */
    private const val SEGMENT_CHARS = 600
    /** 单段合成重试次数 */
    private const val SYNTH_ATTEMPTS = 3

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
            val chunks = buildChunks(body)
            if (chunks.isEmpty()) {
                if (myGen == generation) _state.value = State.Idle
                return@launch
            }
            val dir = File(context.cacheDir, "tts")
            // 流水线：先合成第 1 段；播第 i 段时第 i+1 段已在后台合成
            var pending: kotlinx.coroutines.Deferred<File?>? = async { synthesizeWithRetry(chunks[0], dir) }
            for (i in chunks.indices) {
                val cur = pending ?: break
                pending = if (i + 1 < chunks.size) {
                    async { synthesizeWithRetry(chunks[i + 1], dir) }
                } else {
                    null
                }
                // 注意：不能把“被停止的取消信号”当成合成失败吞掉，
                // 否则被叫停的旧流水线会误判走系统 TTS 兜底，和新播报叠在一起
                val file = try {
                    cur.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (myGen != generation) return@launch
                if (file == null || file.length() == 0L) {
                    // 重试后仍失败：剩余文本整体交给系统 TTS 兜底
                    speakWithSystem(context, chunks.drop(i).joinToString(""), myGen)
                    return@launch
                }
                val ok = playFileAwait(file, myGen)
                runCatching { file.delete() }
                if (myGen != generation) return@launch
                if (!ok) {
                    speakWithSystem(context, chunks.drop(i + 1).joinToString(""), myGen)
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
     * 合成分段策略（v0.7.8 重构，修复"播几句就断"）：
     * v0.7.5 按短句切分后每句各开一条到微软服务器的连接，一篇回答几十条连接，
     * 网络稍有抖动/被限流就中途失败。现在只拆第一句（保证首句秒出），
     * 其余按约 600 字合并成大段——连接数降到个位数，且每段自带重试。
     */
    private fun buildChunks(text: String): List<String> {
        val sentences = splitForSpeech(text)
        if (sentences.isEmpty()) return emptyList()
        if (sentences.size == 1) return sentences
        val out = mutableListOf<String>(sentences[0])
        val sb = StringBuilder()
        for (s in sentences.drop(1)) {
            if (sb.isNotEmpty() && sb.length + s.length > SEGMENT_CHARS) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 单段合成：失败自动重试（网络抖动/限流下，多试几次基本都能过） */
    private suspend fun synthesizeWithRetry(text: String, dir: File): File? {
        repeat(SYNTH_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(500L * attempt)
            val file = runCatching { EdgeTts.synthesize(text, dir) }.getOrNull()
            if (file != null && file.length() > 0) return file
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
        // 过滤纯符号段
        return out.filter { it.any { c -> c.isLetterOrDigit() || c.code > 0x2E80 } }
    }

    /** 播放一个音频文件并挂起直到播完/出错/被取消；返回是否完整播完 */
    private suspend fun playFileAwait(file: File, gen: Int): Boolean {
        return withContext(Dispatchers.Main) {
            // 开播前最后核对一次代次（和 stop()/speak() 同在主线程执行，此检查无竞态窗口）：
            // 已被接管的旧流水线到此为止，一个音都不许出
            if (gen != generation) return@withContext false
            suspendCancellableCoroutine { cont ->
                var started = false
                var p: MediaPlayer? = null
                try {
                    val mp = MediaPlayer()
                    p = mp
                    player = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener {
                        runCatching { it.release() }
                        if (player === it) player = null
                        if (cont.isActive) cont.resume(true)
                    }
                    mp.setOnErrorListener { errPlayer, _, _ ->
                        runCatching { errPlayer.release() }
                        if (player === errPlayer) player = null
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    mp.prepare()
                    if (gen == generation) {
                        _state.value = State.Speaking
                        mp.start()
                        started = true
                    }
                } catch (_: Exception) {
                }
                if (!started) {
                    // 创建/预加载失败（或已被接管）：释放自己的播放器并报告失败
                    runCatching { p?.release() }
                    if (p != null && player === p) player = null
                }
                cont.invokeOnCancellation {
                    // 只释放自己创建的那个播放器，绝不能误伤后继流水线的播放器
                    runCatching { p?.release() }
                    if (p != null && player === p) player = null
                }
                if (!started && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
    }

    /** 系统 TTS 兜底：初始化（带超时）→ speak → 等待完成 */
    private suspend fun speakWithSystem(context: Context, text: String, gen: Int) {
        if (gen != generation) return
        val engine = ensureSystemTts(context)
        if (engine == null) {
            if (gen == generation) {
                _state.value = State.Unavailable("语音合成不可用：网络合成多次重试仍失败，这台手机也没有可用的语音引擎")
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
