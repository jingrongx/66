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
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.DeviceBattery
import com.lvyoubao.app.data.DeviceVolume
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RobotClient
import com.lvyoubao.app.data.RobotIntent
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.ui.common.ContinuousConversation
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val role: String, // "user" / "assistant"
    val text: String,
    val photos: List<Bitmap> = emptyList(),
)

/** 待发送照片：解码转正后的位图 + 视觉模型要的 base64 dataUrl */
data class PendingPhoto(val bitmap: Bitmap, val dataUrl: String)

class SparkViewModel(app: TravelApp) : ViewModel() {

    private companion object {
        // 回答播报完毕后静默 N 秒自动回到萌眼待机
        const val EYES_RETURN_AFTER_MS = 4_000L
    }

    private val repo = app.settingsRepository
    private val memory = app.memoryStore
    private val notes = app.noteStore
    private val appContext = app.applicationContext

    // 必须 Eagerly：灵动模式界面从不收集这个流，WhileSubscribed 会让上游永不启动，
    // settings.value 永远停在默认空配置——曾导致退出灵动模式把默认空配置整体写回、
    // 清空用户全部配置，以及误报「未配置语音识别 Key」
    val settings: StateFlow<AiSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiSettings())

    private val _eyeState = MutableStateFlow(SparkEyeState.IDLE)
    val eyeState: StateFlow<SparkEyeState> = _eyeState.asStateFlow()

    // 界面显示切换：true 显示可爱大眼动画；false 显示会话文字与照片框
    private val _showEyes = MutableStateFlow(true)
    val showEyes: StateFlow<Boolean> = _showEyes.asStateFlow()

    private val _statusHint = MutableStateFlow<String?>(null)
    val statusHint: StateFlow<String?> = _statusHint.asStateFlow()

    // 按住说话录音状态
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordTick = MutableStateFlow(0)
    val recordTick: StateFlow<Int> = _recordTick.asStateFlow()

    // 完整会话流：挂进程级单例（低内存设备上 VM/Activity 被系统重建时会话不丢）
    val messages: StateFlow<List<SparkMessage>> = SparkChatStore.messages

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // 待发送照片缓冲：拍照/相册可连续加多张，按住说话时随问题一起发，或点「立即识别」单独问
    private val _pendingPhotos = MutableStateFlow<List<PendingPhoto>>(emptyList())
    val pendingPhotos: StateFlow<List<PendingPhoto>> = _pendingPhotos.asStateFlow()

    private var activeRecording: SpeechInput.Recording? = null
    private var recordTimerJob: Job? = null
    private var aiRequestJob: Job? = null
    private var idleToEyesJob: Job? = null

    // 连续对话（免按住、可语音打断）：引擎会话 + 界面开关态 + 连续识别失败计数（防网络抖动反复念叨）
    private var continuous: ContinuousConversation? = null
    private val _isContinuous = MutableStateFlow(false)
    val isContinuous: StateFlow<Boolean> = _isContinuous.asStateFlow()
    private var consecutiveAsrFailures = 0

    // 唤醒词监听（待机时喊「小源小源」进连续对话）：与连续对话共用一套引擎，状态机决定开谁
    private var wakeSession: ContinuousConversation? = null
    // 唤醒后的「单次聆听」会话：收一句话就停，不常驻占麦
    private var wakeOnceSession: ContinuousConversation? = null
    // 待机监听里「这次开口」的时刻：用来量化唤醒链路每一段耗时（判活 → 转写 → 命中）
    @Volatile
    private var wakeSpeechStartedAt = 0L
    // 流式播报进行中：句与句之间的 TtsPlayer「Idle」不算播完，别把眼睛提前顶出来
    @Volatile
    private var ttsStreaming = false
    private var sparkActive = false
    // 全双工通话占用麦克风期间，唤醒监听让路（一台设备同一时刻只能有一个 AudioRecord）
    private var duplexAudioActive = false
    // 通话中本机确认话术的去处：非空时 speakHint 不自己朗读，改由豆包音色说出来
    private var duplexReplySink: ((String) -> Unit)? = null
    private var lastConvUploadAt = 0L
    private var lastWakeUploadAt = 0L
    private var wakeUploadCount = 0
    private var wakeWindowStart = 0L

    // 主动提醒（电量低/充满）：键控一次（同档位只提醒一次），空闲才说，静默安睡不说
    private var lastLowKey: String? = null
    private var fullReminded = false
    private var wasCharging = false

    // 倒计时（语音「计时10分钟」）：眼睛区大字显示 时分秒，到点先语音告知（带提醒事项）再滴滴滴循环响
    private var timerJob: Job? = null
    private val _timerRemainingSec = MutableStateFlow<Int?>(null)
    val timerRemainingSec: StateFlow<Int?> = _timerRemainingSec.asStateFlow()

    // 到点告警：滴滴滴循环（AudioTrack 手工 PCM，ToneGenerator 在低端机连发几次就哑），点屏幕/说「别响了」停止
    private var alarmJob: Job? = null
    private var alarmTrack: android.media.AudioTrack? = null
    private val _alarmActive = MutableStateFlow(false)
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()
    private val _alarmText = MutableStateFlow("")
    val alarmText: StateFlow<String> = _alarmText.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) onBatteryChanged(intent)
        }
    }

    init {
        // 监听 TTS 状态。
        // 注意双段播报时序：流式先读前几字（很短，几秒播完发 Idle），完整答案再 speak() 重启播放——
        // 交界的瞬时 Idle 曾把状态误判成「播完」并切回 IDLE，之后计时器在真播报中把眼睛顶出来。
        // 所以：Speaking 到达一律重断言（从 THINKING/IDLE 拉回 SPEAKING）并取消回萌眼倒计时；
        // Idle 只在「当前确实是播报态」时才当播完处理；计时到点再复核一次真实播放状态。
        viewModelScope.launch {
            TtsPlayer.state.collect { ttsState ->
                when {
                    ttsState is TtsPlayer.State.Speaking -> {
                        // 连续对话引擎：播报中切换为「打断监听」阈值；播报即表明处理链路走完了，空闲计时恢复
                        continuous?.setTtsActive(true)
                        continuous?.setBusy(false)
                        wakeSession?.setTtsActive(true)
                        wakeOnceSession?.setTtsActive(true)
                        idleToEyesJob?.cancel()
                        if (_eyeState.value == SparkEyeState.THINKING || _eyeState.value == SparkEyeState.IDLE) {
                            _eyeState.value = SparkEyeState.SPEAKING
                        }
                    }
                    ttsState is TtsPlayer.State.Idle -> {
                        continuous?.setTtsActive(false)
                        wakeSession?.setTtsActive(false)
                        wakeOnceSession?.setTtsActive(false)
                        // 流式播报是一句一句合成的，句间必然出现 Idle 空档——
                        // 这时判「播完」会把眼睛提前顶出来，随后下一句又被 Speaking 拉回去，看着一闪一闪
                        if (ttsStreaming) return@collect
                        if (_eyeState.value == SparkEyeState.SPEAKING) finishSpeaking()
                    }
                }
            }
        }
        // 电量广播：注册即收到粘性广播一次（初始状态），此后每次电量/充电状态变化触发——主动提醒的数据源
        runCatching {
            appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        // 设置变化（含自动保存落盘）→ 重算唤醒监听该不该开
        viewModelScope.launch {
            settings.collect { syncWakeEngine() }
        }
    }

    /** 点会话消息：重新朗读这条内容（打断当前播报） */
    fun replayMessage(context: Context, text: String) {
        if (text.isBlank()) return
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.SPEAKING
        _statusHint.value = null
        TtsPlayer.speak(context, text)
    }

    /**
     * 语音优先设备的提示也要说出来（识别没听清/识别失败/缺 Key 等）：
     * 切播报态后朗读，播完由 TTS 收集器按「静默 4 秒回萌眼」接管
     */
    private fun speakHint(context: Context, text: String) {
        // 全双工通话中：不走本机 TTS（会和豆包的声音叠在一起），把确认话术交给通话层去说
        duplexReplySink?.let { sink ->
            sink(text)
            return
        }
        _eyeState.value = SparkEyeState.SPEAKING
        TtsPlayer.speak(context, text)
    }

    /**
     * 长按会话消息：收藏/取消收藏（与普通模式回答卡同一套个人飞轮证据，
     * 以内容前 60 字为标题判重，已收藏再点则移除）
     */
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

    /** 追加一条会话消息（仓库内部防照片占内存） */
    private fun appendMessage(msg: SparkMessage) = SparkChatStore.append(msg)

    /**
     * 按住说话：按下开始录音
     * 操作逻辑完全对齐主模式与大字模式：按住录音、松手即问
     * 按下时显示大眼睛动画（对应需求：按住按钮说话的时候就显示眼睛动画）
     */
    fun startHoldTalk(context: Context, onRequestRecordPermission: () -> Unit) {
        if (!SpeechInput.hasRecordPermission(context)) {
            onRequestRecordPermission()
            return
        }

        // 按住说话用 MediaRecorder，与唤醒监听的 AudioRecord 抢麦：先停唤醒采集再开录音
        wakeSession?.stop()
        wakeSession = null
        stopAlarm() // 用户主动开口交互，铃响也先安静
        // 停止之前的朗读，取消「回萌眼」倒计时（用户开始新交互了）
        // 注意：这里绝不能切视图（v0.8.81 曾在按住时强制 showEyes=true——会话态下按住说话
        // 会把按钮从界面上摘掉，手势瞬间被打断直接走「松手识别」，录到的全是空气）
        // 录音状态由按钮变红+「松手发送(Ns)」提示；萌眼态本来就显示眼睛，不受影响
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.LISTENING
        _statusHint.value = "正在听你说，松手发送…"

        val curSettings = settings.value

        // 如果未配置 ASR Key，提示填写
        if (curSettings.asrKey.isBlank()) {
            _statusHint.value = "提示：可在设置里填入语音识别Key（硅基流动免费）"
        }

        SpeechInput.startRecording(context).fold(
            onSuccess = { rec ->
                activeRecording = rec
                _isRecording.value = true
                _recordTick.value = 0
                recordTimerJob?.cancel()
                recordTimerJob = viewModelScope.launch {
                    while (_isRecording.value) {
                        delay(1000)
                        _recordTick.value += 1
                    }
                }
            },
            onFailure = { err ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = err.message ?: "麦克风启动失败"
            },
        )
    }

    /**
     * 按住说话：松手发送
     * 停止录音 -> 转写文字 -> 切换为会话框显示文字 -> DeepSeek 回答 + TTS
     */
    fun endHoldTalk(context: Context) {
        val rec = activeRecording
        activeRecording = null
        _isRecording.value = false
        recordTimerJob?.cancel()

        if (rec == null) {
            _eyeState.value = SparkEyeState.IDLE
            return
        }

        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "正在识别语音…"

        viewModelScope.launch {
            // 与 sendAiRequest 同口径：读仓库实时配置，不依赖界面是否订阅过 settings 流
            val curSettings = repo.settings.firstOrNull() ?: settings.value
            val asrKey = curSettings.asrKey.trim()

            if (asrKey.isBlank()) {
                // 没有配置 ASR Key
                _eyeState.value = SparkEyeState.IDLE
                appendMessage(SparkMessage("user", "（未配置语音识别 Key）"))
                appendMessage(
                    SparkMessage("assistant", "请先在「设置」中配置语音识别 Key（硅基流动 SenseVoice 免费），即可畅享毫秒级语音对话。"),
                )
                _showEyes.value = false
                _statusHint.value = "请在设置中配置语音识别 Key"
                speakHint(context, "请先在设置中配置语音识别 Key")
                SpeechInput.cancelRecording(rec)
                return@launch
            }

            SpeechInput.stopAndTranscribe(rec, asrKey, curSettings.dialectMode).fold(
                onSuccess = { spoken ->
                    val clean = HotwordCorrector.correct(spoken.trim())
                    if (clean.isNotBlank()) {
                        // 按住说话时若缓冲里有照片，随这个问题一起发出去
                        val pending = _pendingPhotos.value
                        _pendingPhotos.value = emptyList()
                        // 与唤醒单次同一条链路（本地快路 → 本机意图 → 聊天）
                        answerVoiceQuestion(clean, pending)
                    } else {
                        _eyeState.value = SparkEyeState.IDLE
                        _statusHint.value = "没听清楚，请再试一次"
                        speakHint(context, "没听清楚，请再试一次")
                    }
                },
                onFailure = { err ->
                    _eyeState.value = SparkEyeState.IDLE
                    appendMessage(SparkMessage("user", "（识别未完成）"))
                    appendMessage(SparkMessage("assistant", err.message ?: "没听清楚，请再试一次"))
                    _showEyes.value = false
                    // 播报按分类给话术：没听清说「没听清楚」、服务端故障说「网络服务问题」、其余说具体原因
                    val spoken = SpeechInput.spokenHint(err)
                    _statusHint.value = spoken
                    speakHint(context, spoken)
                },
            )
            syncWakeEngine() // 按住说话结束（MediaRecorder 已释放），恢复待机唤醒监听
        }
    }

    /**
     * 拍照/相册照片入待发送缓冲（可连续多张）：
     * EXIF 转正 → 降采样 → 缩略图条显示；点「立即识别」或按住说话时一起发出去。
     * 之前用 Camera2 裸抓帧：传感器方向没处理（照片是横的）、无预览暖场首帧即抓（曝光未就绪全黑），
     * 现已改走系统相机（TakePicture）+系统图库（GetMultipleContents 多选）。
     */
    fun addPendingPhotos(context: Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        _statusHint.value = "正在读取照片…"

        viewModelScope.launch(Dispatchers.IO) {
            val added = uris.mapNotNull { uri ->
                SparkImageCodec.decodeUpright(context, uri)?.let { bitmap ->
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
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

    /** 缩略图条上的「立即识别」：把缓冲里的照片一起交给视觉解析 */
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

    /** 照片轮入会话（缩略图只挂这一轮）+ 视觉解析，并清空待发送缓冲 */
    private fun sendImageQuestion(
        context: Context,
        photos: List<PendingPhoto>,
        userLabel: String,
        prompt: String,
    ) {
        appendMessage(SparkMessage("user", userLabel, photos.map { it.bitmap }))
        appendMessage(SparkMessage("assistant", "小源正在观察画面…"))
        _pendingPhotos.value = emptyList()
        _showEyes.value = false // 切换为会话流，露出刚选的照片和回答

        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "小源正在观察…"

        sendAiRequest(
            context = context,
            userText = prompt,
            imageDataUrls = photos.map { it.dataUrl },
        )
    }

    /**
     * 语音助手意图统一入口（AI 解析判断，机器人动作 + 设备指令一体化）：
     * ①机器人快路（急停/头部短句，开了机器人控制才生效）+ 设备快路（计时/记事短句，零延迟）；
     * ②其余交给 AI 解析意图（chatPlain 单轮、思考关闭）——一句话可含多个动作（AI 按顺序每行输出一条）：
     *   机器人动作（MOVE/TURN/HEAD/STOP）走中枢串行编排（动作间按运动耗时留间隔，结束一次性播报）；
     *   设备指令（LOOK 拍照看一眼 / BATTERY / VOLUME_* / TIMER / NOTE / MEMORY_SEARCH）直接本机执行，
     *   不受机器人开关限制；LOOK 终止编排（拍照问答接管会话）；触发说法不设死，AI 判断；
     * ③解析为空返回 false 照常聊天；机器人动作在没开机器人控制时也回落聊天；失败即停编排并播报原因。
     */
    private suspend fun handleAssistantIntent(context: Context, text: String): Boolean {
        val curSettings = repo.settings.firstOrNull() ?: settings.value

        // 响铃中：明确的停止口令给确认（小源小源/退出/关闭/别响了都算）；其余任何语音也先停铃
        // ——开口就是想让它安静，后续指令照常往下走
        if (_alarmActive.value) {
            if (matchAlarmStop(text)) {
                stopAlarm()
                appendMessage(SparkMessage("assistant", "好的，不响了"))
                _statusHint.value = null
                speakHint(context, "好的，不响了")
                return true
            }
            stopAlarm()
        }

        // 机器人快路：急停/头部短句（没开机器人控制就没有机器人可指挥，直接过）
        if (curSettings.robotEnabled) {
            if (RobotIntent.matchStopFast(text)) {
                val robot = robotClientOrNull(context, curSettings) ?: return true
                runRobot(context, robot, RobotIntent.Command.Stop)
                return true
            }
            RobotIntent.matchHeadFast(text)?.let { headCmd ->
                val robot = robotClientOrNull(context, curSettings) ?: return true
                runRobot(context, robot, headCmd)
                return true
            }
        }

        // 道别快路（连聊中）：再见/拜拜/退出等短句直接结束对话，零延迟
        if (_isContinuous.value && matchByeFast(text)) {
            stopContinuous()
            appendMessage(SparkMessage("assistant", "好的，下次再聊"))
            _statusHint.value = null
            speakHint(context, "好的，下次再聊")
            return true
        }

        // 设备快路：计时/记事短句（零延迟）
        matchTimerFast(text)?.let { (sec, label) ->
            startTimerWithConfirm(context, sec, label)
            return true
        }
        matchNoteFast(text)?.let { content ->
            executeNote(context, content)
            return true
        }

        // AI 解析（机器人动作 + 设备指令统一判断）
        _statusHint.value = "正在理解指令…"
        val commands = RobotIntent.parse(text, curSettings)
        if (commands.isEmpty()) return false

        // LOOK 终止编排：拍照问答接管会话，其后的指令忽略
        val lookIndex = commands.indexOf(RobotIntent.Command.Look)
        val chain = if (lookIndex >= 0) commands.take(lookIndex + 1) else commands

        // 设备能力指令（不需要机器人中枢，单发即执行）
        if (chain.contains(RobotIntent.Command.Battery)) {
            answerBattery(context)
            return true
        }
        chain.filterIsInstance<RobotIntent.Command.Volume>().firstOrNull()?.let { volCmd ->
            answerVolume(context, volCmd.action, volCmd.percent)
            return true
        }
        chain.filterIsInstance<RobotIntent.Command.Timer>().firstOrNull()?.let { t ->
            startTimerWithConfirm(context, t.totalSec, t.remindText)
            return true
        }
        if (chain.contains(RobotIntent.Command.TimerStop)) {
            cancelTimer()
            appendMessage(SparkMessage("assistant", RobotIntent.Command.TimerStop.confirmText))
            _statusHint.value = null
            speakHint(context, RobotIntent.Command.TimerStop.confirmText)
            return true
        }
        chain.filterIsInstance<RobotIntent.Command.Note>().firstOrNull()?.let { n ->
            executeNote(context, n.content)
            return true
        }
        chain.filterIsInstance<RobotIntent.Command.MemorySearch>().firstOrNull()?.let { ms ->
            executeMemorySearch(context, ms.keyword)
            return true
        }
        // AI 判断是道别：结束连续对话（设备指令，不受机器人开关限制）
        if (chain.contains(RobotIntent.Command.AssistantOff)) {
            stopContinuous()
            appendMessage(SparkMessage("assistant", RobotIntent.Command.AssistantOff.confirmText))
            _statusHint.value = null
            speakHint(context, RobotIntent.Command.AssistantOff.confirmText)
            return true
        }

        // LOOK 单发：设备相机就行，不依赖机器人中枢
        if (chain.size == 1 && chain[0] == RobotIntent.Command.Look) {
            executeLook(context)
            return true
        }

        // 剩下的是机器人动作：没开机器人控制→回落聊天
        if (!curSettings.robotEnabled) return false

        val robot = robotClientOrNull(context, curSettings) ?: return true

        val executed = mutableListOf<String>()
        for ((i, cmd) in chain.withIndex()) {
            if (cmd == RobotIntent.Command.Look) {
                if (executed.isNotEmpty()) announceChain(context, executed)
                executeLook(context)
                return true
            }
            val ok = executeRobot(robot, cmd).fold(
                onSuccess = {
                    executed.add(cmd.label)
                    true
                },
                onFailure = { err ->
                    appendMessage(SparkMessage("assistant", "机器人没动起来：${err.message ?: "连接失败"}"))
                    _statusHint.value = err.message ?: "机器人没动起来"
                    speakHint(context, err.message ?: "机器人没动起来")
                    false
                },
            )
            if (!ok) return true // 失败即停编排
            if (i < chain.lastIndex) {
                _statusHint.value = "执行中（${i + 1}/${chain.size}）…"
                delay(estimateMillis(cmd)) // 留出动作的运动耗时，节奏可感知
            }
        }
        announceChain(context, executed)
        return true
    }

    /** 编排完成后一次性播报（比每步都打断 TTS 自然） */
    private fun announceChain(context: Context, executed: List<String>) {
        val spoken = "好的，" + executed.joinToString("，然后")
        appendMessage(SparkMessage("assistant", spoken))
        _statusHint.value = null
        speakHint(context, spoken)
    }

    /** 中枢客户端；地址没填时语音引导并返回 null */
    private suspend fun robotClientOrNull(context: Context, curSettings: AiSettings): RobotClient? {
        val host = RobotClient.normalizeHost(curSettings.robotHost)
        if (host == null) {
            appendMessage(SparkMessage("assistant", "机器人地址还没填，请到「设置 → 机器人控制」填写机器人 IP。"))
            _statusHint.value = "请先填写机器人地址"
            speakHint(context, "机器人地址还没填，请到设置里填写")
            return null
        }
        return RobotClient(host)
    }

    private suspend fun executeRobot(robot: RobotClient, cmd: RobotIntent.Command): Result<Unit> = when (cmd) {
        RobotIntent.Command.Stop -> robot.stop()
        is RobotIntent.Command.Move -> robot.move(cmd.distCm)
        is RobotIntent.Command.Turn -> robot.turn(cmd.angleDeg)
        is RobotIntent.Command.Head -> robot.head(cmd.angleDeg)
        else -> Result.success(Unit) // LOOK 不走中枢
    }

    /** 单条动作的完整执行（执行+播报），急停快路与单指令路径共用 */
    private suspend fun runRobot(context: Context, robot: RobotClient, cmd: RobotIntent.Command) {
        executeRobot(robot, cmd).fold(
            onSuccess = {
                appendMessage(SparkMessage("assistant", cmd.confirmText))
                _statusHint.value = null
                speakHint(context, cmd.confirmText)
            },
            onFailure = { err ->
                appendMessage(SparkMessage("assistant", "机器人没动起来：${err.message ?: "连接失败"}"))
                _statusHint.value = err.message ?: "机器人没动起来"
                speakHint(context, err.message ?: "机器人没动起来")
            },
        )
    }

    /** 动作的运动耗时估计（间隔节奏用；中枢队列本身串行，估少了也只是节奏紧不碍正确性） */
    private fun estimateMillis(cmd: RobotIntent.Command): Long = when (cmd) {
        is RobotIntent.Command.Move -> ((kotlin.math.abs(cmd.distCm) / 8f).coerceIn(1f, 8f) * 1000).toLong()
        is RobotIntent.Command.Turn -> ((kotlin.math.abs(cmd.angleDeg) / 60f).coerceIn(1f, 6f) * 1000).toLong()
        is RobotIntent.Command.Head -> 1_200L
        else -> 500L
    }

    /**
     * LOOK「看看前面有什么」类：设备相机静默抓一帧（CameraSnapshotHelper 即拍即走，零待机占用）
     * → 走现有视觉解析管线（照片轮入会话 + 流式回答 + 自动朗读）。
     */
    private suspend fun executeLook(context: Context) {
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
        sendImageQuestion(
            context = context,
            photos = listOf(PendingPhoto(snap.bitmap, snap.dataUrl)),
            userLabel = "👀 看看前面",
            prompt = "这是小源眼前刚拍到的画面，请用通俗易懂的自然口语说说前面有什么：最主要的东西是什么、有没有人，一两句话即可。",
        )
    }

    // ---------- 计时 / 记事 / 查记忆 ----------

    /** 启动倒计时：眼睛区大字显示；到点先语音告知（带提醒事项），再滴滴滴循环响直到被停 */
    fun startTimer(totalSec: Int, label: String = "") {
        timerJob?.cancel()
        val tip = label.trim()
        var left = totalSec.coerceIn(1, 86_400)
        _timerRemainingSec.value = left
        timerJob = viewModelScope.launch {
            while (left > 0) {
                delay(1_000)
                left--
                _timerRemainingSec.value = left
            }
            _timerRemainingSec.value = null
            TtsPlayer.stop()
            val msg = if (tip.isBlank()) "时间到啦" else "时间到啦，$tip"
            _alarmText.value = msg
            _statusHint.value = msg
            speakHint(appContext, msg)
            startAlarm()
        }
    }

    /**
     * 滴滴滴循环告警（经典闹钟音色：三连短音 1600Hz + 停顿，AudioTrack 写 PCM 循环）。
     * 之前用 ToneGenerator：低端机连发几次就哑（startTone 静默失败）且音色软趴趴不像闹钟。
     * 走 USAGE_ALARM 音量流（独立的闹钟音量，不受媒体静音影响）；等语音告知播完再响不掐自己的话。
     */
    private fun startAlarm() {
        alarmJob?.cancel()
        _alarmActive.value = true
        alarmJob = viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (TtsPlayer.state.value is TtsPlayer.State.Speaking && waited < 8_000) {
                delay(200)
                waited += 200
            }
            if (!_alarmActive.value) return@launch
            val sampleRate = 16_000
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = try {
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8192))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                return@launch
            }
            if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                track.release()
                return@launch
            }
            alarmTrack = track
            val cycle = alarmCyclePcm(sampleRate)
            try {
                track.play()
                while (_alarmActive.value) {
                    val written = track.write(cycle, 0, cycle.size)
                    if (written < 0) break // 被 stop/release：退出
                }
            } finally {
                if (alarmTrack === track) alarmTrack = null
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    /** 一轮「滴滴滴」PCM：三连 150ms 短音（1600Hz 正弦，首尾渐变防爆音）+ 1 秒停顿 */
    private fun alarmCyclePcm(sampleRate: Int): ShortArray {
        val out = ShortArray(sampleRate * 1650 / 1000)
        var idx = 0
        fun beep(ms: Int) {
            val n = sampleRate * ms / 1000
            val fade = (sampleRate * 0.005).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                if (idx >= out.size) return
                val env = minOf(1.0, i.toDouble() / fade, (n - i).toDouble() / fade).coerceIn(0.0, 1.0)
                val v = (Math.sin(2 * Math.PI * 1600.0 * i / sampleRate) * 0.55 * env * Short.MAX_VALUE).toInt().toShort()
                out[idx++] = v
            }
        }
        fun silence(ms: Int) {
            repeat(sampleRate * ms / 1000) {
                if (idx < out.size) out[idx++] = 0
            }
        }
        beep(150); silence(100)
        beep(150); silence(100)
        beep(150); silence(1000)
        return out
    }

    /** 停止滴滴滴告警（点屏幕任意处 / 说「别响了」） */
    fun stopAlarm() {
        _alarmActive.value = false
        alarmJob?.cancel()
        alarmJob = null
        // 直接停/释放音轨：writer 阻塞中的 write 会立刻报错退出，不会拖泥带水多响一轮
        val track = alarmTrack
        alarmTrack = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
    }

    /** 告警停止口令（仅响铃时判定）：说停/退出/关闭/小源小源 都算，宽容到「开口就是想让它安静」 */
    private fun matchAlarmStop(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 12) return false
        return t.contains("别响") || t.contains("别滴滴") || t.contains("停止提醒") || t.contains("取消提醒") ||
            t.contains("关掉提醒") || t.contains("别闹了") || t.contains("关掉闹钟") || t.contains("关闭闹钟") ||
            t.contains("关闹钟") || t.contains("不响了") || t.contains("退出") || t.contains("关闭") ||
            t.contains("关掉") || t.contains("小源小源") || t.contains("停止") || t.contains("别说了")
    }

    fun cancelTimer() {
        timerJob?.cancel()
        _timerRemainingSec.value = null
        stopAlarm()
    }

    private fun startTimerWithConfirm(context: Context, totalSec: Int, label: String) {
        startTimer(totalSec, label)
        appendMessage(SparkMessage("assistant", RobotIntent.Command.Timer(totalSec, label).confirmText))
        _statusHint.value = null
        speakHint(context, RobotIntent.Command.Timer(totalSec, label).confirmText)
    }

    /** 计时/倒计时短句本地匹配（零延迟），带提醒事项：「15分钟后提醒我关火」→(900, 关火)；长句交给 AI */
    private fun matchTimerFast(text: String): Pair<Int, String>? {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 20) return null
        if (t.startsWith("别") || t.startsWith("不要") || t.contains("取消") || t.contains("停止")) return null

        fun toSec(num: Int, unit: String) = when (unit) {
            "小时", "时" -> num * 3600
            "分钟", "分" -> num * 60
            else -> num
        }

        // A：提醒我/计时/倒计时/闹钟/定个 N 单位 [后] [提醒事项]
        Regex("(提醒我|计时|倒计时|闹钟|定个?)[约]?\\s*(\\d{1,3})\\s*(?:个)?(小时|时|分钟|分|秒)\\s*(?:之?后|以后?)?(.*)").find(t)?.let { a ->
            val sec = toSec(a.groupValues[2].toIntOrNull() ?: return null, a.groupValues[3])
            if (sec !in 1..86_400) return null
            var label = a.groupValues[4].trim()
            if (label.startsWith("提醒我")) {
                label = label.removePrefix("提醒我").trim()
                label = label.removePrefix("后").trim()
            }
            label = label.removeSuffix("闹钟").trim()
            // 只有「提醒我」句式或明确带事项才算提醒；纯「计时10分钟」就是普通倒计时
            val isReminder = a.groupValues[1] == "提醒我" || (label.isNotBlank() && label != "闹钟")
            return sec to if (isReminder) label else ""
        }

        // B：N 单位 后 提醒我 [事项]（「15分钟后提醒我关火」）
        Regex("(\\d{1,3})\\s*(?:个)?(小时|时|分钟|分|秒)(?:之?后|以后?)提醒我\\s*(.*)").find(t)?.let { b ->
            val sec = toSec(b.groupValues[1].toIntOrNull() ?: return null, b.groupValues[2])
            if (sec !in 1..86_400) return null
            return sec to b.groupValues[3].trim()
        }
        return null
    }

    /** 记事短句本地匹配：「记一下，明天九点开会」→内容提取；「记忆里…」等以记开头但非记事的放行给 AI */
    private fun matchNoteFast(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 60) return null
        val m = Regex("^(?:帮我)?记(?:一下|一笔|个事|着|录)[：:，,]?\\s*(.+)$").find(t) ?: return null
        return m.groupValues[1].trim().takeIf { it.length >= 2 }
    }

    /** 道别短句本地匹配（仅连聊中）：再见/拜拜/退出/不聊了等，零延迟结束对话；长句交给 AI 判断 */
    private fun matchByeFast(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 6) return false
        return listOf("再见", "拜拜", "退出", "不聊了", "先这样", "告辞", "撤了").any { t == it || t.startsWith(it) }
    }

    /** 记一笔随手记（NoteStore 本机 JSONL，随手记页可见） */
    private suspend fun executeNote(context: Context, content: String) {
        val ok = runCatching { notes.add(content) }.getOrNull() != null
        if (ok) {
            appendMessage(SparkMessage("assistant", "记好了：$content"))
            _statusHint.value = null
            speakHint(context, "记好了")
        } else {
            appendMessage(SparkMessage("assistant", "没记下来，请再说一次"))
            _statusHint.value = "记事失败"
            speakHint(context, "没记下来，请再说一次")
        }
    }

    /** 在记忆和随手记里找（AI 提炼的检索词，标题/内容包含匹配，最多各 3 条） */
    private suspend fun executeMemorySearch(context: Context, keyword: String) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val sdf = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
        val lines = mutableListOf<String>()
        memory.entries.value
            .filter { it.title.contains(k) || it.detail.contains(k) }
            .take(3)
            .forEach { e ->
                val title = e.title.ifBlank { e.detail.take(30) }
                lines += "▪ $title（${sdf.format(java.util.Date(e.ts))}）"
            }
        notes.notes.value
            .filter { it.text.contains(k) }
            .take(3)
            .forEach { n ->
                lines += "▪ 📝 ${n.text.take(40)}（${sdf.format(java.util.Date(n.ts))}）"
            }
        val spoken = if (lines.isEmpty()) {
            "记忆里没有找到「$k」相关的内容"
        } else {
            val first = lines.first().removePrefix("▪ ").removePrefix("📝 ")
            "找到了${lines.size}条，最相关的是：$first"
        }
        val bubble = if (lines.isEmpty()) spoken else "在记忆和随手记里找到「$k」相关 ${lines.size} 条：\n" + lines.joinToString("\n")
        appendMessage(SparkMessage("assistant", bubble))
        _statusHint.value = null
        speakHint(context, spoken)
    }

    /**
     * 电量问句本地匹配：电量/多少电/几度电/还有电/电池/剩电类短句直接本地答（零延迟零联网），
     * 长句不拦交给 AI（AI 协议里也有 BATTERY 兜底）。只拦小源自己——问别的设备的电量交给聊天。
     */
    private fun matchBatteryQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        if (t.contains("电量") || t.contains("多少电") || t.contains("几度电") || t.contains("还有电") || t.contains("电池")) return true
        return t.contains("电") && (t.contains("剩") || t.contains("够不够"))
    }

    /** 回答本机电量（DeviceBattery 读系统；读不到如实说，绝不编数字） */
    private fun answerBattery(context: Context) {
        val state = DeviceBattery.read(context)
        val spoken = when {
            state == null -> "这台设备读不到电量"
            state.charging && state.percent >= 99 -> "正在充电，已经充满啦"
            state.charging -> "正在充电，电量还有百分之${state.percent}"
            state.percent <= 15 -> "电量只剩百分之${state.percent}了，快去充电吧"
            else -> "电量还有百分之${state.percent}"
        }
        appendMessage(SparkMessage("assistant", spoken))
        _statusHint.value = null
        speakHint(context, spoken)
    }

    /**
     * 音量问句本地匹配（零延迟）：静音/大小声/最大/恢复类短句直接执行，长句交给 AI 协议（VOLUME_*）。
     * 「别那么大声」是调小、「别说了」是停止——否定前缀放行给 AI 判断。
     */
    private fun matchVolumeFast(text: String): DeviceVolume.Action? {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 8) return null
        if (t.startsWith("别") || t.startsWith("不要")) return null
        return when {
            t.contains("静音") || t.contains("不出声") || t.contains("关掉声音") || t.contains("关声音") -> DeviceVolume.Action.MUTE
            t.contains("最大") -> DeviceVolume.Action.MAX
            t.contains("恢复音量") || t.contains("音量正常") || t.contains("正常音量") -> DeviceVolume.Action.RESTORE
            t.contains("大点声") || t.contains("大声点") || t.contains("大声些") || t.contains("声音大") ||
                t.contains("音量大") || t.contains("太轻了") || t.contains("听不清") -> DeviceVolume.Action.UP
            t.contains("小点声") || t.contains("小声点") || t.contains("小声些") || t.contains("声音小") ||
                t.contains("音量小") || t.contains("太吵") || t.contains("太大声") -> DeviceVolume.Action.DOWN
            else -> null
        }
    }

    /** 执行音量调整并播报；静音后不再朗读（自相矛盾），用状态条显示 */
    private fun answerVolume(context: Context, action: DeviceVolume.Action, percent: Int = 60) {
        val label = DeviceVolume.apply(context, action, percent)
        appendMessage(SparkMessage("assistant", label))
        if (action == DeviceVolume.Action.MUTE) {
            _statusHint.value = label
        } else {
            _statusHint.value = null
            speakHint(context, label)
        }
    }

    /** 现在能不能自主说话：录音/流式回答/播报中都别打扰，静默安睡模式不出声 */
    private fun canSpeakProactively(): Boolean =
        !_isRecording.value &&
            !_isStreaming.value &&
            TtsPlayer.state.value is TtsPlayer.State.Idle &&
            _eyeState.value != SparkEyeState.MUTED

    /** 自主说话：进会话流留个记录 + 朗读（用 applicationContext，主动提醒不依赖界面） */
    private fun speakProactive(text: String) {
        appendMessage(SparkMessage("assistant", text))
        speakHint(appContext, text)
    }

    /**
     * 主动提醒：电量低（≤20% 一般提醒 / ≤10% 加急）/ 充满电时小源自己说一句。
     * 防打扰三道闸：①设置「主动提醒」开关（默认开）；②只在空闲时说（录音/回答/播报中都跳过，
     * 且不消耗提醒键——忙完下一个电量事件自然补上）；③同一档位只提醒一次（20→19 不连着念，
     * 充满只说一次，拔掉充电器重置）。
     */
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
            if (!charging) fullReminded = false // 拔掉充电器，下个充电周期重新提醒
        }
        if (!settings.value.proactiveReminders) return
        if (!canSpeakProactively()) return // 不消耗提醒键：忙完由下一个电量事件补上

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
            if (percent > 20) lastLowKey = null // 电量回升，跨档键复位
        }
    }

    private fun sendAiRequest(
        context: Context,
        userText: String,
        imageDataUrls: List<String>,
    ) {
        aiRequestJob?.cancel()
        // 先占一条 assistant 消息，后续流式/结果/出错都更新它
        appendMessage(SparkMessage("assistant", ""))
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

            // 位置：读缓存快照；没有（无 GPS 设备首次/过期）就快速要一次（含 IP 定位兜底，城市级联网反查）
            // 天气类问题：主动拉实时天气注入——灵动模式聊天不带工具/搜索，不注入模型只能瞎编或答「查不了」
            val wantsWeather = listOf("天气", "气温", "温度", "下雨", "下雪", "刮风", "风力", "湿度", "穿什么", "热不热", "冷不冷", "带伞", "防晒")
                .any { userText.contains(it) }
            val place = LocationContext.current(context)
                ?: runCatching { LocationContext.fresh(context, timeoutMs = 2_500) }.getOrNull()
            val weatherLine = if (wantsWeather && place != null) {
                runCatching { WeatherClient.current(place.lon, place.lat) }.getOrNull()
            } else {
                null
            }
            val contextInfo = buildString {
                val cal = CalendarContext.block()
                if (!cal.isNullOrBlank()) appendLine(cal)
                place?.description?.let { appendLine("位置：$it") }
                if (weatherLine != null) appendLine("【实时天气】$weatherLine")
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

            val historyPayload = SparkChatStore.historyBudget(48_000)

            val currentUserMsg = OutMessage(
                role = "user",
                text = userText,
                images = imageDataUrls,
            )

            val fullPayload = listOfNotNull(
                OutMessage("system", systemPrompt),
                if (contextInfo.isNotBlank()) OutMessage("system", "【情境】\n$contextInfo") else null,
            ) + historyPayload + listOf(currentUserMsg)

            val speechBuffer = StringBuilder()
            var spokenUpTo = 0
            _isStreaming.value = true

            // 流式播报：拿到的字按句子切开，一句一句送合成、顺序播，不再等全文再开口。
            // 旧逻辑要等 onSuccess 才 TtsPlayer.speak(全文)，长回答要干等好几秒。
            ttsStreaming = true
            val speechChannel = Channel<String>(Channel.UNLIMITED)
            val speaker = launch {
                try {
                    for (piece in speechChannel) {
                        if (piece.isBlank()) continue
                        TtsPlayer.speak(context, piece)
                        // 等这一句播完再接下一句（TtsPlayer 是单实例，不能并发）。
                        // 带上限兜底：万一播放器状态卡在 Speaking（个别 ROM 播完不回调），
                        // 到点就往下走，下一句 speak() 的 stop() 会把卡住的那条流水线顶掉，
                        // 不至于整轮播报和眼睛状态一起吊死。
                        val capMs = (piece.length * 400L).coerceIn(8_000L, 60_000L)
                        withTimeoutOrNull(capMs) {
                            TtsPlayer.state.first {
                                it is TtsPlayer.State.Idle || it is TtsPlayer.State.Unavailable
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    TtsPlayer.stop()
                }
            }

            AiClient(curSettings).chat(
                messages = fullPayload,
                thinking = false,
                searchEnabled = false,
                onAnswer = { chunk ->
                    speechBuffer.append(chunk)
                    SparkChatStore.updateLastAssistant(speechBuffer.toString())
                    if (_eyeState.value != SparkEyeState.SPEAKING) {
                        _eyeState.value = SparkEyeState.SPEAKING
                        _statusHint.value = null
                    }
                    // 一遇到句末标点就把这一句送出去合成（首句通常 0.3~0.8 秒就能开口）
                    var cut = findSentenceEnd(speechBuffer, spokenUpTo)
                    while (cut >= 0) {
                        val piece = speechBuffer.substring(spokenUpTo, cut + 1).trim()
                        spokenUpTo = cut + 1
                        if (piece.isNotEmpty()) speechChannel.trySend(piece)
                        cut = findSentenceEnd(speechBuffer, spokenUpTo)
                    }
                    // 长句兜底：一口气 34 字还没标点，先播出去，别让人干等
                    if (speechBuffer.length - spokenUpTo >= 34) {
                        val piece = speechBuffer.substring(spokenUpTo).trim()
                        spokenUpTo = speechBuffer.length
                        if (piece.isNotEmpty()) speechChannel.trySend(piece)
                    }
                },
            ).fold(
                onSuccess = { reply ->
                    val finalAnswer = reply.answer.trim()
                    SparkChatStore.updateLastAssistant(finalAnswer)
                    _isStreaming.value = false

                    SparkChatStore.appendHistory(currentUserMsg)
                    SparkChatStore.appendHistory(OutMessage("assistant", finalAnswer))

                    // 收尾：最后一段没标点的余量也播掉，然后等队列放完
                    val tail = speechBuffer.substring(spokenUpTo).trim()
                    if (tail.isNotEmpty()) speechChannel.trySend(tail)
                    speechChannel.close()
                    speaker.join()
                    ttsStreaming = false
                    if (_eyeState.value == SparkEyeState.SPEAKING) finishSpeaking()
                },
                onFailure = { err ->
                    speechChannel.close()
                    ttsStreaming = false
                    _eyeState.value = SparkEyeState.IDLE
                    _isStreaming.value = false
                    SparkChatStore.updateLastAssistant("请求出错啦：${err.message ?: "网络超时"}")
                    _statusHint.value = "请求失败"
                    continuous?.setBusy(false) // 失败不出声，别让连续对话的空闲计时卡死
                },
            )
        }
        // 取消（用户又问一句）也要把流式标记和队列收干净
        requestJob.invokeOnCompletion { ttsStreaming = false }
        aiRequestJob = requestJob
    }

    /** 从 [from] 起找第一个句末标点（至少 5 个字才算一句，避免「好。」这种碎句） */
    private val sentenceEnds = "。！？!?；;…"

    /** 从 [from] 起找第一个句末标点（至少 5 个字才算一句，避免「好。」这种碎句）；换行也算 */
    private fun findSentenceEnd(text: CharSequence, from: Int): Int {
        if (text.length <= from + 4) return -1
        for (i in (from + 4) until text.length) {
            val c = text[i]
            if (sentenceEnds.indexOf(c) >= 0 || c.code == 10) return i
        }
        return -1
    }

    /** 一遍回答真正播完：回待机眼睛 + 到点（EYES_RETURN_AFTER_MS）自动收起会话流 */
    private fun finishSpeaking() {
        _eyeState.value = if (_isContinuous.value) SparkEyeState.LISTENING else SparkEyeState.IDLE
        idleToEyesJob?.cancel()
        idleToEyesJob = viewModelScope.launch {
            delay(EYES_RETURN_AFTER_MS)
            if (TtsPlayer.state.value is TtsPlayer.State.Idle &&
                (_eyeState.value == SparkEyeState.IDLE || _eyeState.value == SparkEyeState.LISTENING) &&
                !_isRecording.value
            ) {
                _showEyes.value = true
            }
        }
    }

    /**
     * 退出灵动模式 = 退出应用（对齐大字模式）：
     * 模式切换只认设置页开关，这里只做收尾（停朗读、停录音），退出交给调用方 finishAffinity
     */
    fun exitApp() {
        sparkActive = false
        idleToEyesJob?.cancel()
        timerJob?.cancel()
        _timerRemainingSec.value = null
        stopContinuousInternal()
        wakeSession?.stop()
        wakeSession = null
        TtsPlayer.stop()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
    }

    // ---------- 连续对话：已并入「按住说话 / 唤醒单次」同一条链路（v0.9.16 起不再有独立「连聊」模式） ----------

    fun stopContinuous() {
        stopContinuousInternal()
        _eyeState.value = SparkEyeState.IDLE
        _statusHint.value = null
    }

    private fun stopContinuousInternal() {
        continuous?.stop()
        continuous = null
        _isContinuous.value = false
        syncWakeEngine()
    }

    // ---------- 唤醒词（待机时喊「小源小源」→ 单次聆听一句话，与按住说话同一逻辑） ----------

    /** 灵动模式进入前台时调用：待机唤醒监听随页面开关 */
    fun onSparkActive() {
        sparkActive = true
        syncWakeEngine()
    }

    /**
     * 通话中执行 app 自身能力（机器人动作 / 拍照 / 电量 / 音量 / 计时 / 记事 / 记忆查找 / 定位类问句）。
     * 复用与聊天同一条 handleAssistantIntent 链路，命中返回要说的确认话术（交给豆包音色播报），
     * 没命中返回 null —— 这句话照常交给全双工模型自由回答。
     */
    suspend fun handleCallCommand(context: Context, text: String): String? {
        var reply: String? = null
        duplexReplySink = { reply = it }
        return try {
            if (handleAssistantIntent(context, text)) reply ?: "好的" else null
        } finally {
            duplexReplySink = null
        }
    }

    /**
     * 全双工通话开关：通话期间把麦克风整条链路让给豆包客户端，
     * 停朗读、停连聊、停待机唤醒监听；挂断后按原设置恢复唤醒监听。
     */
    fun setDuplexAudioActive(active: Boolean) {
        duplexAudioActive = active
        if (active) {
            TtsPlayer.stop()
            stopContinuous()
            wakeSession?.stop()
            wakeSession = null
        } else {
            syncWakeEngine()
        }
    }

    /**
     * 唤醒监听状态机：灵动模式前台 + 开关开 + 有 ASR Key + 有麦克风权限 + 不在连聊 + 不在按住说话 → 开引擎；
     * 其余一律关。与连续对话共用 ContinuousConversation，但一台设备同一时刻只允许一个采集会话。
     */
    private fun syncWakeEngine() {
        val cur = settings.value
        val curVoiceSens = cur.voiceBargeIn // 0 关 / 1 标准 / 2 灵敏
        val shouldRun = sparkActive &&
            cur.wakeWordEnabled &&
            cur.asrKey.isNotBlank() &&
            SpeechInput.hasRecordPermission(appContext) &&
            !_isContinuous.value &&
            !duplexAudioActive &&
            activeRecording == null &&
            wakeOnceSession == null // 唤醒后的单次聆听正在占麦，待机监听让路
        if (shouldRun && wakeSession == null) {
            val session = ContinuousConversation(
                appContext,
                config = ContinuousConversation.Config(
                    // 设置里的「灵敏度」以前只管连聊打断，连聊并掉之后就等于没用；现在它直接决定唤醒门槛
                    silenceEndMs = if (curVoiceSens >= 2) 320L else 380L,
                    minSpeechMs = if (curVoiceSens >= 2) 150L else 220L,
                    bargeInSensitivity = 0, // 待机不需要打断：主动提醒播报期间不收音，防回灌
                    idleTimeoutMs = Long.MAX_VALUE / 2, // 待机监听常驻，不自动退
                    onsetMarginDb = if (curVoiceSens >= 2) 6f else 10f,
                    minOnsetDb = if (curVoiceSens >= 2) -58f else -50f,
                ),
                listener = object : ContinuousConversation.Listener {
                    // 一开口就给反馈（约开口后 160ms）：眼睛立刻变「在听」，不用等识别结果
                    override fun onSpeechStart() {
                        if (!duplexAudioActive && activeRecording == null) {
                            _eyeState.value = SparkEyeState.LISTENING
                            wakeSpeechStartedAt = SystemClock.elapsedRealtime()
                        }
                    }

                    override fun onUtterance(wav: java.io.File) {
                        handleWakeUtterance(wav)
                    }

                    override fun onBargeIn() {}
                    override fun onAutoTimeout() {}

                    override fun onError(message: String) {
                        viewModelScope.launch {
                            wakeSession?.stop()
                            wakeSession = null
                            _statusHint.value = "唤醒监听停了：$message"
                        }
                    }
                },
            )
            session.setTtsActive(TtsPlayer.state.value is TtsPlayer.State.Speaking)
            session.start() // 后台重试；最终失败由 onError 清理并提示
            wakeSession = session
            LogStore.log(
                "wake",
                "待机监听启动：灵敏度 $curVoiceSens 档，判活门槛 " +
                    "${if (curVoiceSens >= 2) 6f else 10f}dB / 下限 ${if (curVoiceSens >= 2) -58f else -50f}dB，" +
                    "最短 ${if (curVoiceSens >= 2) 150L else 220L}ms，静音收段 ${if (curVoiceSens >= 2) 320L else 380L}ms",
            )
        } else if (!shouldRun && wakeSession != null) {
            wakeSession?.stop()
            wakeSession = null
        }
    }

    /**
     * 待机一段语音：云转写 → 含唤醒词才进连续对话；没命中静默丢弃（杂音/电视声不成词就不打扰）。
     * 限流：2 秒冷却 + 每分钟最多 6 条上传，防持续噪声刷请求。
     */
    private fun handleWakeUtterance(wav: java.io.File) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeUploadAt < 800) {
            wav.delete()
            return@launch
        }
        if (now - wakeWindowStart > 60_000) {
            wakeWindowStart = now
            wakeUploadCount = 0
        }
        if (++wakeUploadCount > 20) {
            wav.delete()
            return@launch
        }
        lastWakeUploadAt = now
        val cur = repo.settings.firstOrNull() ?: settings.value
        val spoken = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, cur.asrKey.trim(), cur.dialectMode).getOrNull()
        }
        val asrAt = SystemClock.elapsedRealtime()
        val wavBytes = wav.length()
        wav.delete()
        val text = spoken.orEmpty().trim()
        // 唤醒链路实测：一开口 → 判活收段 → 云转写回来，各段耗时都记下来。
        // 「喊不醒」时先看这里：没这行 = 根本没判成一段话（VAD 门槛高了/麦克风没声）；
        // 有这行但文本不对 = 识别没听清（录音电平、方言、噪音）；文本对却没反应 = 唤醒词表要放宽。
        val speakStart = wakeSpeechStartedAt
        if (speakStart > 0) {
            val durMs = (wavBytes / 32).coerceAtLeast(0) // 16k/16bit 单声道 ≈ 32 字节每毫秒
            LogStore.log(
                "wake",
                "待机语音：录音 ${durMs}ms，判活收段 +${now - speakStart}ms，转写 +${asrAt - speakStart}ms，" +
                    "文本「${text.ifBlank { "（空）" }}」",
            )
            wakeSpeechStartedAt = 0L
        }
        // 铃响时待机任何语音都先停铃（开口就是想让它安静）：小源小源/小源小源退出/关闭闹钟/别响了都算
        val wasAlarming = _alarmActive.value
        if (text.isNotBlank() && wasAlarming) {
            if (matchAlarmStop(text)) {
                stopAlarm()
                appendMessage(SparkMessage("assistant", "好的，不响了"))
                _statusHint.value = null
                speakHint(appContext, "好的，不响了")
                return@launch
            }
            stopAlarm()
        }
        val ask = wakeWordPrefix(text)
        // 命中/未命中都记一行：现场拿日志就能分清是「没喊对」还是「词表没认出来」
        if (text.isNotBlank()) {
            LogStore.log(
                "wake",
                if (ask != null) "唤醒命中（灵敏度 ${settings.value.voiceBargeIn}）→ 剩余「${ask}」"
                else "未命中唤醒词：文本「$text」，已丢弃",
            )
        }
        if (ask != null && !_isContinuous.value && activeRecording == null) {
            // 「小源小源退出/关闭」这类只要停铃，别进对话
            if (wasAlarming && listOf("退出", "关闭", "关掉", "停止", "别说了").any { text.contains(it) }) {
                _statusHint.value = "好的，闹钟停了"
                return@launch
            }
            // v0.9.15 单次交互：唤醒后只做一轮，不再进「连聊」——
            // 连聊会一直占着麦克风、自己判断断句，用户说到一半被打断的体验比多喊一次唤醒词差得多。
            _showEyes.value = true
            TtsPlayer.stop() // 唤醒这一刻不要有任何残留播报，免得盖住第一句
            SparkChime.play(appContext) // 本地合成提示音，0 网络，确认「听见了」
            _eyeState.value = SparkEyeState.LISTENING
            if (ask.length >= 2) {
                // 「小源小源，今天天气怎么样」：唤醒词后面已经带问题，直接答，不用再喊一次
                answerVoiceQuestion(ask)
            } else {
                // 只喊了唤醒词：开一轮单次聆听，说完（约 0.9 秒静音）就收，下次还要喊唤醒词
                delay(110) // 让提示音先响完，别被这一轮聆听录进去
                startWakeOneShot()
                _eyeState.value = SparkEyeState.LISTENING
                _statusHint.value = "我在听，说完就好"
            }
        } else if (wasAlarming) {
            _statusHint.value = "好的，闹钟停了"
        }
    }

    /**
     * 唤醒词匹配（v0.9.17 放宽）：
     * 云识别对「小源」的写法太多（小袁/小圆/小原/小娟/小雨……），按固定词表比对经常漏，
     * 改成「小/晓/筱 + 近似字」的组合判定，且允许出现在前 8 个字里（真人常带「嗯」「那个」）。
     * 命中返回「去掉唤醒词之后剩下的话」（剩下的直接当问题），没命中返回 null。
     */
    private val wakeFirstChars = charArrayOf('小', '晓', '筱', '肖')
    private val wakeSecondChars = charArrayOf(
        '源', '原', '袁', '圆', '园', '远', '元', '缘', '媛', '沅', '苑', '院', '愿', '员',
        '娟', '雨', '玉', '宇', '语', '瑜', '愉', '于', '余', '俞',
    )

    private fun wakeWordPrefix(text: String): String? {
        if (text.isEmpty()) return null
        val t = text.trim()
        val head = t.take(8)
        var hitAt = -1
        for (i in 0 until head.length - 1) {
            if (wakeFirstChars.contains(head[i]) && wakeSecondChars.contains(head[i + 1])) {
                hitAt = i
                break
            }
        }
        if (hitAt < 0) return null
        val trimChars = charArrayOf('，', ',', '。', '.', '！', '!', '？', '?', '、', ' ', '　', '：', ':')
        var rest = t.substring(hitAt + 2).trimStart(*trimChars)
        // 连续重复的唤醒词都吃掉（「小源小源」「小源、小源」）
        while (rest.length >= 2 && wakeFirstChars.contains(rest[0]) && wakeSecondChars.contains(rest[1])) {
            rest = rest.substring(2).trimStart(*trimChars)
        }
        return rest.trim(*trimChars)
    }

    /**
     * 一句话走完整条问答链：**按住说话**与**唤醒后的单次聆听**共用这一个入口（同一个逻辑）。
     * 顺序：本地快路（电量 / 音量）→ 本机意图（机器人、计时、记事、记忆查找）→ 聊天。
     */
    private suspend fun answerVoiceQuestion(clean: String, photos: List<PendingPhoto> = emptyList()) {
        appendMessage(SparkMessage(role = "user", text = clean, photos = photos.map { it.bitmap }))
        _showEyes.value = false // 提问完成，自动切到会话流展示
        _eyeState.value = SparkEyeState.THINKING
        if (matchBatteryQuestion(clean)) {
            answerBattery(appContext)
            return
        }
        matchVolumeFast(clean)?.let { action ->
            answerVolume(appContext, action)
            return
        }
        if (!handleAssistantIntent(appContext, clean)) {
            sendAiRequest(appContext, clean, photos.map { it.dataUrl })
        }
    }

    /**
     * 唤醒后的一轮聆听：只收「一句话」，说完就关（silenceEndMs 放到 1 秒，允许句中停顿），
     * 半双工（bargeInSensitivity=0）保证播报时不收音、也不会打断用户；
     * 15 秒一直没人说话就回待机。拿到的语音交给 handleWakeOnce。
     */
    private fun startWakeOneShot() {
        wakeOnceSession?.stop()
        wakeOnceSession = null
        val session = ContinuousConversation(
            appContext,
            config = ContinuousConversation.Config(
                silenceEndMs = 850L, // 允许句中停顿，但别让人干等
                minSpeechMs = 300L,
                bargeInSensitivity = 0,
                idleTimeoutMs = 15_000L,
            ),
            listener = object : ContinuousConversation.Listener {
                override fun onUtterance(wav: java.io.File) {
                    // 单次：拿到第一段就停录音，处理完回待机
                    wakeOnceSession?.stop()
                    wakeOnceSession = null
                    handleWakeOnce(wav)
                }

                override fun onBargeIn() {}

                override fun onAutoTimeout() {
                    wakeOnceSession?.stop()
                    wakeOnceSession = null
                    _eyeState.value = SparkEyeState.IDLE
                    _statusHint.value = null
                    syncWakeEngine()
                }

                override fun onError(message: String) {
                    viewModelScope.launch {
                        wakeOnceSession?.stop()
                        wakeOnceSession = null
                        _eyeState.value = SparkEyeState.IDLE
                        _statusHint.value = "没听清：$message"
                        syncWakeEngine()
                    }
                }
            },
        )
        session.setTtsActive(TtsPlayer.state.value is TtsPlayer.State.Speaking)
        session.start()
        wakeOnceSession = session
        // 同一时刻只允许一个采集会话：待机监听先让路
        wakeSession?.stop()
        wakeSession = null
    }

    /** 单次聆听录到的一段：转写 → 走问答链 → 回待机（下次仍要喊唤醒词） */
    private fun handleWakeOnce(wav: java.io.File) = viewModelScope.launch {
        val cur = repo.settings.firstOrNull() ?: settings.value
        val asrKey = cur.asrKey.trim()
        if (asrKey.isBlank()) {
            wav.delete()
            _eyeState.value = SparkEyeState.IDLE
            _statusHint.value = "请先在设置里配置语音识别 Key"
            syncWakeEngine()
            return@launch
        }
        _statusHint.value = "正在识别…"
        val result = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, asrKey, cur.dialectMode)
        }
        wav.delete()
        result.fold(
            onSuccess = { spoken ->
                val clean = HotwordCorrector.correct(spoken.trim())
                if (clean.isBlank()) {
                    _eyeState.value = SparkEyeState.IDLE
                    _statusHint.value = "没听清，再喊一次「小源小源」"
                } else {
                    answerVoiceQuestion(clean)
                }
            },
            onFailure = { err ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = (err as? SpeechInput.SpeechException)?.let { SpeechInput.spokenHint(it) }
                    ?: "没听清，再喊一次「小源小源」"
            },
        )
        syncWakeEngine()
    }

    /**
     * 一段语音的完整处理（连续模式核心管线）：云端转写 → 本地热词 → 电量快路 → 机器人意图 → 聊天。
     * 与按住说话的区别：空结果/失败不朗读——TTS 会回灌麦克风形成正反馈，只给状态条静默续听；
     * 连续 ≥2 次失败才出声提醒，≥4 次自动退出（省电+别折磨用户）。
     */
    override fun onCleared() {
        super.onCleared()
        sparkActive = false
        timerJob?.cancel()
        stopContinuousInternal()
        wakeSession?.stop()
        wakeSession = null
        wakeOnceSession?.stop()
        wakeOnceSession = null
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
        TtsPlayer.stop()
    }
}
