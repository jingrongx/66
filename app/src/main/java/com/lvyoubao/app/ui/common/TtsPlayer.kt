package com.lvyoubao.app.ui.common

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
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
        stop()
        _state.value = State.Loading
        job = scope.launch {
            val chunks = splitForSpeech(body)
            if (chunks.isEmpty()) {
                _state.value = State.Idle
                return@launch
            }
            val dir = File(context.cacheDir, "tts")
            // 流水线：先合成第 1 段；播第 i 段时第 i+1 段已在后台合成
            var pending: kotlinx.coroutines.Deferred<File?>? = async { EdgeTts.synthesize(chunks[0], dir) }
            for (i in chunks.indices) {
                val cur = pending ?: break
                pending = if (i + 1 < chunks.size) {
                    async { EdgeTts.synthesize(chunks[i + 1], dir) }
                } else {
                    null
                }
                val file = runCatching { cur.await() }.getOrNull()
                if (file == null || file.length() == 0L) {
                    // 云端合成失败：剩余文本整体交给系统 TTS 兜底
                    speakWithSystem(context, chunks.drop(i).joinToString(""))
                    return@launch
                }
                val ok = playFileAwait(file)
                runCatching { file.delete() }
                if (!ok) {
                    speakWithSystem(context, chunks.drop(i + 1).joinToString(""))
                    return@launch
                }
            }
            if (_state.value is State.Speaking || _state.value is State.Loading) {
                _state.value = State.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { player?.let { if (it.isPlaying) it.stop() } }
        runCatching { player?.release() }
        player = null
        runCatching { systemTts?.stop() }
        if (_state.value !is State.Idle) _state.value = State.Idle
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
    private suspend fun playFileAwait(file: File): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val started = runCatching {
                    val p = MediaPlayer()
                    player = p
                    p.setDataSource(file.absolutePath)
                    p.setOnCompletionListener {
                        runCatching { it.release() }
                        if (player === it) player = null
                        if (cont.isActive) cont.resume(true)
                    }
                    p.setOnErrorListener { mp, _, _ ->
                        runCatching { mp.release() }
                        if (player === mp) player = null
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    p.prepare()
                    _state.value = State.Speaking
                    p.start()
                    true
                }.getOrElse { false }
                if (!started && cont.isActive) {
                    cont.resume(false)
                }
                cont.invokeOnCancellation {
                    runCatching { player?.release() }
                    if (player != null) player = null
                }
            }
        }
    }

    /** 系统 TTS 兜底：初始化（带超时）→ speak → 等待完成 */
    private suspend fun speakWithSystem(context: Context, text: String) {
        val engine = ensureSystemTts(context)
        if (engine == null) {
            _state.value = State.Unavailable("语音合成不可用：网络合成失败，这台手机也没有可用的语音引擎")
            return
        }
        var speakFailed = false
        withContext(Dispatchers.Main) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = State.Speaking
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_state.value is State.Speaking) _state.value = State.Idle
                            if (cont.isActive) cont.resume(Unit)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (_state.value is State.Speaking) _state.value = State.Idle
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
            }
        }
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
