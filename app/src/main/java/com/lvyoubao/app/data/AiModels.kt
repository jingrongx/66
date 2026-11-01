package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

/** 单卡结构：一句短回答 + 一个可执行动作（对应推演方案里的“回答 + 单卡”） */
@Serializable
data class AssistantReply(
    val answer: String,
    val action: String? = null,
)

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ThinkingMode(val type: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val response_format: ResponseFormat? = null,
    val thinking: ThinkingMode? = null,
)

@Serializable
data class AssistantMessage(val content: String? = null)

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
