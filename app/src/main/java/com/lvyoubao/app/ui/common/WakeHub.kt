package com.lvyoubao.app.ui.common

import android.content.Context
import android.os.SystemClock
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.RobotIntent
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.ui.spark.SparkAck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * 全应用统一的待机唤醒与单次聆听调度器（普通 / 大字 / 灵动三模式共用同一引擎）：
 *
 * 1. **双通道待机监听**：
 *    - 端侧 KWS（sherpa-onnx，零配额，始终开启）；
 *    - 云端词表辅助（配了 `asrKey` 时同步启用，带 550ms 过短过滤、-50dBFS 噪声闸门、
 *      SNR 近讲择优队列、连续空转 25 秒惩罚、以及「小源小源+问题连说直接答」能力）。
 * 2. **统一响应灵敏度**：三模式统一读取 `voiceBargeIn`（0/1/2）调整待机起振门槛。
 * 3. **唤醒后单次聆听**：内置回应声延时保护、近讲 SNR ≥ 15dB 筛选、碎片词（「嗯」「啊」等）
 *    自动续听；若未配置 `asrKey`，自动降级系统自带 `SpeechRecognizer`，不再无反应。
 * 4. **音频安全锁与前后台门控**：通过 [opLock] 与 [standbyAllowed] 彻底避免 `AudioRecord`
 *    双起竞态或亮屏切后台长期占麦。
 */
object WakeHub {

    private const val WAKE_PICK_WINDOW_MS = 1_500L
    private const val MIN_WAKE_SEG_MS = 550L
    private const val NOISE_GATE_DB = -50f
    private const val EMPTY_STREAK_BOOST_DB = 6f
    private const val EMPTY_PENALTY_DURATION_MS = 25_000L

    private val WAKE_FIRST_CHARS = charArrayOf('小', '晓', '筱', '肖')
    private val WAKE_SECOND_CHARS = charArrayOf(
        '源', '原', '袁', '圆', '元', '远', '园', '缘', '援', '院',
        '雨', '语', '玉', '月', '韵', '言', '羽',
    )
    private val FRAGMENT_WORDS = setOf(
        "嗯", "嗯嗯", "啊", "哦", "噢", "咦", "呃", "好", "好的", "对", "是的", "开始", "这个", "那个",
    )

    data class StandbyConfig(
        val sensitivity: Int = 1,
        val asrKey: String = "",
        val dialect: Boolean = false,
        val aiSettings: AiSettings? = null,
        val amapKey: String = "",
        val preferDirectCall: Boolean = false,
        /** 返回 true 表示当前正在通话/按住录音/不可被打断，命中回调需拦截 */
        val shouldSuppressHit: () -> Boolean = { false },
    )

    interface Listener {
        /** 待机检测到人声开口（灵动模式据此切眼睛动画，其它模式可空） */
        fun onStandbySpeechStart() {}

        /** 待机语音未命中唤醒词（灵动模式据此把眼睛退回待机） */
        fun onStandbyMiss() {}

        /** 唤醒后已播放「我在」准备开听（只喊唤醒词、无带问题时会回调） */
        fun onWakeListening() {}

        /** 唤醒要求直接拉起豆包全双工通话（设置「唤醒后=直接通话」） */
        fun onWakeCallRequested() {}

        /** 拿到用户有效提问文本（`fromDirectWake = true` 表示「小源小源+问题」连说直接提取） */
        fun onTranscript(text: String, fromDirectWake: Boolean)

        /** 唤醒单次聆听超时或失败 */
        fun onOneShotFailed(hint: String?) {}

        /** 唤醒过程中或响铃时触发停铃后提示（可选） */
        fun onAlarmStoppedByVoice(spokenReply: String) {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opLock = Any()

    // 亮屏切到其它应用时主动让出麦克风；息屏待机仍保持允许
    private val _standbyAllowed = MutableStateFlow(true)
    val standbyAllowed: StateFlow<Boolean> = _standbyAllowed.asStateFlow()

    // 当前是否正处于「唤醒后单次聆听一句话」阶段（UI 据此停止待机/显示聆听提示）
    private val _listeningOneShot = MutableStateFlow(false)
    val listeningOneShot: StateFlow<Boolean> = _listeningOneShot.asStateFlow()

    private var standby: ContinuousConversation? = null
    private var oneShot: ContinuousConversation? = null
    private var systemOneShotJob: Job? = null
    private var wakePrepJob: Job? = null

    private var currentContext: Context? = null
    private var currentConfig: StandbyConfig? = null
    private var currentListener: Listener? = null

    private var ttsJob: Job? = null
    private var echoJob: Job? = null
    private var wakeDrainJob: Job? = null

    @Volatile private var lastWakeHitAt = 0L
    @Volatile private var lastWakeConfirmAt = 0L
    @Volatile private var lastWeatherWarmAt = 0L
    @Volatile private var wakeSpeechStartedAt = 0L

    private var pendingWakeWav: File? = null
    private var pendingWakeSnr = 0f
    private var pendingWakeAt = 0L
    private var wakeUploadCount = 0
    private var wakeWindowStart = 0L

    private var emptyWakeStreak = 0
    private var wakeOnsetBoostDb = 0f
    private var wakeOnsetBoostUntil = 0L
    private var wakeSkipCount = 0

    fun standbyActive(): Boolean = synchronized(opLock) { standby != null }
    fun oneShotActive(): Boolean = synchronized(opLock) { oneShot != null || systemOneShotJob?.isActive == true }

    /**
     * 更新是否允许保持待机麦：切到后台且屏幕亮着时立即同步停麦，
     * 防止其它应用录音失败；回到前台后自动按最近配置恢复待机。
     */
    fun setStandbyAllowed(allowed: Boolean) {
        if (_standbyAllowed.value == allowed) return
        _standbyAllowed.value = allowed
        if (!allowed) {
            stopStandby()
        } else {
            resumeStandby()
        }
    }

    /** 统一开启（或更新参数重启）待机唤醒监听 */
    fun startStandby(context: Context, config: StandbyConfig, listener: Listener) {
        val ctx = context.applicationContext
        val oldToStop: ContinuousConversation?
        synchronized(opLock) {
            val prevCfg = currentConfig
            currentContext = ctx
            currentConfig = config
            currentListener = listener
            val sameAudioParams = prevCfg != null &&
                prevCfg.sensitivity == config.sensitivity &&
                prevCfg.asrKey == config.asrKey &&
                prevCfg.dialect == config.dialect
            if (standby != null && sameAudioParams && _standbyAllowed.value && !_listeningOneShot.value) {
                // 采集参数没变（仅上层回调/配置副本更新）：就地刷新引用，避免拆建 AudioRecord 闪断
                return
            }
            if (!_standbyAllowed.value) {
                oldToStop = detachStandbyLocked()
                LogStore.log("wake", "待机监听暂缓启动（当前退至后台）")
            } else if (_listeningOneShot.value || oneShot != null || systemOneShotJob?.isActive == true) {
                oldToStop = detachStandbyLocked()
            } else {
                oldToStop = detachStandbyLocked()
                SparkAck.preload(ctx)
                LocalKws.start(ctx) { keyword ->
                    scope.launch { handleLocalKwsHit(keyword) }
                }

                val sens = config.sensitivity
                lateinit var session: ContinuousConversation
                session = ContinuousConversation(
                    ctx,
                    config = ContinuousConversation.Config(
                        silenceEndMs = if (sens >= 2) 320L else 380L,
                        minSpeechMs = if (sens >= 2) 180L else 220L,
                        bargeInSensitivity = 0,
                        idleTimeoutMs = Long.MAX_VALUE / 2,
                        maxUtteranceMs = 6_000L,
                        peakDropDb = 16f,
                        onsetMarginDb = if (sens >= 2) 8f else 12f,
                        minOnsetDb = if (sens >= 2) -46f else -42f,
                    ),
                    listener = object : ContinuousConversation.Listener {
                        override fun onSpeechStart() {
                            if (!config.shouldSuppressHit()) {
                                wakeSpeechStartedAt = SystemClock.elapsedRealtime()
                                listener.onStandbySpeechStart()
                            }
                        }

                        override fun onUtterance(wav: File, snrDb: Float) {
                            val activeKey = currentConfig?.asrKey?.trim().orEmpty()
                            if (activeKey.isBlank()) {
                                wav.delete()
                                return
                            }
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
                            enqueueWakeUtterance(wav, snrDb)
                        }

                        override fun onBargeIn() {}
                        override fun onAutoTimeout() {}
                        override fun onError(message: String) {
                            LogStore.log("wake", "待机监听停了：$message")
                            scope.launch { clearStandbyIfSame(session) }
                        }
                    },
                )
                bindTtsMonitorLocked(session)
                session.setTtsActive(TtsPlayer.state.value is TtsPlayer.State.Speaking)
                session.setTtsEchoRisk(TtsPlayer.echoRisk.value)
                session.start()
                val boostLeft = wakeOnsetBoostUntil - SystemClock.elapsedRealtime()
                if (wakeOnsetBoostDb > 0f && boostLeft > 0) {
                    session.boostOnset(wakeOnsetBoostDb, boostLeft)
                }
                standby = session
                LogStore.log(
                    "wake",
                    "待机监听启动（${if (config.asrKey.isNotBlank()) "KWS+云端双通道" else "端侧 KWS 零配额"}，灵敏度=$sens 档）",
                )
            }
        }
        oldToStop?.stop()
    }

    fun stopStandby() {
        val old = synchronized(opLock) { detachStandbyLocked() }
        old?.stop()
    }

    fun cancelOneShot() {
        wakePrepJob?.cancel()
        wakePrepJob = null
        val old: ContinuousConversation?
        synchronized(opLock) {
            systemOneShotJob?.cancel()
            systemOneShotJob = null
            _listeningOneShot.value = false
            old = oneShot.also { oneShot = null }
        }
        old?.stop()
    }

    /** 录音/识别/通话结束或失败后按最新允许态恢复待机监听 */
    fun resumeStandby() {
        val (ctx, cfg, cb) = synchronized(opLock) {
            if (standby != null || !_standbyAllowed.value || _listeningOneShot.value) return
            Triple(currentContext, currentConfig, currentListener)
        }
        if (ctx == null || cfg == null || cb == null || cfg.shouldSuppressHit()) return
        LogStore.log("wake", "resumeStandby：恢复待机唤醒监听")
        startStandby(ctx, cfg, cb)
    }

    private fun detachStandbyLocked(): ContinuousConversation? {
        val had = standby != null
        if (had) LogStore.log("wake", "stopStandby 请求让麦（standby=true）")
        ttsJob?.cancel()
        ttsJob = null
        echoJob?.cancel()
        echoJob = null
        wakeDrainJob?.cancel()
        wakeDrainJob = null
        pendingWakeWav?.delete()
        pendingWakeWav = null
        return standby.also { standby = null }
    }

    private fun clearStandbyIfSame(session: ContinuousConversation) {
        val matched = synchronized(opLock) {
            if (standby === session) {
                detachStandbyLocked()
                true
            } else false
        }
        if (matched) session.stop()
    }

    private fun bindTtsMonitorLocked(session: ContinuousConversation) {
        ttsJob?.cancel()
        ttsJob = scope.launch {
            TtsPlayer.state.collect { st ->
                session.setTtsActive(st is TtsPlayer.State.Speaking)
            }
        }
        echoJob?.cancel()
        echoJob = scope.launch {
            TtsPlayer.echoRisk.collect { session.setTtsEchoRisk(it) }
        }
    }

    private suspend fun handleLocalKwsHit(keyword: String) {
        val cfg = currentConfig ?: return
        val listener = currentListener ?: return
        if (cfg.shouldSuppressHit() || oneShotActive()) {
            LogStore.log("kws", "本地命中「$keyword」但正忙（通话/录音/聆听中），忽略")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHitAt < 1_500L) {
            LogStore.log("kws", "本地命中但 1.5 秒内已唤醒过，忽略")
            return
        }
        lastWakeHitAt = now
        LogStore.log("kws", "本地命中「$keyword」→ 立即回应")
        if (CallTimer.alarmActive.value) {
            CallTimer.stopAlarm()
            val reply = "好的，不响了"
            currentContext?.let { ctx ->
                TtsPlayer.stop()
                TtsPlayer.speak(ctx, reply)
            }
            listener.onAlarmStoppedByVoice(reply)
            return
        }
        onWakeConfirmed("", listener)
    }

    private fun enqueueWakeUtterance(wav: File, snrDb: Float) {
        val now = SystemClock.elapsedRealtime()
        synchronized(opLock) {
            val held = pendingWakeWav
            if (held != null && now - pendingWakeAt < WAKE_PICK_WINDOW_MS && pendingWakeSnr >= snrDb) {
                wav.delete()
                LogStore.log("wake", "丢弃较弱一段：SNR ${"%.0f".format(snrDb)}dB < ${"%.0f".format(pendingWakeSnr)}dB")
                return
            }
            held?.delete()
            pendingWakeWav = wav
            pendingWakeSnr = snrDb
            pendingWakeAt = now
            if (wakeDrainJob?.isActive != true) {
                wakeDrainJob = scope.launch { drainWakeQueue() }
            }
        }
    }

    private suspend fun drainWakeQueue() {
        while (true) {
            val item = synchronized(opLock) {
                val f = pendingWakeWav ?: return@synchronized null
                val snr = pendingWakeSnr
                pendingWakeWav = null
                f to snr
            } ?: break
            val hit = runCatching { processWakeWav(item.first, item.second) }
                .onFailure { item.first.delete() }
                .getOrDefault(false)
            if (!hit && !oneShotActive() && currentConfig?.shouldSuppressHit() != true) {
                currentListener?.onStandbyMiss()
            }
            delay(400)
        }
    }

    private suspend fun processWakeWav(wav: File, segSnrDb: Float): Boolean {
        val ctx = currentContext ?: run { wav.delete(); return false }
        val cfg = currentConfig ?: run { wav.delete(); return false }
        val listener = currentListener ?: run { wav.delete(); return false }
        val arrivedAt = SystemClock.elapsedRealtime()
        val wavBytes = wav.length()
        val segMs = wavBytes / 32
        if (segMs < MIN_WAKE_SEG_MS) {
            wav.delete()
            return false
        }
        val peakDb = withContext(Dispatchers.IO) { wavPeakDbfs(wav) }
        if (peakDb < NOISE_GATE_DB) {
            wav.delete()
            return false
        }
        if (arrivedAt - wakeWindowStart > 60_000L) {
            wakeWindowStart = arrivedAt
            wakeUploadCount = 0
        }
        if (++wakeUploadCount > 30) {
            LogStore.log("wake", "唤醒上行配额用尽（每分钟 30 条），跳过")
            wav.delete()
            return false
        }
        val spoken = withContext(Dispatchers.IO) {
            SpeechInput.transcribeFile(wav, cfg.asrKey.trim(), cfg.dialect).getOrNull()
        }
        val asrDoneAt = SystemClock.elapsedRealtime()
        wav.delete()
        val text = spoken.orEmpty().trim()
        if (text.length <= 1) registerEmptyWake() else clearEmptyWake()

        val speakStart = wakeSpeechStartedAt
        val e2e = if (speakStart in 1 until arrivedAt) "${asrDoneAt - speakStart}ms" else "?"
        LogStore.log(
            "wake",
            "待机语音：${segMs}ms 峰${peakDb.toInt()}dBFS SNR ${"%.0f".format(segSnrDb)}dB " +
                "转写${asrDoneAt - arrivedAt}ms(总$e2e)「${text.ifBlank { "空" }}」",
        )

        val wasAlarming = CallTimer.alarmActive.value
        if (text.isNotBlank() && wasAlarming) {
            if (CallTimer.matchAlarmStop(text)) {
                CallTimer.stopAlarm()
                val reply = "好的，不响了"
                TtsPlayer.stop()
                TtsPlayer.speak(ctx, reply)
                listener.onAlarmStoppedByVoice(reply)
                return false
            }
            CallTimer.stopAlarm()
        }

        val ask = extractWakeRemainder(text)
        if (ask != null && !cfg.shouldSuppressHit()) {
            if (wasAlarming && listOf("退出", "关闭", "关掉", "停止", "别说了").any { text.contains(it) }) {
                return false
            }
            if (SystemClock.elapsedRealtime() - lastWakeHitAt < 8_000L) {
                if (ask.length >= 2) {
                    LogStore.log("wake", "云端迟到整句「$ask」：停单次聆听直达问答")
                    cancelOneShot()
                    lastWakeHitAt = SystemClock.elapsedRealtime()
                    if (RobotIntent.matchEndFast(ask)) {
                        TtsPlayer.stop()
                        resumeStandby()
                    } else {
                        listener.onTranscript(ask, fromDirectWake = true)
                    }
                }
                return true
            }
            lastWakeHitAt = SystemClock.elapsedRealtime()
            onWakeConfirmed(ask, listener)
            return true
        }
        return false
    }

    private suspend fun onWakeConfirmed(remainder: String, listener: Listener) {
        val ctx = currentContext ?: return
        val cfg = currentConfig ?: return
        val nowHit = SystemClock.elapsedRealtime()
        if (nowHit - lastWakeConfirmAt < 1_200L) {
            LogStore.log("wake", "唤醒重复回调（${nowHit - lastWakeConfirmAt}ms 内），忽略")
            return
        }
        lastWakeConfirmAt = nowHit
        clearEmptyWake()
        wakeSkipCount = 0

        ScreenAwake.wakeUp(ctx)
        TtsPlayer.stop()
        SparkAck.play(ctx)

        cfg.aiSettings?.let { ai ->
            runCatching { AiClient.prewarm(ai) }
            runCatching { warmWeatherCache(ctx, ai.amapKey) }
        }
        runCatching { EdgeTts.prewarm() }

        val canDirectCall = cfg.preferDirectCall && cfg.aiSettings != null &&
            (cfg.aiSettings.callEngine != "doubao" || cfg.aiSettings.duplexApiKey.isNotBlank())
        if (canDirectCall) {
            stopStandby()
            listener.onWakeCallRequested()
            return
        }

        if (remainder.length >= 2) {
            if (RobotIntent.matchEndFast(remainder)) {
                TtsPlayer.stop()
                resumeStandby()
            } else {
                listener.onTranscript(remainder, fromDirectWake = true)
            }
            return
        }

        listener.onWakeListening()
        wakePrepJob?.cancel()
        wakePrepJob = scope.launch {
            delay(SparkAck.DURATION_MS)
            startOneShotInternal(ctx, cfg, listener)
        }
    }

    private fun startOneShotInternal(ctx: Context, cfg: StandbyConfig, listener: Listener) {
        val key = cfg.asrKey.trim()
        stopStandby()
        if (key.isBlank()) {
            // 没配云端 ASR Key 时，退回系统 SpeechRecognizer，不再无声失败
            if (!SpeechInput.isAvailable(ctx)) {
                listener.onOneShotFailed("未配置语音识别 Key，且设备无系统语音识别")
                resumeStandby()
                return
            }
            synchronized(opLock) {
                systemOneShotJob?.cancel()
                _listeningOneShot.value = true
                systemOneShotJob = scope.launch(Dispatchers.Main) {
                    try {
                        val res = SpeechInput.listen(ctx, "我在听，请说话")
                        res.fold(
                            onSuccess = { spoken ->
                                val clean = spoken.trim()
                                _listeningOneShot.value = false
                                if (clean.isBlank() || isFragmentUtterance(clean)) {
                                    listener.onOneShotFailed(null)
                                } else if (RobotIntent.matchEndFast(clean)) {
                                    TtsPlayer.stop()
                                } else {
                                    listener.onTranscript(clean, fromDirectWake = false)
                                }
                            },
                            onFailure = { err ->
                                _listeningOneShot.value = false
                                listener.onOneShotFailed(SpeechInput.spokenHint(err))
                            },
                        )
                    } finally {
                        _listeningOneShot.value = false
                        systemOneShotJob = null
                        resumeStandby()
                    }
                }
            }
            return
        }

        val old: ContinuousConversation?
        synchronized(opLock) {
            old = oneShot.also { oneShot = null }
            _listeningOneShot.value = true
            lateinit var session: ContinuousConversation
            session = ContinuousConversation(
                ctx,
                config = ContinuousConversation.Config(
                    silenceEndMs = 1_300L,
                    adaptiveSilence = true,
                    minSpeechMs = 300L,
                    bargeInSensitivity = 0,
                    idleTimeoutMs = 15_000L,
                    maxUtteranceMs = 15_000L,
                    peakDropDb = 22f,
                ),
                listener = object : ContinuousConversation.Listener {
                    override fun onUtterance(wav: File, snrDb: Float) {
                        if (snrDb < 15f) {
                            LogStore.log("wake", "单次聆听太弱（${"%.0f".format(snrDb)}dB），继续等")
                            wav.delete()
                            return
                        }
                        session.stop()
                        scope.launch {
                            clearOneShotIfSame(session, stillListening = true)
                            val t0 = SystemClock.elapsedRealtime()
                            val res = withContext(Dispatchers.IO) {
                                SpeechInput.transcribeFile(wav, key, cfg.dialect)
                            }
                            val recMs = ((wav.length() - 44).coerceAtLeast(0)) / 32
                            wav.delete()
                            LogStore.log(
                                "wake",
                                "单次聆听：录音 ${recMs}ms 转写 ${SystemClock.elapsedRealtime() - t0}ms「${res.getOrNull()?.trim().orEmpty()}」",
                            )
                            res.fold(
                                onSuccess = { raw ->
                                    val clean = raw.trim()
                                    if (isFragmentUtterance(clean)) {
                                        LogStore.log("wake", "单次聆听：碎片词「$clean」，继续听")
                                        startOneShotInternal(ctx, cfg, listener)
                                    } else {
                                        _listeningOneShot.value = false
                                        if (RobotIntent.matchEndFast(clean)) {
                                            TtsPlayer.stop()
                                        } else {
                                            listener.onTranscript(clean, fromDirectWake = false)
                                        }
                                        resumeStandby()
                                    }
                                },
                                onFailure = { err ->
                                    _listeningOneShot.value = false
                                    listener.onOneShotFailed(SpeechInput.spokenHint(err))
                                    resumeStandby()
                                },
                            )
                        }
                    }

                    override fun onBargeIn() {}

                    override fun onAutoTimeout() {
                        session.stop()
                        scope.launch {
                            clearOneShotIfSame(session, stillListening = false)
                            listener.onOneShotFailed(null)
                            resumeStandby()
                        }
                    }

                    override fun onError(message: String) {
                        session.stop()
                        scope.launch {
                            val matched = clearOneShotIfSame(session, stillListening = false)
                            if (matched) {
                                listener.onOneShotFailed("没听清：$message")
                                resumeStandby()
                            }
                        }
                    }
                },
            )
            session.setTtsActive(TtsPlayer.state.value is TtsPlayer.State.Speaking)
            session.setTtsEchoRisk(TtsPlayer.echoRisk.value)
            oneShot = session
            session.start()
        }
        old?.stop()
    }

    private fun clearOneShotIfSame(session: ContinuousConversation, stillListening: Boolean): Boolean {
        return synchronized(opLock) {
            if (oneShot === session) {
                oneShot = null
                if (!stillListening) _listeningOneShot.value = false
                true
            } else false
        }
    }

    private fun registerEmptyWake() {
        emptyWakeStreak++
        if (emptyWakeStreak < 2) return
        emptyWakeStreak = 0
        wakeOnsetBoostDb = EMPTY_STREAK_BOOST_DB
        wakeOnsetBoostUntil = SystemClock.elapsedRealtime() + EMPTY_PENALTY_DURATION_MS
        wakeSkipCount = 0
        standby?.boostOnset(EMPTY_STREAK_BOOST_DB, EMPTY_PENALTY_DURATION_MS)
    }

    private fun clearEmptyWake() {
        if (emptyWakeStreak == 0 && wakeOnsetBoostDb == 0f) return
        emptyWakeStreak = 0
        if (wakeOnsetBoostDb != 0f) {
            wakeOnsetBoostDb = 0f
            wakeOnsetBoostUntil = 0L
            standby?.boostOnset(0f, 0L)
            LogStore.log("wake", "恢复正常唤醒灵敏度（识别到正常文本）")
        }
    }

    private fun warmWeatherCache(ctx: Context, amapKey: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWeatherWarmAt < 3 * 60 * 1000L) return
        lastWeatherWarmAt = now
        scope.launch(Dispatchers.IO) {
            runCatching {
                val p = LocationContext.current(ctx) ?: LocationContext.fresh(ctx, timeoutMs = 2_500)
                if (p != null) {
                    WeatherClient.prewarm(p.lon, p.lat, amapKey = amapKey, cityHint = p.description)
                }
            }
        }
    }

    private fun wavPeakDbfs(wav: File): Float = runCatching {
        val bytes = wav.readBytes()
        if (bytes.size <= 46) return@runCatching -100f
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

    /** 判定极短无意义碎片词（唤醒后继续等，别打断用户思考） */
    internal fun isFragmentUtterance(clean: String): Boolean {
        val t = clean.trim()
        return t.isBlank() || t.length <= 1 || t in FRAGMENT_WORDS
    }

    /**
     * 唤醒词匹配（模糊容错）：匹配前 8 字内的「小/晓/筱/肖 + 近音字」，消费连续叠字后返回余下提问。
     * 未命中返回 null。
     */
    internal fun extractWakeRemainder(text: String): String? {
        val s = text.trim()
        if (s.length < 2) return null
        val head = s.take(8)
        var hitAt = -1
        for (i in 0 until head.length - 1) {
            if (WAKE_FIRST_CHARS.contains(head[i]) && WAKE_SECOND_CHARS.contains(head[i + 1])) {
                hitAt = i
                break
            }
        }
        if (hitAt < 0) return null
        var rest = s.substring(hitAt + 2)
        while (rest.length >= 2 && WAKE_FIRST_CHARS.contains(rest[0]) && WAKE_SECOND_CHARS.contains(rest[1])) {
            rest = rest.substring(2)
        }
        return rest.trimStart(' ', '，', '。', '！', '？', '、', ',', '.', '!', '?')
    }
}
