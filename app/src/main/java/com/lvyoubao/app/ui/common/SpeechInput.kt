package com.lvyoubao.app.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.concurrent.TimeUnit

/**
 * 语音输入统一入口：
 * 1. 优先系统 [SpeechRecognizer]（零体积、离线可用，国产 ROM 多有自带识别服务）；
 * 2. 没有系统识别时，用大模型云端转文字兜底：录音 → 硅基流动 SenseVoice（免费、效果好、零 APK 体积），
 *    需用户在设置里填「语音识别 Key」。
 * 所有失败都带直白原因回调，绝不静默。
 */
object SpeechInput {

    private const val ASR_BASE_URL = "https://api.siliconflow.cn/v1"
    private const val ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
    private const val MAX_RECORD_MS = 30_000

    /** 云端转文字进行中的一段录音 */
    data class Recording(val recorder: MediaRecorder, val file: File)

    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 系统识别：部分 ROM 冷启动会报"忙"，自动重试一次 */
    suspend fun listen(context: Context, prompt: String): Result<String> {
        val first = listenOnce(context, prompt)
        val err = first.exceptionOrNull()
        if (err is RecognizeError && (err.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || err.code == SpeechRecognizer.ERROR_CLIENT)) {
            kotlinx.coroutines.delay(900)
            return listenOnce(context, prompt)
        }
        return first
    }

    private class RecognizeError(val code: Int, message: String) : IllegalStateException(message)

    /** 系统识别：起内置 UI 聆听并返回结果 */
    private suspend fun listenOnce(context: Context, prompt: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!isAvailable(context)) {
                cont.resume(Result.failure(IllegalStateException("这台手机没有系统语音识别，请改用其他方式")))
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
                    if (cont.isActive) cont.resume(Result.failure(RecognizeError(error, friendlyError(error))))
                }

                override fun onResults(results: Bundle?) {
                    recognizer.destroy()
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (cont.isActive) {
                        cont.resume(
                            if (text.isNullOrBlank())
                                Result.failure(RecognizeError(SpeechRecognizer.ERROR_NO_MATCH, "没听清你说什么，再试一次？"))
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

    /** 开始录音（云端转文字路径；调用前确保已授予 RECORD_AUDIO） */
    fun startRecording(context: Context): Result<Recording> = runCatching {
        val file = File(context.cacheDir, "asr_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(96_000)
        recorder.setAudioSamplingRate(44_100)
        recorder.setMaxDuration(MAX_RECORD_MS)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        Recording(recorder, file)
    }

    /** 停止录音并上传大模型转文字；成功返回识别文本 */
    suspend fun stopAndTranscribe(recording: Recording, asrKey: String): Result<String> {
        val stopped = runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        val result = if (stopped.isFailure || recording.file.length() < 2_000) {
            Result.failure(IllegalStateException("没录到声音，请贴近手机再说一次"))
        } else {
            transcribe(recording.file, asrKey)
        }
        recording.file.delete()
        return result
    }

    fun cancelRecording(recording: Recording) {
        runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        recording.file.delete()
    }

    private suspend fun transcribe(file: File, asrKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", ASR_MODEL)
                    .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                    .build()
                val request = Request.Builder()
                    .url("$ASR_BASE_URL/audio/transcriptions")
                    .header("Authorization", "Bearer $asrKey")
                    .post(body)
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val hint = when (response.code) {
                            401 -> "语音识别 Key 无效，请到设置里检查"
                            402 -> "硅基流动账号余额不足：语音识别用的是免费模型，一般不扣余额，请到 siliconflow.cn 检查账号状态（是否实名/可用）"
                            403 -> "语音识别请求被拒：请到 siliconflow.cn 确认账号可用（是否需要实名认证）"
                            429 -> "语音识别太频繁，稍后再试"
                            else -> "语音识别服务异常（HTTP ${response.code}）"
                        }
                        error(hint)
                    }
                    val parsed = runCatching {
                        json.decodeFromString<TranscribeResponse>(text).text
                    }.getOrDefault("")
                    parsed.trim().ifBlank { error("没听清你说什么，再试一次？") }
                }
            }.recoverCatching { e ->
                if (e is IllegalStateException) throw e
                throw IllegalStateException("语音识别失败：${e.message ?: "网络异常"}，请再试一次")
            }
        }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TranscribeResponse(val text: String = "")

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
