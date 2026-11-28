package com.lvyoubao.app.ui.spark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocalCommandRouter
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RobotIntent
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.data.duplex.DuplexCallController
import com.lvyoubao.app.data.duplex.DuplexCallSetup
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.common.EdgeTts
import com.lvyoubao.app.ui.common.HoldTalkController
import com.lvyoubao.app.ui.common.LiveTtsSession
import com.lvyoubao.app.ui.common.ScreenAwake
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.common.WakeHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SparkEyeState {
    IDLE,        // 待机呼吸，日常俏皮
    LISTENING,   // 聆听中，专注睁大
    THINKING,    // 思考中，眼神转动
    SPEAKING,    // 播报中，随语调灵动闪烁
    CAPTURING,   // 拍照快门眨眼反馈
    MUTED,       // 静默安睡模式，月牙眯眼
}

/** 会话流中的一条消息：photos 仅在带图那一轮携带（可多张），随消息滚走，不再常驻顶部 */
data class SparkMessage(
    val role: String,
    val text: String,
    val photos: List<Bitmap> = emptyList(),
    val mapCard: MapCardData? = null,
)

/** 待发送照片：解码转正后的位图 + 视觉模型要的 base64 dataUrl */
data class PendingPhoto(val bitmap: Bitmap, val dataUrl: String)

/**
 * 灵动模式 ViewModel：
 * 经过统一重构，语音唤醒（[WakeHub]）、按住说话（[holdTalk]）、流式播报（[LiveTtsSession]）、
 * 全双工通话（[callController]）、倒计时告警（[CallTimer]）及本地快路（[LocalCommandRouter]）
 * 全部与普通/大字模式共用核心引擎，本类只保留灵动模式的 UI 状态、萌眼动画与电量主动提醒。
 */
class SparkViewModel(private val app: TravelApp) : ViewModel() {

    private companion object {
        const val EYES_RETURN_AFTER_MS = 4_000L
    }

    private val repo = app.settingsRepository
    private val memory = app.memoryStore
    private val notes = app.noteStore
    private val appContext = app.applicationContext

    val settings: StateFlow<AiSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiSettings())

    private val _eyeState = MutableStateFlow(SparkEyeState.IDLE)
    val eyeState: StateFlow<SparkEyeState> = _eyeState.asStateFlow()

    private val _showEyes = MutableStateFlow(true)
    val showEyes: StateFlow<Boolean> = _showEyes.asStateFlow()

    private val _statusHint = MutableStateFlow<String?>(null)
    val statusHint: StateFlow<String?> = _statusHint.asStateFlow()

    val messages: StateFlow<List<SparkMessage>> = SparkChatStore.messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** 供共享 VoiceCallOverlay 渲染经典串行通话消息列表与流式回答气泡 */
    val classicCallMessages: StateFlow<List<SessionMessage>> =
        combine(messages, _isStreaming) { list, streaming ->
            val trimmed = if (streaming && list.lastOrNull()?.role == "assistant") list.dropLast(1) else list
            trimmed.mapIndexed { idx, m ->
                SessionMessage(
                    role = m.role,
                    text = m.text,
                    hadImage = m.photos.isNotEmpty(),
                    mapCard = m.mapCard,
                    ts = idx.toLong(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val classicCallStream: StateFlow<ChatViewModel.StreamState?> =
        combine(messages, _isStreaming) { list, streaming ->
            if (!streaming) null
            else ChatViewModel.StreamState(answer = list.lastOrNull { it.role == "assistant" }?.text.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pendingPhotos = MutableStateFlow<List<PendingPhoto>>(emptyList())
    val pendingPhotos: StateFlow<List<PendingPhoto>> = _pendingPhotos.asStateFlow()

    /** 共用按住说话控制器（统一录音/系统识别兜底/恢复待机） */
    val holdTalk = HoldTalkController(viewModelScope)
    val isRecording: StateFlow<Boolean> = holdTalk.state
        .map { it.isRecording }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val recordTick: StateFlow<Int> = holdTalk.state
        .map { it.recordTick }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 共用倒计时与闹钟（全应用统一，切页不丢失） */
    val timerRemainingSec: StateFlow<Int?> = CallTimer.state
        .map { if (it.remainSec > 0) it.remainSec else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val alarmActive: StateFlow<Boolean> = CallTimer.alarmActive
    val alarmText: StateFlow<String> = CallTimer.alarmText

    /** 唤醒后动作=直接通话时的跳转事件 */
    val wakeCallRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 统一豆包全双工通话控制器（配自动收起回萌眼不断连 + 本地命令抢断） */
    val callController = DuplexCallController(
        context = appContext,
        scope = viewModelScope,
        onCommitTurn = { asr, answer, _ ->
            SparkChatStore.appendDuplexTurn(asr, answer)
            summarizeCallTurns()
        },
        onAttachMapCard = { card -> SparkChatStore.attachMapCard(card) },
        onLocalAsrCommand = { text -> handleCallCommand(appContext, text) },
        onHangUpCallback = {
            _showEyes.value = true
            syncWakeEngine()
        },
    )

    private var aiRequestJob: Job? = null
    private var liveTts: LiveTtsSession? = null
    private var idleToEyesJob: Job? = null

    @Volatile private var sparkActive = false
    @Volatile private var classicCallActive = false
    @Volatile private var wakeHitAt = 0L
    private var duplexReplySink: ((String) -> Unit)? = null

    // 主动提醒（电量低/充满）
    private var lastLowKey: String? = null
    private var fullReminded = false
    private var wasCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) onBatteryChanged(intent)
        }
    }

    init {
        viewModelScope.launch {
            runCatching {
                val store = app.sessionStore
                val cur = runCatching { repo.currentSessionId.firstOrNull() }.getOrNull()
                val sid = cur
                    ?: store.sessions.value.firstOrNull()?.id
                    ?: store.create().id.also { store.setCurrent(it) }
                SparkChatStore.bind(appContext, store, sid, viewModelScope)
            }
        }

        // 监听统一的 TTS 播放态驱动眼睛表情与息屏倒计时
        viewModelScope.launch {
            TtsPlayer.state.collect { ttsState ->
                when (ttsState) {
                    is TtsPlayer.State.Speaking -> {
                        idleToEyesJob?.cancel()
                        if (_eyeState.value == SparkEyeState.THINKING || _eyeState.value == SparkEyeState.IDLE) {
                            _eyeState.value = SparkEyeState.SPEAKING
                        }
                    }
                    is TtsPlayer.State.Idle -> {
                        if (liveTts != null) return@collect
                        if (_eyeState.value == SparkEyeState.SPEAKING) finishSpeaking()
                    }
                    else -> Unit
                }
            }
        }

        // 通话自动收起/展开时同步控制眼睛与会话显示
        viewModelScope.launch {
            callController.ui.collect { st ->
                if (st.active) {
                    _showEyes.value = !st.uiVisible
                }
                syncWakeEngine()
            }
        }

        runCatching {
            appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        viewModelScope.launch { settings.collect { syncWakeEngine() } }
        viewModelScope.launch { WakeHub.standbyAllowed.collect { syncWakeEngine() } }
    }

    fun startDuplexCall() {
        val cur = settings.value
        callController.start(
            settings = cur,
            persona = DuplexCallSetup.PERSONA_SPARK,
            history = SparkChatStore.historyBudget(8_000),
            deviceTools = false,
            autoCollapse = true,
        )
    }

    fun setClassicCallActive(active: Boolean) {
        if (classicCallActive == active) return
        classicCallActive = active
        if (active) {
            TtsPlayer.stop()
            WakeHub.stopStandby()
            WakeHub.cancelOneShot()
        } else {
            syncWakeEngine()
        }
    }

    fun stopAlarm() = CallTimer.stopAlarm()

    fun onSparkActive() {
        sparkActive = true
        syncWakeEngine()
        ScreenAwake.idle()
    }

    fun replayMessage(context: Context, text: String) {
        if (text.isBlank()) return
        ScreenAwake.keepOn()
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.SPEAKING
        _statusHint.value = null
        TtsPlayer.speak(context, text)
    }

    private fun speakHint(context: Context, text: String) {
        duplexReplySink?.let { sink ->
            sink(text)
            return
        }
        ScreenAwake.keepOn()
        _eyeState.value = SparkEyeState.SPEAKING
        TtsPlayer.speak(context, text)
    }

    fun toggleFavorite(msg: SparkMessage, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val title = msg.text.take(60)
            val existing = memory.entries.value.firstOrNull {
                it.kind == com.lvyoubao.app.data.MemoryStore.MemoryEntry.EVIDENCE && it.title == title
            }
            if (existing != null) {
                memory.remove(existing.ts)
                onResult("已取消收藏")
            } else {
                memory.add(
                    kind = com.lvyoubao.app.data.MemoryStore.MemoryEntry.EVIDENCE,
                    title = title,
                    detail = msg.text.take(2000),
                    source = "灵动模式（用户收藏）",
                )
                onResult("已收藏 ✓")
            }
        }
    }

    fun setTransientHint(msg: String?) {
        _statusHint.value = msg
    }

    fun toggleShowEyes() {
        _showEyes.value = !_showEyes.value
    }

    fun setShowEyes(show: Boolean) {
        _showEyes.value = show
    }

    private fun appendMessage(msg: SparkMessage) = SparkChatStore.append(msg)

    /** 统一按住说话：与普通/大字模式走同一条 HoldTalkController 管线 */
    fun startHoldTalk(context: Context, onRequestRecordPermission: () -> Unit) {
        val cur = settings.value
        holdTalk.begin(
            context = context,
            settings = cur,
            prompt = "我在听，请说话",
            onBeforeMic = {
                idleToEyesJob?.cancel()
                _eyeState.value = SparkEyeState.LISTENING
                _statusHint.value = "正在听你说，松手发送…"
            },
            onRequestPermission = {
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = "需要麦克风权限才能录音"
                onRequestRecordPermission()
            },
            onError = { uiMsg, spokenMsg ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = uiMsg
                speakHint(context, spokenMsg)
                syncWakeEngine()
            },
            onSystemTranscript = { spoken ->
                viewModelScope.launch {
                    val pending = _pendingPhotos.value
                    _pendingPhotos.value = emptyList()
                    answerVoiceQuestion(spoken, pending)
                    syncWakeEngine()
                }
            },
        )
    }

    fun endHoldTalk(context: Context) {
        if (!holdTalk.state.value.isRecording) {
            if (!holdTalk.state.value.listening) _eyeState.value = SparkEyeState.IDLE
            return
        }
        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "正在识别语音…"
        holdTalk.release(
            settings = settings.value,
            onTranscript = { clean ->
                val pending = _pendingPhotos.value
                _pendingPhotos.value = emptyList()
                answerVoiceQuestion(clean, pending)
                syncWakeEngine()
            },
            onError = { uiMsg, spokenMsg ->
                _eyeState.value = SparkEyeState.IDLE
                appendMessage(SparkMessage("user", "（识别未完成）"))
                appendMessage(SparkMessage("assistant", uiMsg))
                _showEyes.value = false
                _statusHint.value = spokenMsg
                speakHint(context, spokenMsg)
                syncWakeEngine()
            },
        )
    }

    fun setHoldTalkCancelling(cancelling: Boolean) {
        holdTalk.setCancelling(cancelling)
        if (_eyeState.value == SparkEyeState.LISTENING || cancelling) {
            _statusHint.value = if (cancelling) "松开手指，取消本次说话" else "正在听你说，上滑取消 · 松手发送"
        }
    }

    fun cancelHoldTalk() {
        holdTalk.cancelTalk()
        _eyeState.value = SparkEyeState.IDLE
        _statusHint.value = "已取消语音"
        syncWakeEngine()
    }

    fun addPendingPhotos(context: Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        _statusHint.value = "正在读取照片…"
        viewModelScope.launch(Dispatchers.IO) {
            val added = uris.mapNotNull { uri ->
                SparkImageCodec.decodeUpright(context, uri)?.let { bitmap ->
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    PendingPhoto(bitmap, "data:image/jpeg;base64,$base64")
                }
            }
            if (added.isEmpty()) {
                _statusHint.value = "照片读取失败，请重试"
                return@launch
            }
            _pendingPhotos.value = (_pendingPhotos.value + added).takeLast(9)
            _statusHint.value = "已加 ${_pendingPhotos.value.size} 张照片，点「识别」或按住说话"
        }
    }

    fun analyzePending(context: Context) {
        val pending = _pendingPhotos.value
        if (pending.isEmpty()) return
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        val label = if (pending.size == 1) "📷 帮我看看这张照片" else "📷 帮我看看这 ${pending.size} 张照片"
        val prompt = if (pending.size == 1) {
            "请结合这张照片，用通俗易懂的自然口语简要说明画面里是什么内容。"
        } else {
            "请结合这 ${pending.size} 张照片，用通俗易懂的自然口语简要说明拍到了什么内容。"
        }
        sendImageQuestion(context, pending, label, prompt)
    }

    private fun sendImageQuestion(
        context: Context,
        photos: List<PendingPhoto>,
        userLabel: String,
        prompt: String,
        attachToLastUser: Boolean = false,
    ) {
        val bitmaps = photos.map { it.bitmap }
        val attached = attachToLastUser && SparkChatStore.attachPhotosToLastUser(bitmaps)
        if (!attached) {
            appendMessage(SparkMessage("user", userLabel, bitmaps))
        }
        SparkChatStore.upsertAssistantPlaceholder("小源正在观察画面…")
        _pendingPhotos.value = emptyList()
        _showEyes.value = false
        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "小源正在观察…"
        sendAiRequest(context, prompt, photos.map { it.dataUrl }, reuseLastAssistant = true)
    }

    /** 统一问答与本地指令入口 */
    suspend fun answerVoiceQuestion(clean: String, photos: List<PendingPhoto> = emptyList()) {
        if (LocalCommandRouter.matchByeFast(clean) || RobotIntent.matchEndFast(clean)) {
            TtsPlayer.stop()
            WakeHub.cancelOneShot()
            _eyeState.value = SparkEyeState.IDLE
            _showEyes.value = true
            _statusHint.value = null
            ScreenAwake.idle()
            syncWakeEngine()
            return
        }
        ScreenAwake.keepOn()
        appendMessage(SparkMessage(role = "user", text = clean, photos = photos.map { it.bitmap }))
        _showEyes.value = false
        _eyeState.value = SparkEyeState.THINKING

        val outcome = LocalCommandRouter.tryHandle(
            context = appContext,
            settings = settings.value,
            text = clean,
            noteStore = notes,
            memoryStore = memory,
            host = object : LocalCommandRouter.Host {
                override fun onStatusHint(hint: String?) {
                    _statusHint.value = hint
                }

                override fun onTimerStarted(sec: Int, label: String, confirmText: String) {
                    CallTimer.start(sec, label) { msg ->
                        _statusHint.value = msg
                        _eyeState.value = SparkEyeState.SPEAKING
                    }
                }
            },
        )

        when (outcome) {
            is LocalCommandRouter.Outcome.Replied -> {
                appendMessage(SparkMessage("assistant", outcome.displayText))
                _eyeState.value = SparkEyeState.IDLE
                if (outcome.shouldSpeak) speakHint(appContext, outcome.spokenText)
                else finishSpeaking()
            }
            is LocalCommandRouter.Outcome.StopConversationRequested -> {
                appendMessage(SparkMessage("assistant", outcome.replyText))
                _eyeState.value = SparkEyeState.IDLE
                speakHint(appContext, outcome.replyText)
            }
            is LocalCommandRouter.Outcome.LookRequested -> {
                executeLook(appContext, outcome.userAsk, attachToLastUser = true)
            }
            LocalCommandRouter.Outcome.NotHandled -> {
                sendAiRequest(appContext, clean, photos.map { it.dataUrl })
            }
        }
    }

    /** 通话中本地能力钩子（命中即本地秒回） */
    suspend fun handleCallCommand(context: Context, text: String): String? {
        var reply: String? = null
        duplexReplySink = { reply = it }
        return try {
            when (
                val outcome = LocalCommandRouter.tryHandle(
                    context = context,
                    settings = settings.value,
                    text = text,
                    noteStore = notes,
                    memoryStore = memory,
                )
            ) {
                is LocalCommandRouter.Outcome.Replied -> {
                    appendMessage(SparkMessage("assistant", outcome.displayText))
                    reply ?: outcome.spokenText
                }
                is LocalCommandRouter.Outcome.LookRequested -> {
                    executeLook(context, outcome.userAsk)
                    reply ?: "好的，我看一下"
                }
                else -> null
            }
        } finally {
            duplexReplySink = null
        }
    }

    private suspend fun executeLook(
        context: Context,
        userAsk: String? = null,
        attachToLastUser: Boolean = false,
    ) {
        if (!CameraSnapshotHelper.hasCameraPermission(context)) {
            appendMessage(SparkMessage("assistant", "需要相机权限才能帮你看，请点一下底部相机按钮授权一次。"))
            _statusHint.value = "需要相机权限"
            speakHint(context, "需要相机权限才能帮你看，请点一下相机按钮授权")
            return
        }
        _statusHint.value = "正在看…"
        val snap = CameraSnapshotHelper.takeSnapshot(context)
        if (snap == null) {
            appendMessage(SparkMessage("assistant", "没拍下来（相机没开起来），请再说一次试试。"))
            _statusHint.value = "没拍下来"
            speakHint(context, "没拍下来，请再说一次")
            return
        }
        val ask = userAsk?.trim()?.takeIf { it.isNotBlank() }
        sendImageQuestion(
            context = context,
            photos = listOf(PendingPhoto(snap.bitmap, snap.dataUrl)),
            userLabel = ask ?: "👀 看看前面",
            prompt = if (ask != null) {
                "这是小源眼前刚拍到的画面，用户的问题是：「$ask」。请结合画面回答用户的问题，" +
                    "直接给答案，别只描述画面；画面里找不到答案就直说没看到，用通俗易懂的自然口语。"
            } else {
                "这是小源眼前刚拍到的画面，请用通俗易懂的自然口语说说前面有什么：最主要的东西是什么、有没有人，一两句话即可。"
            },
            attachToLastUser = attachToLastUser,
        )
    }

    /** 统一将流式回答送入共享 LiveTtsSession 播报 */
    private fun sendAiRequest(
        context: Context,
        userText: String,
        imageDataUrls: List<String>,
        reuseLastAssistant: Boolean = false,
    ) {
        aiRequestJob?.cancel()
        liveTts?.cancel()
        liveTts = null
        SparkChatStore.clearStalePlaceholders()
        if (reuseLastAssistant) {
            SparkChatStore.upsertAssistantPlaceholder("")
        } else {
            appendMessage(SparkMessage("assistant", ""))
        }
        val tEnter = SystemClock.elapsedRealtime()

        val requestJob = viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repo.settings.firstOrNull() ?: AiSettings()
            if (curSettings.apiKey.isBlank()) {
                _eyeState.value = SparkEyeState.IDLE
                _isStreaming.value = false
                SparkChatStore.updateLastAssistant("未配置 API Key，请去「设置」选择服务商并填写 Key。")
                _statusHint.value = "未配置 API Key"
                speakHint(context, "未配置 API Key，请去设置填写")
                return@launch
            }

            val wantsWeather = listOf("天气", "气温", "温度", "下雨", "下雪", "刮风", "风力", "湿度", "穿什么", "热不热", "冷不冷", "带伞", "防晒")
                .any { userText.contains(it) }
            val wantsPlace = wantsWeather ||
                listOf("附近", "周边", "在哪", "哪儿", "多远", "怎么走", "导航", "位置", "这是哪").any { userText.contains(it) }
            val place = LocationContext.current(context)
                ?: if (wantsPlace) runCatching { LocationContext.fresh(context, timeoutMs = 1_200) }.getOrNull() else null
            val weatherLine = if (wantsWeather && place != null) {
                WeatherClient.currentQuick(
                    place.lon,
                    place.lat,
                    timeoutMs = 1_500,
                    amapKey = curSettings.amapKey,
                    cityHint = place.description,
                )
            } else null

            val contextInfo = buildString {
                CalendarContext.block()?.let { appendLine(it) }
                place?.description?.let { appendLine("位置：$it") }
                if (weatherLine != null) appendLine("【实时天气】$weatherLine")
                runCatching {
                    val store = (appContext as? TravelApp)?.memoryStore
                    val facts = store?.profileFacts().orEmpty()
                    if (facts.isNotEmpty()) {
                        appendLine("【关于用户】")
                        facts.take(10).forEach { appendLine("- $it") }
                    }
                }
            }

            val systemPrompt = """
                你是灵动模式智能伴侣「小源」，搭载于小屏随身智能设备。
                你的回应将直接转换为语音播放，并显示在屏幕会话框中。
                【表达原则】
                1. 语气亲切生动、自然口语化，适合小屏阅读与语音朗读；
                2. 回答要极其简练扼要，直接说出答案或关键要点，通常在2至4句话以内；
                3. 严禁输出任何 Markdown 标记（如加粗**、标题#、列表项、代码块等），全部使用自然口语排版；
                4. 若涉及视觉看图，生动具体地告诉用户拍到了什么。
            """.trimIndent()

            val fullPayload = listOfNotNull(
                OutMessage("system", systemPrompt),
                if (contextInfo.isNotBlank()) OutMessage("system", "【情境】\n$contextInfo") else null,
            ) + SparkChatStore.historyBudget(48_000) + listOf(
                OutMessage(role = "user", text = userText, images = imageDataUrls),
            )

            val speechBuffer = StringBuilder()
            _isStreaming.value = true
            val tSend = SystemClock.elapsedRealtime()
            val slowHint = launch {
                delay(6_000)
                if (speechBuffer.isEmpty() && _isStreaming.value) {
                    _statusHint.value = "还在等回应（网络有点慢）…"
                }
            }

            EdgeTts.warmUp()
            val live = LiveTtsSession(appContext).also { session ->
                session.onFirstSound = {
                    val hitAt = wakeHitAt
                    if (hitAt > 0) {
                        LogStore.log("ai", "首句起播：距唤醒 ${SystemClock.elapsedRealtime() - hitAt}ms")
                        wakeHitAt = 0L
                    }
                }
                session.onFinished = {
                    viewModelScope.launch {
                        if (liveTts === session) liveTts = null
                        if (_eyeState.value == SparkEyeState.SPEAKING && TtsPlayer.state.value is TtsPlayer.State.Idle) {
                            finishSpeaking()
                        }
                    }
                }
                liveTts = session
            }
            val mapCards = java.util.Collections.synchronizedList(mutableListOf<MapCardData>())

            AiClient(curSettings).chat(
                messages = fullPayload,
                thinking = curSettings.thinking,
                searchEnabled = false,
                toolExecutor = if (wantsPlace) {
                    { name, args -> AppTools.execute(appContext, curSettings, name, args) { card -> mapCards.add(card) } }
                } else null,
                onAnswer = { chunk ->
                    if (speechBuffer.isEmpty() && chunk.isNotBlank()) {
                        slowHint.cancel()
                        LogStore.log("ai", "首字 ${SystemClock.elapsedRealtime() - tSend}ms（准备 ${tSend - tEnter}ms）")
                    }
                    speechBuffer.append(chunk)
                    SparkChatStore.updateLastAssistant(speechBuffer.toString())
                    if (_eyeState.value != SparkEyeState.SPEAKING) {
                        _eyeState.value = SparkEyeState.SPEAKING
                        _statusHint.value = null
                    }
                    if (chunk.isNotBlank()) live.feed(chunk)
                },
            ).fold(
                onSuccess = { reply ->
                    slowHint.cancel()
                    val finalAnswer = reply.answer.trim()
                    SparkChatStore.updateLastAssistant(finalAnswer)
                    mapCards.lastOrNull()?.let { SparkChatStore.attachMapCard(it) }
                    _isStreaming.value = false
                    SparkChatStore.appendHistory(OutMessage("user", userText, imageDataUrls))
                    SparkChatStore.appendHistory(OutMessage("assistant", finalAnswer))

                    live.finish()
                    val spoke = live.awaitSpoken(4_000)
                    if (!spoke && finalAnswer.isNotBlank()) {
                        if (liveTts === live) liveTts = null
                        TtsPlayer.speak(appContext, finalAnswer)
                    }
                    LogStore.log("ai", "全文完成 总耗时 ${SystemClock.elapsedRealtime() - tEnter}ms（${finalAnswer.length} 字）")
                },
                onFailure = { err ->
                    slowHint.cancel()
                    live.cancel()
                    if (liveTts === live) liveTts = null
                    _eyeState.value = SparkEyeState.IDLE
                    _isStreaming.value = false
                    SparkChatStore.updateLastAssistant("请求出错啦：${err.message ?: "网络超时"}")
                    _statusHint.value = "请求失败"
                },
            )
        }
        requestJob.invokeOnCompletion {
            if (liveTts == null) _isStreaming.value = false
        }
        aiRequestJob = requestJob
    }

    private fun finishSpeaking() {
        _eyeState.value = SparkEyeState.IDLE
        ScreenAwake.idle()
        idleToEyesJob?.cancel()
        idleToEyesJob = viewModelScope.launch {
            delay(EYES_RETURN_AFTER_MS)
            if (TtsPlayer.state.value is TtsPlayer.State.Idle &&
                _eyeState.value == SparkEyeState.IDLE &&
                !holdTalk.state.value.isBusy &&
                !callController.ui.value.active
            ) {
                _showEyes.value = true
            }
        }
    }

    fun summarizeCallTurns() {
        val settingsNow = settings.value
        if (settingsNow.apiKey.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val store = (appContext as? TravelApp)?.sessionStore ?: return@launch
                val sid = repo.currentSessionId.firstOrNull() ?: return@launch
                val session = store.get(sid) ?: return@launch
                val userCount = session.messages.count { it.role == "user" }
                if (userCount <= session.summarizedUserCount || userCount - session.summarizedUserCount < 3) return@launch
                if (memory.updateProfileFromSession(session, settingsNow)) {
                    store.updateSummarizedUserCount(sid, userCount)
                }
            }
        }
    }

    private fun syncWakeEngine() {
        val cur = settings.value
        val inDuplexCall = callController.ui.value.active
        val busy = holdTalk.state.value.isBusy || inDuplexCall || classicCallActive
        val shouldRun = sparkActive &&
            WakeHub.standbyAllowed.value &&
            cur.wakeWordEnabled &&
            !busy &&
            SpeechInput.hasRecordPermission(appContext)
        if (!shouldRun) {
            WakeHub.stopStandby()
            return
        }
        WakeHub.startStandby(
            context = appContext,
            config = WakeHub.StandbyConfig(
                sensitivity = cur.voiceBargeIn,
                asrKey = cur.asrKey,
                dialect = cur.dialectMode,
                aiSettings = cur,
                amapKey = cur.amapKey,
                preferDirectCall = cur.wakeAction == "call",
                shouldSuppressHit = {
                    holdTalk.state.value.isBusy || callController.ui.value.active || classicCallActive
                },
            ),
            listener = object : WakeHub.Listener {
                override fun onStandbySpeechStart() {
                    if (!callController.ui.value.active && !holdTalk.state.value.isBusy) {
                        _eyeState.value = SparkEyeState.LISTENING
                    }
                }

                override fun onStandbyMiss() {
                    if (!WakeHub.oneShotActive() && !holdTalk.state.value.isBusy &&
                        !callController.ui.value.active && _eyeState.value == SparkEyeState.LISTENING
                    ) {
                        _eyeState.value = SparkEyeState.IDLE
                    }
                }

                override fun onWakeListening() {
                    wakeHitAt = SystemClock.elapsedRealtime()
                    _showEyes.value = true
                    _eyeState.value = SparkEyeState.LISTENING
                    _statusHint.value = "我在听，说完就好"
                }

                override fun onWakeCallRequested() {
                    wakeHitAt = SystemClock.elapsedRealtime()
                    _showEyes.value = true
                    wakeCallRequest.tryEmit(Unit)
                }

                override fun onTranscript(text: String, fromDirectWake: Boolean) {
                    wakeHitAt = SystemClock.elapsedRealtime()
                    viewModelScope.launch {
                        val clean = HotwordCorrector.correct(text)
                        answerVoiceQuestion(clean)
                    }
                }

                override fun onOneShotFailed(hint: String?) {
                    _eyeState.value = SparkEyeState.IDLE
                    _statusHint.value = hint
                    ScreenAwake.idle()
                }

                override fun onAlarmStoppedByVoice(spokenReply: String) {
                    appendMessage(SparkMessage("assistant", spokenReply))
                    _statusHint.value = null
                    _eyeState.value = SparkEyeState.SPEAKING
                }
            },
        )
    }

    private fun canSpeakProactively(): Boolean =
        !holdTalk.state.value.isBusy &&
            !_isStreaming.value &&
            TtsPlayer.state.value is TtsPlayer.State.Idle &&
            _eyeState.value != SparkEyeState.MUTED

    private fun speakProactive(text: String) {
        appendMessage(SparkMessage("assistant", text))
        speakHint(appContext, text)
    }

    private fun onBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val full = status == BatteryManager.BATTERY_STATUS_FULL

        if (charging != wasCharging) {
            wasCharging = charging
            if (!charging) fullReminded = false
        }
        if (!settings.value.proactiveReminders || !canSpeakProactively()) return

        if (full && !fullReminded) {
            fullReminded = true
            speakProactive("充好电啦")
            return
        }
        if (!charging) {
            val key = when {
                percent <= 10 -> "low10"
                percent <= 20 -> "low20"
                else -> null
            }
            if (key != null && key != lastLowKey) {
                lastLowKey = key
                speakProactive(
                    if (key == "low10") "电量只剩${percent}了，快没电啦，记得充电"
                    else "电量只剩${percent}了，记得及时充电",
                )
            }
            if (percent > 20) lastLowKey = null
        }
    }

    fun exitApp() {
        sparkActive = false
        idleToEyesJob?.cancel()
        liveTts?.cancel()
        liveTts = null
        WakeHub.stopStandby()
        WakeHub.cancelOneShot()
        holdTalk.cancel()
        callController.destroy()
        TtsPlayer.stop()
    }

    override fun onCleared() {
        super.onCleared()
        exitApp()
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
    }
}
