package com.lvyoubao.app.ui.common

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 语音输入统一入口：优先用应用内 [SpeechRecognizer]（兼容国产 ROM 自带识别服务、错误可见）。
 * 识别失败一定回调失败原因，绝不静默。挂在主线程调用即可（回调在主线程）。
 */
object SpeechInput {

    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** 开始聆听并在结果出来后返回；取消由协程取消自动处理 */
    suspend fun listen(context: Context, prompt: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!isAvailable(context)) {
                cont.resume(Result.failure(IllegalStateException("这台手机没有可用的语音识别服务，请改用打字提问")))
                return@suspendCancellableCoroutine
            }
            val recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }.getOrNull()
            if (recognizer == null) {
                cont.resume(Result.failure(IllegalStateException("语音识别启动失败，请改用打字提问")))
                return@suspendCancellableCoroutine
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException(friendlyError(error))))
                }

                override fun onResults(results: Bundle?) {
                    recognizer.destroy()
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (cont.isActive) {
                        cont.resume(
                            if (text.isNullOrBlank())
                                Result.failure(IllegalStateException("没听清你说什么，再试一次？"))
                            else Result.success(text),
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
            recognizer.startListening(intent)
        }

    private fun friendlyError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到你说话，请靠近一点再试"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常，稍后再试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别权限被拒绝，请检查系统设置"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请再试一次"
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别忙，稍等一下再试"
        SpeechRecognizer.ERROR_SERVER -> "语音服务出了点问题，稍后再试"
        else -> "语音识别失败，请再试一次"
    }
}
