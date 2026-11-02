package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : IOException(message)

/**
 * 统一 AI 接口层（OpenAI 兼容，默认 DeepSeek）。
 * - 深度思考：thinking 参数（DeepSeek）
 * - 联网搜索：DeepSeek Search API（/v1/tools/search），结果注入上下文后再生成
 * - Function Calling：工具定义 + tool_calls 协议循环（POI/天气/路线）
 * - 视觉：user 消息 content 数组 + base64 data URL 图片
 */
class AiClient(private val settings: AiSettings) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val preset = presetById(settings.presetId)
    private val isDeepSeek get() = preset.id == "deepseek"
    private val baseUrl get() = settings.baseUrl.trimEnd('/')
    private val authHeader get() = "Bearer ${settings.apiKey}"

    /** 工具执行器：模型发起 tool_calls 时回调（在 IO 线程执行，允许阻塞） */
    fun interface ToolExecutor {
        fun execute(name: String, argumentsJson: String): String
    }

    /** 主对话：messages 已含系统提示与上下文；searchEnabled 时先取实时搜索结果注入 */
    suspend fun chat(
        messages: List<OutMessage>,
        thinking: Boolean,
        searchEnabled: Boolean,
        toolExecutor: ToolExecutor? = null,
    ): Result<AssistantReply> = withContext(Dispatchers.IO) {
        guard {
            val effective = if (searchEnabled && isDeepSeek) {
                val lastUser = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
                trySearchInject(lastUser, messages)
            } else {
                messages
            }
            generate(effective, thinking, toolExecutor)
        }
    }

    /**
     * 无结构化输出的普通对话（随手记生成游记、纯聊天场景），返回模型原文。
     * 不带 response_format/tool，兼容所有 OpenAI 兼容服务商。
     */
    suspend fun chatPlain(messages: List<OutMessage>, thinking: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            guard {
                val turn = requestOnce(
                    buildMessagesJson(messages),
                    thinking = thinking && isDeepSeek,
                    withJson = false,
                    withTools = false,
                )
                turn.content?.takeIf { it.isNotBlank() }
                    ?: throw ApiException("这次没有生成出内容，请重试一次")
            }
        }

    /** 搜索失败时静默降级为无搜索（不阻断回答） */
    private suspend fun trySearchInject(query: String, messages: List<OutMessage>): List<OutMessage> {        if (query.isBlank()) return messages
        val results = runCatching { search(query.take(128)) }.getOrNull().orEmpty()
        if (results.isEmpty()) return messages
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val block = buildString {
            appendLine("【实时搜索结果】（DeepSeek 搜索，检索时间 $now）")
            results.forEachIndexed { i, r ->
                appendLine("${i + 1}. ${r.title}（${r.site_name ?: host(r.url)}${r.date?.let { "，" + it.take(10) } ?: ""}）")
                appendLine("   摘要：${r.summary.ifBlank { r.snippet }}")
                appendLine("   来源：${r.url}")
            }
            append("请优先采用以上实时信息中的事实；若与你的既有知识冲突，以搜索结果为准。引用时给出具体数字。")
        }
        return messages.dropLast(1) +
            OutMessage("system", block) +
            messages.last()
    }

    private fun host(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    /** POST {base}/v1/tools/search（DeepSeek 联网搜索，$0.75/千次成功请求） */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = json.encodeToJsonElement(
            SearchRequestBody(query = query, count = 5, need_content = false),
        ).toString()
        val request = Request.Builder()
            .url("$baseUrl/v1/tools/search")
            .header("Authorization", authHeader)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(mapError(response.code, text), response.code)
            json.decodeFromString<SearchResponseBody>(text).results
        }
    }

    /**
     * 生成入口。
     * - 有工具执行器且为 DeepSeek：走 Function Calling 循环（最多 3 轮），事实来自工具返回
     * - 工具参数被拒（400）→ 降级为纯生成（三级参数降级）
     */
    private fun generate(
        messages: List<OutMessage>,
        thinking: Boolean,
        toolExecutor: ToolExecutor?,
    ): AssistantReply {
        val useTools = toolExecutor != null && isDeepSeek
        if (!useTools) return generateWithAttempts(messages, thinking, withTools = false, executor = null)

        var convo: JsonArray = buildMessagesJson(messages)
        var round = 0
        while (true) {
            val turn = try {
                requestOnce(convo, thinking = thinking, withJson = false, withTools = true)
            } catch (e: ApiException) {
                if (e.code == 400) return generateWithAttempts(messages, thinking, withTools = false, executor = null)
                throw e
            }
            if (turn.toolCalls.isEmpty() || round >= 3) {
                val content = turn.content?.takeIf { it.isNotBlank() }
                    ?: turn.rawAssistant.let { extractReasoning(it) }?.takeIf { it.isNotBlank() }
                return content?.let { parseReply(it) }
                    ?: AssistantReply(answer = "这次没有生成出回答内容，请重试一次或换个问法。")
            }
            val results = turn.toolCalls.map { call ->
                val name = call.function?.name.orEmpty()
                val output = runCatching {
                    toolExecutor.execute(name, call.function?.arguments ?: "{}")
                }.getOrElse { e -> "工具执行失败：${e.message}" }
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", output)
                }
            }
            convo = JsonArray(convo + listOf(turn.rawAssistant) + results)
            round++
        }
    }

    private data class Attempt(val withJson: Boolean, val withThinking: Boolean, val withTools: Boolean)

    /** 无工具（或工具被拒后）的生成：三级参数降级，兼容不支持扩展参数的服务商 */
    private fun generateWithAttempts(
        messages: List<OutMessage>,
        thinking: Boolean,
        withTools: Boolean,
        executor: ToolExecutor?,
    ): AssistantReply {
        val attempts = listOf(
            Attempt(withJson = true, withThinking = true, withTools = false),
            Attempt(withJson = false, withThinking = true, withTools = false),
            Attempt(withJson = false, withThinking = false, withTools = false),
        )
        var lastError: ApiException? = null
        attempts.forEach { attempt ->
            try {
                val turn = requestOnce(
                    buildMessagesJson(messages),
                    thinking = attempt.withThinking && thinking,
                    withJson = attempt.withJson,
                    withTools = false,
                )
                turn.content?.takeIf { it.isNotBlank() }?.let { return parseReply(it) }
                // content 为空白时视为本次没生成出内容，继续下一次尝试（避免返回空卡片）
                lastError = ApiException("这次没有生成出回答内容，请重试一次或换个问法。")
            } catch (e: ApiException) {
                lastError = e
            }
        }
        throw lastError ?: ApiException("这次没有生成出回答内容，请重试一次或换个问法。")
    }

    private data class ChatTurn(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val rawAssistant: JsonElement,
    )

    private fun requestOnce(
        messagesJson: JsonArray,
        thinking: Boolean,
        withJson: Boolean,
        withTools: Boolean,
    ): ChatTurn {
        val body = buildJsonObject {
            put("model", settings.model)
            put("messages", messagesJson)
            if (withJson) {
                put("response_format", json.encodeToJsonElement(ResponseFormat("json_object")))
            }
            if (isDeepSeek) {
                put("thinking", json.encodeToJsonElement(ThinkingMode(if (thinking) "enabled" else "disabled")))
            }
            if (withTools) {
                put("tools", json.encodeToJsonElement(AppTools.definitions.map {
                    ToolDefinition(type = "function", function = ToolFunction(it.name, it.description, it.parameters))
                }))
            }
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", authHeader)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(mapError(response.code, bodyText), response.code)
            val message = json.decodeFromString<ChatResponse>(bodyText)
                .choices.firstOrNull()?.message
                ?: throw ApiException("这次没有生成出回答内容，请重试一次或换个问法。")
            return ChatTurn(
                content = message.content,
                toolCalls = message.tool_calls,
                rawAssistant = json.encodeToJsonElement(message),
            )
        }
    }

    private fun buildMessagesJson(messages: List<OutMessage>) = buildJsonArray {
        messages.forEach { m ->
            add(
                buildJsonObject {
                    put("role", m.role)
                    if (m.images.isEmpty() || m.role != "user") {
                        put("content", m.text)
                    } else {
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", m.text) })
                                m.images.forEach { url ->
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", json.encodeToJsonElement(ImageUrlPart(url)))
                                        }
                                    )
                                }
                            },
                        )
                    }
                }
            )
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
                if (!response.isSuccessful) throw ApiException(mapError(response.code, bodyText), response.code)
                json.decodeFromString<ModelsResponse>(bodyText).data.map { it.id }.filter { it.isNotBlank() }
            }
        }
    }

    private fun parseReply(content: String): AssistantReply {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start in 0 until end) {
            val parsed = runCatching {
                json.decodeFromString<AssistantReply>(content.substring(start, end + 1))
            }.getOrNull()
            if (parsed != null && (parsed.answer.isNotBlank() || parsed.steps.any { it.isNotBlank() })) {
                return parsed.copy(
                    answer = parsed.answer.trim(),
                    steps = parsed.steps.map { it.trim() }.filter { it.isNotEmpty() },
                    action = parsed.action?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) },
                )
            }
        }
        // 模型没按 JSON 输出（或 JSON 里没有实际内容）时兜底：整段当回答，交给规则校验层标记
        return AssistantReply(answer = content.trim())
    }

    /** 兜底读取思考模型放在 reasoning_content 里的正文（部分服务商在 content 留空时用） */
    private fun extractReasoning(rawAssistant: JsonElement): String? = runCatching {
        (rawAssistant as? JsonObject)?.get("reasoning_content")?.jsonPrimitive?.content
    }.getOrNull()

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

    companion object {
        /**
         * 回答策略：快问快答短而准；攻略类给可执行要点。
         * json_object 模式要求提示词里出现 “json” 一词。
         */
        val SYSTEM_PROMPT = """
            你是旅行助手「旅游宝」，像一个懂行的贴身向导，只回答旅行场景的问题（路线、玩法、吃饭、休息、交通、天气、门票、关门时间等）。
            按问题类型组织回答：
            1. 快问快答（还要走多久、附近有没有吃的、该不该回）：answer 用 1-2 句话，必须包含具体数字（距离/时间/价格/排队分钟），steps 留空。
            2. 攻略与路线（怎么玩、怎么上去、有什么好玩的、值不值得去）：answer 给一句总述，steps 给 3-5 条要点，每条都要具体可执行并带数字（耗时、费用、距离、班次、发车时间），按先后顺序排列；上山路线要写清入口位置、方式（徒步/索道/车）、大致耗时和体力要求。
            3. 需要实时信息（附近地点、天气、去某地的路线）时，先调用工具查真实数据，再回答；绝不编造数字。
            4. 不确定的数字绝不编造：优先使用搜索结果或工具返回的事实；没有可靠来源就明说"不确定"，并建议问谁（景区电话、工作人员、官方公众号）。
            以 json 格式输出：{"answer": "一句话总结，不超过2句", "steps": ["要点1", "要点2", ...]，快问快答时为空数组, "action": "一句话的下一步动作建议，没有则为 null"}
        """.trimIndent()
    }
}
