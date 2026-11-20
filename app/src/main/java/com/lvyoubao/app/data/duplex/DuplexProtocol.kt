package com.lvyoubao.app.data.duplex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

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

    /** 建会话：session.create。extension 三块留空 map，专有配置后续可在此扩展。 */
    fun sessionCreate(
        sessionId: String,
        instructions: String,
        voice: String,
        speed: Int = 0,
        loudness: Int = 0,
        inputType: String = "pcm",
        outputType: String = "pcm_s16le",
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
        }
        putJsonObject("extension") {
            putJsonObject("asr") { putJsonObject("extra") {} }
            putJsonObject("tts") { putJsonObject("extra") {} }
            putJsonObject("dialog") { putJsonObject("extra") {} }
        }
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
    }

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
                Downstream.AsrCompleted(s("item_id"), s("transcript"))
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
