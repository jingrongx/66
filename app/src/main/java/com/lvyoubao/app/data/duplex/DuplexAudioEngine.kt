package com.lvyoubao.app.data.duplex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * 全双工语音的本地音频引擎：
 * - 上行：AudioRecord 16kHz / mono / PCM16 小端，按 20ms（640 字节）分片回调；
 * - 下行：AudioTrack 24kHz / mono / PCM16 小端，边收边播；
 * - 插话：收到打断时 [flushPlayback] 立刻清空播放队列，实现"按住即停"。
 *
 * 与项目现有 SpeechInput（按键录音→云端识别）和 TtsPlayer（整段朗读）不同，
 * 这里必须持续流式收发，不能等一整段说完再说，所以单独实现。
 */
internal class DuplexAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPcmFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {
    companion object {
        private const val TAG = "DuplexAudio"
        private const val CAPTURE_RATE = 16000
        private const val PLAYBACK_RATE = 24000
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var prevAudioMode: Int? = null
    // 采集源：默认 VOICE_COMMUNICATION（带回波消除）；若发现持续满量程削顶，
    // 自动退到 MIC —— 部分车机/机器人 BSP 的 voip 通道增益被打满，采出来全是方波
    @Volatile private var source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var saturatedSeconds = 0
    private var triedMicFallback = false
    private var needMicFallback = false
    private var aec: AcousticEchoCanceler? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    @Volatile private var capturing = false
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private val playLock = Any()

    /** 开始采集。调用前需已拿到 RECORD_AUDIO 权限。 */
    fun startCapture(): Boolean {
        if (capturing) return true
        // voip 通道要靠 AudioManager 的通信模式才会启用 AEC/AGC，否则增益可能失控
        if (prevAudioMode == null) {
            prevAudioMode = audioManager?.mode
            runCatching { audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize 返回 $minBuf，设备不支持 16k 采集")
            return false
        }
        val rec = try {
            AudioRecord(
                source, // 默认 VOICE_COMMUNICATION（回波消除，全双工必备）
                CAPTURE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, DuplexProtocol.FRAME_BYTES * 4),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "录音权限被拒绝", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败 state=${rec.state}")
            rec.release()
            return false
        }
        recorder = rec
        // 显式打开 AEC（VOICE_COMMUNICATION 一般自带，切到 MIC 后必须自己开，否则会把自己的声音当成用户说话）
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
            rec.release()
            recorder = null
            return false
        }
        captureJob = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(DuplexProtocol.FRAME_BYTES)
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
                    // 峰值估算输入音量（0..1），供 UI 声波用；避免逐样本开方
                    var peak = 0
                    var i = 0
                    while (i < frame.size) {
                        val v = (frame[i].toInt() shl 8) or (frame[i + 1].toInt() and 0xff)
                        val a = abs(v)
                        if (a > peak) peak = a
                        i += 2
                    }
                    onLevel((peak / 32768f).coerceIn(0f, 1f))
                    // 每秒打一次上行峰值：现场排查「没说话却一直出识别文字」时看麦克风底噪
                    if (peak > secPeak) secPeak = peak
                    frames++
                    if (frames % 50 == 0) {
                        Log.i(TAG, "上行电平 peak=$secPeak (${"%.3f".format(secPeak / 32768f)}) source=$source")
                        // 连续 3 秒贴满量程 = 采集饱和（波形已被削平，识别只会出幻觉文字）
                        if (secPeak >= 32000) saturatedSeconds++ else saturatedSeconds = 0
                        if (!triedMicFallback && saturatedSeconds >= 3) {
                            triedMicFallback = true
                            needMicFallback = true
                            Log.w(TAG, "上行持续削顶，切换到 MIC 采集源重试")
                            capturing = false
                        }
                        secPeak = 0
                    }
                    onPcmFrame(frame.copyOf())
                }
            }
            Log.d(TAG, "采集循环结束")
            if (needMicFallback) {
                needMicFallback = false
                runCatching { recorder?.stop() }
                recorder?.release()
                recorder = null
                runCatching { aec?.release() }
                aec = null
                source = MediaRecorder.AudioSource.MIC
                startCapture()
            }
        }
        return true
    }

    /** 启动播放（24k）。在 session.created 后调用即可，先启动避免首包丢失。 */
    fun startPlayback() {
        if (player != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
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
        player = track
        scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(4096)
            while (isActive) {
                val pcm = pcmQueue.poll()
                if (pcm == null) {
                    kotlinx.coroutines.delay(10)
                    continue
                }
                var offset = 0
                while (offset < pcm.size) {
                    val n = minOf(chunk.size, pcm.size - offset)
                    System.arraycopy(pcm, offset, chunk, 0, n)
                    offset += n
                    var written = 0
                    while (written < n) {
                        val w = track.write(chunk, written, n - written)
                        if (w <= 0) break
                        written += w
                    }
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
        synchronized(playLock) { pcmQueue.clear() }
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
        captureJob?.cancel()
        captureJob = null
        try {
            if (recorder?.state == AudioRecord.STATE_INITIALIZED) recorder?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "recorder.stop 异常", e)
        }
        runCatching { aec?.release() }
        aec = null
        recorder?.release()
        recorder = null
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
        // 还原系统音频模式，别把整机留在通信模式（会影响媒体音量/铃声路由）
        prevAudioMode?.let { mode ->
            runCatching { audioManager?.mode = mode }
            prevAudioMode = null
        }
        Log.d(TAG, "音频引擎已停止")
    }
}
