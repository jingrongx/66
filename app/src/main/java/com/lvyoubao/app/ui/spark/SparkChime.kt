package com.lvyoubao.app.ui.spark

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 唤醒确认音（本地合成，0 网络开销）：
 * 云端识别要等一句话说完 + 上传 + 推理，怎么也得半秒以上；这段提示音在「确认是唤醒词」的瞬间响，
 * 用户立刻知道「它听见了」，比等 TTS 的「嗯，我在」快一个数量级（TTS 走网络首帧 300ms 起）。
 * 两个短音 C6→G6，总长约 120ms，音量压得很低，不刺耳。
 */
object SparkChime {

    private const val SAMPLE_RATE = 44_100

    @Volatile
    private var playing = false

    fun play(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (playing) return
        playing = true
        Thread {
            var track: AudioTrack? = null
            try {
                val pcm = buildTone()
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                )
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / SAMPLE_RATE) + 30L)
                runCatching { track.stop() }
            } catch (_: Throwable) {
                // 提示音失败不影响任何主流程
            } finally {
                runCatching { track?.release() }
                playing = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** C6 → G6 两声短音，各自带 6ms 淡入淡出，避免爆音 */
    private fun buildTone(): ShortArray {
        val tones = listOf(1046.5 to 55, 1568.0 to 65)
        val total = tones.sumOf { it.second } * SAMPLE_RATE / 1000
        val out = ShortArray(total)
        var offset = 0
        for ((freq, ms) in tones) {
            val n = ms * SAMPLE_RATE / 1000
            val fade = min(n / 4, SAMPLE_RATE * 6 / 1000)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toFloat() / fade
                    i > n - fade -> (n - i).toFloat() / fade
                    else -> 1f
                }
                val v = sin(2 * PI * freq * i / SAMPLE_RATE) * 0.20 * env
                out[offset + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            offset += n
        }
        return out
    }
}
