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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * 全双工语音的本地音频引擎：
 * - 上行：AudioRecord → 统一降采样到 16kHz / mono / PCM16 小端，按 20ms（640 字节）分片回调；
 * - 下行：AudioTrack 24kHz / mono / PCM16 小端，边收边播；
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
        private const val PLAYBACK_SRC_RATE = 24000
        private const val PLAYBACK_OUT_RATE = 48000
        private const val FRAME_MS = 20

        /**
         * 将 24kHz 单声道 PCM16LE 线性插值上采样到 48kHz（2 倍点数），与 TtsPlayer 保持同一播放采样率与波形连续性。
         * [carrySample] 为上一块结尾样本，保证块边界平滑无台阶；返回「48kHz 字节数组 + 本块最后一个源样本」。
         */
        internal fun upsample24kTo48k(src: ByteArray, carrySample: Short): Pair<ByteArray, Short> {
            val samples = src.size / 2
            if (samples <= 0) return ByteArray(0) to carrySample
            val dst = ByteArray(samples * 4)
            var prev = carrySample.toInt()
            var di = 0
            for (i in 0 until samples) {
                val si = i * 2
                val curr = ((src[si + 1].toInt() shl 8) or (src[si].toInt() and 0xFF)).toShort().toInt()
                val mid = prev + (curr - prev) / 2
                dst[di] = (mid and 0xFF).toByte()
                dst[di + 1] = ((mid shr 8) and 0xFF).toByte()
                dst[di + 2] = (curr and 0xFF).toByte()
                dst[di + 3] = ((curr shr 8) and 0xFF).toByte()
                di += 4
                prev = curr
            }
            return dst to prev.toShort()
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
    private var aec: AcousticEchoCanceler? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var capturing = false
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playCarrySample: Short = 0
    private val playLock = Any()

    /** 确保语音采集具备 AEC/AGC 通信模式，同时未插耳机时走外放免提避免误切听筒或音量偏小 */
    private fun ensureAudioRouting() {
        val am = audioManager ?: return
        // voip 通道要靠 AudioManager 的通信模式才会启用 AEC/AGC，否则增益可能失控
        if (prevAudioMode == null) {
            prevAudioMode = am.mode
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        if (prevSpeakerphoneOn == null && !hasExternalAudioOutput(am)) {
            val currentSpeaker = runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
            prevSpeakerphoneOn = currentSpeaker
            if (!currentSpeaker) {
                runCatching { am.isSpeakerphoneOn = true }
            }
        }
    }

    private fun restoreAudioRouting() {
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
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()
            runCatching { aec?.enabled = true }
        }
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

    /** 启动播放（输入 24kHz 上采样至 48kHz 媒体流，对齐微软 TTS 音量与外放曲线）。 */
    fun startPlayback() {
        if (player != null) return
        ensureAudioRouting()
        synchronized(playLock) {
            playCarrySample = 0
        }
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_OUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 使用 USAGE_MEDIA 与微软 TTS（STREAM_MUSIC）走同一套媒体音量和外放均衡曲线，并上采样到 48kHz
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, PLAYBACK_OUT_RATE * 2)) // 约 1s 缓冲
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
        Log.d(TAG, "AudioTrack 已启动（${PLAYBACK_SRC_RATE}→${PLAYBACK_OUT_RATE}Hz / USAGE_MEDIA）")

        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val up = synchronized(playLock) {
                    val pcm = pcmQueue.poll() ?: return@synchronized null
                    val (resampled, nextCarry) = upsample24kTo48k(pcm, playCarrySample)
                    playCarrySample = nextCarry
                    resampled
                }
                if (up == null) {
                    delay(10)
                    continue
                }
                var offset = 0
                while (offset < up.size && isActive) {
                    val w = runCatching {
                        track.write(up, offset, up.size - offset, AudioTrack.WRITE_BLOCKING)
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
            playCarrySample = 0
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
            playCarrySample = 0
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
