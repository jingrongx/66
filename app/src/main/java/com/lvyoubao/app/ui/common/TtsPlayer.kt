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
            val file = runCatching { EdgeTts.synthesize(body, File(context.cacheDir, "tts")) }
                .getOrNull()
            if (file != null && file.length() > 0) {
                playFile(context, file, body)
            } else {
                speakWithSystem(context, body)
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

    private suspend fun playFile(context: Context, file: File, fallbackText: String) {
        val ok = withContext(Dispatchers.Main) {
            runCatching {
                val p = MediaPlayer()
                player = p
                p.setDataSource(file.absolutePath)
                p.setOnCompletionListener {
                    runCatching { it.release() }
                    if (player === it) player = null
                    if (_state.value is State.Speaking) _state.value = State.Idle
                }
                p.setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (player === mp) player = null
                    if (_state.value is State.Speaking) _state.value = State.Idle
                    true
                }
                p.prepare()
                _state.value = State.Speaking
                p.start()
                true
            }.getOrElse { false }
        }
        if (!ok) {
            playFileCleanup(file)
            speakWithSystem(context, fallbackText)
        }
    }

    private fun playFileCleanup(file: File) {
        runCatching { player?.release() }
        player = null
        file.delete()
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
