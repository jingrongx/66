package com.lvyoubao.app.ui.common

import android.Manifest
import com.lvyoubao.app.data.LogStore
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

    /**
     * 识别失败分类（语音优先设备的播报按类给固定话术，气泡里保留具体原因）：
     * NOT_HEARD 没听清（再说一次就好）/ NETWORK 网络与服务端故障 / CONFIG 配置问题（Key/权限）/ OTHER 其他
     */
    enum class SpeechFailure { NOT_HEARD, NETWORK, CONFIG, OTHER }

    open class SpeechException(
        val failure: SpeechFailure,
        val code: Int = 0,
        message: String,
    ) : IllegalStateException(message)

    /** 播报话术：没听清/网络故障给固定话术，其余说具体原因 */
    fun spokenHint(err: Throwable): String {
        val e = err as? SpeechException ?: return err.message ?: "没听清楚，请再试一次"
        return when (e.failure) {
            SpeechFailure.NOT_HEARD -> "没听清楚，请再试一次"
            SpeechFailure.NETWORK -> "网络服务有问题，请稍后再试"
            else -> e.message ?: "没听清楚，请再试一次"
        }
    }

    private const val ASR_BASE_URL = "https://api.siliconflow.cn/v1"

    /** 普通识别：SenseVoice 免费模型，普通话准、响应快 */
    private const val ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"

    /**
     * 方言口音识别：TeleSpeech-ASR（中国电信 TeleAI 开源，10 万小时语料、覆盖 23 种方言口音，
     * 赣语抚州话这类本地说法识别明显更好），硅基流动托管，同样是免费模型。
     */
    private const val ASR_MODEL_DIALECT = "teleai/teleSpeechASR"

    /**
     * 按住说话模式下用户自己控制录音时长，这里只留 5 分钟的保险上限（防止忘记松手把存储录满）。
     * 老的 30 秒上限会把长话/慢话硬生生截断。
     */
    private const val MAX_RECORD_MS = 300_000

    /** 云端转文字进行中的一段录音 */
    data class Recording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAt: Long = System.currentTimeMillis(),
    )

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

    /** 系统识别给大字模式的宽松停顿参数：至少说 15 秒，静音 8 秒才算说完（很多 ROM 默认静音 1~2 秒就抢着断句） */
    const val PAUSE_HINT_MS = 8_000L
    const val MIN_SPEECH_MS = 15_000L

    private class RecognizeError(code: Int, message: String) :
        SpeechException(failureKind(code), code, message)

    /** 系统识别错误码 → 失败分类（没听清/网络/配置/其他） */
    private fun failureKind(error: Int): SpeechFailure = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NOT_HEARD
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechFailure.NETWORK
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.CONFIG
        else -> SpeechFailure.OTHER
    }

    /** 系统识别：起内置 UI 聆听并返回结果 */
    private suspend fun listenOnce(context: Context, prompt: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!isAvailable(context)) {
                cont.resume(Result.failure(SpeechException(SpeechFailure.OTHER, message = "这台手机没有系统语音识别，请改用其他方式")))
                return@suspendCancellableCoroutine
            }
            val recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }.getOrNull()
            if (recognizer == null) {
                cont.resume(Result.failure(SpeechException(SpeechFailure.OTHER, message = "语音识别启动失败，请改用打字提问")))
                return@suspendCancellableCoroutine
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                // 充分的停顿时间：老人说话慢、中途爱停顿，默认的静音断句阈值太急，
                // 这里放宽到静音 8 秒才算说完、至少给 15 秒说话时间（部分 ROM 不支持则自动忽略）。
                // 这两个 key 是框架 @hide 常量，公开 SDK 没有符号，只能按字符串传（运行时有效）
                putExtra("android.speech.extras.SPEECH_INPUT_COMPLETELY_SILENCE_LENGTH_MILLIS", PAUSE_HINT_MS)
                putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", MIN_SPEECH_MS)
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
                                Result.failure(RecognizeError(SpeechRecognizer.ERROR_NO_MATCH, "没听清楚，请再试一次"))
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
        // 用户还在说话的时候就把 DNS/TCP/TLS 建起来：松手后直接发正文，
        // 省掉那 200~400ms 的握手（这台设备上冷连接尤其明显）。
        warmConnectionAsync()
        val file = File(context.cacheDir, "asr_${System.currentTimeMillis()}.m4a")
        val startedAt = System.currentTimeMillis()
        // ASR 只吃 16k 单声道：以前 44.1k/96kbps 录出来的 m4a 是它的 6 倍大，
        // 上传、服务端解码都跟着变慢，说 10 秒能拖出上百 KB。
        // 个别机型不认 16k AAC 编码器，退回原来的参数，不能让录音直接失败。
        // 待机 KWS 刚让出 builtin mic 时，audio policy 的释放有交接延迟：紧跟着 start()
        // 会连吃 -38（INVALID_OPERATION），连 44.1k 回退也在同一个窗口内同样失败。
        // 交替两档采样率 + 递增退避重试，覆盖约 1 秒的交接期（与 ContinuousConversation
        // 对 AudioRecord 做的 5 次重试同一个现象，那条路早有重试，MediaRecorder 以前没有）。
        var lastError: Throwable? = null
        var recorder: MediaRecorder? = null
        for (attempt in 0 until 6) {
            if (attempt > 0) {
                Thread.sleep(120L * attempt) // 120/240/360/480/600ms，累计约 1.8s
            }
            val (rate, bitRate) = if (attempt % 2 == 0) 16_000 to 48_000 else 44_100 to 96_000
            runCatching { buildRecorder(context, file, rate, bitRate) }
                .fold(
                    onSuccess = { recorder = it },
                    onFailure = { lastError = it },
                )
            if (recorder != null) break
        }
        recorder?.let { Recording(it, file, startedAt) }
            ?: throw (lastError ?: IllegalStateException("录音启动失败"))
    }

    private fun buildRecorder(context: Context, file: File, sampleRate: Int, bitRate: Int): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(bitRate)
        recorder.setAudioSamplingRate(sampleRate)
        recorder.setAudioChannels(1)
        recorder.setMaxDuration(MAX_RECORD_MS)
        recorder.setOutputFile(file.absolutePath)
        runCatching {
            recorder.prepare()
            recorder.start()
        }.onFailure {
            // start 失败（-38）必须释放对象，否则残留的 recorder 继续占 mic，
            // 会让后面的退避重试全部落空。
            runCatching { recorder.release() }
            throw it
        }
        return recorder
    }

    @Volatile
    private var lastWarmAt = 0L

    /** 录音期间预热连接：只建连不发业务请求（401/404 都无所谓，连接池已经拿到连接了） */
    private fun warmConnectionAsync() {
        val now = System.currentTimeMillis()
        if (now - lastWarmAt < 30_000L) return
        lastWarmAt = now
        kotlin.concurrent.thread(isDaemon = true, name = "asr-warm") {
            runCatching {
                httpClient.newCall(
                    Request.Builder()
                        .url("$ASR_BASE_URL/audio/transcriptions")
                        .head()
                        .build(),
                ).execute().close()
            }
        }
    }

    /**
     * 停止录音并上传大模型转文字；成功返回识别文本。方言开关切换到方言口音模型。
     * postRollMs: 松手后延时毫秒数（默认 400ms）。用户往往刚吐完最后一个字就松手甚至发音未尽，
     * 后台多录 400ms 留足尾音衰减包络再真正 stop，从源头上消灭句末吞字（如「呢/吗/酸的」）。
     */
    suspend fun stopAndTranscribe(
        recording: Recording,
        asrKey: String,
        dialect: Boolean = false,
        postRollMs: Long = 400L,
    ): Result<String> {
        if (postRollMs > 0) {
            kotlinx.coroutines.delay(postRollMs)
        }
        // stop() 抛错有两种情况：压根没录进去（文件极小）或到时长上限被自动停了（文件是好的），
        // 所以只看文件大小判断，别误伤长录音。
        // **必须在 IO 线程 stop**：MediaRecorder.stop() 要把 m4a 收尾写盘，主线程上会卡住界面，
        // 而且它耗时随录音长度增长——这就是「有时候按完半天没反应」的一大来源。
        val tStop = android.os.SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            runCatching { recording.recorder.stop() }
            runCatching { recording.recorder.release() }
        }
        val stopCost = android.os.SystemClock.elapsedRealtime() - tStop
        val durMs = (System.currentTimeMillis() - recording.startedAt).coerceAtLeast(0)
        val sizeKb = recording.file.length() / 1024
        val result = if (recording.file.length() < 2_000) {
            Result.failure(SpeechException(SpeechFailure.NOT_HEARD, message = "没录到声音，请贴近手机再说一次"))
        } else {
            val tAsr = android.os.SystemClock.elapsedRealtime()
            val r = transcribeFile(recording.file, asrKey, dialect)
            val asrCost = android.os.SystemClock.elapsedRealtime() - tAsr
            LogStore.log(
                "asr",
                "按住说话：录音 ${durMs}ms / ${sizeKb}KB，收尾 ${stopCost}ms，转写 ${asrCost}ms" +
                    (if (r.isSuccess) "（${r.getOrNull()?.trim().orEmpty().take(20)}）" else "（失败 ${r.exceptionOrNull()?.message}）"),
            )
            r
        }
        recording.file.delete()
        return result
    }

    /**
     * 上传任意音频文件转文字（连续对话引擎切好的 WAV 段也走这里；调用方负责删文件）。
     * 方言开关切换到方言口音模型。
     */
    suspend fun transcribeFile(
        file: File,
        asrKey: String,
        dialect: Boolean = false,
    ): Result<String> = transcribe(file, asrKey, dialect)

    fun cancelRecording(recording: Recording) {
        runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        recording.file.delete()
    }

    private suspend fun transcribe(file: File, asrKey: String, dialect: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", if (dialect) ASR_MODEL_DIALECT else ASR_MODEL)
                    .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                    .build()
                val request = Request.Builder()
                    .url("$ASR_BASE_URL/audio/transcriptions")
                    .header("Authorization", "Bearer $asrKey")
                    .post(body)
                    .build()
                // 复用进程级连接池：以前每次转写都新建 OkHttpClient，
                // 等于每次重走 DNS+TCP+TLS，唤醒那 1.5 秒里有 200~400ms 是这么白扔的。
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 分类：Key/账号类给 CONFIG（说具体原因）；服务端故障一律 NETWORK（播报说「网络服务问题」，气泡留 HTTP 码）
                        val (failure, hint) = when (response.code) {
                            401 -> SpeechFailure.CONFIG to "语音识别 Key 无效，请到设置里检查"
                            402 -> SpeechFailure.CONFIG to "硅基流动账号余额不足：语音识别用的是免费模型，一般不扣余额，请到 siliconflow.cn 检查账号状态（是否实名/可用）"
                            403 -> SpeechFailure.CONFIG to "语音识别请求被拒：请到 siliconflow.cn 确认账号可用（是否需要实名认证）"
                            429 -> SpeechFailure.OTHER to "语音识别太频繁，稍后再试"
                            else -> SpeechFailure.NETWORK to "语音识别服务异常（HTTP ${response.code}）"
                        }
                        throw SpeechException(failure, response.code, hint)
                    }
                    val parsed = runCatching {
                        json.decodeFromString<TranscribeResponse>(text).text
                    }.getOrDefault("")
                    parsed.trim().ifBlank { throw SpeechException(SpeechFailure.NOT_HEARD, message = "没听清楚，请再试一次") }
                }
            }.recoverCatching { e ->
                if (e is IllegalStateException) throw e // 含 SpeechException：分类已带好，原样上抛
                // 走到这的都是网络层异常（连接失败/超时/DNS）：按用户要求统一说「网络服务问题」
                throw SpeechException(SpeechFailure.NETWORK, message = "网络服务有问题，请稍后再试")
            }
        }

    private val json = Json { ignoreUnknownKeys = true }

    /** 转写专用连接池：唤醒与按住说话共用，连接复用后省掉每次的 DNS/TCP/TLS 握手 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class TranscribeResponse(val text: String = "")

    private fun friendlyError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清楚，请再试一次"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "网络服务有问题，请稍后再试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别权限被拒绝，请检查系统设置"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请再试一次"
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别忙，稍等一下再试"
        else -> "没听清楚，请再试一次"
    }
}
