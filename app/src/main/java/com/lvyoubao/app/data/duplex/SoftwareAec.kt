package com.lvyoubao.app.data.duplex

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max

/**
 * 软件回声消除（AEC）——用于卓易通容器等「系统 AcousticEchoCanceler 不可用 + 通话被绕开走
 * USAGE_MEDIA、硬件 AEC 参考线缺席」的设备。
 *
 * 原理：下行播报的 PCM 作为参考信号 x，上行麦克风采集 d = 近端人声 + 经扬声器/空气/麦克风
 * 传回的回声。用 NLMS 自适应滤波器拟合回声路径，从上行里减去估计的回声 y，输出 e = d - y。
 *
 * 关键设计：
 * - 16kHz / mono / PCM16，与上行帧格式一致（每 20ms 一帧 = 320 samples）。
 * - 参考环形缓冲覆盖最大回声延迟（AudioTrack 缓冲 + 声学传播），互相关自动估计对齐点。
 * - 双讲检测：用户插话时（近端能量显著高于参考）暂停/放缓滤波器更新，避免把人声当回声抵消，
 *   从而保留全双工打断能力。
 *
 * 线程：单 AudioRecord 采集协程调用 [process]，播放侧单协程调用 [feedReference]；
 * 参考缓冲用 synchronized 保护，避免跨线程可见性问题。
 */
internal class SoftwareAec(
    private val sampleRate: Int = 16000,
    private val filterLen: Int = 512,          // 32ms @16k，覆盖典型扬声器→麦克风回声路径
    private val maxDelayMs: Int = 1200,        // 最大补偿延迟：AudioTrack 缓冲约 1s + 声学传播
    private val stepSize: Float = 0.45f,       // NLMS 步长（偏大以加快收敛，播报通常只有数秒）
) {
    companion object {
        private const val FRAME_MS = 20
        private const val DT_SPEECH_FACTOR = 1.6f   // 近端能量 > 参考×此倍数判为双讲
        private const val DT_HANG_MS = 400          // 双讲后保持冻结的时长
    }

    private val frameSamples = sampleRate * FRAME_MS / 1000
    private val maxDelaySamples = sampleRate * maxDelayMs / 1000
    // 参考环形缓冲：最大延迟 + 滤波器长度 + 余量，全部以 sample 为单位
    private val refBuffer = ShortArray(maxDelaySamples + filterLen + frameSamples * 4)
    private var refWrite = 0                  // 环形写指针
    private var refCount = 0                  // 已写入的参考样本总数（用于判断是否攒够）

    private val weights = FloatArray(filterLen)
    private val xVec = FloatArray(filterLen)   // 当前滤波器输入（最近 filterLen 个参考样本，倒序）

    private var delaySamples = 0               // 估计的回声延迟
    private var delayConfident = false
    private var lastAlignCheckMs = 0L
    private var doubleTalkUntilMs = 0L

    private fun maskedIndex(i: Int): Int {
        var v = i % refBuffer.size
        if (v < 0) v += refBuffer.size
        return v
    }

    /**
     * 喂入下行参考音频（16k PCM16LE）。由播放侧在入队时调用（用 24k 源降采样后的结果）。
     */
    @Synchronized
    fun feedReference(pcm16: ByteArray) {
        var i = 0
        while (i + 1 < pcm16.size) {
            val v = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort()
            refBuffer[maskedIndex(refWrite)] = v
            refWrite++
            if (refCount < Int.MAX_VALUE) refCount++
            i += 2
        }
    }

    /**
     * 处理一帧上行（16k PCM16LE），返回抵消回声后的音频。
     * [forcePassthrough] 为 true 时不做处理（例如还没攒够参考、或通话刚建立）。
     */
    @Synchronized
    fun process(mic16: ByteArray, nowMs: Long, forcePassthrough: Boolean = false): ByteArray {
        if (forcePassthrough || refCount < maxDelaySamples + filterLen) return mic16
        val n = mic16.size / 2
        if (n <= 0) return mic16

        // mic 样本
        val d = ShortArray(n)
        var p = 0
        var i = 0
        while (i + 1 < mic16.size) {
            d[p++] = ((mic16[i + 1].toInt() shl 8) or (mic16[i].toInt() and 0xFF)).toShort()
            i += 2
        }

        // 周期性用互相关估计/校正延迟（每 ~2 秒一次，避免频繁抖动）
        if (!delayConfident || nowMs - lastAlignCheckMs >= 2_000L) {
            estimateDelay(d)
            lastAlignCheckMs = nowMs
            delayConfident = true
        }

        val inputRms = rms(d, 0, d.size)
        val refRms = referenceRms(n)
        val doubleTalk = refRms > 1f && inputRms > refRms * DT_SPEECH_FACTOR
        if (doubleTalk) doubleTalkUntilMs = nowMs + DT_HANG_MS
        val freezeUpdate = nowMs < doubleTalkUntilMs

        val out = ByteArray(d.size * 2)
        // 参考读取位置：写指针往前 maxDelaySamples 作为搜索窗起点对齐
        val base = refWrite - 1 - delaySamples
        var s = 0
        while (s < n) {
            // 取最近 filterLen 个参考样本（距当前对齐点），构造 xVec
            var k = 0
            while (k < filterLen) {
                val idx = maskedIndex(base - s - k)
                xVec[k] = refBuffer[idx].toFloat() / 32768f
                k++
            }
            val dNorm = d[s].toFloat() / 32768f

            // NLMS 滤波：y = w·x
            var y = 0f
            var j = 0
            while (j < filterLen) {
                y += weights[j] * xVec[j]
                j++
            }
            val e = dNorm - y

            if (!freezeUpdate) {
                var power = 0f
                j = 0
                while (j < filterLen) {
                    power += xVec[j] * xVec[j]
                    j++
                }
                val mu = stepSize / (power + 1e-4f)
                j = 0
                while (j < filterLen) {
                    weights[j] += mu * e * xVec[j]
                    j++
                }
            }

            val echoCancelled = (e * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            out[s * 2] = (echoCancelled.toInt() and 0xFF).toByte()
            out[s * 2 + 1] = ((echoCancelled.toInt() shr 8) and 0xFF).toByte()
            s++
        }
        return out
    }

    /** 互相关估计延迟：在 [0, maxDelaySamples] 内找使参考与上行最相关的对齐点 */
    private fun estimateDelay(mic: ShortArray) {
        // 抽稀搜索（步长 = 32 samples = 2ms）以控制开销
        val step = 32
        val micLen = minOf(mic.size, frameSamples)
        var bestDelay = 0
        var bestScore = -1f
        var delay = 0
        while (delay <= maxDelaySamples) {
            var corr = 0f
            var refPower = 0f
            var micPower = 0f
            var s = 0
            while (s < micLen) {
                val ref = refBuffer[maskedIndex(refWrite - 1 - delay - s)].toFloat()
                val m = mic[s].toFloat()
                corr += ref * m
                refPower += ref * ref
                micPower += m * m
                s += 4 // 再抽稀，4 sample 取 1
            }
            val denom = sqrt(refPower * micPower) + 1e-3f
            val score = corr / denom
            if (score > bestScore) {
                bestScore = score
                bestDelay = delay
            }
            delay += step
        }
        // 相关性太低说明此刻没在播报/回声很弱，保持原延迟，避免乱跳
        if (bestScore > 0.25f) {
            delaySamples = bestDelay
        }
    }

    private fun referenceRms(n: Int): Float {
        var sum = 0f
        var s = 0
        while (s < n) {
            val v = refBuffer[maskedIndex(refWrite - 1 - delaySamples - s)].toFloat() / 32768f
            sum += v * v
            s++
        }
        return sqrt(sum / n)
    }

    private fun rms(buf: ShortArray, from: Int, len: Int): Float {
        var sum = 0f
        var i = from
        val end = from + len
        while (i < end) {
            val v = buf[i].toFloat() / 32768f
            sum += v * v
            i++
        }
        return sqrt(sum / max(1, len))
    }

    fun reset() {
        refWrite = 0
        refCount = 0
        delaySamples = 0
        delayConfident = false
        lastAlignCheckMs = 0L
        doubleTalkUntilMs = 0L
        java.util.Arrays.fill(weights, 0f)
    }

    fun stats(): String = "AEC[delay=${delaySamples * 1000 / sampleRate}ms, w0=${weights[0]}]"
}
