package com.lvyoubao.app.ui.spark

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RobotClient
import com.lvyoubao.app.data.RobotIntent
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
                        idleToEyesJob?.cancel()
                        if (_eyeState.value == SparkEyeState.THINKING || _eyeState.value == SparkEyeState.IDLE) {
                            _eyeState.value = SparkEyeState.SPEAKING
                        }
                    }
                    ttsState is TtsPlayer.State.Idle && _eyeState.value == SparkEyeState.SPEAKING -> {
                        _eyeState.value = SparkEyeState.IDLE
                        idleToEyesJob?.cancel()
                        idleToEyesJob = viewModelScope.launch {
                            delay(EYES_RETURN_AFTER_MS)
                            if (TtsPlayer.state.value is TtsPlayer.State.Idle &&
                                _eyeState.value == SparkEyeState.IDLE &&
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

        _statusHint.value = "正在理解指令…"
        val commands = RobotIntent.parse(text, curSettings)
        if (commands.isEmpty()) return false

        // LOOK 终止编排：拍照问答接管会话，其后的指令忽略
        val lookIndex = commands.indexOf(RobotIntent.Command.Look)
        val chain = if (lookIndex >= 0) commands.take(lookIndex + 1) else commands

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

            val contextInfo = buildString {
                val cal = CalendarContext.block()
                if (!cal.isNullOrBlank()) appendLine(cal)
                if (LocationContext.hasPermission(context)) {
                    LocationContext.current(context)?.description?.let { appendLine("位置：$it") }
                }
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
                },
            )
        }
    }

    /**
     * 退出灵动屏 = 退出应用（对齐大字模式）：
     * 模式切换只认设置页开关，这里只做收尾（停朗读、停录音），退出交给调用方 finishAffinity
     */
    fun exitApp() {
        idleToEyesJob?.cancel()
        TtsPlayer.stop()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
        TtsPlayer.stop()
    }
}
