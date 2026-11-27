package com.lvyoubao.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 共享倒计时与到点告警（data 层单例）：
 * 无论普通、大字、灵动还是豆包通话（FC `set_timer`）均统一共用，切模式倒计时不丢失。
 * 到点先由 [TtsPlayer] 语音播报提醒事项，随后按需播放 `AudioTrack` 三连闹钟短音直到被停。
 */
object CallTimer {

    data class State(val remainSec: Int = 0, val label: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _alarmActive = MutableStateFlow(false)
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    private val _alarmText = MutableStateFlow("")
    val alarmText: StateFlow<String> = _alarmText.asStateFlow()

    private var job: Job? = null
    private var alarmJob: Job? = null
    @Volatile
    private var alarmTrack: AudioTrack? = null
    private var appCtx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 停铃短语判据：在响铃时开口喊停/取消/别响了即立刻安静 */
    fun matchAlarmStop(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 12) return false
        return t.contains("别响") || t.contains("别滴滴") || t.contains("停止提醒") || t.contains("取消提醒") ||
            t.contains("关掉提醒") || t.contains("别闹了") || t.contains("关掉闹钟") || t.contains("关闭闹钟") ||
            t.contains("关闹钟") || t.contains("不响了") || t.contains("退出") || t.contains("关闭") ||
            t.contains("关掉") || t.contains("小源小源") || t.contains("停止") || t.contains("别说了")
    }

    /** 开始倒计时：重复调用覆盖上一个 */
    fun start(
        totalSec: Int,
        label: String = "",
        ringAlarm: Boolean = true,
        onBeforeAlarm: ((String) -> Unit)? = null,
    ) {
        val sec = totalSec.coerceIn(1, 86_400)
        val tip = label.trim()
        job?.cancel()
        _state.value = State(sec, tip)
        job = scope.launch {
            var remain = sec
            while (remain > 0) {
                delay(1_000)
                remain--
                _state.value = State(remain, tip)
            }
            _state.value = State()
            val msg = if (tip.isBlank()) "时间到啦" else "时间到啦，$tip"
            _alarmText.value = msg
            onBeforeAlarm?.invoke(msg)
            val ctx = appCtx
            if (ctx != null) {
                TtsPlayer.stop()
                TtsPlayer.speak(ctx, msg)
            }
            if (ringAlarm) startAlarm()
        }
    }

    fun cancel(): Boolean {
        val running = _state.value.remainSec > 0 || _alarmActive.value
        job?.cancel()
        _state.value = State()
        stopAlarm()
        return running
    }

    fun stopAlarm() {
        _alarmActive.value = false
        alarmJob?.cancel()
        alarmJob = null
        val track = alarmTrack
        alarmTrack = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
    }

    /** App 启动时注入 applicationContext，保证后台切页时到点仍能正常提醒发声 */
    fun attach(context: Context) {
        appCtx = context.applicationContext
    }

    /** 滴滴滴循环告警（走 USAGE_ALARM 流，等语音播报完再起响以免掐断提醒语） */
    private fun startAlarm() {
        alarmJob?.cancel()
        _alarmActive.value = true
        alarmJob = scope.launch(Dispatchers.IO) {
            var waited = 0
            while (TtsPlayer.state.value is TtsPlayer.State.Speaking && waited < 8_000) {
                delay(200)
                waited += 200
            }
            if (!_alarmActive.value) return@launch
            val sampleRate = 16_000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                return@launch
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return@launch
            }
            alarmTrack = track
            val cycle = alarmCyclePcm(sampleRate)
            try {
                track.play()
                while (_alarmActive.value) {
                    val written = track.write(cycle, 0, cycle.size)
                    if (written < 0) break
                }
            } finally {
                if (alarmTrack === track) alarmTrack = null
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

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
}
