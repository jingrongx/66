package com.lvyoubao.app.ui.common

import android.content.Context
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.HotwordCorrector
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
import kotlin.math.sqrt

/**
 * 全应用统一的「按住说话 / 上滑取消 / 实时声波」控制器（普通 / 大字 / 灵动 / 通话共用）。
 *
 * 统一封装：
 * 1. 按下 0ms 立即进入 `pressing = true` 并弹出全屏语音动效；同步停闹钟、停 TTS、停待机唤醒；
 * 2. 有 `asrKey` 且未显式选系统识别时走后台 IO 线程开启 `MediaRecorder` + 60ms 真实声压采集；
 *    无 `asrKey` 或选系统识别时也走同一套按住体验（`SpeechInput.startSystemListening` + 真实 Rms 声波 + 松手收听）；
 * 3. 手指上滑切换 `cancelling = true`；取消松手走 [cancelTalk] 立即异步释麦并静音退出；
 * 4. 正常松手 [release] 停止录音/收音 -> 转写 + 热词纠错 -> 任何分支确保调用 `WakeHub.resumeStandby()` 恢复待机。
 */
class HoldTalkController(private val scope: CoroutineScope) {

    data class State(
        val recording: SpeechInput.Recording? = null,
        val listening: Boolean = false,
        val recordTick: Int = 0,
        /** 手指是否正按住说话按钮（按住期间弹出全屏声波 HUD，兼容录音冷启与系统识别） */
        val pressing: Boolean = false,
        /** 手指是否滑入取消区域 */
        val cancelling: Boolean = false,
        /** 归一化真实麦克风音量 0f..1f（驱动全屏实时声纹动效） */
        val amplitude: Float = 0f,
    ) {
        val isRecording: Boolean get() = recording != null || (pressing && !listening)
        val isBusy: Boolean get() = pressing || recording != null || listening
    }

    private enum class PendingAction { NONE, RELEASE_EARLY, CANCEL }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var ampJob: Job? = null
    private var startJob: Job? = null
    private var systemSession: SpeechInput.SystemSession? = null
    private var sessionToken: Long = 0L
    private var pressStartedAt: Long = 0L
    @Volatile
    private var pendingAction: PendingAction = PendingAction.NONE

    private var activePrepareCorpus: (suspend () -> Unit)? = null
    private var activeOnTranscript: (suspend (String) -> Unit)? = null
    private var activeOnError: ((String, String) -> Unit)? = null

    /** 更新手指是否滑入上方取消热区 */
    fun setCancelling(cancelling: Boolean) {
        if (!_state.value.pressing) return
        _state.update { if (it.cancelling == cancelling) it else it.copy(cancelling = cancelling) }
    }

    /** 按下开始收集语音（云端录音与系统识别均统一按「按住说话 / 松手完成 / 上滑取消」处理） */
    fun begin(
        context: Context,
        settings: AiSettings,
        prompt: String = "请说话",
        onBeforeMic: () -> Unit = {},
        onRequestPermission: () -> Unit,
        onError: (userVisibleMsg: String, spokenMsg: String) -> Unit,
        onSystemTranscript: suspend (String) -> Unit,
    ) {
        if (_state.value.isBusy) return
        onBeforeMic()
        CallTimer.stopAlarm()
        TtsPlayer.stop()
        WakeHub.cancelOneShot()
        WakeHub.stopStandby()

        val preferSystem = settings.asrKey.isBlank() || settings.voiceAsrModel == "system_asr"
        if (!preferSystem && !SpeechInput.hasRecordPermission(context)) {
            WakeHub.resumeStandby()
            onRequestPermission()
            return
        }
        if (preferSystem && !SpeechInput.isAvailable(context)) {
            val guide = if (settings.asrKey.isBlank()) {
                "这台手机没有系统语音识别，可在设置里填「语音识别 Key」（免费）"
            } else {
                "这台手机没有系统语音识别"
            }
            onError(guide, guide)
            WakeHub.resumeStandby()
            return
        }
        if (preferSystem && !SpeechInput.hasRecordPermission(context)) {
            WakeHub.resumeStandby()
            onRequestPermission()
            return
        }

        val token = ++sessionToken
        pendingAction = PendingAction.NONE
        pressStartedAt = System.currentTimeMillis()
        activeOnError = onError
        activeOnTranscript = onSystemTranscript
        _state.value = State(
            recording = null,
            listening = false,
            recordTick = 0,
            pressing = true,
            cancelling = false,
            amplitude = 0.08f,
        )

        if (!preferSystem) {
            startJob?.cancel()
            startJob = scope.launch {
                val res = withContext(Dispatchers.IO) { SpeechInput.startRecording(context) }
                if (token != sessionToken || pendingAction == PendingAction.CANCEL) {
                    res.onSuccess { rec -> withContext(Dispatchers.IO) { SpeechInput.cancelRecording(rec) } }
                    if (token == sessionToken) {
                        _state.value = State()
                        WakeHub.resumeStandby()
                    }
                    return@launch
                }
                if (pendingAction == PendingAction.RELEASE_EARLY) {
                    res.onSuccess { rec ->
                        val heldMs = System.currentTimeMillis() - pressStartedAt
                        if (heldMs < 450L) {
                            withContext(Dispatchers.IO) { SpeechInput.cancelRecording(rec) }
                            _state.value = State()
                            onError("按住说话时间太短，请按住多说一会", "按住说话时间太短，请再试一次")
                            WakeHub.resumeStandby()
                        } else {
                            startLoopsForRecording(token, rec)
                            performCloudTranscribe(settings, rec)
                        }
                    }.onFailure { err ->
                        _state.value = State()
                        val msg = err.message ?: "录音启动失败，请再试一次"
                        onError(msg, "录音启动失败，请再试一次")
                        WakeHub.resumeStandby()
                    }
                    return@launch
                }
                res.onSuccess { rec ->
                    startLoopsForRecording(token, rec)
                }.onFailure { err ->
                    if (token == sessionToken) {
                        _state.value = State()
                        val msg = err.message ?: "录音启动失败，请再试一次"
                        onError(msg, "录音启动失败，请再试一次")
                        WakeHub.resumeStandby()
                    }
                }
            }
            return
        }

        // 系统语音识别同样纳入受控按住周期：按下即听并实时回传声压，松手调用 stopListening() 收音
        startJob?.cancel()
        startJob = scope.launch(Dispatchers.Main) {
            if (token != sessionToken || pendingAction == PendingAction.CANCEL) {
                if (token == sessionToken) {
                    _state.value = State()
                    WakeHub.resumeStandby()
                }
                return@launch
            }
            var smoothedAmp = 0.08f
            val session = SpeechInput.startSystemListening(
                context = context,
                prompt = prompt,
                onRmsDb = { rmsDb ->
                    if (token != sessionToken) return@startSystemListening
                    // SpeechRecognizer 的 rmsdB 通常在 -2f..10f 波动，归一化成 0f..1f 真实驱动声波
                    val target = ((rmsDb + 2f) / 12f).coerceIn(0.06f, 1f)
                    smoothedAmp = smoothedAmp * 0.55f + target * 0.45f
                    _state.update { it.copy(amplitude = smoothedAmp) }
                },
                onResult = { result ->
                    if (token != sessionToken) return@startSystemListening
                    timerJob?.cancel()
                    timerJob = null
                    systemSession = null
                    scope.launch {
                        try {
                            result.fold(
                                onSuccess = { raw ->
                                    val clean = HotwordCorrector.correct(raw.trim())
                                    if (clean.isNotBlank()) onSystemTranscript(clean)
                                    else onError("没听清楚，请再试一次", "没听清楚，请再试一次")
                                },
                                onFailure = { err ->
                                    val hint = SpeechInput.spokenHint(err)
                                    onError(err.message ?: hint, hint)
                                },
                            )
                        } finally {
                            if (token == sessionToken) _state.value = State()
                            WakeHub.resumeStandby()
                        }
                    }
                },
            )
            if (session == null) {
                if (token == sessionToken && _state.value.pressing) {
                    _state.value = State()
                    WakeHub.resumeStandby()
                }
                return@launch
            }
            systemSession = session
            startTimerLoop(token)
            if (pendingAction == PendingAction.CANCEL) {
                systemSession?.cancel()
                systemSession = null
                _state.value = State()
                WakeHub.resumeStandby()
            } else if (pendingAction == PendingAction.RELEASE_EARLY) {
                triggerSystemRelease(token)
            }
        }
    }

    private fun startLoopsForRecording(token: Long, rec: SpeechInput.Recording) {
        if (token != sessionToken) return
        _state.update { it.copy(recording = rec, pressing = true, listening = false, recordTick = 0) }
        startTimerLoop(token)
        ampJob?.cancel()
        ampJob = scope.launch(Dispatchers.IO) {
            var smoothed = 0.08f
            while (token == sessionToken && _state.value.recording === rec && _state.value.pressing) {
                val raw = runCatching { rec.recorder.maxAmplitude }.getOrDefault(0)
                // MediaRecorder.maxAmplitude 范围 0..32767，sqrt 压缩动态范围让人声更直观跳动
                val norm = sqrt((raw / 32767f).coerceIn(0f, 1f)).coerceIn(0.06f, 1f)
                smoothed = smoothed * 0.55f + norm * 0.45f
                _state.update { if (it.pressing) it.copy(amplitude = smoothed) else it }
                delay(60L)
            }
        }
    }

    private fun startTimerLoop(token: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (token == sessionToken && _state.value.pressing) {
                delay(1_000L)
                _state.update { if (it.pressing) it.copy(recordTick = it.recordTick + 1) else it }
            }
        }
    }

    /** 手指正常松手：停止录音或结束系统聆听并回调结果 */
    fun release(
        settings: AiSettings,
        prepareCorpus: suspend () -> Unit = {},
        onTranscript: suspend (String) -> Unit,
        onError: (userVisibleMsg: String, spokenMsg: String) -> Unit,
    ) {
        if (!_state.value.pressing) return
        val token = sessionToken
        activePrepareCorpus = prepareCorpus
        activeOnTranscript = onTranscript
        activeOnError = onError

        val rec = _state.value.recording
        val sys = systemSession
        val heldMs = System.currentTimeMillis() - pressStartedAt

        if (rec == null && sys == null) {
            // 录音/识别硬件还在冷启交接期，标记为提前松手，交由 startJob 接续判断处理
            pendingAction = PendingAction.RELEASE_EARLY
            return
        }

        if (sys != null) {
            if (heldMs < 450L) {
                // 按住不足 0.45 秒：直接礼貌退出而不是等待系统报超长错误
                sessionToken++
                timerJob?.cancel()
                timerJob = null
                systemSession = null
                sys.cancel()
                _state.value = State()
                onError("按住说话时间太短，请按住多说一会", "按住说话时间太短，请再试一次")
                WakeHub.resumeStandby()
                return
            }
            triggerSystemRelease(token)
            return
        }

        if (rec != null) {
            if (heldMs < 450L) {
                sessionToken++
                timerJob?.cancel()
                ampJob?.cancel()
                _state.value = State()
                scope.launch(Dispatchers.IO) {
                    SpeechInput.cancelRecording(rec)
                }
                onError("按住说话时间太短，请按住多说一会", "按住说话时间太短，请再试一次")
                WakeHub.resumeStandby()
                return
            }
            performCloudTranscribe(settings, rec)
        }
    }

    private fun triggerSystemRelease(token: Long) {
        val sys = systemSession ?: return
        timerJob?.cancel()
        timerJob = null
        _state.update { it.copy(pressing = false, cancelling = false, listening = true, amplitude = 0f) }
        sys.stop()
        // 万一回调未触发的兜底由 SystemSession 4.5s 超时保护保证
        if (token != sessionToken) sys.cancel()
    }

    private fun performCloudTranscribe(settings: AiSettings, rec: SpeechInput.Recording) {
        val token = sessionToken
        timerJob?.cancel()
        timerJob = null
        ampJob?.cancel()
        ampJob = null
        _state.value = State(recording = null, listening = true, pressing = false, cancelling = false, amplitude = 0f)

        scope.launch {
            try {
                val asrKey = settings.asrKey.trim()
                if (asrKey.isBlank()) {
                    withContext(Dispatchers.IO) { SpeechInput.cancelRecording(rec) }
                    val msg = "请先在设置中配置语音识别 Key（硅基流动免费）"
                    activeOnError?.invoke(msg, msg)
                    return@launch
                }
                val result = SpeechInput.stopAndTranscribe(rec, asrKey, settings.dialectMode)
                if (token != sessionToken) return@launch
                result.fold(
                    onSuccess = { raw ->
                        activePrepareCorpus?.invoke()
                        val clean = HotwordCorrector.correct(raw.trim())
                        if (clean.isNotBlank()) {
                            activeOnTranscript?.invoke(clean)
                        } else {
                            activeOnError?.invoke("没听清楚，请再试一次", "没听清楚，请再试一次")
                        }
                    },
                    onFailure = { err ->
                        val spoken = SpeechInput.spokenHint(err)
                        activeOnError?.invoke(err.message ?: spoken, spoken)
                    },
                )
            } finally {
                if (token == sessionToken) _state.value = State()
                WakeHub.resumeStandby()
            }
        }
    }

    /** 手指上滑到取消区域后松手：静音取消，删掉音频，直接恢复待机 */
    fun cancelTalk() {
        pendingAction = PendingAction.CANCEL
        sessionToken++
        timerJob?.cancel()
        timerJob = null
        ampJob?.cancel()
        ampJob = null
        startJob?.cancel()
        startJob = null

        val rec = _state.value.recording
        val sys = systemSession
        systemSession = null
        _state.value = State()

        sys?.cancel()
        if (rec != null) {
            scope.launch(Dispatchers.IO) {
                SpeechInput.cancelRecording(rec)
            }
        }
        WakeHub.resumeStandby()
    }

    /** 权限被拒后回调恢复待机 */
    fun onPermissionDenied() {
        WakeHub.resumeStandby()
    }

    /** 宿主销毁/退出时确保不遗留录音或识别句柄 */
    fun cancel() {
        pendingAction = PendingAction.CANCEL
        sessionToken++
        timerJob?.cancel()
        timerJob = null
        ampJob?.cancel()
        ampJob = null
        startJob?.cancel()
        startJob = null
        val rec = _state.value.recording
        val sys = systemSession
        systemSession = null
        _state.value = State()
        sys?.cancel()
        if (rec != null) {
            scope.launch(Dispatchers.IO) {
                SpeechInput.cancelRecording(rec)
            }
        }
    }
}
