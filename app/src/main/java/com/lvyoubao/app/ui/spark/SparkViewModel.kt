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
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RobotClient
import com.lvyoubao.app.data.RobotIntent
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.ui.common.ContinuousConversation
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    private val appContext = app.applicationContext

    // 必须 Eagerly：灵动屏界面从不收集这个流，WhileSubscribed 会让上游永不启动，
    // settings.value 永远停在默认空配置——曾导致退出灵动屏把默认空配置整体写回、
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
    private var sparkActive = false
    private var lastConvUploadAt = 0L
    private var lastWakeUploadAt = 0L
    private var wakeUploadCount = 0
    private var wakeWindowStart = 0L

    // 主动提醒（电量低/充满）：键控一次（同档位只提醒一次），空闲才说，静默安睡不说
    private var lastLowKey: String? = null
    private var fullReminded = false
    private var wasCharging = false

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
                        idleToEyesJob?.cancel()
                        if (_eyeState.value == SparkEyeState.THINKING || _eyeState.value == SparkEyeState.IDLE) {
                            _eyeState.value = SparkEyeState.SPEAKING
                        }
                    }
                    ttsState is TtsPlayer.State.Idle -> {
                        continuous?.setTtsActive(false)
                        if (_eyeState.value == SparkEyeState.SPEAKING) {
                            // 连续对话：播完回「聆听中」接着听；普通模式：回 IDLE 待机
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
                    source = "灵动屏（用户收藏）",
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
                        appendMessage(SparkMessage("user", clean, pending.map { it.bitmap }))
                        _pendingPhotos.value = emptyList()
                        _showEyes.value = false // 提问完成，自动切换为会话流展示
                        // 电量/音量走本地快路（读系统，零延迟零联网），其余再过机器人意图 / 聊天
                        if (matchBatteryQuestion(clean)) {
                            answerBattery(context)
                            return@launch
                        }
                        matchVolumeFast(clean)?.let { action ->
                            answerVolume(context, action)
                            return@launch
                        }
                        // 机器人控制优先：开启且解析出控制指令时执行并播报结果，否则照常聊天
                        if (!handleRobotCommand(context, clean)) {
                            sendAiRequest(context, clean, pending.map { it.dataUrl })
                        }
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
     * 语音控制机器人（设置里开「机器人控制」后生效，控制对象：xrobotctl 中枢驱动的机器人，现内置优必选小方头）：
     * ①「停」类词本地快路零延迟急停（急停不能等 AI 往返）；
     * ②其余交给 AI 解析意图（chatPlain 单轮、思考关闭）——一句话可含多个动作（AI 按顺序每行输出一条），
     *   这里串行编排执行：动作间按运动耗时留间隔（中枢队列本身也串行，间隔只是给动作留出可感知的节奏），
     *   结束后一次性播报「好的，抬头，然后低头」；LOOK（让小源当场看一眼）只用设备相机静默拍照，
     *   不需要机器人中枢，且终止编排（拍照问答接管会话）；触发说法不设死，AI 判断；
     * ③解析为空 / 未启用机器人返回 false，照常走聊天流程；失败即停编排并播报原因，绝不静默。
     */
    private suspend fun handleRobotCommand(context: Context, text: String): Boolean {
        val curSettings = repo.settings.firstOrNull() ?: settings.value
        if (!curSettings.robotEnabled) return false

        // 急停快路：不等 AI 往返
        if (RobotIntent.matchStopFast(text)) {
            val robot = robotClientOrNull(context, curSettings) ?: return true
            runRobot(context, robot, RobotIntent.Command.Stop)
            return true
        }

        // 头部动作快路（平视/抬头/低头）：短句直接执行，不经 AI——堵住「平视」被误判成拍照
        RobotIntent.matchHeadFast(text)?.let { headCmd ->
            val robot = robotClientOrNull(context, curSettings) ?: return true
            runRobot(context, robot, headCmd)
            return true
        }

        _statusHint.value = "正在理解指令…"
        val commands = RobotIntent.parse(text, curSettings)
        if (commands.isEmpty()) return false

        // LOOK 终止编排：拍照问答接管会话，其后的指令忽略
        val lookIndex = commands.indexOf(RobotIntent.Command.Look)
        val chain = if (lookIndex >= 0) commands.take(lookIndex + 1) else commands

        // 电量问答：读本机系统电池（协议约定单发），不需要机器人中枢
        if (chain.contains(RobotIntent.Command.Battery)) {
            answerBattery(context)
            return true
        }

        // 音量控制：调系统 STREAM_MUSIC（设备能力，不需要机器人中枢）
        chain.filterIsInstance<RobotIntent.Command.Volume>().firstOrNull()?.let { volCmd ->
            answerVolume(context, volCmd.action, volCmd.percent)
            return true
        }

        // LOOK 单发：设备相机就行，不依赖机器人中枢
        if (chain.size == 1 && chain[0] == RobotIntent.Command.Look) {
            executeLook(context)
            return true
        }

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
        aiRequestJob = viewModelScope.launch(Dispatchers.IO) {
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
            // 天气类问题：主动拉实时天气注入——灵动屏聊天不带工具/搜索，不注入模型只能瞎编或答「查不了」
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
                你是灵动屏智能伴侣「小源」，搭载于小屏随身智能设备。
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
            var hasStartedTts = false
            _isStreaming.value = true

            AiClient(curSettings).chat(
                messages = fullPayload,
                thinking = false,
                searchEnabled = false,
                onAnswer = { chunk ->
                    speechBuffer.append(chunk)
                    SparkChatStore.updateLastAssistant(speechBuffer.toString())

                    if (!hasStartedTts && speechBuffer.length >= 6) {
                        hasStartedTts = true
                        _eyeState.value = SparkEyeState.SPEAKING
                        _statusHint.value = null
                        TtsPlayer.speak(context, speechBuffer.toString())
                    }
                },
            ).fold(
                onSuccess = { reply ->
                    val finalAnswer = reply.answer.trim()
                    SparkChatStore.updateLastAssistant(finalAnswer)
                    _isStreaming.value = false

                    SparkChatStore.appendHistory(currentUserMsg)
                    SparkChatStore.appendHistory(OutMessage("assistant", finalAnswer))

                    _eyeState.value = SparkEyeState.SPEAKING
                    _statusHint.value = null
                    TtsPlayer.speak(context, finalAnswer)
                },
                onFailure = { err ->
                    _eyeState.value = SparkEyeState.IDLE
                    _isStreaming.value = false
                    SparkChatStore.updateLastAssistant("请求出错啦：${err.message ?: "网络超时"}")
                    _statusHint.value = "请求失败"
                    continuous?.setBusy(false) // 失败不出声，别让连续对话的空闲计时卡死
                },
            )
        }
    }

    /**
     * 退出灵动屏 = 退出应用（对齐大字模式）：
     * 模式切换只认设置页开关，这里只做收尾（停朗读、停录音），退出交给调用方 finishAffinity
     */
    fun exitApp() {
        sparkActive = false
        idleToEyesJob?.cancel()
        stopContinuousInternal()
        wakeSession?.stop()
        wakeSession = null
        TtsPlayer.stop()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
    }

    // ---------- 连续对话（免按住、语音打断） ----------

    /**
     * 连续对话开关：开=麦克风常听（说完自动断句→回答→播完接着听，播报中开口即打断）；再点一次关。
     * 灵动屏是语音优先设备，这里用 applicationContext 全程驱动，界面只是状态展示。
     */
    fun startContinuous(context: Context, onRequestRecordPermission: () -> Unit) {
        if (_isContinuous.value) {
            stopContinuous()
            _statusHint.value = "连续对话已关"
            return
        }
        if (!SpeechInput.hasRecordPermission(context)) {
            onRequestRecordPermission()
            return
        }
        // 唤醒监听若在跑必须先彻底停掉再开连聊采集：Android 10+ 三方应用不允许并发录音，
        // 后开的 AudioRecord 会被静音——v0.8.94 喊「小源小源」后连聊收不到声音（问啥都没反应、
        // 也无法再唤醒）的根因。手动点「连聊」按钮时唤醒监听同样在跑，两路都要先停
        wakeSession?.stop()
        wakeSession = null
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        _showEyes.value = true
        _eyeState.value = SparkEyeState.LISTENING
        _statusHint.value = "连续对话：直接说就行"
        val session = ContinuousConversation(
            appContext,
            config = ContinuousConversation.Config(
                bargeInSensitivity = settings.value.voiceBargeIn,
                idleTimeoutMs = 180_000L, // 空闲 3 分钟才休息（老人聊会儿歇会儿很正常），退出前会先告辞
            ),
            listener = object : ContinuousConversation.Listener {
                override fun onUtterance(wav: java.io.File) {
                    handleContinuousUtterance(wav)
                }

                override fun onBargeIn() {
                    // 播报中真人开口：掐 TTS、取消在途回答，回到聆听（音频线程触发，切主协程）
                    viewModelScope.launch {
                        aiRequestJob?.cancel()
                        _isStreaming.value = false
                        TtsPlayer.stop()
                        _eyeState.value = SparkEyeState.LISTENING
                        _statusHint.value = "听你说…"
                    }
                }

                override fun onAutoTimeout() {
                    viewModelScope.launch {
                        stopContinuousInternal()
                        _statusHint.value = "连续对话休息了，点「连聊」或喊「小源小源」唤醒"
                        speakHint(appContext, "我先休息啦，点连聊再叫我")
                    }
                }

                override fun onError(message: String) {
                    viewModelScope.launch {
                        stopContinuousInternal()
                        _statusHint.value = message
                        speakHint(appContext, message)
                    }
                }
            },
        )
        // start 改后台重试（音频策略交接期 startRecording 会不生效）；若最终失败由 onError 清理
        session.start()
        continuous = session
        _isContinuous.value = true
        speakHint(context, "连续对话模式，直接说就行")
        syncWakeEngine() // 连聊开着时待机唤醒监听让路（一台设备同一时刻只有一个 AudioRecord 采集）
    }

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

    // ---------- 唤醒词（待机时喊「小源小源」直接进连续对话） ----------

    /** 灵动屏进入前台时调用：待机唤醒监听随页面开关 */
    fun onSparkActive() {
        sparkActive = true
        syncWakeEngine()
    }

    /**
     * 唤醒监听状态机：灵动屏前台 + 开关开 + 有 ASR Key + 有麦克风权限 + 不在连聊 + 不在按住说话 → 开引擎；
     * 其余一律关。与连续对话共用 ContinuousConversation，但一台设备同一时刻只允许一个采集会话。
     */
    private fun syncWakeEngine() {
        val cur = settings.value
        val shouldRun = sparkActive &&
            cur.wakeWordEnabled &&
            cur.asrKey.isNotBlank() &&
            SpeechInput.hasRecordPermission(appContext) &&
            !_isContinuous.value &&
            activeRecording == null
        if (shouldRun && wakeSession == null) {
            val session = ContinuousConversation(
                appContext,
                config = ContinuousConversation.Config(
                    silenceEndMs = 600L,
                    minSpeechMs = 250L,
                    bargeInSensitivity = 0, // 待机不需要打断：主动提醒播报期间不收音，防回灌
                    idleTimeoutMs = Long.MAX_VALUE / 2, // 待机监听常驻，不自动退
                ),
                listener = object : ContinuousConversation.Listener {
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
            session.start() // 后台重试；最终失败由 onError 清理并提示
            wakeSession = session
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
        if (now - lastWakeUploadAt < 2_000) {
            wav.delete()
            return@launch
        }
        if (now - wakeWindowStart > 60_000) {
            wakeWindowStart = now
            wakeUploadCount = 0
        }
        if (++wakeUploadCount > 6) {
            wav.delete()
            return@launch
        }
        lastWakeUploadAt = now
        val cur = repo.settings.firstOrNull() ?: settings.value
        val spoken = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, cur.asrKey.trim(), cur.dialectMode).getOrNull()
        }
        wav.delete()
        if (isWakeWord(spoken.orEmpty().trim()) && !_isContinuous.value && activeRecording == null) {
            // 命中唤醒词：麦克风权限必然已授予（监听本来就开着），直接进连续对话
            _showEyes.value = true
            startContinuous(appContext) {}
            speakHint(appContext, "嗯，我在")
        }
    }

    /** 唤醒词判定：ASR 对「小源小源」的常见同音误写都算，长句不拦（交给聊天） */
    private fun isWakeWord(text: String): Boolean {
        if (text.isEmpty() || text.length > 12) return false
        return listOf("小源", "小原", "小袁", "小圆", "小远", "小元", "小缘", "晓源", "小媛", "小沅")
            .any { text.contains(it) }
    }

    /**
     * 一段语音的完整处理（连续模式核心管线）：云端转写 → 本地热词 → 电量快路 → 机器人意图 → 聊天。
     * 与按住说话的区别：空结果/失败不朗读——TTS 会回灌麦克风形成正反馈，只给状态条静默续听；
     * 连续 ≥2 次失败才出声提醒，≥4 次自动退出（省电+别折磨用户）。
     */
    private fun handleContinuousUtterance(wav: java.io.File) = viewModelScope.launch {
        val cur = repo.settings.firstOrNull() ?: settings.value
        val asrKey = cur.asrKey.trim()
        if (asrKey.isBlank()) {
            appendMessage(SparkMessage("assistant", "请先在「设置」中配置语音识别 Key，连续对话才能听懂你。"))
            speakHint(appContext, "请先在设置中配置语音识别 Key")
            stopContinuousInternal()
            return@launch
        }
        continuous?.setBusy(true)
        // 1 秒冷却：静音/噪声段间隔着触发切段时别连着刷 ASR（真说话的切段天然间隔 >800ms，不受影响）
        val now = SystemClock.elapsedRealtime()
        if (now - lastConvUploadAt < 1_000) {
            wav.delete()
            continuous?.setBusy(false)
            return@launch
        }
        lastConvUploadAt = now
        val result = withContext(Dispatchers.IO) { SpeechInput.transcribeFile(wav, asrKey, cur.dialectMode) }
        wav.delete()
        result.fold(
            onSuccess = { spoken ->
                consecutiveAsrFailures = 0
                val clean = HotwordCorrector.correct(spoken.trim())
                if (clean.isBlank()) {
                    _statusHint.value = "没听清，再说一次"
                    _eyeState.value = SparkEyeState.LISTENING
                    continuous?.setBusy(false)
                } else {
                    appendMessage(SparkMessage("user", clean))
                    _showEyes.value = false
                    _eyeState.value = SparkEyeState.THINKING
                    if (matchBatteryQuestion(clean)) {
                        answerBattery(appContext)
                        return@launch // answerBattery 会朗读，TTS 收集器恢复计时
                    }
                    matchVolumeFast(clean)?.let { action ->
                        answerVolume(appContext, action)
                        return@launch
                    }
                    if (!handleRobotCommand(appContext, clean)) {
                        sendAiRequest(appContext, clean, emptyList())
                    }
                }
            },
            onFailure = { err ->
                // 「没听清」类（静音/噪声段被当成话传上去，云识别返回空）：静默续听——
                // 朗读提示会回灌麦克风自激，还会反复念叨（v0.8.93「没听清楚」连说三次后退出连聊的根因）
                val failure = (err as? SpeechInput.SpeechException)?.failure
                if (failure == SpeechInput.SpeechFailure.NOT_HEARD) {
                    _statusHint.value = "没听清，再说一次"
                    _eyeState.value = SparkEyeState.LISTENING
                    continuous?.setBusy(false)
                    return@launch
                }
                // 真失败（网络/服务/配置）才计数：≥2 次出声提醒、≥4 次退出
                consecutiveAsrFailures++
                _eyeState.value = SparkEyeState.LISTENING
                if (consecutiveAsrFailures >= 2) {
                    val spoken = SpeechInput.spokenHint(err)
                    _statusHint.value = spoken
                    speakHint(appContext, spoken)
                    if (consecutiveAsrFailures >= 4) stopContinuousInternal()
                } else {
                    _statusHint.value = err.message ?: "没听清"
                }
                continuous?.setBusy(false)
            },
        )
    }

    override fun onCleared() {
        super.onCleared()
        sparkActive = false
        stopContinuousInternal()
        wakeSession?.stop()
        wakeSession = null
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
        TtsPlayer.stop()
    }
}
