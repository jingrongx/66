package com.lvyoubao.app.data.duplex

import com.lvyoubao.app.data.AppTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * dialog_context 里的一条历史（user/assistant 严格成对，官方最多记 20 轮）。
 * 顶层 public：DuplexProtocol 是 internal，客户端/组装层的公开 API 也要传它。
 */
data class DialogTurn(val role: String, val text: String, val ts: Long)

/**
 * 豆包端到端实时语音（Seeduplex，全双工）Realtime 事件协议模型。
 *
 * 协议要点（对应火山官方文档 + go1.24_duplex_demo）：
 * - WebSocket Text Message，一帧一事件，纯 JSON；音频以 Base64 内嵌在 audio / delta 字段。
 * - 顶层 type 路由；event_id 可选但强烈建议携带（服务端下行回带，便于排障）。
 * - model 固定 1.2.6.1；专有能力（唱歌/联网/热词/位置）收敛进 extension.asr/tts/dialog。
 * - 上行 sample：16k / 单声道 / int16 小端 / 20ms 一包 640 字节；下行 24k。
 */
internal object DuplexProtocol {
    const val WS_URL = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
    const val MODEL = "1.2.6.1"
    const val INPUT_RATE = 16000
    const val OUTPUT_RATE = 24000
    /** 20ms @ 16k int16 mono = 640 字节 */
    const val FRAME_BYTES = 640

    // 音色：全双工 4 个官方主推音色；ID 来自 go demo 与官方音色列表
    const val VOICE_VIVI = "zh_female_vv_jupiter_bigtts"       // Vivi 2.0 女声（方言/歌唱）
    const val VOICE_XIAOHE = "zh_male_xiaohe_jupiter_bigtts"   // 小何 2.0 男声（方言/歌唱）
    const val VOICE_YUNZHOU = "zh_male_yunzhou_jupiter_bigtts" // 云舟 2.0 沉稳男声
    const val VOICE_XIAOTIAN = "zh_female_xiaotian_jupiter_bigtts" // 小天 2.0 甜美女声

    val VOICES = listOf(
        VOICE_VIVI to "Vivi 2.0 女声 · 方言/歌唱",
        VOICE_XIAOHE to "小何 2.0 男声 · 方言/歌唱",
        VOICE_YUNZHOU to "云舟 2.0 沉稳男声",
        VOICE_XIAOTIAN to "小天 2.0 甜美女声",
    )

    private val counter = AtomicLong(0)
    fun newEventId(): String = "event_${counter.incrementAndGet()}"
    fun newSessionId(): String = java.util.UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true }

    // ============ 上行事件 ============

    /**
     * 建会话：session.create。
     * @param sessionId 断线续接时传 session.created 返回的 id（服务端上下文跟着走）；新会话传随机 id
     * @param tools Function Calling 工具定义（AppTools.definitions 直传），模型语音里决定要不要调
     * @param dialogContext 初始化上下文：三种模式共用会话的最近成对 QA。注意续接时不要再传，
     *   否则服务端已有的上下文 + 注入的历史会双份重复
     */
    fun sessionCreate(
        sessionId: String,
        instructions: String,
        voice: String,
        speed: Int = 0,
        loudness: Int = 0,
        inputType: String = "pcm",
        outputType: String = "pcm_s16le",
        tools: List<AppTools.ToolDef> = emptyList(),
        dialogContext: List<DialogTurn> = emptyList(),
    ): String = buildJsonObject {
        put("type", "session.create")
        put("event_id", newEventId())
        putJsonObject("session") {
            put("id", sessionId)
            put("model", MODEL)
            if (instructions.isNotBlank()) put("instructions", instructions)
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", inputType)
                        put("rate", INPUT_RATE)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", outputType)
                        put("rate", OUTPUT_RATE)
                    }
                    put("voice", voice)
                    put("speed", speed)
                    put("loudness", loudness)
                }
            }
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", t.name)
                            put("description", t.description)
                            // parameters 是现成的 JSON Schema 字符串，parse 后原样塞入
                            put("parameters", runCatching { json.parseToJsonElement(t.parameters) }.getOrNull() ?: buildJsonObject {})
                        })
                    }
                })
            }
        }
        putJsonObject("extension") {
            putJsonObject("asr") { putJsonObject("extra") {} }
            putJsonObject("tts") { putJsonObject("extra") {} }
            putJsonObject("dialog") {
                putJsonObject("extra") {}
                if (dialogContext.isNotEmpty()) {
                    put("dialog_context", buildJsonArray {
                        dialogContext.forEach { t ->
                            add(buildJsonObject {
                                put("role", t.role)
                                put("text", t.text)
                                put("timestamp", t.ts / 1000)
                            })
                        }
                    })
                }
            }
        }
    }.toString()

    /** FC 结果回传：response.function_call_arguments.done 之后，本地执行完把结果交给模型继续说 */
    fun toolResult(callId: String, result: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        put("event_id", newEventId())
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("call_id", callId)
                put("role", "tool")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", result)
                    })
                })
            })
        })
    }.toString()

    /** 更新会话（音色/语速/工具等），一次性全量覆盖 */
    fun sessionUpdate(voice: String, speed: Int = 0, loudness: Int = 0): String = buildJsonObject {
        put("type", "session.update")
        put("event_id", newEventId())
        putJsonObject("session") {
            putJsonObject("audio") {
                putJsonObject("output") {
                    put("voice", voice)
                    put("speed", speed)
                    put("loudness", loudness)
                }
            }
        }
    }.toString()

    /** 音频分片：Base64 内嵌 audio 字段 */
    fun audioAppend(base64Pcm: String): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("event_id", newEventId())
        put("audio", base64Pcm)
    }.toString()

    /** 简单事件：commit / mute / unmute / close / cancel */
    fun simple(type: String): String = buildJsonObject {
        put("type", type)
        put("event_id", newEventId())
    }.toString()

    /** 主动打招呼 / 干预回复：speech_text_buffer.append/commit */
    fun speechText(type: String, speechId: String, text: String): String = buildJsonObject {
        put("type", type)
        put("event_id", newEventId())
        put("speech_id", speechId)
        put("text", text)
    }.toString()

    // ============ 下行事件 ============

    /** 客户端关心的下行事件；其余（usage / context ack 等）忽略。 */
    sealed interface Downstream {
        data class SessionCreated(val id: String) : Downstream
        data class SessionClosed(val code: Int, val message: String) : Downstream
        data object AudioCommitted : Downstream
        data class AsrStarted(val itemId: String) : Downstream
        data class AsrDelta(val itemId: String, val delta: String) : Downstream
        data class AsrCompleted(val itemId: String, val transcript: String) : Downstream
        data class AsrFailed(val raw: String) : Downstream
        data class TextDelta(val responseId: String, val delta: String) : Downstream
        data class TextDone(val responseId: String, val text: String) : Downstream
        data class AudioStarted(val responseId: String, val ttsType: String) : Downstream
        /** base64 解码后的 24k PCM16LE 音频块 */
        data class AudioDelta(val responseId: String, val pcm: ByteArray) : Downstream
        data class AudioDone(val responseId: String, val statusCode: String) : Downstream
        data class ResponseCreated(val responseId: String) : Downstream
        data class Interrupted(val eventId: String) : Downstream
        data class Error(val code: String, val message: String, val type: String) : Downstream
        /** 一轮交互结束（携带用量）。这是播报态切换的权威信号，不依赖 ASR。 */
        data class ResponseDone(val responseId: String, val raw: String) : Downstream
        /** FC：模型决定调工具，参数已生成完。一次可能带多个调用项。 */
        data class FunctionCalls(val calls: List<FnCall>) : Downstream
        /** conversation.item.create 的 ack（上下文/FC 回传成功），仅确认用 */
        data object ItemAdded : Downstream
    }

    /** 一次函数调用：callId 用于结果回传配对 */
    data class FnCall(val callId: String, val name: String, val arguments: String)

    fun parse(frame: String): Downstream? {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        val type = root["type"]?.jsonPrimitive?.content ?: return null
        fun s(key: String) = root[key]?.jsonPrimitive?.content ?: ""
        return when (type) {
            "session.created" -> Downstream.SessionCreated(
                root["session"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
            )
            "session.closed" -> Downstream.SessionClosed(0, "")
            "input_audio_buffer.committed" -> Downstream.AudioCommitted
            "conversation.item.input_audio_transcription.started" -> Downstream.AsrStarted(s("item_id"))
            "conversation.item.input_audio_transcription.delta" -> Downstream.AsrDelta(s("item_id"), s("delta"))
            "conversation.item.input_audio_transcription.completed" ->
                // 真机抓包（2026-11 v3 duplex 实测）：文档写 transcript，服务端实际下发的字段是 text，
                // 只读 transcript 会拿到空串——通话里本机命令（机器人等）全靠这个 final 文本触发。
                Downstream.AsrCompleted(s("item_id"), s("transcript").ifBlank { s("text") })
            "conversation.item.input_audio_transcription.failed" -> Downstream.AsrFailed(frame)
            "response.output_text.delta" -> Downstream.TextDelta(s("response_id"), s("delta"))
            "response.output_text.done" -> Downstream.TextDone(s("response_id"), s("text"))
            "response.output_audio.started" -> Downstream.AudioStarted(s("response_id"), s("tts_type"))
            "response.output_audio.delta" -> {
                val pcm = runCatching {
                    android.util.Base64.decode(s("delta"), android.util.Base64.DEFAULT)
                }.getOrDefault(ByteArray(0))
                Downstream.AudioDelta(s("response_id"), pcm)
            }
            "response.output_audio.done" -> Downstream.AudioDone(s("response_id"), s("status_code"))
            "response.created" -> Downstream.ResponseCreated(s("response_id"))
            "response.canceled" -> Downstream.Interrupted(s("event_id"))
            "response.done" -> Downstream.ResponseDone(s("response_id"), frame)
            "response.function_call_arguments.done" -> {
                // items 数组，每项 call_id / name / arguments（arguments 是 JSON 字符串）
                val calls = (root["items"] as? JsonArray)?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    FnCall(
                        callId = obj["call_id"]?.jsonPrimitive?.content ?: "",
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        arguments = obj["arguments"]?.jsonPrimitive?.content ?: "{}",
                    )
                }.orEmpty().filter { it.callId.isNotBlank() && it.name.isNotBlank() }
                Downstream.FunctionCalls(calls)
            }
            "conversation.item.added" -> Downstream.ItemAdded
            "error" -> {
                val err = root["error"]?.jsonObject
                Downstream.Error(
                    code = err?.get("code")?.jsonPrimitive?.content ?: "",
                    message = err?.get("message")?.jsonPrimitive?.content ?: "",
                    type = err?.get("type")?.jsonPrimitive?.content ?: "",
                )
            }
            else -> null
        }
    }
}
