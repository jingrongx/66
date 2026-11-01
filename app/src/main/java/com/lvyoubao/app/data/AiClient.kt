package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/**
 * 统一的 AI 接口层：所有服务商都走 OpenAI 兼容协议（chat/completions + models），
 * 默认 DeepSeek。后续 Function Calling（查 POI/客流/路由）就在这一层扩展。
 */
class AiClient(private val settings: AiSettings) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val preset = presetById(settings.presetId)
    private val baseUrl get() = settings.baseUrl.trimEnd('/')
    private val authHeader get() = "Bearer ${settings.apiKey}"

    suspend fun chat(question: String): Result<AssistantReply> = withContext(Dispatchers.IO) {
        guard {
            val full = buildJsonObject {
                put("model", settings.model)
                put("messages", json.encodeToJsonElement(listOf(
                    ChatMessage("system", SYSTEM_PROMPT),
                    ChatMessage("user", question),
                )))
                put("response_format", json.encodeToJsonElement(ResponseFormat("json_object")))
                if (preset.supportsThinkingToggle) {
                    put("thinking", json.encodeToJsonElement(ThinkingMode(if (settings.thinking) "enabled" else "disabled")))
                }
            }.toString()
            val reply = postForReply(full)
            // 个别兼容服务商不支持 response_format/thinking，去掉扩展参数后重试一次
            if (reply == null) {
                val minimal = buildJsonObject {
                    put("model", settings.model)
                    put("messages", json.encodeToJsonElement(listOf(
                        ChatMessage("system", SYSTEM_PROMPT),
                        ChatMessage("user", question),
                    )))
                }.toString()
                postForReply(minimal) ?: throw ApiException("服务端没有返回内容")
            } else {
                reply
            }
        }
    }

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        guard {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", authHeader)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(mapError(response.code, bodyText))
                json.decodeFromString<ModelsResponse>(bodyText).data.map { it.id }.filter { it.isNotBlank() }
            }
        }
    }

    /** 返回 null 表示请求因扩展参数被拒（400），调用方应降级重试 */
    private fun postForReply(body: String): AssistantReply? {
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", authHeader)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (response.code == 400) {
                val serverMessage = extractErrorMessage(bodyText)
                if (serverMessage != null) return null
                throw ApiException(mapError(400, bodyText))
            }
            if (!response.isSuccessful) throw ApiException(mapError(response.code, bodyText))
            val content = json.decodeFromString<ChatResponse>(bodyText)
                .choices.firstOrNull()?.message?.content
                ?: throw ApiException("服务端没有返回内容")
            return parseReply(content)
        }
    }

    private fun parseReply(content: String): AssistantReply {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start in 0 until end) {
            val parsed = runCatching {
                json.decodeFromString<AssistantReply>(content.substring(start, end + 1))
            }.getOrNull()
            if (parsed != null) {
                return parsed.copy(
                    answer = parsed.answer.trim(),
                    action = parsed.action?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) },
                )
            }
        }
        // 模型没按 JSON 输出时兜底：整段当回答，交给规则校验层标记降级
        return AssistantReply(answer = content.trim())
    }

    private fun extractErrorMessage(bodyText: String): String? = runCatching {
        json.decodeFromString<ApiErrorBody>(bodyText).error?.message
    }.getOrNull()

    private fun mapError(code: Int, bodyText: String): String {
        val server = extractErrorMessage(bodyText)
        val hint = when (code) {
            401 -> "API Key 无效，请在设置里检查"
            402 -> "账户余额不足"
            422, 400 -> "请求参数被拒绝"
            429 -> "请求太频繁，请稍后再试"
            in 500..599 -> "服务商暂时不可用"
            else -> "请求失败（HTTP $code）"
        }
        return if (server.isNullOrBlank()) hint else "$hint：$server"
    }

    private inline fun <T> guard(block: () -> T): Result<T> = runCatching(block).recoverCatching { e ->
        throw when (e) {
            is ApiException -> e
            is IOException -> ApiException(if (e.message?.contains("timeout", true) == true) "网络超时，请检查网络后重试" else "网络异常，请检查网络后重试")
            else -> ApiException("出错了：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        /**
         * 一句话、一个动作、一个数字；不确定就说不知道。
         * 要求 JSON 输出（json_object 模式要求提示词里出现 “json” 一词）。
         */
        val SYSTEM_PROMPT = """
            你是旅行助手「旅游宝」，像一个懂行的贴身向导。
            回答规则：
            1. 只回答旅行场景的问题（路线、吃饭、休息、交通、天气、关门时间等）；
            2. 回答不超过 2 句话；
            3. 必须包含具体数字（距离、时间、价格或排队分钟数），不确定的数字不许编；
            4. 尽量落到一个立刻能执行的动作上；
            5. 不确定或不知道的，直说不知道并建议问谁，绝不编造。
            以 json 格式输出：{"answer": "不超过2句的短回答", "action": "一句话的下一步动作建议，没有则为 null"}
        """.trimIndent()
    }
}
