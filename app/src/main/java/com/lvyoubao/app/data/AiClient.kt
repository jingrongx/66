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
import kotlinx.serialization.json.jsonObject
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
        onAnswer: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
    ): Result<AssistantReply> = withContext(Dispatchers.IO) {
        guard {
            val effective = if (searchEnabled && isDeepSeek) {
                val lastUser = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
                trySearchInject(lastUser, messages)
            } else {
                messages
            }
            generate(effective, thinking, toolExecutor, onAnswer, onReasoning)
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
        onAnswer: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
    ): AssistantReply {
        val useTools = toolExecutor != null && isDeepSeek
        if (!useTools) return generateWithAttempts(messages, thinking, withTools = false, executor = null, onAnswer, onReasoning)

        // 回答字段增量提取器：模型输出的是 JSON 信封，把 "answer" 的值剥出来流给界面
        val streamer = AnswerFieldStreamer(onAnswer)
        var convo: JsonArray = buildMessagesJson(messages)
        var round = 0
        while (true) {
            val turn = try {
                requestOnceStream(
                    convo,
                    thinking = thinking,
                    withJson = false,
                    withTools = true,
                    onContent = { streamer.feed(it) },
                    onReasoning = onReasoning,
                )
            } catch (e: ApiException) {
                if (e.code == 400) return generateWithAttempts(messages, thinking, withTools = false, executor = null, onAnswer, onReasoning)
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
        onAnswer: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
    ): AssistantReply {
        val attempts = listOf(
            Attempt(withJson = true, withThinking = true, withTools = false),
            Attempt(withJson = false, withThinking = true, withTools = false),
            Attempt(withJson = false, withThinking = false, withTools = false),
        )
        var lastError: ApiException? = null
        attempts.forEach { attempt ->
            // json 信封的尝试不往界面流（避免闪现原始 JSON）；无 json 的尝试流 answer 值/纯文本
            val streamer = if (attempt.withJson) null else AnswerFieldStreamer(onAnswer)
            try {
                val turn = requestOnceStream(
                    buildMessagesJson(messages),
                    thinking = attempt.withThinking && thinking,
                    withJson = attempt.withJson,
                    withTools = false,
                    onContent = streamer?.let { s -> { d: String -> s.feed(d) } },
                    onReasoning = onReasoning,
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

    /**
     * 流式单轮：SSE 解析 delta.content / delta.reasoning_content / delta.tool_calls。
     * content 增量经 onContent 回调（调用方负责剥 JSON 信封），工具调用按 index 累积。
     */
    private fun requestOnceStream(
        messagesJson: JsonArray,
        thinking: Boolean,
        withJson: Boolean,
        withTools: Boolean,
        onContent: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
    ): ChatTurn {
        val body = buildJsonObject {
            put("model", settings.model)
            put("messages", messagesJson)
            if (withJson) {
                put("response_format", json.encodeToJsonElement(ResponseFormat("json_object")))
            }
            put("stream", true)
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
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                throw ApiException(mapError(response.code, bodyText), response.code)
            }
            val source = response.body?.source() ?: throw ApiException("服务端没有返回内容")
            val contentBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            class ToolAcc { var id = ""; var name = ""; var args = StringBuilder() }
            val toolAccs = linkedMapOf<Int, ToolAcc>()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"] as? JsonObject ?: continue
                (delta["reasoning_content"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { r ->
                    if (r.isNotEmpty()) {
                        reasoningBuf.append(r)
                        onReasoning?.invoke(r)
                    }
                }
                (delta["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { s ->
                    if (s.isNotEmpty()) {
                        contentBuf.append(s)
                        onContent?.invoke(s)
                    }
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                    val o = el.jsonObject
                    val idx = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val acc = toolAccs.getOrPut(idx) { ToolAcc() }
                    (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { if (it.isNotEmpty()) acc.id = it }
                    val fn = o["function"] as? JsonObject
                    (fn?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { if (it.isNotEmpty()) acc.name = it }
                    (fn?.get("arguments") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { if (it.isNotEmpty()) acc.args.append(it) }
                }
            }
            val toolCalls = toolAccs.toSortedMap().values
                .filter { it.name.isNotEmpty() }
                .map { ToolCall(id = it.id, type = "function", function = FunctionCall(name = it.name, arguments = it.args.toString().ifEmpty { "{}" })) }
            val rawAssistant = buildJsonObject {
                put("role", "assistant")
                if (contentBuf.isNotEmpty()) put("content", contentBuf.toString())
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", json.encodeToJsonElement(toolCalls))
                }
            }
            return ChatTurn(
                content = contentBuf.toString().ifEmpty { null },
                toolCalls = toolCalls,
                rawAssistant = rawAssistant,
            )
        }
    }

    /**
     * 从 JSON 信封里增量剥 "answer" 字段的值流给界面。
     * 若开头就不是 JSON（模型走了纯文本兜底），整段原文直接流出去。
     */
    private class AnswerFieldStreamer(val sink: ((String) -> Unit)?) {
        private val content = StringBuilder()
        private var emitted = 0
        private var mode = 0   // 0=探测中 1=JSON answer 值流 2=纯文本流

        fun feed(delta: String) {
            if (sink == null) return
            content.append(delta)
            val s = content.toString()
            if (mode == 0) {
                val trimmed = s.trimStart()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("{")) mode = 2
            }
            if (mode == 2) {
                // 流式阶段同步清 ** / 反引号（终稿 parseReply 还会全量清一遍），避免星号在生成中一闪而过
                val clean = stripStreamMarkdown(s)
                if (clean.length > emitted) {
                    sink.invoke(clean.substring(emitted))
                    emitted = clean.length
                }
                return
            }
            // JSON 模式：answer 值可以未闭合（流式增长），贪婪匹配到当前末尾
            val m = ANSWER_FIELD.find(s) ?: return
            val raw = unescapeJson(m.groupValues[1])
            val clean = stripStreamMarkdown(raw)
            if (clean.length > emitted) {
                sink.invoke(clean.substring(emitted))
                emitted = clean.length
            }
        }

        /**
         * 流式阶段的安全剥离：只清上下文无关的 ** 和反引号；末尾孤立的 * 可能与下一字符组成 **，
         * 押着一个不发（前缀稳定，已发出的文字不会被改写）。行首 # 等交给终稿统一清理。
         */
        private fun stripStreamMarkdown(s: String): String {
            var t = s.replace("**", "").replace("`", "")
            if (t.endsWith("*")) t = t.dropLast(1)
            return t
        }

        companion object {
            // "answer":"...（可未闭合）——值部分贪婪匹配到当前位置
            private val ANSWER_FIELD = Regex("\"answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")
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
                    answer = stripMarkdown(parsed.answer.trim()),
                    steps = parsed.steps.map { stripMarkdown(it.trim()) }.filter { it.isNotEmpty() },
                    action = parsed.action?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
                        ?.let { stripMarkdown(it) },
                    map = normalizeMapIntent(parsed.map),
                )
            }
            // 严格解析失败：模型的 JSON 字符串值里常有未转义引号（如 搜"赣运"），
            // 按字段边界把 answer/steps/action/map 抠出来，绝不把整段裸 JSON 当回答摆给用户
            lenientReply(content.substring(start, end + 1))?.let { return it }
        }
        // 模型把 map 意图 JSON 单独当回答输出（如 {"type":"place","keyword":"麻姑山"}）：
        // 识别出来转成真正的地图意图，别把裸 JSON 显示给用户
        val bareIntent = runCatching {
            val o = json.parseToJsonElement(content.trim()).jsonObject
            val type = o["type"]?.jsonPrimitive?.content.orEmpty()
            val keyword = o["keyword"]?.jsonPrimitive?.content.orEmpty()
            val destination = o["destination"]?.jsonPrimitive?.content.orEmpty()
            val isRoute = type.equals("route", true) || type == "路线" || type == "导航"
            if ((isRoute && destination.isNotBlank()) || (!isRoute && keyword.isNotBlank())) {
                MapIntent(
                    type = if (isRoute) "route" else "place",
                    keyword = keyword.trim(),
                    origin = o["origin"]?.jsonPrimitive?.content.orEmpty().trim(),
                    destination = destination.trim(),
                )
            } else null
        }.getOrNull()
        if (bareIntent != null) {
            val where = bareIntent.destination.ifBlank { bareIntent.keyword }
            return AssistantReply(
                answer = "帮你把「$where」放到地图上了，下方可以直接看位置和路线。",
                map = bareIntent,
            )
        }
        // 模型没按 JSON 输出（或 JSON 里没有实际内容）时兜底：整段当回答（同样清 Markdown），交给规则校验层标记
        return AssistantReply(answer = stripMarkdown(content.trim()))
    }

    /** map 意图归一：route 要有终点、place 要有关键词，中英写法统一成 place/route */
    private fun normalizeMapIntent(m: MapIntent?): MapIntent? {
        if (m == null) return null
        val isRoute = m.type.equals("route", true) || m.type == "路线" || m.type == "导航"
        val valid = (isRoute && m.destination.isNotBlank()) || (!isRoute && m.keyword.isNotBlank())
        if (!valid) return null
        return m.copy(
            type = if (isRoute) "route" else "place",
            keyword = m.keyword.trim(),
            origin = m.origin.trim(),
            destination = m.destination.trim(),
        )
    }

    /**
     * JSON 信封的宽容解析：模型输出的字符串值常带未转义引号，严格 JSON 解析必失败，
     * 整段裸 JSON 就会漏到回答卡上。按字段边界（", "steps" / ", "action" / ", "map" / "}）
     * 把各字段抠出来；抠不出非空 answer 返回 null，继续走裸 JSON 意图 / 纯文本兜底。
     */
    private fun lenientReply(envelope: String): AssistantReply? {
        val answer = Regex("\"answer\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"(steps|action|map)\"", RegexOption.DOT_MATCHES_ALL)
            .find(envelope)?.groupValues?.get(1)
            ?: Regex("\"answer\"\\s*:\\s*\"(.*)\"", RegexOption.DOT_MATCHES_ALL)
                .find(envelope)?.groupValues?.get(1)
            ?: return null
        if (answer.isBlank()) return null
        val steps = Regex("\"steps\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(envelope)?.groupValues?.get(1)
            ?.split("\",\"")
            ?.map { unescapeJson(it.trim().trim('"', ' ', ',')) }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val action = Regex("\"action\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"map\"", RegexOption.DOT_MATCHES_ALL)
            .find(envelope)?.groupValues?.get(1)
            ?: Regex("\"action\"\\s*:\\s*\"(.*?)\"\\s*\\}", RegexOption.DOT_MATCHES_ALL)
                .find(envelope)?.groupValues?.get(1)
        val mapIntent = Regex("\"map\"\\s*:\\s*\\{", RegexOption.DOT_MATCHES_ALL).find(envelope)?.let { mm ->
            val bodyStart = mm.range.last + 1
            val bodyEnd = envelope.indexOf('}', bodyStart)
            if (bodyEnd > bodyStart) {
                val body = envelope.substring(bodyStart, bodyEnd)
                fun field(name: String): String =
                    Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1).orEmpty()
                MapIntent(
                    type = field("type").ifBlank { "place" },
                    keyword = field("keyword"),
                    origin = field("origin"),
                    destination = field("destination"),
                )
            } else null
        }
        return AssistantReply(
            answer = stripMarkdown(unescapeJson(answer).trim()),
            steps = steps.map { stripMarkdown(unescapeJson(it)) }.filter { it.isNotEmpty() },
            action = action?.let {
                stripMarkdown(unescapeJson(it)).trim().takeIf { s -> s.isNotEmpty() && !s.equals("null", true) }
            },
            map = normalizeMapIntent(mapIntent),
        )
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
            你是全能生活助手「旅游宝」：旅行是你的看家本领（路线、玩法、吃饭、休息、交通、天气、门票、关门时间等），像懂行的贴身向导；但能力绝不设限——生活百科、做菜、健康常识、学习工作、闲聊解闷等任何日常话题都照常认真回答，绝不拒答、绝不说"我只能答旅行问题"。
            按问题类型组织回答：
            1. 快问快答（还要走多久、该不该回）：answer 用 1-2 句话，必须包含具体数字（距离/时间/价格/排队分钟），steps 留空。
            2. 攻略与路线（怎么玩、怎么上去、有什么好玩的、值不值得去）：answer 给一句总述，steps 给 3-5 条要点，每条都要具体可执行并带数字（耗时、费用、距离、班次、发车时间），按先后顺序排列；上山路线要写清入口位置、方式（徒步/索道/车）、大致耗时和体力要求。
            3. 涉及地点位置（附近吃的/歇脚/厕所/药店/加油站在哪、某地在哪、从A到B怎么走、回家的路）：必须先调用 search_poi / get_route 查真实数据再回答，绝不回答"没法实时确认"，也绝不让用户自己去地图搜；answer 只用 1-2 句给结论（最近的名字+距离/步行时间），steps 留空，并输出 map 字段。绝不要写"打开地图APP→点搜索→筛距离"这类使用教程——界面会直接在回答下方摆出地图卡，用户点「去这里」就开始导航，教程对他们毫无用处。
            4. 不确定的数字绝不编造：优先使用搜索结果或工具返回的事实；没有可靠来源就明说"不确定"，并建议问谁（景区电话、工作人员、官方公众号）。
            5. 情境里给了「常用地址·家的地址」时，问回家/回家的路怎么走：直接输出 map（type=route，origin 留空=当前位置，destination 填家的地址），answer 一句话报距离和预计时间；绝不要反问"回哪"，也不要联想成歌名/游戏/影视。
            以 json 格式输出：{"answer": "一句话总结，不超过2句", "steps": ["要点1", "要点2", ...]，快问快答时为空数组, "action": "一句话的下一步动作建议，没有则为 null"}
            注意：answer 字段必须放在 JSON 最前面（用户边生成边看，answer 先出才有流式体验）
            另外，当回答涉及具体地点时（某景点/场馆/店铺的位置、怎么去、从A到B、值得一去的去处），加一个 "map" 字段让界面在回答下方配一张地图：
            {"type": "place" 或 "route", "keyword": "地点关键词", "origin": "起点名", "destination": "目的地名"}
            - 讲某个地方在哪/介绍某地：type=place，keyword 填规范地点名（如"汤显祖纪念馆"）
            - 从A到B怎么走/多远：type=route，origin 填起点名（用户说"从这里/从我这"就填空字符串，界面会用当前位置），destination 填终点的规范地点名
            - keyword/destination 只填地名本身，绝不照抄用户的原句：用户问"大会岭有什么特色"，destination 只能是"大会岭"，"大会岭有什么特色"是问题不是地名
            - 回答与具体地点无关（纯聊天、天气、时间、体力建议）时不要输出 map 字段
            - 地点名要规范完整（用官方名称，不要写"那个博物馆"），宁缺毋滥：拿不准就别输出 map
            - map 字段必须和 answer/steps 一起输出，绝不允许只输出一段 map JSON、也绝不允许把 JSON 当回答正文；用户看不到 JSON，只看得到 answer 的中文
        """.trimIndent()

        /**
         * 清掉模型输出里的 Markdown 痕迹：星号加粗、下划线加粗、反引号代码、三反引号代码块、
         * 行首井号标题、行首星号和圆点列表符。
         * 界面卡片是纯文本渲染，星号井号原样摆出来只会碍眼；语音播报同样不该读这些符号。
         */
        internal fun stripMarkdown(s: String): String {
            var t = s
                .replace("```", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
            t = t.replace(Regex("(?m)^#{1,6} +"), "")      // 行首标题井号（# 后必须带空格，#话题标签 不受影响）
            t = t.replace(Regex("(?m)^[ \\t]*[*•][ \\t]+"), "") // 行首圆点/星号列表符
            return t.trim()
        }

        /**
         * 回答详略指令（设置页可选 简洁/中等/详尽，默认简洁）。
         * 单独一条 system 消息发，声明优先级最高：与基础提示词里的长度要求冲突时以这条为准；
         * 但地点位置类（基础提示词第 3 条）任何时候都是 1-2 句短答，不受详略影响。
         * 用户在提问里明确要"详细说说/给攻略"时，即使简洁档也要展开要点。
         */
        fun answerStyleRule(style: String): String {
            val body = when (style) {
                "medium" -> "中等——快问快答 1-2 句带关键数字；攻略、路线、方案对比类给一句总述 + 3-5 条要点。"
                "detailed" -> "详尽——攻略、路线、方案对比类给一句总述 + 5-8 条要点，把费用、耗时、班次、注意事项、备选方案讲全；只有纯闲聊才简短。"
                else -> "简洁（平时问答的默认档）——answer 只用 1-2 句给结论并带上最关键的一两个数字，steps 一律留空；只有用户在提问里明确要求详细（如\"详细说说\"\"给我攻略\"\"一步步讲\"）才按攻略类展开要点。"
            }
            return "【回答详略 · 用户设置】$body 本条优先级最高，与基础提示词里任何长度/条数要求冲突时以本条为准；但地点位置类问题任何时候都是 1-2 句短答，不受本条影响。"
        }

        /** JSON 字符串转义还原：流式 answer 剥壳与宽容解析共用 */
        private fun unescapeJson(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == 92.toChar() && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        'u' -> {
                            if (i + 5 < s.length) {
                                runCatching { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()) }
                                i += 6
                            } else { i = s.length }
                        }
                        '/' -> { sb.append('/'); i += 2 }
                        else -> { sb.append(n); i += 2 }
                    }
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
