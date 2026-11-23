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
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.DeviceBattery
import com.lvyoubao.app.data.DeviceVolume
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RobotClient
import com.lvyoubao.app.data.RobotIntent
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.ui.common.ContinuousConversation
import com.lvyoubao.app.ui.common.LocalKws
import com.lvyoubao.app.ui.common.EdgeTts
import com.lvyoubao.app.ui.common.ScreenAwake
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

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
    // 工具层带回的地图卡（路线 / 附近检索）：普通和大字模式一直有，灵动模式以前只有文字
    val mapCard: MapCardData? = null,
)

/** 待发送照片：解码转正后的位图 + 视觉模型要的 base64 dataUrl */
data class PendingPhoto(val bitmap: Bitmap, val dataUrl: String)

class SparkViewModel(app: TravelApp) : ViewModel() {

    private companion object {
        // 回答播报完毕后静默 N 秒自动回到萌眼待机
        const val EYES_RETURN_AFTER_MS = 4_000L

        /** 同一时刻只有一个人可能说「小源小源」：这个窗口内按 SNR 挑更近的那段 */
        const val WAKE_PICK_WINDOW_MS = 1_500L

        /**
         * 待机片段短于这个长度就不发云端：叹气、咳嗽、「嗯」「啊」都在 200~500ms，
         * 而「小源小源」四音节正常语速 700~1000ms，取 550ms 不会误伤真正的唤醒。
         */
        const val MIN_WAKE_SEG_MS = 550L

        /** 首句最少几个字就允许在逗号处切：够成一口气，又不至于让人干等 */
        const val FIRST_SENTENCE_CHARS = 8

        /** 首句说到多少字还没标点就强制切走播报（后续句子仍用 48 字兜底） */
        const val FIRST_TTS_CHARS = 18

        /** 首句最少几个字才单独成段：再短就并到下一句，碎句是「一顿一顿」的主要来源 */
        const val MIN_FIRST_PIECE = 4

        /** 后续句子最少几个字才单独成段 */
        const val MIN_PIECE = 10
    }

    private val repo = app.settingsRepository
    private val memory = app.memoryStore
    private val notes = app.noteStore
    private val appContext = app.applicationContext

    init {
        // 三种模式共用同一份会话数据：灵动模式以前把历史只放在内存里（SparkChatStore），
        // 换到普通/大字模式就「看不到刚才聊的」，进程一死全丢。
        // 现在绑定到 SessionStore 的「当前会话」，磁盘上就是同一份数据。
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
    }

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
    // 唤醒命中时刻：用来量「从喊中到听见第一声」的总延迟
    @Volatile
    private var wakeHitAt = 0L
    // 两条唤醒通道（端侧 KWS / 云端词表）共用的去重时刻：同一次唤醒只算一次
    @Volatile
    private var lastWakeHitAt = 0L
    // 唤醒动作（回应声 + 开麦）的去重时刻：两条通道先后命中时会各调一次 onWakeConfirmed，
    // 只挡 lastWakeHitAt 不够——云端那条不走 KWS 的判断
    @Volatile
    private var lastWakeConfirmAt = 0L
    // 流式播报进行中：句与句之间的 TtsPlayer「Idle」不算播完，别把眼睛提前顶出来
    @Volatile
    private var ttsStreaming = false
    private var sparkActive = false
    // 全双工通话占用麦克风期间，唤醒监听让路（一台设备同一时刻只能有一个 AudioRecord）
    private var duplexAudioActive = false
    // 通话中本机确认话术的去处：非空时 speakHint 不自己朗读，改由豆包音色说出来
    private var duplexReplySink: ((String) -> Unit)? = null
    private var pendingWakeSnr = 0f
    private var pendingWakeAt = 0L

    private var lastConvUploadAt = 0L
    private var wakeUploadCount = 0
    private var wakeWindowStart = 0L
    // 待机语音的处理队列（只留最新一段）：房间噪音频繁触发时，老实现按冷却窗直接删，
    // 常常把真正那句「小源小源」扔掉；改成排队串行，宁可多跑一次转写也不丢用户的话。
    private var pendingWakeWav: java.io.File? = null
    private var wakeDrainJob: Job? = null

    // 待机空转惩罚：直播/旁人聊天能过电平门槛，云端却转写为空（实测 30 秒 13 次）。
    // 连续两次空转就进入惩罚期：直接丢弃待机语音段（只抬门槛没用，实测照样过线）。
    // 惩罚期本来 90 秒，等于「一分半钟叫不醒」——用户反馈「经常唤不醒」大多撞在这上面，
    // 收紧到 25 秒，并且最多连丢 6 段就放一段上去复核（见 onUtterance）。
    private var emptyWakeStreak = 0
    private var wakeOnsetBoostDb = 0f
    private var wakeOnsetBoostUntil = 0L
    // 惩罚期一共丢了多少段（日志节流用，否则能刷几十行）
    private var wakeSkipCount = 0

    // 天气缓存预热节流（唤醒时顺手查一次，3 分钟内不重复）
    private var lastWeatherWarmAt = 0L

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
        // 回声风险跟播报句子走（句级）：念到「小源」的句子停喂 KWS，其余句子照喂可打断
        viewModelScope.launch {
            TtsPlayer.echoRisk.collect { risk ->
                continuous?.setTtsEchoRisk(risk)
                wakeSession?.setTtsEchoRisk(risk)
                wakeOnceSession?.setTtsEchoRisk(risk)
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
        ScreenAwake.keepOn() // 点消息重播：屏幕必须亮着，否则「有声音没画面」
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
        ScreenAwake.keepOn() // 要说话了：屏幕不能在这时候熄
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

        // 本地闸门：不含任何动作/拍摄类说法，直接放行聊天。
        // 以前每句话都要先花一次完整 AI 调用（8 秒超时）去判断「这是不是机器人指令」，
        // 连「现在几点了」也要空等这一趟——这是「问一句半天没反应」最大的单一来源。
        if (robotHints.none { text.contains(it) }) return false

        // AI 解析（机器人动作 + 设备指令统一判断）
        val commands = RobotIntent.parse(text, curSettings)
        if (commands.isEmpty()) {
            // 解析为空是常态（绝大多数话都不是指令）。提示必须收回去，
            // 否则「正在理解指令…」会一直挂在左上角，看起来就像卡死。
            _statusHint.value = null
            return false
        }
        _statusHint.value = "正在理解指令…"

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
            executeLook(context, text)
            return true
        }

        // 剩下的是机器人动作：没开机器人控制→回落聊天
        if (!curSettings.robotEnabled) return false

        val robot = robotClientOrNull(context, curSettings) ?: return true

        // 流程先行展开：AI 只给流程名（NOD/SHAKE/...），这里转成「起点平视/原朝向、终点回正」
        // 的控制指令序列。播报按流程名（「好的，点个头」），不逐条念「平视、抬头、低头、平视」。
        val steps = chain.map { it.label to RobotIntent.expandFlow(it) }
        val executed = mutableListOf<String>()
        val total = steps.sumOf { it.second.size }
        var done = 0
        for ((label, cmds) in steps) {
            for (cmd in cmds) {
                if (cmd == RobotIntent.Command.Look) {
                    if (executed.isNotEmpty()) announceChain(context, executed)
                    executeLook(context, text)
                    return true
                }
                val ok = executeRobot(robot, cmd).fold(
                    onSuccess = {
                        done++
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
                if (done < total) {
                    _statusHint.value = "执行中（$done/$total）…"
                    delay(estimateMillis(cmd)) // 留出动作的运动耗时，节奏可感知
                }
            }
            executed.add(label)
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
        is RobotIntent.Command.Head -> 700L // 舵机俯仰本身很快；点头这类流程有 4 步，1.2s/步太拖
        else -> 500L
    }

    /**
     * LOOK「看看前面有什么」类：设备相机静默抓一帧（CameraSnapshotHelper 即拍即走，零待机占用）
     * → 走现有视觉解析管线（照片轮入会话 + 流式回答 + 自动朗读）。
     * [userAsk] 是触发 LOOK 的那句原话：AI 此前只收到写死的「说说前面有什么」，用户问
     * 「这是什么产品」也只会得到画面描述——原话必须拼进提示词，问什么答什么。
     */
    private suspend fun executeLook(context: Context, userAsk: String? = null) {
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
        SparkChatStore.clearStalePlaceholders()
        // 先占一条 assistant 消息，后续流式/结果/出错都更新它
        appendMessage(SparkMessage("assistant", ""))
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

            // 位置：读缓存快照。**只有问天气/附近这类真需要位置的问题**才主动要一次，
            // 其余问题直接放行——老逻辑是「缓存没有就等 2.5 秒定位」，这台设备没有 GPS，
            // 等于每句话都先白等 2.5 秒才开始问模型，这才是「回应特别慢」的大头。
            val wantsWeather = listOf("天气", "气温", "温度", "下雨", "下雪", "刮风", "风力", "湿度", "穿什么", "热不热", "冷不冷", "带伞", "防晒")
                .any { userText.contains(it) }
            val wantsPlace = wantsWeather ||
                listOf("附近", "周边", "在哪", "哪儿", "多远", "怎么走", "导航", "位置", "这是哪").any { userText.contains(it) }
            val cachedPlace = LocationContext.current(context)
            val place = cachedPlace
                ?: if (wantsPlace) {
                    runCatching { LocationContext.fresh(context, timeoutMs = 1_200) }.getOrNull()
                } else {
                    null
                }
            // 天气：**先吃缓存**。缓存里已经有这个坐标的天气（唤醒时预热写入的，或 10 分钟内问过），
            // 就是 0ms；只有真的没有才联网，而且最多等 1.6 秒——Open-Meteo 在境外，
            // 实测 RTT 250ms、丢包 33%，之前 10s/30s 的超时等于「问天气先站十几秒」。
            // 查不到也不挡路：位置照常给模型，让它按常识和季节回答，比干等强。
            val weatherLine = if (wantsWeather && place != null) {
                // 高德 Key 已在设置里（路线/POI 用的同一个）：国内接口 100ms 级，
                // Open-Meteo 只在没 Key / 高德失败时兜底
                WeatherClient.currentQuick(
                    place.lon,
                    place.lat,
                    timeoutMs = 1_500,
                    amapKey = curSettings.amapKey,
                    cityHint = place.description,
                )
            } else {
                null
            }
            val contextInfo = buildString {
                val cal = CalendarContext.block()
                if (!cal.isNullOrBlank()) appendLine(cal)
                place?.description?.let { appendLine("位置：$it") }
                if (weatherLine != null) appendLine("【实时天气】$weatherLine")
                // 长期记忆：普通/大字模式一直注入 MemoryStore 的画像，灵动模式此前没接，
                // 同一个用户换个模式就「失忆」——同一个 App 里两种人格，很割裂。
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
            val tSend = SystemClock.elapsedRealtime()

            // 首字迟迟不来时给个明确提示：界面一直转圈最像「死机」，
            // 说清「还在等、是网络慢」比沉默好得多（今晚就是 45 秒干等才报超时）。
            val slowHint = launch {
                delay(6_000)
                if (speechBuffer.isEmpty() && _isStreaming.value) {
                    _statusHint.value = "还在等回应（网络有点慢）…"
                }
            }

            // 预热 TTS 主机：模型出首字的这一两秒里顺带把 TLS 建好
            EdgeTts.warmUp()
            // 流式播报：拿到的字按句子切开，一句一句送合成、顺序播，不再等全文再开口。
            // 旧逻辑要等 onSuccess 才 TtsPlayer.speak(全文)，长回答要干等好几秒。
            ttsStreaming = true
            // 首句单独一条通道：播放器拿到就直接「边收边播」（首帧 ≈ 0.5 秒出声），
            // 不走「整句合成到文件再播」——那一步每句要白等 1.5~3 秒，正是首句慢的主因。
            val firstPiece = CompletableDeferred<String?>()
            val speechChannel = Channel<String>(Channel.UNLIMITED)
            // 文件和句文本一起排队：播放侧靠文本标记回声风险（这句含不含「小源」）
            val audioChannel = Channel<Pair<java.io.File, String>>(Channel.UNLIMITED)
            val ttsDir = java.io.File(context.cacheDir, "tts_stream").apply {
                runCatching { mkdirs() }
                // 上一轮被中途打断时，队列里还没播到的 mp3 会留在这里。每轮开播前清一次，
                // 别让 /data 里堆几十兆垃圾（这台设备只剩 1.7GB）。
                runCatching { listFiles()?.forEach { it.delete() } }
            }
            var spokeAny = false
            val playGen = TtsPlayer.startStreamSession()

            // 第一句进首句通道（流式秒播），其余进合成队列（等到播的时候文件已躺好）
            fun emit(piece: String) {
                if (piece.isBlank()) return
                if (!firstPiece.complete(piece)) speechChannel.trySend(piece)
            }

            fun markFirstPlay() {
                if (spokeAny) return
                spokeAny = true
                val hitAt = wakeHitAt
                if (hitAt > 0) {
                    LogStore.log("ai", "首句起播：距唤醒 ${SystemClock.elapsedRealtime() - hitAt}ms")
                    wakeHitAt = 0L
                }
            }

            // 合成器：句子一到就合成，不等播放。
            // 合成约 1.5 秒/句，播放要 4~6 秒/句，队列会稳定领先——
            // 这就是句间那 2~3 秒空档消失的原因：以前是「播完第一句才开始连第二句的 WSS」。
            val synthesizer = launch(Dispatchers.IO) {
                try {
                    for (piece in speechChannel) {
                        if (piece.isBlank()) continue
                        val f = TtsPlayer.synthesizeOnly(piece, ttsDir)
                        if (f != null) audioChannel.send(f to piece)
                    }
                } finally {
                    audioChannel.close()
                }
            }

            // 播放器：首句边收边播，之后严格按顺序播预取好的文件
            val speaker = launch {
                try {
                    val first = firstPiece.await()
                    if (!first.isNullOrBlank() && TtsPlayer.isActive(playGen)) {
                        markFirstPlay()
                        val streamed = TtsPlayer.playStreamedPiece(first, playGen)
                        if (!streamed && TtsPlayer.isActive(playGen)) {
                            // 流式一帧都没出（连不上/协议失败）：这一句退回文件链，别把首句丢了
                            val f = TtsPlayer.synthesizeOnly(first, ttsDir)
                            if (f != null) {
                                TtsPlayer.playPrepared(f, playGen, first)
                                runCatching { f.delete() }
                            }
                        }
                    }
                    for ((f, piece) in audioChannel) {
                        if (!TtsPlayer.isActive(playGen)) break
                        markFirstPlay()
                        val ok = TtsPlayer.playPrepared(f, playGen, piece)
                        runCatching { f.delete() }
                        // 只有被新播报接管才收工。单句起播失败不该连坐后面的话——
                        // 老实现是 `if (!ok) break`，一次 start 抖动就把剩下半篇全吞了。
                        if (!ok && !TtsPlayer.isActive(playGen)) break
                    }
                    // 整段回答共用一条音轨：文件都写进去了不等于已经播完，
                    // 必须等播放头追上写入量再收尾，否则最后一句还在响就回待机、开麦，
                    // 自己的尾音会被当成用户开口（"播完又自己触发了"）。
                    TtsPlayer.awaitPlaybackDone(playGen)
                } catch (_: CancellationException) {
                    TtsPlayer.stop()
                } finally {
                    TtsPlayer.endStreamSession(playGen)
                }
            }

            // 工具层捕获的地图卡（问路线/地点时由 get_route / search_poi 带回）
            val mapCards = java.util.Collections.synchronizedList(mutableListOf<MapCardData>())

            AiClient(curSettings).chat(
                messages = fullPayload,
                thinking = false,
                searchEnabled = false,
                // 三种模式功能对齐：路线 / 附近检索这些工具，灵动模式也要能用，
                // 而不是「语音问导航只能听一句文字」。
                // 但**只在问题确实要出门时才挂工具**：带上工具说明会让每次请求多几百个 token，
                // 闲聊类问题不该为它多等那一下。
                toolExecutor = if (wantsPlace) {
                    { name, args ->
                        AppTools.execute(appContext, curSettings, name, args) { card -> mapCards.add(card) }
                    }
                } else {
                    null
                },
                onAnswer = { chunk ->
                    if (speechBuffer.isEmpty() && chunk.isNotBlank()) {
                        slowHint.cancel()
                        LogStore.log(
                            "ai",
                            "首字 ${SystemClock.elapsedRealtime() - tSend}ms" +
                                "（进流程到发请求 ${tSend - tEnter}ms，含位置/情境）",
                        )
                    }
                    speechBuffer.append(chunk)
                    SparkChatStore.updateLastAssistant(speechBuffer.toString())
                    if (_eyeState.value != SparkEyeState.SPEAKING) {
                        _eyeState.value = SparkEyeState.SPEAKING
                        _statusHint.value = null
                    }
                    // 一遇到句末标点就把这一句送出去合成（首句通常 0.3~0.8 秒就能开口）
                    var cut = findSentenceEnd(speechBuffer, spokenUpTo, !firstPiece.isCompleted)
                    while (cut >= 0) {
                        val len = cut + 1 - spokenUpTo
                        // 太碎的片段不单独成段：「好。」「是的。」各自送去合成、各自起播，
                        // 每个边界都要多等一次合成+一次起播，听感就是「这，是一个好，东西」。
                        // 并到下一句一起播，语义和语调反而更自然。
                        val minLen = if (!firstPiece.isCompleted) MIN_FIRST_PIECE else MIN_PIECE
                        if (len < minLen) {
                            // 同上：首句没落地前继续用首句规则，否则「好的，……」会把整段拖到句号
                            cut = findSentenceEnd(speechBuffer, cut + 1, !firstPiece.isCompleted)
                            continue
                        }
                        val piece = speechBuffer.substring(spokenUpTo, cut + 1).trim()
                        spokenUpTo = cut + 1
                        emit(piece)
                        cut = findSentenceEnd(speechBuffer, spokenUpTo, !firstPiece.isCompleted)
                    }
                    // 长句兜底：一口气说到上限还没标点，先播出去，别让人干等。
                    // 首句门槛小得多——第一句出来的早晚，直接决定「回应快不快」的听感。
                    // 后续句子放宽到 48 字：34 字就切常常切在定语中间，读出来是半截话。
                    val cutThreshold = if (!firstPiece.isCompleted) FIRST_TTS_CHARS else 48
                    if (speechBuffer.length - spokenUpTo >= cutThreshold) {
                        val piece = speechBuffer.substring(spokenUpTo).trim()
                        spokenUpTo = speechBuffer.length
                        emit(piece)
                    }
                },
            ).fold(
                onSuccess = { reply ->
                    slowHint.cancel()
                    val finalAnswer = reply.answer.trim()
                    SparkChatStore.updateLastAssistant(finalAnswer)
                    mapCards.lastOrNull()?.let { card ->
                        SparkChatStore.attachMapCard(card)
                        LogStore.log("spark", "地图卡已挂载：${card.title}")
                    }
                    _isStreaming.value = false

                    SparkChatStore.appendHistory(currentUserMsg)
                    SparkChatStore.appendHistory(OutMessage("assistant", finalAnswer))

                    // 收尾：最后一段没标点的余量也播掉，然后等队列放完
                    val tail = speechBuffer.substring(spokenUpTo).trim()
                    if (tail.isNotEmpty()) emit(tail)
                    // 全文没有可播内容（纯符号/极短）时也要放行首句通道，否则播放器永远等在 await 上
                    if (!firstPiece.isCompleted) firstPiece.complete(null)
                    speechChannel.close()
                    synthesizer.join()
                    speaker.join()
                    LogStore.log(
                        "ai",
                        "全文完成 总耗时 ${SystemClock.elapsedRealtime() - tEnter}ms（${finalAnswer.length} 字）",
                    )
                    ttsStreaming = false
                    if (_eyeState.value == SparkEyeState.SPEAKING) finishSpeaking()
                },
                onFailure = { err ->
                    slowHint.cancel()
                    if (!firstPiece.isCompleted) firstPiece.complete(null)
                    speechChannel.close()
                    TtsPlayer.stop()
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

    /** 从 [from] 起找第一个可切分点（至少 3 个字才算一句，避免「好。」这种碎句）；换行也算 */
    private fun findSentenceEnd(text: CharSequence, from: Int, isFirst: Boolean): Int {
        // 首句额外允许在逗号/顿号处切：AI 的第一句常是「低糖电饭煲啊，它是靠……这类结构」，
        // 一路等到句号才送合成要多等 1~2 秒；10 个字的话头已经够合成一段自然的起句。
        val pauseMin = if (isFirst) FIRST_SENTENCE_CHARS else 40
        for (i in from until text.length) {
            val c = text[i]
            if ((sentenceEnds.indexOf(c) >= 0 || c.code == 10) && i - from + 1 >= 3) return i
            val isPause = c == '，' || c == ',' || c == '、' || c == '：' || c == ':'
            if (isPause && i - from + 1 >= pauseMin) return i
        }
        return -1
    }

    /** 一遍回答真正播完：回待机眼睛 + 到点（EYES_RETURN_AFTER_MS）自动收起会话流 */
    private fun finishSpeaking() {
        _eyeState.value = if (_isContinuous.value) SparkEyeState.LISTENING else SparkEyeState.IDLE
        // 一轮对话结束：屏幕进入「空闲 N 秒后熄屏」倒计时
        ScreenAwake.idle()
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
        // 进入待机就开始算空闲：没人管它的话，到点自动熄屏省电；
        // 喊「小源小源」时 onWakeConfirmed 会把屏幕点亮
        ScreenAwake.idle()
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

    /** 唤醒后动作=直接通话：VM 不碰 UI，发这个事件由界面层拉起通话覆盖层 */
    val wakeCallRequest = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 通话落库后触发：通话也是会话，攒够新 user 消息就总结进长期画像（与文本聊天同口径） */    fun summarizeCallTurns() {
        val settingsNow = settings.value
        if (settingsNow.apiKey.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val store = (appContext as? com.lvyoubao.app.TravelApp)?.sessionStore ?: return@launch
                val sid = repo.currentSessionId.firstOrNull() ?: return@launch
                val session = store.get(sid) ?: return@launch
                val userCount = session.messages.count { it.role == "user" }
                if (userCount == 0 || userCount <= session.summarizedUserCount) return@launch
                if (userCount - session.summarizedUserCount < 3) return@launch
                if (memory.updateProfileFromSession(session, settingsNow)) {
                    store.updateSummarizedUserCount(sid, userCount)
                }
            }
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
            // 回应语音提前加载好：SoundPool 首次 load 是异步的，等到真唤醒才加载会没声
            SparkAck.preload(appContext)
            // 端侧唤醒词常驻：模型只加载一次（~5MB int8），之后每帧只做一次前向
            ensureLocalKws()
            val session = ContinuousConversation(
                appContext,
                config = ContinuousConversation.Config(
                    // 设置里的「灵敏度」以前只管连聊打断，连聊并掉之后就等于没用；现在它直接决定唤醒门槛
                    silenceEndMs = if (curVoiceSens >= 2) 320L else 380L,
                    minSpeechMs = if (curVoiceSens >= 2) 180L else 220L,
                    bargeInSensitivity = 0, // 待机不需要打断：主动提醒播报期间不收音，防回灌
                    idleTimeoutMs = Long.MAX_VALUE / 2, // 待机监听常驻，不自动退
                    // 单段上限收到 6 秒：屋里电视/聊天一响就是十几秒的长段，
                    // 唤醒词埋在长段里识别容易被串音盖掉，切碎一点反而更容易命中
                    maxUtteranceMs = 6_000L,
                    peakDropDb = 16f,
                    onsetMarginDb = if (curVoiceSens >= 2) 8f else 12f,
                    minOnsetDb = if (curVoiceSens >= 2) -46f else -42f,
                ),
                listener = object : ContinuousConversation.Listener {
                    // 一开口就给反馈（约开口后 160ms）：眼睛立刻变「在听」，不用等识别结果
                    override fun onSpeechStart() {
                        if (!duplexAudioActive && activeRecording == null) {
                            _eyeState.value = SparkEyeState.LISTENING
                            wakeSpeechStartedAt = SystemClock.elapsedRealtime()
                        }
                    }

                    override fun onUtterance(wav: java.io.File, snrDb: Float) {
                        // 空转惩罚期：整段丢弃，连云端转写都不跑。
                        // 只抬 6dB 门槛对直播人声根本没用——实测那段峰值 -8dBFS，门槛从 -46 抬到 -40
                        // 照样过线；只有「干脆不送」才真能省电、省配额、少占采集通道。
                        // 这段时间唤醒仍由端侧 KWS 兜着；但为了不让 KWS 漏命中变成「彻底聋」，
                        // 连跳 6 段就放一段上去复核——真有人在说话，这一下就把惩罚卸掉了。
                        if (wakeOnsetBoostUntil > SystemClock.elapsedRealtime()) {
                            if (wakeSkipCount >= 6) {
                                wakeSkipCount = 0
                                LogStore.log("wake", "空转惩罚：连跳 6 段，放一段去云端复核")
                            } else {
                                wakeSkipCount++
                                wav.delete()
                                if (wakeSkipCount % 5 == 0) {
                                    LogStore.log(
                                        "wake",
                                        "空转惩罚中：跳过待机语音（已跳 $wakeSkipCount 段，端侧唤醒照常）",
                                    )
                                }
                                return
                            }
                        }
                        handleWakeUtterance(wav, snrDb)
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
            session.setTtsEchoRisk(TtsPlayer.echoRisk.value)
            session.start() // 后台重试；最终失败由 onError 清理并提示
            wakeSession = session
            // 空转惩罚还在一半时重建了会话（切前后台/灵敏度改动），把门槛接着抬，别白罚
            val boostLeft = wakeOnsetBoostUntil - SystemClock.elapsedRealtime()
            if (wakeOnsetBoostDb > 0f && boostLeft > 0) {
                session.boostOnset(wakeOnsetBoostDb, boostLeft)
            }
            LogStore.log(
                "wake",
                "待机监听启动：灵敏度 $curVoiceSens 档，判活门槛 " +
                    "${if (curVoiceSens >= 2) 8f else 12f}dB / 下限 ${if (curVoiceSens >= 2) -46f else -42f}dB，" +
                    "最短 ${if (curVoiceSens >= 2) 180L else 220L}ms，静音收段 ${if (curVoiceSens >= 2) 320L else 380L}ms",
            )
        } else if (!shouldRun && wakeSession != null) {
            wakeSession?.stop()
            wakeSession = null
        }
    }

    /**
     * 待机语音入口：短窗口内**取信噪比最高的那段**（= 最近、最响的那个人）。
     * 以前是「谁最后来就用谁」——电视/旁人说话隔三差五冒一段，反倒把用户这句顶掉；
     * 单麦没有波束成形，SNR 是「近讲」最靠谱的代理：近讲 25~40dB，串音通常不到 12dB。
     */
    private fun handleWakeUtterance(wav: java.io.File, snrDb: Float) {
        val now = SystemClock.elapsedRealtime()
        val held = pendingWakeWav
        if (held != null && now - pendingWakeAt < WAKE_PICK_WINDOW_MS && pendingWakeSnr >= snrDb) {
            // 手上这段更响（更近）：新来的当背景音丢掉
            wav.delete()
            LogStore.log(
                "wake",
                "丢弃较弱的一段：信噪比 ${"%.0f".format(snrDb)}dB < 已持有 ${"%.0f".format(pendingWakeSnr)}dB",
            )
            return
        }
        held?.delete()
        pendingWakeWav = wav
        pendingWakeSnr = snrDb
        pendingWakeAt = now
        if (wakeDrainJob?.isActive == true) return
        wakeDrainJob = viewModelScope.launch { drainWakeQueue() }
    }

    /**
     * 待机语音串行处理：一段处理完再看下一段（只留最新那一句）。
     * v0.9.18 之前是「800ms 冷却窗内的新段直接删掉」——环境噪音频繁触发时，
     * 真正那句「小源小源」常常正好撞进冷却窗被扔掉，这才是「怎么喊都不醒」的主因。
     * 现在改成队列只留最新一段 + 峰值闸门，宁可多跑一次转写，也不丢用户这句话。
     */
    private suspend fun drainWakeQueue() {
        while (true) {
            val wav = pendingWakeWav ?: break
            val snr = pendingWakeSnr
            pendingWakeWav = null
            val hit = runCatching { processWakeWav(wav, snr) }
                .onFailure { wav.delete() }
                .getOrDefault(false)
            // 没命中（多半是环境噪音）：眼睛从「在听」退回待机，别在嘈杂屋子里一直亮着
            if (!hit && wakeOnceSession == null && !_isRecording.value && !duplexAudioActive &&
                _eyeState.value == SparkEyeState.LISTENING
            ) {
                _eyeState.value = SparkEyeState.IDLE
            }
            delay(400) // 段间隔，避免上行连发；这期间来的新段会覆盖 pending
        }
    }

    /**
     * 待机空转：连续两次「转写为空/单字」就进入惩罚期，直接丢弃待机语音，静音 25 秒。
     * 直播里的人在说话时电平能到 -10dBFS、信噪比 20dB，靠能量判据根本挡不住；
     * 只有「云端认不出字」这个信号最诚实——它不是对机器说话。
     *
     * 时长从 90 秒收到 25 秒：90 秒意味着这段时间里**叫天天不应**（云端那条被掐了，
     * 只剩端侧 KWS 兜底），用户感受就是「经常唤不醒」。25 秒足够挡住一波直播爆音，
     * 又不至于把人晾在那儿。
     */
    private fun registerEmptyWake() {
        emptyWakeStreak++
        if (emptyWakeStreak < 2) return
        emptyWakeStreak = 0
        wakeOnsetBoostDb = 6f
        val dur = 25_000L
        wakeOnsetBoostUntil = SystemClock.elapsedRealtime() + dur
        wakeSkipCount = 0
        wakeSession?.boostOnset(wakeOnsetBoostDb, dur)
    }

    /** 待机出现正常文本：立刻卸掉空转惩罚，恢复正常灵敏度 */
    private fun clearEmptyWake() {
        if (emptyWakeStreak == 0 && wakeOnsetBoostDb == 0f) return
        emptyWakeStreak = 0
        if (wakeOnsetBoostDb != 0f) {
            wakeOnsetBoostDb = 0f
            wakeOnsetBoostUntil = 0L
            wakeSession?.boostOnset(0f, 0L)
            LogStore.log("wake", "恢复正常灵敏度（识别到正常文本）")
        }
    }

    /** 处理一段待机语音：噪声闸门 → 云转写 → 唤醒词判定 → 进问答 */
    private suspend fun processWakeWav(wav: java.io.File, segSnrDb: Float): Boolean {
        val arrivedAt = SystemClock.elapsedRealtime()
        val cur = repo.settings.firstOrNull() ?: settings.value
        val wavBytes = wav.length()
        // 极短片段（< 550ms）本地丢弃：叹气、咳嗽、「嗯」「啊」这类口头语都在这个长度，
        // 而「小源小源」四音节正常语速也有 700~1000ms，不会误伤。
        // 以前这些一律往云端发一次，白烧配额、白占采集通道，真人喊话时反而更容易撞车。
        val segMs = wavBytes / 32
        if (segMs < MIN_WAKE_SEG_MS) {
            LogStore.log("wake", "片段过短（${segMs}ms）：口头语/环境声，本地丢弃，不发上行")
            wav.delete()
            return false
        }
        // 闸门：峰值太低（远处杂音/空调/风扇）不值得跑一次云转写，省流量也省配额
        val peakDb = withContext(Dispatchers.IO) { wavPeakDbfs(wav) }
        if (peakDb < NOISE_GATE_DB) {
            LogStore.log(
                "wake",
                "噪声段跳过：峰值 ${peakDb.toInt()}dBFS（门槛 ${NOISE_GATE_DB.toInt()}dBFS），不发上行",
            )
            wav.delete()
            return false
        }
        if (arrivedAt - wakeWindowStart > 60_000) {
            wakeWindowStart = arrivedAt
            wakeUploadCount = 0
        }
        if (++wakeUploadCount > 30) {
            LogStore.log("wake", "唤醒上行配额用尽（每分钟 30 条），这段丢弃")
            wav.delete()
            return false
        }
        val spoken = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, cur.asrKey.trim(), cur.dialectMode).getOrNull()
        }
        val asrDoneAt = SystemClock.elapsedRealtime()
        wav.delete()
        val text = spoken.orEmpty().trim()
        // 空转自适应：电平和信噪比都够、云端却认不出字——基本就是直播/旁人聊天。
        // 一次不罚（也可能真没听清），连续两次就让待机档「装聋」90 秒；
        // 一旦出现正常文本立刻恢复，不耽误真人喊话。
        if (text.length <= 1) registerEmptyWake() else clearEmptyWake()
        // 唤醒链路实测：没这行 = 根本没判成一段话（门槛高/麦克风没声）；
        // 有这行但文本不对 = 识别没听清（电平、方言、噪音）；文本对却没反应 = 词表要放宽。
        val speakStart = wakeSpeechStartedAt
        val e2e = if (speakStart in 1 until arrivedAt) "${asrDoneAt - speakStart}ms" else "?"
        LogStore.log(
            "wake",
            "待机语音：录音 ${wavBytes / 32}ms 峰值 ${peakDb.toInt()}dBFS 环境底噪 " +
                "${wakeSession?.debugNoiseFloorDb()?.toInt() ?: "?"}dBFS 信噪比 ${"%.0f".format(segSnrDb)}dB，" +
                "转写 ${asrDoneAt - arrivedAt}ms" +
                "（开口→识别完 ${e2e}），文本「${text.ifBlank { "（空）" }}」",
        )
        // 铃响时待机任何语音都先停铃（开口就是想让它安静）：小源小源/小源小源退出/关闭闹钟/别响了都算
        val wasAlarming = _alarmActive.value
        if (text.isNotBlank() && wasAlarming) {
            if (matchAlarmStop(text)) {
                stopAlarm()
                appendMessage(SparkMessage("assistant", "好的，不响了"))
                _statusHint.value = null
                speakHint(appContext, "好的，不响了")
                return false
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
                return false
            }
            // 端侧 KWS 已经先命中过这一句（8 秒内）：这是云端慢转写的迟到副本。
            // 以前只靠 onWakeConfirmed 里 1.2 秒窗口兜底，弱网转写一超就播两遍「我在」，
            // 还把已经开始的单次聆听重启——用户正说的话开头被吞。
            if (SystemClock.elapsedRealtime() - lastWakeHitAt < 8_000) {
                if (ask.length >= 2) {
                    // 「小源小源，今天天气怎么样」整句：KWS 那次没拿到问题只开了聆听，
                    // 迟到的整句转写正好补上——停掉聆听直接答，不用让用户再说一遍
                    LogStore.log("wake", "云端迟到整句（问题「$ask」）：停单次聆听直接答")
                    wakeOnceSession?.stop()
                    wakeOnceSession = null
                    lastWakeHitAt = SystemClock.elapsedRealtime()
                    answerVoiceQuestion(ask)
                } else {
                    LogStore.log("wake", "云端迟到命中（KWS 已回过「我在」），忽略")
                }
                return true
            }
            lastWakeHitAt = SystemClock.elapsedRealtime()
            onWakeConfirmed(ask)
            return true
        } else if (wasAlarming) {
            _statusHint.value = "好的，闹钟停了"
        }
        return false
    }

    /**
     * 唤醒命中之后的所有动作，两条通道（端侧 KWS / 云端词表）共用：
     * 回应声 → 预热链路 → 带问题就直接答，不带问题就开一轮单次聆听。
     *
     * v0.9.15 起是单次交互，不再进「连聊」——连聊会一直占着麦克风、自己判断断句，
     * 说到一半被打断的体验比多喊一次唤醒词差得多。
     */
    private suspend fun onWakeConfirmed(remainder: String) {
        // 端侧 KWS 与云端词表是两条独立通道，会**先后**命中同一句「小源小源」：
        // 实测本地 23:16:29.431、云端 23:16:30.009，差 578ms；22:55:39 那次差 332ms。
        // 两边都往下走就会播两遍回应声（「我在」听成两个），并把已经开始的聆听会话
        // 停掉重启——用户正说着话，开头就被吞了。这里统一拦一道。
        val nowHit = SystemClock.elapsedRealtime()
        if (nowHit - lastWakeConfirmAt < 1_200) {
            LogStore.log("wake", "唤醒重复回调（${nowHit - lastWakeConfirmAt}ms 内已唤醒过），忽略")
            return
        }
        lastWakeConfirmAt = nowHit
        // 真有人喊醒了：立刻卸掉「空转惩罚」，恢复正常灵敏度
        clearEmptyWake()
        wakeSkipCount = 0
        // 息屏待机时被喊醒：先把屏幕点亮，再出声——
        // 机器人放在桌上，声音回应了但屏幕黑着，用户会以为它没醒
        ScreenAwake.wakeUp(appContext)
        _showEyes.value = true
        TtsPlayer.stop() // 唤醒这一刻不要有任何残留播报，免得盖住第一句
        // 回应：一句真人声「我在/在呢/来了」，比原来的电子提示音更像在对话
        SparkAck.play(appContext)
        wakeHitAt = SystemClock.elapsedRealtime()
        // 握手预热：用户还在说问题（2~5 秒），TTS 与 AI 两条链路的 DNS/TCP/TLS
        // 都在后台跑完，等答案出来直接复用连接——这段以前是纯等待，占总延迟 1 秒以上
        runCatching { AiClient.prewarm(settings.value) }
        runCatching { EdgeTts.prewarm() }
        // 天气链路也一起预热：Open-Meteo 在境外（实测 RTT 250ms、丢包 33%），
        // 等用户问「今天天气怎么样」才去查，光握手就一两秒。趁他还在说话的这几秒先查好写进缓存。
        runCatching { warmWeatherCache() }
        _eyeState.value = SparkEyeState.LISTENING
        // 唤醒后动作=直接通话（设置：唤醒后）：不进单次语音，拉起豆包全双工
        if (settings.value.wakeAction == "call" && settings.value.duplexApiKey.isNotBlank() && !duplexAudioActive) {
            wakeCallRequest.tryEmit(Unit)
            return
        }
        if (remainder.length >= 2) {
            // 「小源小源，今天天气怎么样」：唤醒词后面已经带问题，直接答，不用再喊一次
            answerVoiceQuestion(remainder)
        } else {
            // 只喊了唤醒词：开一轮单次聆听，说完就收，下次还要喊唤醒词。
            // 等回应声播完再开麦——「我在」被录进问题里，云端会把它当成提问的开头。
            delay(SparkAck.DURATION_MS)
            startWakeOneShot()
            _eyeState.value = SparkEyeState.LISTENING
            _statusHint.value = "我在听，说完就好"
        }
    }

    /** 端侧唤醒词初始化：失败就静默退回云端词表，不影响主流程 */
    private fun ensureLocalKws() {
        if (LocalKws.isReady()) return
        LocalKws.start(appContext) { keyword ->
            viewModelScope.launch { onLocalWakeHit(keyword) }
        }
    }

    /**
     * 后台把「定位 + 实时天气」查好塞进缓存，唤醒时调用。
     *
     * 为什么要提前查：Open-Meteo 在境外，设备侧实测 RTT 200~300ms、丢包 33%，
     * 一次冷查询 TLS 握手 + 请求要 0.8~2 秒；而用户问「今天天气怎么样」时，
     * 这条查询串在 AI 请求前面跑，等于整轮问答白等这么久。
     * 唤醒那一刻用户还在说问题（2~5 秒），正好把这活干完。
     *
     * 3 分钟内只做一次：连着喊几次唤醒不必反复查同一个城市。
     */
    private fun warmWeatherCache() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWeatherWarmAt < 3 * 60 * 1000L) return
        lastWeatherWarmAt = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val p = LocationContext.current(appContext)
                    ?: LocationContext.fresh(appContext, timeoutMs = 2_500)
                if (p != null) {
                    WeatherClient.prewarm(
                        p.lon,
                        p.lat,
                        amapKey = settings.value.amapKey,
                        cityHint = p.description,
                    )
                    LogStore.log("weather", "天气缓存预热：${p.description} ${p.lon},${p.lat}")
                }
            }
        }
    }

    /**
     * 本地 KWS 命中（声学匹配，不经云端转写）→ 直接进唤醒流程，实测比云端快 4~5 倍。
     * 与云端词表同时在线，谁先到谁生效，另一个被 [lastWakeHitAt] 挡掉。
     */
    private suspend fun onLocalWakeHit(keyword: String) {
        if (duplexAudioActive || activeRecording != null || _isRecording.value) {
            // 播报打断排查：这三个守卫以前静默吞掉命中，日志什么都看不到
            LogStore.log(
                "kws",
                "本地命中「$keyword」但被状态拦下（通话=$duplexAudioActive 录音=${activeRecording != null} 按住=${_isRecording.value}）",
            )
            return
        }
        if (wakeOnceSession != null) {
            LogStore.log("kws", "本地命中「$keyword」但单次聆听进行中，忽略")
            return // 已经在听问题，别把「我在听」再打断一次
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHitAt < 1_500) {
            LogStore.log("kws", "本地命中「$keyword」但 1.5 秒内已唤醒过，忽略")
            return
        }
        lastWakeHitAt = now
        LogStore.log("kws", "本地命中「$keyword」→ 立即回应（不经云端转写）")
        if (_alarmActive.value) {
            // 铃响时喊唤醒词就是想让它安静
            stopAlarm()
            appendMessage(SparkMessage("assistant", "好的，不响了"))
            _statusHint.value = null
            speakHint(appContext, "好的，不响了")
            return
        }
        onWakeConfirmed("")
    }

    /**
     * 唤醒词匹配（v0.9.17 放宽）：
     * 云识别对「小源」的写法太多（小袁/小圆/小原/小娟/小雨……），按固定词表比对经常漏，
     * 改成「小/晓/筱 + 近似字」的组合判定，且允许出现在前 8 个字里（真人常带「嗯」「那个」）。
     * 命中返回「去掉唤醒词之后剩下的话」（剩下的直接当问题），没命中返回 null。
     */
    /**
     * 机器人动作 / 拍摄类说法的本地关键词表。
     * 只用来决定「要不要花一次 AI 调用去解析指令」，命中后仍由 AI 判断具体动作；
     * 不含任何关键词的日常提问（天气、时间、常识）直接走聊天，省掉一整趟 AI 往返。
     */
    private val robotHints = listOf(
        "过来", "回去", "前进", "往前", "向前", "后退", "往后",
        "左转", "右转", "转圈", "转个圈", "转身", "掉头", "转过来", "转个身",
        "抬头", "低头", "抬起来", "低下来", "看上面", "看下面",
        "点头", "点个头", "点点头", "摇摇头", "摇头", "歪头", "歪着看",
        "看天上", "看看天上", "天花板", "抬头看天", "看地上", "看看地上", "低头找",
        "抬头看看", "低头看看", "东张西望", "到处看看", "环顾", "鞠躬",
        "平视", "正视", "回正", "摆正",
        "停下", "别动", "停下来", "站住",
        "跳舞", "跳个", "跟着我", "看着我", "面向我", "面向",
        "拍张", "拍个", "拍照", "拍一下", "看看", "看一下", "瞧一瞧",
        "这是什么", "前面是什么", "前面有", "帮我看看",
    )

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

    /** 待机语音的绝对电平闸门（dBFS）：比这更轻的段子当环境噪音，不占一次云转写 */
    private val NOISE_GATE_DB = -50f

    /** 读 WAV 峰值电平（dBFS）：16bit PCM，跳过 44 字节头；读不了就返回 0 放行（宁可不拦） */
    private fun wavPeakDbfs(file: File): Float = runCatching {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return@runCatching 0f
        var peak = 0
        var i = 44
        while (i + 1 < bytes.size) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
            val v = abs(s)
            if (v > peak) peak = v
            i += 2
        }
        if (peak == 0) return@runCatching -100f
        (20.0 * log10(peak / 32768.0)).toFloat()
    }.getOrDefault(0f)

    /**
     * 一句话走完整条问答链：**按住说话**与**唤醒后的单次聆听**共用这一个入口（同一个逻辑）。
     * 顺序：本地快路（电量 / 音量）→ 本机意图（机器人、计时、记事、记忆查找）→ 聊天。
     */
    private suspend fun answerVoiceQuestion(clean: String, photos: List<PendingPhoto> = emptyList()) {
        ScreenAwake.keepOn() // 一轮问答期间屏幕全程亮着，播完再重新计时
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
        ScreenAwake.keepOn() // 正在听人说话，屏幕不能灭
        wakeOnceSession?.stop()
        wakeOnceSession = null
        // 待机监听必须先让出麦克风：一台设备同一时刻只能有一个 AudioRecord。
        // 两个会话同时开着，单次聆听启动时会连吃几次 `AudioRecord start() -38`（EINVAL），
        // 一路降级到 16k/asr 甚至 voip，录出来的音质和识别率都跟着掉。
        // syncWakeEngine 的 shouldRun 里本来就有「wakeOnceSession 存在就让路」，
        // 但它只在状态变化时跑，这里得主动停一次。
        wakeSession?.stop()
        wakeSession = null
        val session = ContinuousConversation(
            appContext,
            config = ContinuousConversation.Config(
                // 一句话的收段判据：**宁多等半秒，也别把话切两半**。
                // 之前 650ms / 峰值回落 16dB 是按「连续说话」调的，老人说话中间常有
                // 800ms 以上的换气停顿，实测切出过「如果一只猫一整天。」「我一直」这种半句，
                // 转写出来驴唇不对马嘴，用户只能重说一遍——省的 0.45 秒远不值这个代价。
                silenceEndMs = 1_300L,
                // 语速自适应：本句里他停过 1.5 秒，就等他 2.4 秒再收段（上限 2.6 秒），
                // 而不是死守固定值把后半句切成新的一句
                adaptiveSilence = true,
                minSpeechMs = 300L,
                bargeInSensitivity = 0,
                idleTimeoutMs = 15_000L,
                // 一句话最多录 15 秒（原 6 → 10 → 15）：老人问长问题 10 秒也常说不完
                maxUtteranceMs = 15_000L,
                peakDropDb = 22f,
            ),
            listener = object : ContinuousConversation.Listener {
                override fun onUtterance(wav: java.io.File, snrDb: Float) {
                    // 唤醒后只认「近讲」的那一段：屋里电视、旁人聊天也会被 VAD 切成段，
                    // 但它们离麦克风远，信噪比明显低（主人对着设备说话通常 25dB 以上）。
                    // 不够格的不退出聆听，删掉继续等——用户常说「等下，我先问……」
                    if (snrDb < 15f) {
                        LogStore.log("wake", "单次聆听：这段太弱（${"%.0f".format(snrDb)}dB），不是对着设备说的，继续等")
                        wav.delete()
                        _eyeState.value = SparkEyeState.LISTENING
                        _statusHint.value = "我在听，说完就好"
                        return
                    }
                    // 单次：拿到第一段就停录音，处理完回待机
                    LogStore.log("wake", "单次聆听收段：信噪比 ${"%.0f".format(snrDb)}dB（近讲越大越可信）")
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
                    // 喊了唤醒词却一直没说话：回待机，屏幕重新开始空闲倒计时，
                    // 否则 keepOn 把倒计时取消了，屏幕会一直亮着
                    ScreenAwake.idle()
                    syncWakeEngine()
                }

                override fun onError(message: String) {
                    viewModelScope.launch {
                        wakeOnceSession?.stop()
                        wakeOnceSession = null
                        _eyeState.value = SparkEyeState.IDLE
                        _statusHint.value = "没听清：$message"
                        ScreenAwake.idle()
                        syncWakeEngine()
                    }
                }
            },
        )
        session.setTtsActive(TtsPlayer.state.value is TtsPlayer.State.Speaking)
        session.setTtsEchoRisk(TtsPlayer.echoRisk.value)
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
            ScreenAwake.idle()
            syncWakeEngine()
            return@launch
        }
        _statusHint.value = "正在识别…"
        val tAsr = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, asrKey, cur.dialectMode)
        }
        val asrMs = SystemClock.elapsedRealtime() - tAsr
        // 录音时长（16k/16bit 单声道 = 32 字节每毫秒，扣掉 44 字节 WAV 头）：
        // 这个数字直接说明「这句话里有多少死等」——2 秒的话录成 18 秒，多的全是噪声拖着不收段
        val recMs = ((wav.length() - 44).coerceAtLeast(0)) / 32
        wav.delete()
        LogStore.log(
            "wake",
            "单次聆听：录音 ${recMs}ms，转写 ${asrMs}ms，文本「${result.getOrNull()?.trim().orEmpty()}」",
        )
        result.fold(
            onSuccess = { spoken ->
                val clean = HotwordCorrector.correct(spoken.trim())
                // 环境声碎片（「嗯」「啊」「好」）不算提问：这类多半是电视/旁人漏进麦克风的，
                // 直接接着听，别让用户"还没来得及问就被识别成别的"。
                if (clean.isBlank() || clean.length <= 1 ||
                    clean in listOf("嗯", "嗯嗯", "啊", "哦", "噢", "咦", "呃", "好", "好的", "对", "是的", "开始", "这个", "那个")
                ) {
                    LogStore.log("wake", "单次聆听：碎片词「$clean」，不算提问，继续听")
                    _eyeState.value = SparkEyeState.LISTENING
                    _statusHint.value = "我在听，说完就好"
                    startWakeOneShot()
                } else {
                    answerVoiceQuestion(clean)
                }
            },
            onFailure = { err ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = (err as? SpeechInput.SpeechException)?.let { SpeechInput.spokenHint(it) }
                    ?: "没听清，再喊一次「小源小源」"
                // 提示留在屏幕上让人看清，N 秒后再进入息屏倒计时
                ScreenAwake.idle()
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
