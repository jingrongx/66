package com.lvyoubao.app.data.duplex

import android.content.Context
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.DeviceTools
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.common.NetKeep
import com.lvyoubao.app.ui.common.ScreenAwake
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.common.WakeHub
import com.lvyoubao.app.ui.common.DuplexTurnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全应用统一的「豆包端到端全双工通话」控制器（普通 / 大字 / 灵动三模式共用）。
 *
 * 将原本在 AskDuplexCall、LargeTextScreen、SparkModeScreen 复制了三遍的逻辑统一收口：
 * - 待机麦让路（WakeHub.stopStandby）+ 播报停音（TtsPlayer.stop）+ 保活防熄屏（ScreenAwake/NetKeep）；
 * - ASR 累计快照覆盖、Answer 增量/快照兼容拼合、turns 会话列表维护；
 * - ToolCall（AppTools + DeviceTools）+ 1.2 秒「我查一下哈」防冷场兜底；
 * - 急停机器人 / 结束词挂断 / 重复指纹去重落库；
 * - 可选 [autoCollapse]（灵动模式播完无人说话 6 秒回萌眼但不断连，开口再弹出）与本地快路抢断钩子。
 */
class DuplexCallController(
    context: Context,
    private val scope: CoroutineScope,
    private val onCommitTurn: (asr: String, answer: String, imagePaths: List<String>) -> Unit,
    private val onAttachMapCard: (MapCardData) -> Unit,
    private val onLocalAsrCommand: (suspend (String) -> String?)? = null,
    private val onHangUpCallback: (() -> Unit)? = null,
) {
    data class UiState(
        val active: Boolean = false,
        val uiVisible: Boolean = false,
        val duplexState: DoubaoDuplexClient.State = DoubaoDuplexClient.State.IDLE,
        val turns: List<DuplexTurnUi> = emptyList(),
        val hint: String? = null,
        val photos: List<ChatViewModel.PendingAttachment> = emptyList(),
        val startedAtMs: Long = 0L,
        val elapsedSec: Int = 0,
        val speakerphone: Boolean = true,
    )

    private val appContext = context.applicationContext
    private val client = DoubaoDuplexClient(appContext)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    companion object {
        private const val ECHO_GUARD_WINDOW_MS = 8_000L

        /** 归一化对比文本：展开常见口语读法差异，并去掉空格与标点 */
        internal fun normalizeEchoText(text: String): String {
            val expanded = text.lowercase()
                .replace("°c", "摄氏度")
                .replace("℃", "摄氏度")
                .replace("%", "百分之")
            return buildString(expanded.length) {
                for (ch in expanded) {
                    if (ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF) {
                        append(ch)
                    }
                }
            }
        }

        /**
         * 兜底识别「扬声器外放被自己麦克风回灌」的自述回声（硬件 AEC 缺失或偶发残余时保护）：
         * 要求清洗后长度 >= 8 字且与最近助手回复的 2-gram 重合率 >= 65%（含直接子串），
         * 确保正常短语打断（「嗯」「好的」「再见」）及正常追问绝对不会被误拦。
         */
        internal fun isLikelyEchoTranscript(asr: String, recentAssistant: String): Boolean {
            val a = normalizeEchoText(asr)
            val b = normalizeEchoText(recentAssistant)
            if (a.length < 8 || b.length < 8) return false
            if (b.contains(a)) return true
            val totalBigrams = a.length - 1
            var hits = 0
            for (i in 0 until totalBigrams) {
                if (b.contains(a.substring(i, i + 2))) hits++
            }
            return hits >= 6 && hits.toFloat() / totalBigrams.toFloat() >= 0.65f
        }
    }

    private val pendingMapCards = mutableListOf<MapCardData>()
    private val tracker = DuplexTurnsTracker(echoGuardWindowMs = ECHO_GUARD_WINDOW_MS)
    private var lastVoiceAt = 0L
    private var lastTurnKey = ""
    private var localHandledAsr = ""
    private var localHandledAt = 0L

    private var currentSettings: AiSettings = AiSettings()
    private var autoCollapseEnabled = false
    private var collapseJob: Job? = null
    private var timerJob: Job? = null

    init {
        scope.launch {
            client.state.collect { st ->
                _ui.update { it.copy(duplexState = st) }
                if (st == DoubaoDuplexClient.State.LISTENING) {
                    lastVoiceAt = System.currentTimeMillis()
                }
            }
        }
        scope.launch {
            client.onEvent.collect { ev -> handleEvent(ev) }
        }
    }

    fun start(
        settings: AiSettings,
        persona: String,
        history: List<OutMessage>,
        deviceTools: Boolean,
        autoCollapse: Boolean = false,
    ) {
        currentSettings = settings
        autoCollapseEnabled = autoCollapse
        WakeHub.stopStandby()
        WakeHub.cancelOneShot()
        TtsPlayer.stop()
        ScreenAwake.pin("豆包通话")
        NetKeep.acquire(appContext, "doubao-call")

        tracker.reset()
        lastTurnKey = ""
        localHandledAsr = ""
        localHandledAt = 0L
        pendingMapCards.clear()
        val now = System.currentTimeMillis()
        lastVoiceAt = now
        _ui.value = UiState(
            active = true,
            uiVisible = true,
            duplexState = client.state.value,
            turns = emptyList(),
            hint = null,
            photos = emptyList(),
            startedAtMs = now,
            elapsedSec = 0,
            speakerphone = settings.callSpeakerphone,
        )

        if (settings.duplexApiKey.isBlank()) {
            _ui.update { it.copy(hint = "未配置豆包实时语音 Key，请到设置 → 凭证中心 → 豆包 填写") }
            return
        }
        if (!SpeechInput.hasRecordPermission(appContext)) {
            _ui.update { it.copy(hint = "需要麦克风权限才能全双工通话") }
            return
        }

        val setup = DuplexCallSetup.build(
            appContext,
            persona = persona,
            history = history,
            settings = settings,
            deviceTools = deviceTools,
        )
        client.start(
            settings.duplexApiKey,
            settings.duplexVoice,
            systemPrompt = setup.instructions,
            callTools = setup.tools,
            historyContext = setup.dialogContext,
            dialect = setup.dialect,
            music = setup.music,
            speakerphone = settings.callSpeakerphone,
        )
        startClockAndAutoCollapse()
    }

    fun toggleSpeakerphone() {
        setSpeakerphone(!_ui.value.speakerphone)
    }

    fun setSpeakerphone(enabled: Boolean) {
        if (!_ui.value.active) return
        _ui.update { it.copy(speakerphone = enabled) }
        client.setSpeakerphone(enabled)
    }

    fun expandUi() {
        if (!_ui.value.active) return
        lastVoiceAt = System.currentTimeMillis()
        _ui.update { it.copy(uiVisible = true) }
    }

    fun collapseUi() {
        if (!_ui.value.active) return
        _ui.update { it.copy(uiVisible = false) }
    }

    fun bargeIn() {
        client.bargeIn()
    }

    fun addPickedPhotos(items: List<ChatViewModel.PendingAttachment>) {
        if (items.isEmpty()) return
        _ui.update { it.copy(photos = it.photos + items) }
    }

    fun removePhoto(index: Int) {
        _ui.update { st ->
            st.copy(photos = st.photos.filterIndexed { i, _ -> i != index })
        }
    }

    fun setHint(msg: String?) {
        _ui.update { it.copy(hint = msg) }
    }

    fun hangUp(resumeWake: Boolean = true) {
        if (!_ui.value.active) return
        collapseJob?.cancel()
        timerJob?.cancel()
        client.close()
        tracker.reset()
        NetKeep.release("doubao-call")
        ScreenAwake.unpin("豆包通话")
        _ui.value = UiState(active = false, uiVisible = false)
        if (resumeWake) WakeHub.resumeStandby()
        onHangUpCallback?.invoke()
    }

    fun destroy() {
        hangUp(resumeWake = false)
        client.destroy()
    }

    private fun startClockAndAutoCollapse() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_ui.value.active) {
                delay(1_000)
                val elapsed = ((System.currentTimeMillis() - _ui.value.startedAtMs) / 1000L).toInt().coerceAtLeast(0)
                _ui.update { it.copy(elapsedSec = elapsed) }
            }
        }
        collapseJob?.cancel()
        if (autoCollapseEnabled) {
            collapseJob = scope.launch {
                while (_ui.value.active) {
                    delay(1_000)
                    val cur = _ui.value
                    val silentMs = System.currentTimeMillis() - lastVoiceAt
                    val listening = cur.duplexState == DoubaoDuplexClient.State.LISTENING ||
                        cur.duplexState == DoubaoDuplexClient.State.CONNECTED
                    val waitMs = if (cur.turns.isEmpty()) 10_000L else 6_000L
                    if (cur.uiVisible && listening && silentMs > waitMs) {
                        _ui.update { it.copy(uiVisible = false) }
                    }
                }
            }
        }
    }

    internal fun handleEvent(ev: DoubaoDuplexClient.Event) {
        when (ev) {
            is DoubaoDuplexClient.Event.AsrStarted -> {
                tracker.onAsrStarted()
                syncTurns()
            }
            is DoubaoDuplexClient.Event.AsrFailed -> {
                tracker.onAsrFailed()
                syncTurns()
            }
            is DoubaoDuplexClient.Event.Asr -> {
                val now = System.currentTimeMillis()
                lastVoiceAt = now
                if (tracker.onAsr(ev.text, isFinal = ev.isFinal, nowMs = now)) {
                    LogStore.log("duplex", "拦截自述回声 ASR：${ev.text.take(40)}")
                    syncTurns()
                    client.cancelResponse()
                    return
                }
                syncTurns()
                if (autoCollapseEnabled && !_ui.value.uiVisible && ev.text.isNotBlank()) {
                    _ui.update { it.copy(uiVisible = true) }
                }
                val handler = onLocalAsrCommand
                if (handler != null && ev.isFinal && ev.text.isNotBlank()) {
                    val said = ev.text
                    scope.launch {
                        val confirm = runCatching { handler(said) }.getOrNull()
                        if (confirm != null) {
                            client.cancelResponse()
                            client.speak(confirm)
                            tracker.onLocalConfirmAnswer(confirm, System.currentTimeMillis())
                            syncTurns()
                            localHandledAsr = said
                            localHandledAt = System.currentTimeMillis()
                            onCommitTurn(said, confirm, emptyList())
                        }
                    }
                }
            }
            is DoubaoDuplexClient.Event.Answer -> {
                val now = System.currentTimeMillis()
                lastVoiceAt = now
                tracker.onAnswer(ev.text, isFinal = ev.isFinal, nowMs = now)
                syncTurns()
            }
            is DoubaoDuplexClient.Event.ToolCall -> {
                lastVoiceAt = System.currentTimeMillis()
                val ackJob = scope.launch {
                    delay(1_200)
                    client.speak("我查一下哈")
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            when (ev.name) {
                                AppTools.POI, AppTools.WEATHER, AppTools.ROUTE ->
                                    AppTools.execute(appContext, currentSettings, ev.name, ev.arguments) {
                                        pendingMapCards.add(it)
                                    }
                                else -> DeviceTools.execute(appContext, currentSettings, ev.name, ev.arguments)
                            }
                        }.getOrElse { "工具执行失败：${it.message ?: "未知原因"}。请如实告知用户。" }
                    }
                    ackJob.cancel()
                    client.sendToolResult(ev.callId, result)
                }
            }
            is DoubaoDuplexClient.Event.StopRequested -> {
                scope.launch {
                    DeviceTools.stopRobot(appContext, currentSettings)
                    if (onLocalAsrCommand != null) client.speak("好的，已停下")
                }
            }
            is DoubaoDuplexClient.Event.HangupRequested -> {
                TtsPlayer.stop()
                hangUp()
            }
            is DoubaoDuplexClient.Event.TurnDone -> {
                val now = System.currentTimeMillis()
                lastVoiceAt = now
                if (tracker.onTurnDone(ev.asr, ev.answer, nowMs = now)) {
                    LogStore.log("duplex", "丢弃回声轮次落库：${ev.asr.take(40)}")
                    pendingMapCards.clear()
                    syncTurns()
                    return
                }
                syncTurns()
                if (ev.answer.isNotBlank()) {
                    val handledLocally = ev.asr.isNotBlank() &&
                        ev.asr == localHandledAsr &&
                        now - localHandledAt < 15_000L
                    if (!handledLocally) {
                        val key = ev.asr.filterNot { it.isWhitespace() } + "|" + ev.answer.filterNot { it.isWhitespace() }
                        if (key != lastTurnKey) {
                            lastTurnKey = key
                            val paths = _ui.value.photos.mapNotNull { it.localPath }
                            onCommitTurn(ev.asr, ev.answer, paths)
                            _ui.update { it.copy(photos = emptyList()) }
                        }
                    }
                }
                pendingMapCards.lastOrNull()?.let { onAttachMapCard(it) }
                pendingMapCards.clear()
            }
            is DoubaoDuplexClient.Event.SessionReady -> {
                tracker.reset()
                lastTurnKey = ""
                localHandledAsr = ""
                localHandledAt = 0L
                pendingMapCards.clear()
                _ui.update { it.copy(hint = null, turns = emptyList(), photos = emptyList()) }
            }
            is DoubaoDuplexClient.Event.Error -> {
                val msg = when (ev.code) {
                    "CONFIG" -> "未配置豆包实时语音 Key，请到设置 → 凭证中心 → 豆包 填写"
                    else -> "通话出错：${ev.message}"
                }
                _ui.update { it.copy(hint = msg) }
            }
            else -> Unit
        }
    }

    private fun syncTurns() {
        val snapshot = tracker.turns
        _ui.update { it.copy(turns = snapshot) }
    }
}
