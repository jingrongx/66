package com.lvyoubao.app.ui.common

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 回答朗读（创新点：老人/骑行/走路场景解放双眼）。进程级单例，避免重复初始化。 */
object TtsPlayer {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** 返回 true 表示开始朗读，false 表示已停止 */
    fun toggle(context: Context, text: String): Boolean {
        val engine = tts
        if (engine == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    runCatching { tts?.setLanguage(Locale.CHINA) }
                    pending?.let { speak(it) }
                }
                pending = null
            }
            return true
        }
        return if (engine.isSpeaking) {
            engine.stop()
            false
        } else {
            speak(text)
            true
        }
    }

    fun stop() {
        tts?.stop()
    }

    private fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lvyoubao_${System.currentTimeMillis()}")
    }
}
