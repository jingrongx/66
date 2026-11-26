package com.lvyoubao.app.ui.common

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 官方语音合成（POST https://api.deepseek.com/v1/tools/tts）。
 *
 * 与硅基流动 CosyVoice2 一样是"一次请求拿整段音频"的普通 HTTP，比 Edge 的 WebSocket
 * 抗网络抖动。音色 stella（女声，默认）/ tide（男声），复用主对话的 DeepSeek API Key。
 * 按官方计费（中文字符计 2 个字符），设置页已标注"收费"。
 *
 * 这是固定端点：DeepSeek 的 /v1/tools/tts 只在官方域名提供，不跟随设置里的自定义 baseUrl
 * （兼容服务没有这个端点）。调用方负责只在选中 deepseek_tts 且 Key 配置时启用。
 */
object DeepSeekTts {

    private const val ENDPOINT = "https://api.deepseek.com/v1/tools/tts"
    /** 官方单次上限 10000 计费字符，这里压到与 TtsPlayer 分段一致，避免超长失败 */
    private const val MAX_CHARS = 900
    /** 音色：stella 女声 / tide 男声 */
    private val VOICES = listOf("stella", "tide")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 最近一次失败原因（供诊断/日志），成功清空 */
    @Volatile
    var lastFailure: String? = null
        private set

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class TtsRequest(
        val text: String,
        @SerialName("voice_id") val voiceId: String = "stella",
        val format: String = "wav",
        @SerialName("filter_markdown") val filterMarkdown: Boolean = true,
    )

    /**
     * 合成到 cacheDir，返回 wav 文件；失败返回 null（上层按引擎链继续降级）。
     * 第一个音色失败时换第二个重试——官方音色 id 偶有调整时自动适应。
     */
    suspend fun synthesize(text: String, cacheDir: File, apiKey: String): File? =
        withContext(Dispatchers.IO) {
            val body = text.trim().take(MAX_CHARS)
            if (body.isEmpty() || apiKey.isBlank()) return@withContext null
            cacheDir.mkdirs()
            val out = File(cacheDir, "tts_ds_${System.currentTimeMillis()}.wav")
            for (voice in VOICES) {
                val file = runCatching { attempt(body, out, apiKey, voice) }
                    .onFailure { e ->
                        ttsLog("DeepSeekTts 请求异常：${e.javaClass.simpleName} ${e.message ?: ""}")
                        lastFailure = "DeepSeek TTS 网络异常：${e.javaClass.simpleName}"
                    }
                    .getOrNull()
                if (file != null) {
                    lastFailure = null
                    return@withContext file
                }
            }
            null
        }

    private fun attempt(body: String, out: File, apiKey: String, voice: String): File? {
        val payload = json.encodeToString(TtsRequest(text = body, voiceId = voice))
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val serverMsg = runCatching {
                    response.body?.string()?.let { err ->
                        Regex("\"(?:message|error)\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
                            ?: err.take(160)
                    }
                }.getOrNull()
                val hint = when (response.code) {
                    401 -> "（Key 无效）"
                    402 -> "（余额不足）"
                    429 -> "（并发超限）"
                    400 -> "（文本为空或未过内容审核）"
                    503 -> "（服务繁忙）"
                    else -> ""
                }
                lastFailure = "DeepSeek TTS HTTP ${response.code}$hint" + (serverMsg?.let { "：$it" } ?: "")
                ttsLog("DeepSeekTts 失败（voice=$voice）：$lastFailure")
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            // 太小的响应基本是错误 JSON，不是音频
            if (bytes.size < 200) {
                lastFailure = "DeepSeek TTS 返回异常（${bytes.size}B，疑似错误信息非音频）"
                ttsLog("DeepSeekTts 失败：$lastFailure")
                return null
            }
            out.writeBytes(bytes)
            ttsLog("DeepSeekTts 成功（voice=$voice，${bytes.size}B）")
            return out
        }
    }
}
