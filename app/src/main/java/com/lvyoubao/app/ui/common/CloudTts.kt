package com.lvyoubao.app.ui.common

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 硅基流动云端朗读（CosyVoice2-0.5B）：
 * 普通 HTTP 一次拿整段音频，比 Edge 的 WebSocket 长连接抗网络抖动、无协议变动风险，
 * 作为 Edge 朗读失败时的兜底引擎（复用「语音识别 Key」，按字节微量计费，
 * 一条回答不到 1 厘钱，只有 Edge 失败时才会真正用到）。
 */
object CloudTts {

    private const val ENDPOINT = "https://api.siliconflow.cn/v1/audio/speech"
    private const val MODEL = "FunAudioLLM/CosyVoice2-0.5B"

    /** 音色候选：主力是预设沉稳女声；服务端若不再认旧格式（HTTP 400），逐个换备用格式重试 */
    private val VOICE_CANDIDATES = listOf(
        "speech:anna",
        "FunAudioLLM/CosyVoice2-0.5B:anna",
        "FunAudioLLM/CosyVoice2-0.5B:bella",
    )

    /** 单次合成上限（字符），与 TtsPlayer 分段大小匹配 */
    private const val MAX_CHARS = 900

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 最近一次云端兜底失败的具体原因（供界面明示），成功后清空 */
    @Volatile
    var lastFailure: String? = null
        private set

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class SpeechRequest(
        val model: String = MODEL,
        val input: String,
        val voice: String,
        @SerialName("response_format") val responseFormat: String = "mp3",
        @SerialName("sample_rate") val sampleRate: Int = 32000,
    )

    /**
     * 合成语音写到 cacheDir，返回 mp3 文件；任何失败返回 null（上层继续走下一级回退）。
     * HTTP 400（参数被拒）时换下一个音色格式重试——服务端音色/参数格式调整时自动适应；
     * 其余状态码（401 Key 无效 / 402 余额不足 / 5xx 服务异常）换音色没有意义，直接终止。
     * 服务端错误 JSON 里的 message 会被抓进 [lastFailure]，界面直接显示具体被拒原因。
     */
    suspend fun synthesize(text: String, cacheDir: File, apiKey: String): File? =
        withContext(Dispatchers.IO) {
            val body = text.trim().take(MAX_CHARS)
            if (body.isEmpty() || apiKey.isBlank()) return@withContext null
            cacheDir.mkdirs()
            val out = File(cacheDir, "tts_cld_${System.currentTimeMillis()}.mp3")
            for (voice in VOICE_CANDIDATES) {
                val (file, tryNext) = runCatching {
                    attempt(body, out, apiKey, voice)
                }.getOrElse { e ->
                    ttsLog("CloudTts 请求异常：${e.javaClass.simpleName} ${e.message ?: ""}")
                    lastFailure = "云端兜底网络异常：${e.javaClass.simpleName}"
                    null to false
                }
                if (file != null) {
                    lastFailure = null
                    return@withContext file
                }
                if (!tryNext) break
                ttsLog("CloudTts 换音色重试：$voice → ${VOICE_CANDIDATES.getOrNull(VOICE_CANDIDATES.indexOf(voice) + 1)}")
            }
            return@withContext null
        }

    /** 单次尝试：返回 文件 + 是否值得换下一个音色（仅 400 换） */
    private fun attempt(body: String, out: File, apiKey: String, voice: String): Pair<File?, Boolean> {
        val payload = json.encodeToString(SpeechRequest(input = body, voice = voice))
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val serverMsg = runCatching {
                    response.body?.string()?.let { err ->
                        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
                            ?: err.take(160)
                    }
                }.getOrNull()
                val hint = when (response.code) {
                    401 -> "（Key 无效或账号未实名）"
                    402 -> "（账户余额不足，硅基流动充值后可用）"
                    400 -> "（参数被拒）"
                    else -> ""
                }
                lastFailure = "云端兜底 HTTP ${response.code}$hint" + (serverMsg?.let { "：$it" } ?: "")
                ttsLog("CloudTts 失败（voice=$voice）：$lastFailure")
                return null to (response.code == 400)
            }
            val bytes = response.body?.bytes() ?: return null to false
            // 太小的响应基本是错误 JSON，不是音频
            if (bytes.size < 200) {
                lastFailure = "云端兜底返回异常（${bytes.size}B，疑似错误信息非音频）"
                ttsLog("CloudTts 失败：$lastFailure")
                return null to false
            }
            out.writeBytes(bytes)
            ttsLog("CloudTts 成功（voice=$voice，${bytes.size}B）")
            return out to false
        }
    }
}
