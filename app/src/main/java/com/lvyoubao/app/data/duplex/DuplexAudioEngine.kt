package com.lvyoubao.app.data.duplex

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.lvyoubao.app.data.CallAudioRouter
import com.lvyoubao.app.data.CallAudioRouter.DuplexOutputMode
import com.lvyoubao.app.data.DeviceProfile
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.ui.common.RnNoise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全双工语音的本地音频引擎：
 * - 上行：AudioRecord → 100Hz 高通滤波 + RNNoise 48kHz 神经降噪 + 系统 AEC/NS →
 *   统一降采样到 16kHz / mono / PCM16 小端，按 20ms（640 字节）分片回调；
 * - 下行：
 *   - 原生安卓及听筒模式：24kHz / mono / PCM16 小端，走 USAGE_VOICE_COMMUNICATION（接入系统硬件 AEC 参考），
 *     并通过 AudioFocus + setCommunicationDevice + preferredDevice 控制听筒/免提切换；
 *   - 鸿蒙 NEXT 卓易通容器免提模式：通过 [CallAudioRouter.DuplexOutputMode.MEDIA_SPEAKER_BYPASS]
 *     自动走 MODE_NORMAL + USAGE_MEDIA（48kHz 线性插值上采样）+ MIC 采集，彻底绕开容器 voip 通路听筒锁死；
 *     用户在卓易通中切换听筒时再热切回 VOICE_COMMUNICATION，切换免提时再热切回 MEDIA_SPEAKER_BYPASS；
 * - 插话：收到打断时 [flushPlayback] 立刻清空播放队列，实现"按住即停"。
 */
internal class DuplexAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPcmFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {
    companion object {
        private const val TAG = "DuplexAudio"
        private const val TARGET_RATE = 16000
        private const val PLAYBACK_SRC_RATE = 24000
        private const val PLAYBACK_MEDIA_RATE = 48000
        private const val FRAME_MS = 20
        // 100Hz 一阶高通系数：R/(R+dt)，压掉空调/手持震动等低频轰鸣
        private const val HP_ALPHA_48K = 0.9871f
        private const val HP_ALPHA_16K = 0.9622f

        private val _callAudioActive = MutableStateFlow(false)

        /** 当前是否有全双工通话占用通话音频路由（STREAM_VOICE_CALL），供主界面同步切换音量键目标流 */
        val isCallAudioActive: StateFlow<Boolean> = _callAudioActive.asStateFlow()

        /**
         * 进入外放通话时计算需要补齐的通话音量：
         * 仅当当前通话音量仍停在系统最低底档（例如外放默认 1/6），且当前媒体音量高于 25% 时，
         * 按媒体音量比例同步到合理档位；其余情况保留用户已调好的通话音量偏好。
         */
        internal fun computeSyncedVoiceVolume(
            curVoice: Int,
            minVoice: Int,
            maxVoice: Int,
            curMusic: Int,
            maxMusic: Int,
        ): Int? {
            if (maxVoice <= minVoice || maxMusic <= 0) return null
            val lowFloor = if (minVoice <= 0) 1 else minVoice
            if (curVoice > lowFloor) return null
            val musicRatio = curMusic.toFloat() / maxMusic.toFloat()
            if (musicRatio <= 0.25f) return null
            val target = (musicRatio * maxVoice).roundToInt().coerceIn(minVoice, maxVoice)
            return target.takeIf { it > curVoice }
        }

        /** 解析实际是否启用免提：用户界面手动切换优先；否则若插着耳机让位给耳机，无耳机跟随设置 */
        internal fun resolveSpeakerphoneTarget(
            desired: Boolean,
            hasExternal: Boolean,
            userOverrode: Boolean,
        ): Boolean = when {
            userOverrode -> desired
            hasExternal -> false
            else -> desired
        }

        /** 24kHz PCM16LE 线性插值上采样到 48kHz，跨音频块保持 [prevCarry] 连续无台阶 */
        internal fun upsample24kTo48k(src: ByteArray, prevCarry: Short): Pair<ByteArray, Short> {
            val sampleCount = src.size / 2
            if (sampleCount <= 0) return ByteArray(0) to prevCarry
            val out = ByteArray(sampleCount * 4)
            var carry = prevCarry.toInt()
            var o = 0
            for (i in 0 until sampleCount) {
                val idx = i * 2
                val curr = ((src[idx + 1].toInt() shl 8) or (src[idx].toInt() and 0xFF)).toShort().toInt()
                val mid = carry + ((curr - carry) / 2)
                out[o] = (mid and 0xFF).toByte()
                out[o + 1] = ((mid shr 8) and 0xFF).toByte()
                out[o + 2] = (curr and 0xFF).toByte()
                out[o + 3] = ((curr shr 8) and 0xFF).toByte()
                o += 4
                carry = curr
            }
            return out to carry.toShort()
        }
    }

    /** 一组采集配置：采样率 × 采集源 */
    private data class CaptureCfg(val rate: Int, val source: Int, val label: String)

    private data class PlaybackTrack(
        val track: AudioTrack,
        val sampleRate: Int,
        val upsampleTo48k: Boolean,
    )

    private data class RoutePlan(
        val targetSpeaker: Boolean,
        val hasExternal: Boolean,
        val outputMode: DuplexOutputMode,
    )

    private val voipLadder = listOf(
        CaptureCfg(48000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "48k/voip"),
        CaptureCfg(48000, MediaRecorder.AudioSource.MIC, "48k/mic"),
        CaptureCfg(16000, MediaRecorder.AudioSource.MIC, "16k/mic"),
        CaptureCfg(16000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "16k/voip"),
    )

    // 卓易通免提专用采集顺序：避开 VOICE_COMMUNICATION 音源，防止容器触发通话会话强锁听筒
    private val mediaSpeakerLadder = listOf(
        CaptureCfg(48000, MediaRecorder.AudioSource.MIC, "48k/mic"),
        CaptureCfg(48000, MediaRecorder.AudioSource.VOICE_RECOGNITION, "48k/asr"),
        CaptureCfg(16000, MediaRecorder.AudioSource.MIC, "16k/mic"),
        CaptureCfg(16000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "16k/voip"),
    )

    private var cfgIndex = 0
    private var saturatedSeconds = 0
    private var needNextCfg = false

    private val appContext = context.applicationContext
    private val isZhuoyi = DeviceProfile.isZhuoyiContainer(appContext)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val focusSession = CallAudioRouter.AudioFocusSession()
    private val switchMutex = Mutex()

    private var prevAudioMode: Int? = null
    private var prevSpeakerphoneOn: Boolean? = null

    @Volatile private var desiredSpeakerphone = true
    @Volatile private var userOverrodeSpeakerphone = false
    @Volatile private var activeOutputMode: DuplexOutputMode? = null
    @Volatile private var stopped = false
    private var speakerVolumeSynced = false

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var denoiseSession: RnNoise.Session? = null
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var switchJob: Job? = null
    @Volatile private var capturing = false
    @Volatile private var captureGen = 0

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private val playLock = Any()
    private var activePlayback: PlaybackTrack? = null
    private var playCarrySample: Short = 0

    private fun logDuplex(msg: String) {
        Log.i(TAG, msg)
        LogStore.log("duplex", msg)
    }

    private fun currentLadder(): List<CaptureCfg> =
        if (activeOutputMode == DuplexOutputMode.MEDIA_SPEAKER_BYPASS) mediaSpeakerLadder else voipLadder

    private fun computeDesiredRoute(): RoutePlan {
        val am = audioManager
        val hasExternal = am?.let { CallAudioRouter.hasExternalAudioOutput(it) } ?: false
        val targetSpeaker = resolveSpeakerphoneTarget(
            desired = desiredSpeakerphone,
            hasExternal = hasExternal,
            userOverrode = userOverrodeSpeakerphone,
        )
        val mode = CallAudioRouter.resolveDuplexOutputMode(
            isZhuoyi = isZhuoyi,
            targetSpeaker = targetSpeaker,
        )
        return RoutePlan(targetSpeaker = targetSpeaker, hasExternal = hasExternal, outputMode = mode)
    }

    /** 启动音频前注入默认输出方向（true=免提外放，false=听筒） */
    fun setInitialSpeakerphone(enabled: Boolean) {
        desiredSpeakerphone = enabled
        userOverrodeSpeakerphone = false
        speakerVolumeSynced = false
    }

    /** 通话中手动切换免提/听筒并立即应用到系统音频路由 */
    fun setSpeakerphone(enabled: Boolean) {
        desiredSpeakerphone = enabled
        userOverrodeSpeakerphone = true
        if (stopped) return
        val plan = computeDesiredRoute()
        val prevMode = activeOutputMode

        if (prevMode != null && prevMode != plan.outputMode) {
            // 卓易通等容器下要在「音乐播放通道(免提)」与「voip通话通道(听筒)」之间热切换：
            // 先串行停旧轨+旧录音，等容器释放 voip HAL 后按新通道重建，保持 WebSocket 会话不断
            logDuplex("触发跨通道热切换：${if (enabled) "免提外放" else "听筒"} ($prevMode → ${plan.outputMode}, zhuoyi=$isZhuoyi)")
            switchJob?.cancel()
            switchJob = scope.launch(Dispatchers.Main) {
                hotSwitchOutputMode()
            }
            return
        }

        val am = audioManager ?: return
        applyRoutePlan(am, plan)
        val currentTrack = synchronized(playLock) { activePlayback?.track }
        if (plan.outputMode == DuplexOutputMode.VOICE_COMMUNICATION) {
            CallAudioRouter.applyPreferredDevice(
                track = currentTrack,
                am = am,
                targetSpeaker = plan.targetSpeaker,
                hasExternal = plan.hasExternal,
                userOverrode = userOverrodeSpeakerphone,
            )
            scheduleRouteCheck(plan.targetSpeaker)
        } else {
            CallAudioRouter.applyPreferredDevice(
                track = currentTrack,
                am = am,
                targetSpeaker = true,
                hasExternal = plan.hasExternal,
                userOverrode = userOverrodeSpeakerphone,
            )
        }
        logDuplex(
            "通话声道切换：${if (enabled) "免提外放" else "听筒"}（mode=${am.mode}, outputMode=${plan.outputMode}, speaker=${CallAudioRouter.isSpeakerOutputActive(am)}, zhuoyi=$isZhuoyi）",
        )
    }

    private suspend fun hotSwitchOutputMode() {
        switchMutex.withLock {
            if (stopped) return
            val plan = computeDesiredRoute()
            if (plan.outputMode == activeOutputMode) return
            val am = audioManager ?: return

            // 1. 停旧播放轨与旧录音，阻止旧 voip 通道继续占用宿主路由
            releasePlaybackTrack()
            stopCaptureInternal()

            // 2. 清理旧的通信路由和音频焦点，留出约 180ms 让容器彻底注销旧 AudioRenderSink
            focusSession.abandon()
            CallAudioRouter.clearCommunicationRoute(am, restoreSpeaker = false)
            if (plan.outputMode == DuplexOutputMode.MEDIA_SPEAKER_BYPASS) {
                runCatching { am.mode = AudioManager.MODE_NORMAL }
            }
            delay(180)
            if (stopped) return

            // 3. 重新读取最新意图（防止切换期间用户又点了一次）
            val latest = computeDesiredRoute()
            applyRoutePlan(am, latest)
            openAndMountPlaybackTrack(latest)
            cfgIndex = 0
            saturatedSeconds = 0
            startCaptureWithRetry()
            if (latest.outputMode == DuplexOutputMode.VOICE_COMMUNICATION) {
                scheduleRouteCheck(latest.targetSpeaker)
            }
            logDuplex(
                "跨通道热切换完成：mode=${am.mode}, outputMode=${latest.outputMode}, speaker=${CallAudioRouter.isSpeakerOutputActive(am)}",
            )
        }
    }

    private fun scheduleRouteCheck(expectSpeaker: Boolean) {
        scope.launch(Dispatchers.Main) {
            delay(180)
            if (stopped) return@launch
            val am = audioManager ?: return@launch
            val plan = computeDesiredRoute()
            if (plan.outputMode != DuplexOutputMode.VOICE_COMMUNICATION) return@launch
            if (plan.targetSpeaker != expectSpeaker) return@launch
            if (CallAudioRouter.isSpeakerOutputActive(am) != expectSpeaker) {
                val detail = CallAudioRouter.applyCommunicationRoute(
                    am = am,
                    enableSpeaker = expectSpeaker,
                    hasExternal = plan.hasExternal,
                    userOverrode = userOverrodeSpeakerphone,
                )
                val track = synchronized(playLock) { activePlayback?.track }
                CallAudioRouter.applyPreferredDevice(
                    track = track,
                    am = am,
                    targetSpeaker = expectSpeaker,
                    hasExternal = plan.hasExternal,
                    userOverrode = userOverrodeSpeakerphone,
                )
                logDuplex("路由二次校准：expect=$expectSpeaker, $detail, actual=${CallAudioRouter.isSpeakerOutputActive(am)}")
            }
            if (expectSpeaker) {
                syncVoiceVolumeIfNeeded()
            }
        }
    }

    private fun applyRoutePlan(am: AudioManager, plan: RoutePlan) {
        if (prevAudioMode == null) prevAudioMode = am.mode
        if (prevSpeakerphoneOn == null) prevSpeakerphoneOn = CallAudioRouter.isSpeakerOutputActive(am)
        activeOutputMode = plan.outputMode

        when (plan.outputMode) {
            DuplexOutputMode.MEDIA_SPEAKER_BYPASS -> {
                CallAudioRouter.clearCommunicationRoute(am, restoreSpeaker = false)
                if (am.mode != AudioManager.MODE_NORMAL) {
                    runCatching { am.mode = AudioManager.MODE_NORMAL }
                }
                focusSession.acquire(am, useVoiceCall = false)
                _callAudioActive.value = false
            }
            DuplexOutputMode.VOICE_COMMUNICATION -> {
                focusSession.acquire(am, useVoiceCall = true)
                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
                }
                val detail = CallAudioRouter.applyCommunicationRoute(
                    am = am,
                    enableSpeaker = plan.targetSpeaker,
                    hasExternal = plan.hasExternal,
                    userOverrode = userOverrodeSpeakerphone,
                )
                _callAudioActive.value = true
                logDuplex("应用通话路由：$detail（targetSpeaker=${plan.targetSpeaker}, ext=${plan.hasExternal}, zhuoyi=$isZhuoyi）")
            }
        }
    }

    /** 确保音频路由与焦点处于当前期望状态 */
    private fun ensureAudioRouting(): RoutePlan? {
        val am = audioManager ?: return null
        val plan = computeDesiredRoute()
        applyRoutePlan(am, plan)
        return plan
    }

    /** 切到免提外放后同步一次过低的通话外放音量，避免系统默认 1 格听不清 */
    private fun syncVoiceVolumeIfNeeded() {
        if (speakerVolumeSynced) return
        if (activeOutputMode != DuplexOutputMode.VOICE_COMMUNICATION) return
        val am = audioManager ?: return
        val isSpeakerActive = CallAudioRouter.isSpeakerOutputActive(am)
        if (!isSpeakerActive) return
        speakerVolumeSynced = true
        val minVoice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(1)
        } else {
            1
        }
        val maxVoice = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(0)
        val curVoice = runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(0)
        val maxMusic = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val curMusic = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val target = computeSyncedVoiceVolume(curVoice, minVoice, maxVoice, curMusic, maxMusic)
        if (target != null) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0) }
            logDuplex("通话音量初始化同步：$curVoice → $target/$maxVoice（对齐媒体 $curMusic/$maxMusic）")
        } else {
            Log.d(TAG, "当前通话音量=$curVoice/$maxVoice 媒体=$curMusic/$maxMusic speaker=$isSpeakerActive")
        }
    }

    private fun restoreAudioRouting() {
        _callAudioActive.value = false
        activeOutputMode = null
        speakerVolumeSynced = false
        userOverrodeSpeakerphone = false
        focusSession.abandon()
        val am = audioManager ?: return
        CallAudioRouter.clearCommunicationRoute(am, restoreSpeaker = prevSpeakerphoneOn)
        prevSpeakerphoneOn = null
        // 还原系统音频模式，别把整机留在通信模式（会影响媒体音量/铃声路由）
        prevAudioMode?.let { mode ->
            runCatching { am.mode = mode }
            prevAudioMode = null
        }
    }

    /** 将处理完（48k 或 16k）的 ShortArray 帧转为 16k 小端 PCM ByteArray */
    private fun to16kPcmBytes(src: ShortArray, factor: Int): ByteArray {
        val f = factor.coerceAtLeast(1)
        val outSamples = src.size / f
        val out = ByteArray(outSamples * 2)
        var o = 0
        var i = 0
        while (i + f <= src.size) {
            val v = if (f == 1) {
                src[i].toInt()
            } else {
                var sum = 0
                var k = 0
                while (k < f) {
                    sum += src[i + k].toInt()
                    k++
                }
                (sum / f).coerceIn(-32768, 32767)
            }
            out[o] = (v and 0xff).toByte()
            out[o + 1] = ((v shr 8) and 0xff).toByte()
            o += 2
            i += f
        }
        return out
    }

    private suspend fun startCaptureWithRetry(): Boolean {
        repeat(3) { attempt ->
            if (stopped) return false
            if (attempt > 0) delay(100)
            if (startCapture()) return true
        }
        return false
    }

    /** 开始采集。调用前需已拿到 RECORD_AUDIO 权限。 */
    fun startCapture(): Boolean {
        if (capturing) return true
        if (stopped) return false
        if (activeOutputMode == null) ensureAudioRouting()
        val activeLadder = currentLadder()
        val cfg = activeLadder[cfgIndex.coerceIn(0, activeLadder.lastIndex)]
        val frameSamples = cfg.rate * FRAME_MS / 1000
        val frameBytes = frameSamples * 2
        val minBuf = AudioRecord.getMinBufferSize(
            cfg.rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "配置 ${cfg.label} 不支持（minBuf=$minBuf），换下一组")
            return advanceCfg()
        }
        val rec = try {
            AudioRecord(
                cfg.source,
                cfg.rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, frameBytes * 4),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "录音权限被拒绝", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "配置 ${cfg.label} 初始化失败 state=${rec.state}，换下一组")
            rec.release()
            return advanceCfg()
        }
        recorder = rec
        // 显式打开系统 AEC 与 NS（切到 MIC 后必须自己开，压掉稳态噪声与扬声器回声）
        val aecAvail = AcousticEchoCanceler.isAvailable()
        if (aecAvail) {
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()
            runCatching { aec?.enabled = true }
        }
        val nsAvail = NoiseSuppressor.isAvailable()
        if (nsAvail) {
            ns = runCatching { NoiseSuppressor.create(rec.audioSessionId) }.getOrNull()
            runCatching { ns?.enabled = true }
        }
        denoiseSession?.close()
        denoiseSession = RnNoise.createSession()
        // AEC 诊断：一次打全采集源 / 系统 mode / 实际外放路由 / AEC 创建与启用状态。
        // 硬件 AEC（高通 Fluence）只有在 MODE_IN_COMMUNICATION + 播放走 USAGE_VOICE_COMMUNICATION 时才拿到远端参考。
        val diagAm = audioManager
        logDuplex(
            "开始采集 ${cfg.label}（source=${cfg.source}, 采集session=${rec.audioSessionId}, " +
                "sysMode=${diagAm?.mode}, speakerActive=${diagAm?.let { CallAudioRouter.isSpeakerOutputActive(it) }}, " +
                "输出模式=$activeOutputMode, AEC avail=$aecAvail created=${aec != null} enabled=${aec?.enabled == true}, " +
                "NS=${ns?.enabled == true}, RNNoise=${denoiseSession != null}）",
        )
        capturing = true
        val myCaptureGen = ++captureGen
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording 失败", e)
            capturing = false
            releaseRecorder()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "配置 ${cfg.label} 启动后未进入录音态（可能被占用），释放并换下一组")
            capturing = false
            releaseRecorder()
            return advanceCfg()
        }
        val sessionDenoise = denoiseSession
        captureJob = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(frameSamples)
            val hpAlpha = if (cfg.rate >= 48000) HP_ALPHA_48K else HP_ALPHA_16K
            var hpPrevIn = 0f
            var hpPrevOut = 0f
            var frames = 0
            var secPeak = 0
            while (isActive && capturing && captureGen == myCaptureGen) {
                var filled = 0
                // AudioRecord 可能短读，补齐满一帧再处理，保证 20ms 节奏与 RNNoise 10ms 整数倍对齐
                while (filled < frame.size && capturing && captureGen == myCaptureGen) {
                    val n = rec.read(frame, filled, frame.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (!capturing || captureGen != myCaptureGen) break
                if (filled == frame.size) {
                    // 1. 100Hz 一阶高通滤波：滤除空调/桌面低频轰鸣，减少嘈杂环境对云端 VAD 的误触发
                    for (i in frame.indices) {
                        val x = frame[i].toFloat()
                        val y = hpAlpha * (hpPrevOut + x - hpPrevIn)
                        hpPrevIn = x
                        hpPrevOut = y
                        frame[i] = y.toInt().coerceIn(-32768, 32767).toShort()
                    }
                    // 2. RNNoise 48kHz 神经降噪：压制非稳态人声背景/电视杂音
                    sessionDenoise?.processInPlace(frame, cfg.rate)

                    // 3. 降采样至 16kHz 20ms PCM16LE 上行，并测峰值供削顶降级
                    val pcm16 = to16kPcmBytes(frame, cfg.rate / TARGET_RATE)
                    var peak = 0
                    var i = 0
                    while (i < pcm16.size) {
                        val v = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort().toInt()
                        val a = abs(v)
                        if (a > peak) peak = a
                        i += 2
                    }
                    onLevel((peak / 32768f).coerceIn(0f, 1f))
                    if (peak > secPeak) secPeak = peak
                    frames++
                    if (frames % 50 == 0) {
                        Log.i(TAG, "上行电平 peak=$secPeak (${"%.3f".format(secPeak / 32768f)}) ${cfg.label}")
                        if (secPeak >= 32000) saturatedSeconds++ else saturatedSeconds = 0
                        if (saturatedSeconds >= 3) {
                            saturatedSeconds = 0
                            if (cfgIndex < activeLadder.lastIndex) {
                                needNextCfg = true
                                Log.w(TAG, "上行持续削顶，${cfg.label} 换下一组采集配置")
                                capturing = false
                            }
                        }
                        secPeak = 0
                    }
                    onPcmFrame(pcm16)
                }
            }
            Log.d(TAG, "采集循环结束 ${cfg.label}")
            if (needNextCfg && captureGen == myCaptureGen && !stopped) {
                needNextCfg = false
                releaseRecorder()
                advanceCfg()
            }
        }
        return true
    }

    private fun releaseRecorder() {
        runCatching { if (recorder?.state == AudioRecord.STATE_INITIALIZED) recorder?.stop() }
        runCatching { aec?.release() }
        aec = null
        runCatching { ns?.release() }
        ns = null
        runCatching { denoiseSession?.close() }
        denoiseSession = null
        runCatching { recorder?.release() }
        recorder = null
    }

    private suspend fun stopCaptureInternal() {
        captureGen++
        capturing = false
        needNextCfg = false
        runCatching { if (recorder?.state == AudioRecord.STATE_INITIALIZED) recorder?.stop() }
        captureJob?.cancelAndJoin()
        captureJob = null
        releaseRecorder()
    }

    /** 换下一组采集配置并重启；已是最后一组返回 false */
    private fun advanceCfg(): Boolean {
        val activeLadder = currentLadder()
        if (cfgIndex >= activeLadder.lastIndex) {
            Log.e(TAG, "所有采集配置都不可用")
            return false
        }
        cfgIndex++
        releaseRecorder()
        return startCapture()
    }

    private fun openAndMountPlaybackTrack(plan: RoutePlan): Boolean {
        val isMediaBypass = plan.outputMode == DuplexOutputMode.MEDIA_SPEAKER_BYPASS
        val outRate = if (isMediaBypass) PLAYBACK_MEDIA_RATE else PLAYBACK_SRC_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            outRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = CallAudioRouter.createStreamingAudioTrack(
            useVoiceCall = !isMediaBypass,
            sampleRate = outRate,
            channelMask = AudioFormat.CHANNEL_OUT_MONO,
            bufferSizeInBytes = maxOf(minBuf * 2, outRate * 2), // 约 1s 缓冲
        )
        CallAudioRouter.applyPreferredDevice(
            track = track,
            am = audioManager,
            targetSpeaker = if (isMediaBypass) true else plan.targetSpeaker,
            hasExternal = plan.hasExternal,
            userOverrode = userOverrodeSpeakerphone,
        )
        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack.play 失败", e)
            track.release()
            return false
        }
        synchronized(playLock) {
            playCarrySample = 0
            activePlayback = PlaybackTrack(track = track, sampleRate = outRate, upsampleTo48k = isMediaBypass)
        }
        // AEC 参考线诊断：硬件 AEC 只认 USAGE_VOICE_COMMUNICATION 的通话下行流；
        // 若这里是 USAGE_MEDIA，或播放 session 与采集 session 不一致，外放回声就消不掉。
        logDuplex(
            "AudioTrack 已启动（${outRate}Hz / ${if (isMediaBypass) "USAGE_MEDIA 卓易通免提" else "USAGE_VOICE_COMMUNICATION"}, " +
                "播放session=${track.audioSessionId}, 采集session=${recorder?.audioSessionId}, " +
                "同会话=${recorder?.audioSessionId == track.audioSessionId}, " +
                "preferredDev=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) track.preferredDevice?.type else "n/a"}, " +
                "sysMode=${audioManager?.mode}）",
        )
        return true
    }

    private fun releasePlaybackTrack() {
        val old = synchronized(playLock) {
            val prev = activePlayback
            activePlayback = null
            playCarrySample = 0
            prev
        }
        old?.let {
            runCatching {
                if (it.track.state == AudioTrack.STATE_INITIALIZED) {
                    it.track.pause()
                    it.track.flush()
                    it.track.stop()
                }
            }
            runCatching { it.track.release() }
        }
    }

    /** 启动播放（普通设备走 USAGE_VOICE_COMMUNICATION；卓易通免提走 USAGE_MEDIA 48kHz）。 */
    fun startPlayback() {
        if (stopped) return
        if (synchronized(playLock) { activePlayback != null }) return
        val plan = ensureAudioRouting() ?: return
        if (!openAndMountPlaybackTrack(plan)) return

        if (plan.outputMode == DuplexOutputMode.VOICE_COMMUNICATION) {
            scheduleRouteCheck(plan.targetSpeaker)
        }

        if (playbackJob?.isActive == true) return
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && !stopped) {
                val (payload, target) = synchronized(playLock) {
                    val active = activePlayback ?: return@synchronized null to null
                    val pcm = pcmQueue.poll() ?: return@synchronized null to active
                    val bytes = if (active.upsampleTo48k) {
                        val (up, nextCarry) = upsample24kTo48k(pcm, playCarrySample)
                        playCarrySample = nextCarry
                        up
                    } else {
                        pcm
                    }
                    bytes to active
                }
                if (payload == null || target == null) {
                    delay(10)
                    continue
                }
                var offset = 0
                while (offset < payload.size && isActive && !stopped) {
                    val current = synchronized(playLock) { activePlayback }
                    if (current !== target) break
                    val w = runCatching {
                        target.track.write(payload, offset, payload.size - offset, AudioTrack.WRITE_BLOCKING)
                    }.getOrDefault(-1)
                    if (w <= 0) break
                    offset += w
                }
            }
        }
    }

    /** 喂入一段 24k PCM16LE 音频（来自下行 delta 的 Base64 解码结果） */
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty() || stopped) return
        synchronized(playLock) { pcmQueue.add(pcm) }
    }

    /** 插话打断：立刻停掉当前播报，丢弃未播缓冲（对应 response.cancel 的本地侧） */
    fun flushPlayback() {
        val track = synchronized(playLock) {
            pcmQueue.clear()
            playCarrySample = 0
            activePlayback?.track
        }
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "flushPlayback 异常", e)
        }
    }

    fun stop() {
        stopped = true
        switchJob?.cancel()
        switchJob = null
        captureGen++
        capturing = false
        needNextCfg = false
        // 先停 AudioRecord 唤醒阻塞在 rec.read() 的采集协程，再释放句柄
        runCatching { if (recorder?.state == AudioRecord.STATE_INITIALIZED) recorder?.stop() }
        captureJob?.cancel()
        captureJob = null
        releaseRecorder()
        playbackJob?.cancel()
        playbackJob = null
        synchronized(playLock) {
            pcmQueue.clear()
        }
        releasePlaybackTrack()
        restoreAudioRouting()
        Log.d(TAG, "音频引擎已停止")
    }
}
