package com.lvyoubao.app.data.duplex

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.LogStore
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
        /** 本轮识别失败（噪声误触发或服务端未听清） */
        data object AsrFailed : Event
        data class Answer(val text: String, val isFinal: Boolean) : Event
        data class StateChanged(val state: State) : Event
        data class Level(val value: Float) : Event
        data class Error(val code: String, val message: String) : Event
        data object SessionReady : Event
        /** 模型决定调工具（FC）：本地执行完用 sendToolResult 回传，模型再接着说 */
        data class ToolCall(val callId: String, val name: String, val arguments: String) : Event
        /**
         * 识别流里蹦出急停词（停/别动…）：客户端已 flush 播放 + response.cancel，
         * 上层再把机器人也停住——急停不等句尾判停。
         */
        data object StopRequested : Event
        /**
         * 识别流里出现结束/道别词（退出/结束/再见/拜拜…）：客户端已停播 + response.cancel，
         * 上层据此挂断通话并回到待机。用 final 文本判定，避免「再见怎么写」被中途误挂。
         */
        data object HangupRequested : Event
        /**
         * 一轮回答收口（response.done / 被打断时的残句）。落会话历史以这个为准：
         * TextDone 不一定到（纯音频段、被打断），answer 是本轮已生成的全文/残文。
         * asr 是本轮回答对应的用户识别稿——response 开始时冻结的快照。全双工下
         * 用户会抢话，界面的活状态（duplexAsr）随时可能被下一轮 AsrStarted 清空，
         * 落库必须用冻结值，否则 user 行丢失。
         */
        data class TurnDone(val asr: String, val answer: String, val interrupted: Boolean) : Event
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
    /** 静默断开（服务端 ~10 分钟无交互释放）自动重连次数：通话当陪伴挂着，断几次都续 */
    private var idleReconnects = 0
    /** 用户主动挂断标记：挂断引发的关闭绝不能自动重连 */
    @Volatile private var userClosed = false
    /** 本轮回答文本累积：TurnDone 的权威来源（TextDone 可能不来，如被打断/纯音频段） */
    private val turnText = StringBuilder()
    /** 本地 speech_text（打招呼/干预回复）触发的 response 周期数：不是模型回答，不外发 TurnDone */
    private var localSpeechCycles = 0
    /** 当前话头的识别稿（delta 累计快照，completed 到达后被 final 文本覆盖） */
    private var pendingAsr = ""
    /** 本轮 response 开始时冻结的识别稿：TurnDone 随事件带给上层落库 */
    private var turnAsr = ""
    /** 本话头是否还允许触发即时急停（一个话头只触发一次，防重复 cancel） */
    private var stopArmed = true
    /** 本话头是否还允许触发结束词挂断（独立于 stopArmed：先说「停」再说「再见」仍能挂断） */
    private var endArmed = true
    /** 方言（explicit_dialect，空=关）与唱歌开关 */
    private var dialect: String = ""
    private var music: Boolean = false
    private var defaultSpeakerphone: Boolean = true
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
                dialect = dialect,
                music = music,
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
                    pendingAsr = ""
                    stopArmed = true
                    endArmed = true
                    _events.tryEmit(Event.AsrStarted)
                    // ASR 只做本地停播（插话手感），不切 SPEAKING/LISTENING：
                    // 官方明确播放状态机与 ASR 解耦，状态只由 response.created/done/canceled 驱动。
                    audio?.flushPlayback()
                    // 用户开新话头：还挂着的本地话术标记作废（speech_text 被打断时不会有 response 周期来消耗它），
                    // 不清零的话下一个真实回答会被误吞一次。
                    localSpeechCycles = 0
                }
                is DuplexProtocol.Downstream.AsrDelta -> {
                    // delta 是本轮识别的累计快照（真机抓包确认），直接覆盖
                    pendingAsr = ev.delta
                    _events.tryEmit(Event.Asr(ev.delta, isFinal = false))
                    // 即时急停：识别流里一出现停类词立刻停播+取消回答，不等句尾判停
                    if (stopArmed && DuplexProtocol.instantStopHit(ev.delta)) {
                        stopArmed = false
                        Log.w(TAG, "识别流命中急停词：「$pendingAsr」，立即停播并取消回答")
                        audio?.flushPlayback()
                        ws.send(DuplexProtocol.simple("response.cancel"))
                        _events.tryEmit(Event.StopRequested)
                    }
                }
                is DuplexProtocol.Downstream.AsrCompleted -> {
                    // 写 LogStore：应用内日志导出要能看到服务端是否收到上行、识别成了什么，
                    // 否则"说话没响应"分不清是麦克风没声音 / 上行发不出 / ASR 没命中 / UI 没显示
                    com.lvyoubao.app.data.LogStore.log("duplex", "ASR final：${ev.transcript.take(40)}")
                    // 若上一轮因嘈杂打断遗留未收口的残余文本且当前不在播报态，先收口清理再进入新问答
                    if (_state.value != State.SPEAKING && turnText.isNotEmpty()) {
                        finishTurn(interrupted = true)
                    }
                    pendingAsr = ev.transcript
                    _events.tryEmit(Event.Asr(ev.transcript, isFinal = true))
                    userSpeaking = false
                    // 结束词：用 final 文本判定（不是 delta），避免用户说「再见怎么写」
                    // 时中途出现「再见」被误挂断。命中即停播+取消服务端回答，再让 UI 挂断。
                    if (endArmed && DuplexProtocol.instantEndHit(ev.transcript)) {
                        endArmed = false
                        Log.w(TAG, "识别流命中结束词：「${ev.transcript}」，停播并挂断通话")
                        audio?.flushPlayback()
                        ws.send(DuplexProtocol.simple("response.cancel"))
                        _events.tryEmit(Event.HangupRequested)
                    }
                }
                is DuplexProtocol.Downstream.AsrFailed -> {
                    LogStore.log("duplex", "ASR 识别未命中（可能为环境噪声）：${ev.raw.take(120)}")
                    userSpeaking = false
                    _events.tryEmit(Event.AsrFailed)
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
                    freezeTurnAsr()
                    setState(State.SPEAKING)
                }
                is DuplexProtocol.Downstream.AudioStarted -> {
                    // 真机抓包：这台服务不发 response.created，音频起点就是新 response 的起点，
                    // 识别稿冻结挂这里才靠得住（只冻结不清 turnText：一轮可能多段音频）
                    freezeTurnAsr()
                    setState(State.SPEAKING)
                }
                is DuplexProtocol.Downstream.AudioDelta -> audio?.enqueuePlayback(ev.pcm)
                is DuplexProtocol.Downstream.AudioDone -> {
                    // 仅一段音频合成完，一轮回答可能有多段；不在此切 LISTENING，
                    // 由 response.done 统一收口（官方：播放状态跟 response 走）。
                }
                is DuplexProtocol.Downstream.ResponseDone -> {
                    com.lvyoubao.app.data.LogStore.log("duplex", "response.done 一轮结束 responseId=${ev.responseId}")
                    setState(State.LISTENING)
                    finishTurn(interrupted = false)
                }
                is DuplexProtocol.Downstream.Interrupted -> {
                    LogStore.log("duplex", "服务端响应被打断/取消（response.canceled）")
                    audio?.flushPlayback()
                    setState(State.LISTENING)
                    finishTurn(interrupted = true)
                }
                is DuplexProtocol.Downstream.FunctionCalls ->
                    ev.calls.forEach { _events.tryEmit(Event.ToolCall(it.callId, it.name, it.arguments)) }
                is DuplexProtocol.Downstream.SessionClosed -> {
                    Log.d(TAG, "session.closed")
                    // 用户没挂断却收到关闭 = 服务端静默释放（官方 45000003：约 10 分钟无对话交互）
                    // → 自动重连续上通话：老 dialog 大概率已释放，改走全新会话 + 重注入历史上下文
                    maybeIdleReconnect() ?: finishClose()
                }
                is DuplexProtocol.Downstream.Error -> {
                    Log.e(TAG, "服务端 error code=${ev.code} msg=${ev.message}")
                    _events.tryEmit(Event.Error(ev.code, ev.message))
                    if (ev.code.startsWith("5")) {
                        // 5xx 统一重连（demo 最佳实践）：静默自愈一次
                        setState(State.ERROR)
                        audio?.stop()
                        audio = null
                        selfHeal("服务端 5xx（code=${ev.code}）")
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
            if (wasMidCall) selfHeal("通话中意外断线")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS 已关闭 code=$code reason=$reason")
            if (_state.value != State.CLOSED) {
                maybeIdleReconnect() ?: finishClose()
            }
        }
    }

    /**
     * 静默断开后的自动重连（用户没挂断、连接却被关/断）：
     * 官方策略是「约 10 分钟没有对话交互，服务端释放连接」——把通话当陪伴挂着的用户
     * 会被这个打断。这里最多连续续 5 次，每次带全量历史上下文（dialogContext 重注入），
     * 模型的记忆不断；用户挂断（close()）绝不触发。
     */
    private fun maybeIdleReconnect(): Boolean {
        if (userClosed) return false
        if (_state.value !in listOf(State.LISTENING, State.SPEAKING, State.CONNECTED)) return false
        if (idleReconnects >= 5) return false
        idleReconnects++
        Log.w(TAG, "通话被服务端静默释放，1 秒后重连续接（第 $idleReconnects 次）")
        scope.launch {
            delay(1_000)
            if (userClosed) return@launch
            if (_state.value in listOf(State.CLOSING, State.CLOSED, State.IDLE)) return@launch
            reconnectIdle()
        }
        return true
    }

    private fun reconnectIdle() {
        webSocket?.cancel()
        webSocket = null
        audio?.stop()
        audio = null
        resumeNext = false // 老 dialog 已被服务端释放：全新会话 + 重注入历史上下文
        setState(State.CONNECTING)
        connect()
    }

    /** 冻结本轮回答对应的识别稿：之后用户抢话、新一轮 AsrStarted 都只改 pendingAsr，不动冻结值 */
    private fun freezeTurnAsr() {
        if (pendingAsr.isNotBlank()) turnAsr = pendingAsr
    }

    /** 一个 response 周期收口：把累积文本 + 冻结识别稿作为 TurnDone 交给上层落会话历史 */
    private fun finishTurn(interrupted: Boolean) {
        val text = turnText.toString()
        turnText.setLength(0)
        // 本地话术（打招呼/干预回复）的合成周期：不走会话历史
        if (localSpeechCycles > 0) {
            localSpeechCycles--
            return
        }
        // 正常路径在 response 起点已冻结；没经过起点（异常流）用当前识别稿兜底
        val asr = turnAsr.ifBlank { pendingAsr }
        turnAsr = ""
        if (text.isNotBlank()) _events.tryEmit(Event.TurnDone(asr, text, interrupted))
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
     * @param callTools Function Calling 工具（AppTools.definitions 等），语音里模型自己决定调用
     * @param historyContext 初始化上下文：共享受话数据的最近成对 QA（跨模式/跨通话记忆）
     * @param dialect 方言（explicit_dialect，空=普通话；仅 4 个精品音色支持）
     * @param music 唱歌能力开关（enable_music）
     */
    fun start(
        key: String,
        voiceId: String = voice,
        systemPrompt: String? = null,
        callTools: List<AppTools.ToolDef> = emptyList(),
        historyContext: List<DialogTurn> = emptyList(),
        dialect: String = "",
        music: Boolean = false,
        speakerphone: Boolean = true,
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
        this.dialect = dialect
        this.music = music
        this.defaultSpeakerphone = speakerphone
        retries = 0
        idleReconnects = 0
        userClosed = false
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

    /** 断线/5xx 静默自愈：带原 dialogId 重连（每次通话最多一次），复用服务端上下文 */
    private fun selfHeal(reason: String) {
        if (retries >= 1 || apiKey.isBlank()) return
        retries++
        Log.w(TAG, "$reason，1.2 秒后自动续接 dialogId=$dialogId")
        scope.launch {
            delay(1_200)
            if (_state.value == State.ERROR) {
                webSocket?.cancel()
                webSocket = null
                audio?.stop()
                audio = null
                resumeNext = true
                setState(State.CONNECTING)
                connect()
            }
        }
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
        engine.setInitialSpeakerphone(defaultSpeakerphone)
        audio = engine
        engine.startPlayback()
        engine.startCapture()
    }

    /** 通话中切换免提外放 / 听筒 */
    fun setSpeakerphone(enabled: Boolean) {
        defaultSpeakerphone = enabled
        audio?.setSpeakerphone(enabled)
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
        userClosed = true // 用户挂断：之后的一切关闭都不触发静默重连
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
