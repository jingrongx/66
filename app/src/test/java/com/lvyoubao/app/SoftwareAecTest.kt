package com.lvyoubao.app

import com.lvyoubao.app.data.duplex.SoftwareAec
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 软件 AEC 验证：合成"下行播报 → 延迟衰减回声 → 上行麦克风"，确认回声被压制且人声保留。
 */
class SoftwareAecTest {

    private fun synthTone(freq: Int, sampleCount: Int, amplitude: Int): ShortArray =
        ShortArray(sampleCount) { i ->
            (sin(2.0 * Math.PI * freq * i / 16000.0) * amplitude).toInt().toShort()
        }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        s.forEachIndexed { i, v ->
            b[i * 2] = (v.toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun rms(s: ShortArray): Double {
        var sum = 0.0
        s.forEach { sum += (it.toDouble() / 32768.0) * (it.toDouble() / 32768.0) }
        return kotlin.math.sqrt(sum / s.size)
    }

    /** 纯回声场景：参考信号经延迟+衰减后成为上行，AEC 应把能量压到很低 */
    @Test
    fun echo_only_is_suppressed() {
        val aec = SoftwareAec()
        val frame = 320
        val delayFrames = 20          // 400ms 延迟
        val far = synthTone(440, frame * 80, 12000)   // 播报（持续 1.6s）
        val frames = 80

        var outRms = 0.0
        var inRms = 0.0
        for (f in 0 until frames) {
            val refFrame = far.copyOfRange(f * frame, (f + 1) * frame)
            aec.feedReference(toBytes(refFrame))

            val mic = ShortArray(frame)
            val srcIdx = (f - delayFrames) * frame
            if (srcIdx >= 0) {
                for (i in 0 until frame) {
                    mic[i] = (far[srcIdx + i] * 0.5).toInt().toShort()  // 回声衰减 0.5
                }
            }
            val out = aec.process(toBytes(mic), (f * 20).toLong(), forcePassthrough = f < 30)
            val outShort = ShortArray(frame)
            for (i in 0 until frame) {
                outShort[i] = ((out[i * 2 + 1].toInt() shl 8) or (out[i * 2].toInt() and 0xFF)).toShort()
            }
            if (f >= 50) {
                inRms += rms(mic)
                outRms += rms(outShort)
            }
        }
        val avgIn = inRms / 30
        val avgOut = outRms / 30
        assertTrue("回声应被明显压制 in=$avgIn out=$avgOut", avgOut < avgIn * 0.5)
    }

    /** 双讲场景：回声 + 用户说话，AEC 不应把人声一起消掉（输出仍保留可观能量） */
    @Test
    fun double_talk_preserves_near_speech() {
        val aec = SoftwareAec()
        val frame = 320
        val delayFrames = 20
        val far = synthTone(440, frame * 80, 6000)
        val near = synthTone(2000, frame * 80, 10000)  // 用户人声（高频、更响）
        val frames = 80

        var outRms = 0.0
        var count = 0
        for (f in 0 until frames) {
            val refFrame = far.copyOfRange(f * frame, (f + 1) * frame)
            aec.feedReference(toBytes(refFrame))
            val mic = ShortArray(frame)
            val srcIdx = (f - delayFrames) * frame
            if (srcIdx >= 0) {
                for (i in 0 until frame) {
                    val echo = (far[srcIdx + i] * 0.4).toInt()
                    val speech = near[f * frame + i].toInt()
                    mic[i] = (echo + speech).coerceIn(-32768, 32767).toShort()
                }
            }
            val out = aec.process(toBytes(mic), (f * 20).toLong(), forcePassthrough = f < 40)
            if (f >= 60) {
                val outShort = ShortArray(frame)
                for (i in 0 until frame) {
                    outShort[i] = ((out[i * 2 + 1].toInt() shl 8) or (out[i * 2].toInt() and 0xFF)).toShort()
                }
                outRms += rms(outShort)
                count++
            }
        }
        val avgOut = outRms / count
        assertTrue("双讲时人声应保留 out=$avgOut", avgOut > 0.05)
    }
}
