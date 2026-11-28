package com.lvyoubao.app.data.duplex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全双工语音的本地音频引擎：
 * - 上行：AudioRecord → 统一降采样到 16kHz / mono / PCM16 小端，按 20ms（640 字节）分片回调；
 * - 下行：AudioTrack 24kHz / mono / PCM16 小端，走 USAGE_VOICE_COMMUNICATION（通话下行流）边收边播，
 *   确保高通 Fluence 等系统硬件 AEC 能拿到远端回声参考信号，避免扬声器声音回灌成 ASR 自问自答；
 * - 路由与音量：未接耳机时主动启用外放免提并在启动时按当前媒体音量比例初始化过低的通话外放档位；
 * - 插话：收到打断时 [flushPlayback] 立刻清空播放队列，实现"按住即停"。
 *
 * 采集兼容性：不同车机/机器人 BSP 对采样率与采集源的实现差异很大。
 * 有的只认 48k（16k 直接返回满量程 -32768 的垃圾数据，识别只会出幻觉），
 * 有的 voip 通道增益被打满。这里按「48k/voip → 48k/mic → 16k/mic → 16k/voip」
 * 逐组降级：任一组连续 3 秒贴满量程就换下一组，保证总能拿到可用波形。
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
        private const val PLAYBACK_RATE = 24000
        private const val FRAME_MS = 20

        private val _callAudioActive = MutableStateFlow(false)
        /** 当前是否有全双工通话占用通话音频路由（供主界面同步切换音量键目标流） */
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

        private fun hasExternalAudioOutput(am: AudioManager): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                    when (dev.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
                        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                }
            }
            @Suppress("DEPRECATION")
            return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
        }
    }

    /** 一组采集配置：采样率 × 采集源 */
    private data class CaptureCfg(val rate: Int, val source: Int, val label: String)

    private val ladder = listOf(
        CaptureCfg(48000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "48k/voip"),
        CaptureCfg(48000, MediaRecorder.AudioSource.MIC, "48k/mic"),
        CaptureCfg(16000, MediaRecorder.AudioSource.MIC, "16k/mic"),
        CaptureCfg(16000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "16k/voip"),
    )
    private var cfgIndex = 0
    private var saturatedSeconds = 0
    private var needNextCfg = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var prevAudioMode: Int? = null
    private var prevSpeakerphoneOn: Boolean? = null
    @Volatile private var desiredSpeakerphone = true
    @Volatile private var userOverrodeSpeakerphone = false
    private var speakerVolumeSynced = false
    private var aec: AcousticEchoCanceler? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var capturing = false
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private val playLock = Any()

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
        val am = audioManager ?: return
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            if (prevAudioMode == null) prevAudioMode = am.mode
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        runCatching { am.isSpeakerphoneOn = enabled }
        Log.i(TAG, "通话声道切换：${if (enabled) "免提外放" else "听筒"}")
        if (enabled) {
            scope.launch(Dispatchers.Main) {
                delay(180)
                if (_callAudioActive.value && desiredSpeakerphone) {
                    if (!runCatching { am.isSpeakerphoneOn }.getOrDefault(false)) {
                        runCatching { am.isSpeakerphoneOn = true }
                    }
                    syncVoiceVolumeIfNeeded()
                }
            }
        }
    }

    /** 确保语音采集具备 AEC/AGC 通信模式，并按设置或手动切换路由到免提外放 / 听筒 */
    private fun ensureAudioRouting() {
        val am = audioManager ?: return
        // voip 通道要靠 AudioManager 的通信模式才会启用 AEC/AGC，否则增益可能失控
        if (prevAudioMode == null) {
            prevAudioMode = am.mode
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        if (prevSpeakerphoneOn == null) {
            prevSpeakerphoneOn = runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
        }
        val targetSpeaker = resolveSpeakerphoneTarget(
            desired = desiredSpeakerphone,
            hasExternal = hasExternalAudioOutput(am),
            userOverrode = userOverrodeSpeakerphone,
        )
        val curSpeaker = runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
        if (curSpeaker != targetSpeaker) {
            runCatching { am.isSpeakerphoneOn = targetSpeaker }
        }
        _callAudioActive.value = true
    }

    /** 切到免提外放后同步一次过低的通话外放音量，避免系统默认 1 格听不清 */
    private fun syncVoiceVolumeIfNeeded() {
        if (speakerVolumeSynced) return
        val am = audioManager ?: return
        val isSpeakerActive = runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
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
            Log.i(TAG, "通话音量初始化同步：$curVoice → $target/$maxVoice（对齐媒体 $curMusic/$maxMusic）")
        } else {
            Log.d(TAG, "当前通话音量=$curVoice/$maxVoice 媒体=$curMusic/$maxMusic speaker=$isSpeakerActive")
        }
    }

    private fun restoreAudioRouting() {
        _callAudioActive.value = false
        speakerVolumeSynced = false
        userOverrodeSpeakerphone = false
        val am = audioManager ?: return
        prevSpeakerphoneOn?.let { wasOn ->
            runCatching { am.isSpeakerphoneOn = wasOn }
            prevSpeakerphoneOn = null
        }
        // 还原系统音频模式，别把整机留在通信模式（会影响媒体音量/铃声路由）
        prevAudioMode?.let { mode ->
            runCatching { am.mode = mode }
            prevAudioMode = null
        }
    }

    /** 48k/32k 采集降采样到 16k（factor 点均值，够 ASR 用，还能压掉部分高频噪声） */
    private fun downsampleTo16k(src: ByteArray, factor: Int): ByteArray {
        if (factor <= 1) return src
        val samples = src.size / 2
        val outSamples = samples / factor
        val out = ByteArray(outSamples * 2)
        var o = 0
        var i = 0
        while (i + factor <= samples) {
            var sum = 0
            var k = 0
            while (k < factor) {
                val idx = (i + k) * 2
                val v = (src[idx].toInt() and 0xff) or (src[idx + 1].toInt() shl 8)
                sum += v
                k++
            }
            val v = (sum / factor).coerceIn(-32768, 32767)
            out[o] = (v and 0xff).toByte()
            out[o + 1] = ((v shr 8) and 0xff).toByte()
            o += 2
            i += factor
        }
        return out
    }

    /** 开始采集。调用前需已拿到 RECORD_AUDIO 权限。 */
    fun startCapture(): Boolean {
        if (capturing) return true
        ensureAudioRouting()
        val cfg = ladder[cfgIndex.coerceIn(0, ladder.lastIndex)]
        val frameBytes = cfg.rate * FRAME_MS / 1000 * 2
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
        Log.i(TAG, "开始采集 ${cfg.label}")
        recorder = rec
        // 显式打开 AEC（VOICE_COMMUNICATION 一般自带；切到 MIC 后必须自己开，否则会把播报当成用户说话）
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        if (aecAvailable) {
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()
            runCatching { aec?.enabled = true }
        }
        Log.i(
            TAG,
            "采集回声消除状态：avail=$aecAvailable created=${aec != null} enabled=${aec?.enabled == true}",
        )
        capturing = true
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording 失败", e)
            capturing = false
            runCatching { aec?.release() }
            aec = null
            rec.release()
            recorder = null
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "配置 ${cfg.label} 启动后未进入录音态（可能被占用），释放并换下一组")
            capturing = false
            runCatching { aec?.release() }
            aec = null
            runCatching { rec.stop() }
            rec.release()
            recorder = null
            return advanceCfg()
        }
        captureJob = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(frameBytes)
            var frames = 0
            var secPeak = 0
            while (isActive && capturing) {
                var filled = 0
                // AudioRecord 可能短读，补齐满一帧再发，保证 20ms 节奏
                while (filled < frame.size && capturing) {
                    val n = rec.read(frame, filled, frame.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == frame.size) {
                    val pcm16 = downsampleTo16k(frame, cfg.rate / TARGET_RATE)
                    var peak = 0
                    var i = 0
                    while (i < pcm16.size) {
                        val v = (pcm16[i].toInt() and 0xff) or (pcm16[i + 1].toInt() shl 8)
                        val a = abs(v)
                        if (a > peak) peak = a
                        i += 2
                    }
                    onLevel((peak / 32768f).coerceIn(0f, 1f))
                    if (peak > secPeak) secPeak = peak
                    frames++
                    if (frames % 50 == 0) {
                        Log.i(TAG, "上行电平 peak=$secPeak (${"%.3f".format(secPeak / 32768f)}) ${cfg.label}")
                        // 连续 3 秒贴满量程 = 波形已被削平，识别只会出幻觉文字 → 换下一组配置
                        if (secPeak >= 32000) saturatedSeconds++ else saturatedSeconds = 0
                        if (saturatedSeconds >= 3) {
                            saturatedSeconds = 0
                            if (cfgIndex < ladder.lastIndex) {
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
            if (needNextCfg) {
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
        recorder?.release()
        recorder = null
    }

    /** 换下一组采集配置并重启；已是最后一组返回 false */
    private fun advanceCfg(): Boolean {
        if (cfgIndex >= ladder.lastIndex) {
            Log.e(TAG, "所有采集配置都不可用")
            return false
        }
        cfgIndex++
        releaseRecorder()
        return startCapture()
    }

    /** 启动播放（24kHz 原生采样率走 USAGE_VOICE_COMMUNICATION 通话流，接入系统硬件 AEC 参考）。 */
    fun startPlayback() {
        if (player != null) return
        ensureAudioRouting()
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 必须走 USAGE_VOICE_COMMUNICATION（非 USAGE_MEDIA）与 24kHz 主混音器：
        // 实测高通等硬件 AEC 在 MODE_IN_COMMUNICATION 下仅拿通话下行流做远端参考，
        // 改为 USAGE_MEDIA + 48k 单声道 DIRECT 会绕开 AEC 参考线导致外放回声灌麦自问自答。
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, PLAYBACK_RATE * 2)) // 约 1s 缓冲
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack.play 失败", e)
            track.release()
            return
        }
        synchronized(playLock) {
            player = track
        }
        // 建流后先立即确认路由，再延迟 180ms 待异步切到 speaker 后校验外放路由与通话音量
        ensureAudioRouting()
        Log.d(TAG, "AudioTrack 已启动（${PLAYBACK_RATE}Hz / USAGE_VOICE_COMMUNICATION）")
        scope.launch(Dispatchers.Main) {
            delay(180)
            if (player === track) {
                ensureAudioRouting()
                syncVoiceVolumeIfNeeded()
            }
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pcm = synchronized(playLock) { pcmQueue.poll() }
                if (pcm == null) {
                    delay(10)
                    continue
                }
                var offset = 0
                while (offset < pcm.size && isActive) {
                    val w = runCatching {
                        track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    }.getOrDefault(-1)
                    if (w <= 0) break
                    offset += w
                }
            }
        }
    }

    /** 喂入一段 24k PCM16LE 音频（来自下行 delta 的 Base64 解码结果） */
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(playLock) { pcmQueue.add(pcm) }
    }

    /** 插话打断：立刻停掉当前播报，丢弃未播缓冲（对应 response.cancel 的本地侧） */
    fun flushPlayback() {
        synchronized(playLock) {
            pcmQueue.clear()
        }
        try {
            player?.pause()
            player?.flush()
            player?.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "flushPlayback 异常", e)
        }
    }

    fun stop() {
        capturing = false
        // 先停 AudioRecord 唤醒阻塞在 rec.read() 的采集协程，再释放句柄
        runCatching { if (recorder?.state == AudioRecord.STATE_INITIALIZED) recorder?.stop() }
        captureJob?.cancel()
        captureJob = null
        releaseRecorder()
        playbackJob?.cancel()
        playbackJob = null
        synchronized(playLock) {
            pcmQueue.clear()
            try {
                if (player?.state == AudioTrack.STATE_INITIALIZED) player?.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "player.stop 异常", e)
            }
            player?.release()
            player = null
        }
        restoreAudioRouting()
        Log.d(TAG, "音频引擎已停止")
    }
}
