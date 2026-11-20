package com.lvyoubao.app.data.duplex

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 豆包端到端实时语音（Seeduplex / 全双工）客户端。
 *
 * 用法：
 *   val client = DoubaoDuplexClient(context)
 *   client.onEvent.collect { ... }        // ASR / 回答文本 / 状态
 *   client.start(apiKey, voice, instructions)  // 建连 + 建会话 + 开采集播放
 *   client.bargeIn()                       // 插话打断
 *   client.close()                         // 发 session.close，等 session.closed 再断连
 *
 * 鉴权：请求头 X-Api-Key（实时语音专用 Key，非文本大模型 Key）；无 HMAC 签名。
 * 生命周期：一条 WebSocket 对应一次会话；续聊复用 session.created 返回的 dialogId。
 */
class DoubaoDuplexClient(private val context: Context) {

    companion object {
        private const val TAG = "DoubaoDuplex"
    }

    enum class State { IDLE, CONNECTING, CONNECTED, LISTENING, SPEAKING, CLOSING, CLOSED, ERROR }

    /** UI 需要感知的一切：识别流、回答流、状态、错误 */
    sealed interface Event {
        /** 新一轮识别开始（服务端切了一个新 item）：界面应清空上一轮的识别文字 */
        data object AsrStarted : Event
        data class Asr(val text: String, val isFinal: Boolean) : Event
        data class Answer(val text: String, val isFinal: Boolean) : Event
        data class StateChanged(val state: State) : Event
        data class Level(val value: Float) : Event
        data class Error(val code: String, val message: String) : Event
        data object SessionReady : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val onEvent: SharedFlow<Event> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var audio: DuplexAudioEngine? = null
    private var apiKey: String = ""
    private var voice: String = DuplexProtocol.VOICE_VIVI
    private var dialogId: String = ""
    @Volatile private var muted = false
    @Volatile private var userSpeaking = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不能设读超时
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)     // 应用层心跳保活
        .build()

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WS 已连接 logid=${response.header("X-Tt-Logid") ?: "-"}")
            setState(State.CONNECTED)
            // 建连成功后立刻建会话
            ws.send(DuplexProtocol.sessionCreate(
                sessionId = DuplexProtocol.newSessionId(),
                instructions = instructions,
                voice = voice,
            ))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val ev = DuplexProtocol.parse(text) ?: return
            when (ev) {
                is DuplexProtocol.Downstream.SessionCreated -> {
                    dialogId = ev.id
                    Log.d(TAG, "session.created dialogId=$dialogId")
                    startAudio(ws)
                    _events.tryEmit(Event.SessionReady)
                    setState(State.LISTENING)
                }
                is DuplexProtocol.Downstream.AsrStarted -> {
                    userSpeaking = true
                    _events.tryEmit(Event.AsrStarted)
                    // ASR 只做本地停播（插话手感），不切 SPEAKING/LISTENING：
                    // 官方明确播放状态机与 ASR 解耦，状态只由 response.created/done/canceled 驱动。
                    audio?.flushPlayback()
                }
                is DuplexProtocol.Downstream.AsrDelta ->
                    _events.tryEmit(Event.Asr(ev.delta, isFinal = false))
                is DuplexProtocol.Downstream.AsrCompleted -> {
                    _events.tryEmit(Event.Asr(ev.transcript, isFinal = true))
                    userSpeaking = false
                }
                is DuplexProtocol.Downstream.TextDelta ->
                    _events.tryEmit(Event.Answer(ev.delta, isFinal = false))
                is DuplexProtocol.Downstream.TextDone ->
                    _events.tryEmit(Event.Answer(ev.text, isFinal = true))
                // 播报开始：Seeduplex 用 output_audio.started；Realtime-1.0 用 response.created。
                // 两者都纳入，谁先到谁触发，避免跨模型切换时状态机失灵。
                is DuplexProtocol.Downstream.ResponseCreated -> setState(State.SPEAKING)
                is DuplexProtocol.Downstream.AudioStarted -> setState(State.SPEAKING)
                is DuplexProtocol.Downstream.AudioDelta -> audio?.enqueuePlayback(ev.pcm)
                is DuplexProtocol.Downstream.AudioDone -> {
                    // 仅一段音频合成完，一轮回答可能有多段；不在此切 LISTENING，
                    // 由 response.done 统一收口（官方：播放状态跟 response 走）。
                }
                is DuplexProtocol.Downstream.ResponseDone -> {
                    Log.d(TAG, "response.done 一轮结束 responseId=${ev.responseId}")
                    setState(State.LISTENING)
                }
                is DuplexProtocol.Downstream.Interrupted -> {
                    audio?.flushPlayback()
                    setState(State.LISTENING)
                }
                is DuplexProtocol.Downstream.SessionClosed -> {
                    Log.d(TAG, "session.closed，可以安全断连")
                    finishClose()
                }
                is DuplexProtocol.Downstream.Error -> {
                    Log.e(TAG, "服务端 error code=${ev.code} msg=${ev.message}")
                    _events.tryEmit(Event.Error(ev.code, ev.message))
                    if (ev.code.startsWith("5")) {
                        // 5xx 统一重连（demo 最佳实践），这里只记录，重连交给上层
                        setState(State.ERROR)
                    }
                }
                else -> Unit
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS 失败：${t.message}", t)
            _events.tryEmit(Event.Error(code = "-1", message = t.message ?: "网络错误"))
            setState(State.ERROR)
            audio?.stop()
            audio = null
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS 已关闭 code=$code reason=$reason")
            if (_state.value != State.CLOSED) finishClose()
        }
    }

    private var instructions: String = "你是旅游宝，一个简洁、口语化、带数字的随身助手。回答尽量短，像朋友聊天。"

    private fun setState(s: State) {
        if (_state.value == s) return
        _state.value = s
        _events.tryEmit(Event.StateChanged(s))
    }

    /**
     * 建连并开始全双工对话。
     * @param key 实时语音 API Key（豆包语音控制台创建，X-Api-Key）
     * @param voiceId 音色 ID，见 DuplexProtocol.VOICES
     * @param systemPrompt 系统提示词（≤64K tokens 含上下文/FC）
     */
    fun start(key: String, voiceId: String = voice, systemPrompt: String? = null): Boolean {
        if (key.isBlank()) {
            _events.tryEmit(Event.Error("CONFIG", "未配置实时语音 API Key"))
            return false
        }
        if (_state.value in listOf(State.CONNECTING, State.CONNECTED, State.LISTENING, State.SPEAKING)) {
            Log.w(TAG, "已在通话中，忽略重复 start")
            return false
        }
        apiKey = key
        voice = voiceId
        if (systemPrompt != null) instructions = systemPrompt
        setState(State.CONNECTING)

        val request = Request.Builder()
            .url(DuplexProtocol.WS_URL)
            .addHeader("X-Api-Key", apiKey)
            .build()
        webSocket = client.newWebSocket(request, listener)
        return true
    }

    private fun startAudio(ws: WebSocket) {
        val engine = DuplexAudioEngine(
            context = context,
            scope = scope,
            onPcmFrame = { pcm ->
                if (ws.send(DuplexProtocol.audioAppend(Base64.encodeToString(pcm, Base64.NO_WRAP)))) {
                    // 分片正常上行
                } else {
                    Log.w(TAG, "audio append 发送失败，连接可能已断")
                }
            },
            onLevel = { _events.tryEmit(Event.Level(it)) },
        )
        audio = engine
        engine.startPlayback()
        engine.startCapture()
    }

    /** 插话打断：本地停播 + 发 response.cancel（对应服务端的 response.canceled） */
    fun bargeIn() {
        audio?.flushPlayback()
        webSocket?.send(DuplexProtocol.simple("response.cancel"))
    }

    /** 静音/恢复：关麦后不发音帧会超时，必须发 mute 事件保活 */
    fun setMuted(muted: Boolean) {
        if (this.muted == muted) return
        this.muted = muted
        webSocket?.send(
            DuplexProtocol.simple(if (muted) "input_audio_mute.commit" else "input_audio_unmute.commit")
        )
    }

    /** 主动推送一句话让模型先开口（打招呼） */
    /** 打断当前回答（本地先清播放队列，再发 response.cancel） */
    fun cancelResponse() {
        audio?.flushPlayback()
        webSocket?.send(DuplexProtocol.simple("response.cancel"))
    }

    /** 用豆包音色说一句话（干预回复）：本机能力执行完的确认话术走这条，避免和 TTS 叠音 */
    fun speak(text: String) {
        greet(text)
    }

    fun greet(text: String) {
        val sid = DuplexProtocol.newSessionId()
        webSocket?.send(DuplexProtocol.speechText("speech_text_buffer.append", sid, text))
        webSocket?.send(DuplexProtocol.speechText("speech_text_buffer.commit", sid, ""))
    }

    /** 优雅关闭：先停采集，再发 session.close，等服务端 session.closed 后再断连 */
    fun close() {
        if (_state.value in listOf(State.IDLE, State.CLOSED, State.CLOSING)) return
        setState(State.CLOSING)
        audio?.stop()
        audio = null
        val sent = webSocket?.send(DuplexProtocol.simple("session.close")) == true
        if (!sent) finishClose()
        // 兜底：若服务端 2s 内没回 session.closed，主动结束避免吊死
        scope.launch {
            kotlinx.coroutines.delay(2000)
            if (_state.value == State.CLOSING) finishClose()
        }
    }

    private fun finishClose() {
        webSocket?.close(1000, "bye")
        webSocket = null
        setState(State.CLOSED)
    }

    fun destroy() {
        audio?.stop()
        audio = null
        finishClose()
        client.dispatcher.executorService.shutdownNow()
    }
}
