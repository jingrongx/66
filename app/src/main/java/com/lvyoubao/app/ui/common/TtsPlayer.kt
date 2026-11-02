package com.lvyoubao.app.ui.common

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 回答朗读（老人/骑行/走路场景解放双眼）。进程级单例。
 * 状态机：未初始化 → 初始化（失败可重试）→ 可用 / 不可用。
 * 所有失败都会落到 [state] 由界面明示，不再出现"点了没声音也没反应"。
 */
object TtsPlayer {

    sealed class State {
        data object Idle : State()
        data object Speaking : State()
        data class Unavailable(val reason: String) : State()
    }

    private var tts: TextToSpeech? = null
    private var initStarted = false
    private var ready = false
    private var queued: String? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 朗读中 → 停止并返回 false；否则开始朗读并返回 true */
    fun toggle(context: Context, text: String): Boolean {
        return if (_state.value is State.Speaking) {
            stop()
            false
        } else {
            speak(context, text)
            true
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        if (_state.value is State.Speaking) _state.value = State.Idle
    }

    /** 直接开始/重新开始朗读（QUEUE_FLUSH，可随时重复调用） */
    fun speak(context: Context, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (_state.value is State.Unavailable) {
            // 上次初始化失败过：允许重试一次
            initStarted = false
            ready = false
        }
        val engine = tts
        if (engine == null || !initStarted) {
            queued = body
            initStarted = true
            _state.value = State.Idle
            shutdown()
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val e = tts
                    if (e == null) {
                        markUnavailable("语音引擎未就绪，请重试")
                    } else {
                        runCatching { e.setLanguage(Locale.SIMPLIFIED_CHINESE) }
                        // 不少国产引擎 setLanguage 返回不支持但 speak 中文正常，先试说再说
                        attachProgressListener(e)
                        val toSpeak = queued
                        queued = null
                        if (toSpeak != null) speakNow(toSpeak)
                    }
                } else {
                    markUnavailable("这台手机的语音合成引擎不可用，可在系统设置里检查“文字转语音”")
                }
                queued = null
            }
        } else if (!ready) {
            queued = body
        } else {
            speakNow(body)
        }
    }

    private fun attachProgressListener(e: TextToSpeech) {
        runCatching {
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.value = State.Speaking
                }

                override fun onDone(utteranceId: String?) {
                    if (_state.value is State.Speaking) _state.value = State.Idle
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (_state.value is State.Speaking) _state.value = State.Idle
                }
            })
        }
    }

    private fun speakNow(text: String) {
        val e = tts ?: return
        val result = runCatching {
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lvyoubao_${System.currentTimeMillis()}")
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.SUCCESS) {
            ready = true
            _state.value = State.Speaking
        } else {
            markUnavailable("语音朗读启动失败，点“朗读”可再试一次")
        }
    }

    private fun markUnavailable(reason: String) {
        ready = false
        _state.value = State.Unavailable(reason)
    }

    private fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
