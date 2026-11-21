package com.lvyoubao.app.data.duplex

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lvyoubao.app.data.AppTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        /** 模型决定调工具（FC）：本地执行完用 sendToolResult 回传，模型再接着说 */
        data class ToolCall(val callId: String, val name: String, val arguments: String) : Event
        /**
         * 一轮回答收口（response.done / 被打断时的残句）。落会话历史以这个为准：
         * TextDone 不一定到（纯音频段、被打断），answer 是本轮已生成的全文/残文。
         */
        data class TurnDone(val answer: String, val interrupted: Boolean) : Event
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
    /** FC 工具定义 + 初始化上下文：start 时传入，建会话时带上 */
    private var tools: List<AppTools.ToolDef> = emptyList()
    private var dialogContext: List<DialogTurn> = emptyList()
    /** 下一次建会话是否续接（断线自愈复用 dialogId，且不再注入上下文防重复） */
    private var resumeNext = false
    /** 断线自愈次数：每次 start 重置，最多一次，避免弱网下无限重连 */
    private var retries = 0
    /** 本轮回答文本累积：TurnDone 的权威来源（TextDone 可能不来，如被打断/纯音频段） */
    private val turnText = StringBuilder()
    /** 本地 speech_text（打招呼/干预回复）触发的 response 周期数：不是模型回答，不外发 TurnDone */
    private var localSpeechCycles = 0
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
            // 建连成功后立刻建会话。断线自愈时复用上次 dialogId（服务端上下文还在），
            // 并跳过 dialog_context 注入——同一份历史注入两遍，模型会把旧话当新事说。
            val resuming = resumeNext
            resumeNext = false
            ws.send(DuplexProtocol.sessionCreate(
                sessionId = if (resuming && dialogId.isNotBlank()) dialogId else DuplexProtocol.newSessionId(),
                instructions = instructions,
                voice = voice,
                tools = tools,
                dialogContext = if (resuming) emptyList() else dialogContext,
            ))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val ev = DuplexProtocol.parse(text) ?: return
            when (ev) {
                is DuplexProtocol.Downstream.SessionCreated -> {
                    dialogId = ev.id
                    retries = 0
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
                    // 用户开新话头：还挂着的本地话术标记作废（speech_text 被打断时不会有 response 周期来消耗它），
                    // 不清零的话下一个真实回答会被误吞一次。
                    localSpeechCycles = 0
                }
                is DuplexProtocol.Downstream.AsrDelta ->
                    _events.tryEmit(Event.Asr(ev.delta, isFinal = false))
                is DuplexProtocol.Downstream.AsrCompleted -> {
                    _events.tryEmit(Event.Asr(ev.transcript, isFinal = true))
                    userSpeaking = false
                }
                is DuplexProtocol.Downstream.TextDelta -> {
                    turnText.append(ev.delta)
                    _events.tryEmit(Event.Answer(ev.delta, isFinal = false))
                }
                is DuplexProtocol.Downstream.TextDone -> {
                    // 累积快照以 done 的全文为准；空 done（纯音频段）不动已有增量
                    if (ev.text.isNotBlank()) {
                        turnText.setLength(0)
                        turnText.append(ev.text)
                    }
                    _events.tryEmit(Event.Answer(ev.text, isFinal = true))
                }
                // 播报开始：Seeduplex 用 output_audio.started；Realtime-1.0 用 response.created。
                // 两者都纳入，谁先到谁触发，避免跨模型切换时状态机失灵。
                is DuplexProtocol.Downstream.ResponseCreated -> {
                    turnText.setLength(0)
                    setState(State.SPEAKING)
                }
                is DuplexProtocol.Downstream.AudioStarted -> setState(State.SPEAKING)
                is DuplexProtocol.Downstream.AudioDelta -> audio?.enqueuePlayback(ev.pcm)
                is DuplexProtocol.Downstream.AudioDone -> {
                    // 仅一段音频合成完，一轮回答可能有多段；不在此切 LISTENING，
                    // 由 response.done 统一收口（官方：播放状态跟 response 走）。
                }
                is DuplexProtocol.Downstream.ResponseDone -> {
                    Log.d(TAG, "response.done 一轮结束 responseId=${ev.responseId}")
                    setState(State.LISTENING)
                    finishTurn(interrupted = false)
                }
                is DuplexProtocol.Downstream.Interrupted -> {
                    audio?.flushPlayback()
                    setState(State.LISTENING)
                    finishTurn(interrupted = true)
                }
                is DuplexProtocol.Downstream.FunctionCalls ->
                    ev.calls.forEach { _events.tryEmit(Event.ToolCall(it.callId, it.name, it.arguments)) }
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
            val wasMidCall = _state.value in listOf(State.CONNECTED, State.LISTENING, State.SPEAKING)
            _events.tryEmit(Event.Error(code = "-1", message = t.message ?: "网络错误"))
            setState(State.ERROR)
            audio?.stop()
            audio = null
            // 通话中意外断线：带原 dialogId 静默自愈一次（这台设备 WiFi 偶发掉线，
            // 用户听到的就是「突然没反应」）。主动挂断走 close()，不会进这里。
            if (wasMidCall && retries < 1 && apiKey.isNotBlank()) {
                retries++
                Log.w(TAG, "通话中意外断线，1.2 秒后自动续接 dialogId=$dialogId")
                scope.launch {
                    delay(1_200)
                    if (_state.value == State.ERROR) {
                        resumeNext = true
                        setState(State.CONNECTING)
                        connect()
                    }
                }
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS 已关闭 code=$code reason=$reason")
            if (_state.value != State.CLOSED) finishClose()
        }
    }

    /** 一个 response 周期收口：把累积文本作为 TurnDone 交给上层落会话历史 */
    private fun finishTurn(interrupted: Boolean) {
        val text = turnText.toString()
        turnText.setLength(0)
        // 本地话术（打招呼/干预回复）的合成周期：不走会话历史
        if (localSpeechCycles > 0) {
            localSpeechCycles--
            return
        }
        if (text.isNotBlank()) _events.tryEmit(Event.TurnDone(text, interrupted))
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
     * @param systemPrompt 系统提示词（人设 + 长期记忆 + 时间/位置情境，≤64K tokens 含上下文/FC）
     * @param callTools Function Calling 工具（AppTools.definitions），语音里模型自己决定调用
     * @param historyContext 初始化上下文：共享受话数据的最近成对 QA（跨模式/跨通话记忆）
     */
    fun start(
        key: String,
        voiceId: String = voice,
        systemPrompt: String? = null,
        callTools: List<AppTools.ToolDef> = emptyList(),
        historyContext: List<DialogTurn> = emptyList(),
    ): Boolean {
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
        tools = callTools
        dialogContext = historyContext
        retries = 0
        resumeNext = false
        setState(State.CONNECTING)
        connect()
        return true
    }

    private fun connect() {
        val request = Request.Builder()
            .url(DuplexProtocol.WS_URL)
            .addHeader("X-Api-Key", apiKey)
            .build()
        webSocket = client.newWebSocket(request, listener)
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
        // 标记一个本地话术周期：这段音频对应的服务端 response 不算「模型回答」，
        // 否则打招呼文本会被 TurnDone 当成模型回答落进会话历史。
        localSpeechCycles++
        val sid = DuplexProtocol.newSessionId()
        webSocket?.send(DuplexProtocol.speechText("speech_text_buffer.append", sid, text))
        webSocket?.send(DuplexProtocol.speechText("speech_text_buffer.commit", sid, ""))
    }

    /** FC 结果回传：本地工具执行完（成功或失败都要）把结果交给模型，模型基于结果继续生成语音 */
    fun sendToolResult(callId: String, result: String) {
        webSocket?.send(DuplexProtocol.toolResult(callId, result))
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
