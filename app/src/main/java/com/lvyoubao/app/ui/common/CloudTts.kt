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

    /** 预设沉稳女声，吐字清楚，听得清 */
    private const val VOICE = "speech:anna"

    /** 单次合成上限（字符），与 TtsPlayer 分段大小匹配 */
    private const val MAX_CHARS = 900

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class SpeechRequest(
        val model: String = MODEL,
        val input: String,
        val voice: String = VOICE,
        @SerialName("response_format") val responseFormat: String = "mp3",
        @SerialName("sample_rate") val sampleRate: Int = 32000,
    )

    /** 合成语音写到 cacheDir，返回 mp3 文件；任何失败返回 null（上层继续走下一级回退） */
    suspend fun synthesize(text: String, cacheDir: File, apiKey: String): File? =
        withContext(Dispatchers.IO) {
            val body = text.trim().take(MAX_CHARS)
            if (body.isEmpty() || apiKey.isBlank()) return@withContext null
            runCatching {
                cacheDir.mkdirs()
                val out = File(cacheDir, "tts_cld_${System.currentTimeMillis()}.mp3")
                val payload = json.encodeToString(SpeechRequest(input = body))
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    // 太小的响应基本是错误 JSON，不是音频
                    if (bytes.size < 200) return@use null
                    out.writeBytes(bytes)
                    out
                }
            }.getOrNull()
        }
}
