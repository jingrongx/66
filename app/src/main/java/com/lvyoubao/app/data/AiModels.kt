package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

/** 单卡结构：一句短回答 + 要点列表（攻略类）+ 一个可执行动作 + 可选的地图意图（AI 自己判断要不要配图） */
@Serializable
data class AssistantReply(
    val answer: String,
    val steps: List<String> = emptyList(),
    val action: String? = null,
    val map: MapIntent? = null,
)

/**
 * AI 输出的地图意图：模型判断回答涉及具体地点时给出，客户端据此去高德解析坐标并生成地图卡。
 * type=place 讲某地在哪/介绍某地（keyword 生效）；type=route 从A到B（origin 可空=当前位置，destination 生效）。
 */
@Serializable
data class MapIntent(
    val type: String = "place",
    val keyword: String = "",
    val origin: String = "",
    val destination: String = "",
)

/** 发给模型的一条消息；images 为 base64 data URL（仅 user 消息） */
data class OutMessage(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
)

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ThinkingMode(val type: String)

@Serializable
data class ImageUrlPart(val url: String, val detail: String = "low")

@Serializable
data class ContentPart(val type: String, val text: String? = null, val image_url: ImageUrlPart? = null)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val response_format: ResponseFormat? = null,
    val thinking: ThinkingMode? = null,
)

@Serializable
data class AssistantMessage(
    val content: String? = null,
    val tool_calls: List<ToolCall> = emptyList(),
)

@Serializable
data class ToolCall(val id: String = "", val type: String = "function", val function: FunctionCall? = null)

@Serializable
data class FunctionCall(val name: String = "", val arguments: String = "{}")

/** Function Calling 里的工具定义与工具结果消息 */
@Serializable
data class ToolFunction(val name: String, val description: String, val parameters: String)

@Serializable
data class ToolDefinition(val type: String, val function: ToolFunction)

@Serializable
data class ToolResultMessage(
    val role: String = "tool",
    val tool_call_id: String,
    val content: String,
)

@Serializable
data class ChatChoice(val message: AssistantMessage? = null)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
data class ModelEntry(val id: String = "")

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ApiErrorBody(val error: ApiError? = null)

@Serializable
data class ApiError(val message: String? = null)

// ---------- DeepSeek 联网搜索（POST /v1/tools/search） ----------

@Serializable
data class SearchRequestBody(
    val query: String,
    val count: Int = 5,
    val need_content: Boolean = false,
)

@Serializable
data class SearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val summary: String = "",
    val date: String? = null,
    val site_name: String? = null,
    val authority: Int = -1,
)

@Serializable
data class SearchResponseBody(val results: List<SearchResult> = emptyList())
