package com.lvyoubao.app.ui.common

import android.os.SystemClock
import com.lvyoubao.app.data.LogStore

/**
 * RNNoise 小型神经网络降噪（xiph/rnnoise，BSD-3，425KB 模型内嵌在 .so 里）。
 *
 * 它做的是逐帧「人声 / 非人声」分离：稳态噪声（空调、风扇、电流）和
 * 非稳态噪声（电视、音乐、远处聊天）都会被压下去，人声频段被保留。
 * 这正是平台 NoiseSuppressor 的短板——后者只对稳态噪声有效。
 *
 * 只在 48kHz 域工作（10ms = 480 采样一帧）。16k 采集会先线性插值上采样到 48k，
 * 处理完再降回 16k，链路其余部分（VAD / WAV / ASR）不用改。
 *
 * 支持两种用法：
 * - 单例便捷接口 [ensure] / [processInPlace]（供 [ContinuousConversation] 使用）；
 * - 独立会话 [createSession]（供全双工通话等需要独立降噪状态机的链路使用，互不干扰）。
 *
 * 任何环节失败（.so 没打进包、低端机跑不动）都静默降级成「不降噪」，
 * 绝不能让语音功能因为降噪挂掉。
 */
object RnNoise {

    /** 480 采样一帧（10ms @ 48k） */
    private const val FRAME = 480

    /** 单帧处理超过这个耗时就认定跑不动，自动关闭 */
    private const val SLOW_FRAME_MS = 40

    private var libReady = false
    private var libFailed = false

    private var state = 0L
    private var failed = false
    private var slowCount = 0
    private var processedFrames = 0
    private var costSumMs = 0L

    /** 最近一帧的人声概率（0~1），-1 表示本帧没跑降噪 */
    @Volatile
    var lastVad: Float = -1f
        private set

    @Volatile
    var enabled = false
        private set

    /** 独立的 RNNoise 降噪实例，用于隔离不同音频采集管线的状态 */
    class Session internal constructor(private var handle: Long) {
        private var slowCount = 0
        @Volatile var active = handle != 0L
            private set

        /** 就地对 16k/48k（10ms 整数倍）PCM 做神经降噪；返回人声概率（0~1），不可用返回 -1 */
        fun processInPlace(pcm: ShortArray, rate: Int): Float {
            if (!active || handle == 0L) return -1f
            if (rate != 16_000 && rate != 48_000) return -1f
            if (pcm.isEmpty()) return -1f
            val t0 = SystemClock.elapsedRealtimeNanos()
            val vad = try {
                if (rate == 48_000) {
                    nativeProcess(handle, pcm, pcm.size)
                } else {
                    val up = upsample3x(pcm)
                    val p = nativeProcess(handle, up, up.size)
                    downsample3x(up, pcm)
                    p
                }
            } catch (t: Throwable) {
                close()
                LogStore.log("denoise", "RNNoise 会话异常关闭：${t.javaClass.simpleName}")
                return -1f
            }
            val costMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
            if (costMs > SLOW_FRAME_MS) {
                if (++slowCount >= 8) {
                    close()
                    LogStore.log("denoise", "RNNoise 会话耗时过长（${costMs}ms），已降级关闭")
                }
            } else {
                slowCount = 0
            }
            return vad
        }

        fun close() {
            active = false
            val h = handle
            handle = 0L
            if (h != 0L) runCatching { nativeDestroy(h) }
        }
    }

    @Synchronized
    private fun ensureLibraryLoaded(): Boolean {
        if (libReady) return true
        if (libFailed) return false
        return try {
            System.loadLibrary("rnnoise_jni")
            val frame = nativeFrameSize()
            if (frame != FRAME) {
                libFailed = true
                LogStore.log("denoise", "RNNoise 帧长异常（$frame），放弃降噪")
                false
            } else {
                libReady = true
                true
            }
        } catch (t: Throwable) {
            libFailed = true
            LogStore.log("denoise", "RNNoise 不可用：${t.javaClass.simpleName}，退回无降噪")
            false
        }
    }

    /** 创建一个线程独立的 RNNoise 降噪会话；不可用时返回 null */
    fun createSession(): Session? {
        if (!ensureLibraryLoaded()) return null
        val h = runCatching { nativeCreate() }.getOrDefault(0L)
        if (h == 0L) return null
        return Session(h)
    }

    /** 懒加载：第一次用到才 loadLibrary，避免启动时就吃 dex/so 加载开销 */
    fun ensure(): Boolean {
        if (enabled) return true
        if (failed) return false
        if (!ensureLibraryLoaded()) {
            failed = true
            return false
        }
        state = runCatching { nativeCreate() }.getOrDefault(0L)
        enabled = state != 0L
        if (!enabled) failed = true
        LogStore.log(
            "denoise",
            if (enabled) "RNNoise 就绪（48k/10ms，模型内置）" else "RNNoise 初始化失败，退回无降噪",
        )
        return enabled
    }

    /**
     * 就地降噪一帧（时长必须是 10ms 的整数倍）。
     *
     * @param pcm 16bit PCM，采样率 [rate]（16000 或 48000），处理后就地改写
     * @return 这一帧的人声概率；不可用或已关闭时返回 -1
     */
    fun processInPlace(pcm: ShortArray, rate: Int): Float {
        if (!enabled) return -1f
        if (rate != 16_000 && rate != 48_000) return -1f
        if (pcm.isEmpty()) return -1f

        val t0 = SystemClock.elapsedRealtimeNanos()
        val vad = try {
            if (rate == 48_000) {
                nativeProcess(state, pcm, pcm.size)
            } else {
                // 16k → 48k 上采样 → 降噪 → 降回 16k
                val up = upsample3x(pcm)
                val p = nativeProcess(state, up, up.size)
                downsample3x(up, pcm)
                p
            }
        } catch (t: Throwable) {
            disable(t)
            return -1f
        }
        lastVad = vad

        val costMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        processedFrames++
        costSumMs += costMs
        if (costMs > SLOW_FRAME_MS) {
            if (++slowCount >= 5) disable(null, "单帧 ${costMs}ms，超出实时预算")
        } else {
            slowCount = 0
        }
        return vad
    }

    /** 每处理 N 帧由调用方问一次，用来在日志里留一条性能基线 */
    fun takeStats(): String? {
        if (processedFrames < 120) return null
        val avg = costSumMs.toDouble() / processedFrames
        val text = "降噪开销：平均 %.1fms/80ms 帧（%d 帧，占实时 %.0f%%）"
            .format(avg, processedFrames, avg / 80.0 * 100)
        processedFrames = 0
        costSumMs = 0
        return text
    }

    private fun disable(t: Throwable?, reason: String? = null) {
        if (!enabled) return
        enabled = false
        failed = true
        runCatching { if (state != 0L) nativeDestroy(state) }
        state = 0L
        LogStore.log(
            "denoise",
            "RNNoise 已关闭：${reason ?: t?.javaClass?.simpleName ?: "未知原因"}",
        )
    }

    /** 3 倍线性插值上采样（16k → 48k） */
    private fun upsample3x(src: ShortArray): ShortArray {
        val out = ShortArray(src.size * 3)
        for (i in src.indices) {
            val cur = src[i].toFloat()
            val next = if (i + 1 < src.size) src[i + 1].toFloat() else cur
            val d = (next - cur) / 3f
            out[i * 3] = cur.toInt().toShort()
            out[i * 3 + 1] = (cur + d).toInt().toShort()
            out[i * 3 + 2] = (cur + d * 2f).toInt().toShort()
        }
        return out
    }

    /** 3 点均值降采样（48k → 16k），与采集链路的均值降采样保持一致 */
    private fun downsample3x(src: ShortArray, dst: ShortArray) {
        var i = 0
        var o = 0
        while (i + 2 < src.size && o < dst.size) {
            val sum = src[i].toInt() + src[i + 1].toInt() + src[i + 2].toInt()
            dst[o] = (sum / 3).coerceIn(-32768, 32767).toShort()
            i += 3
            o++
        }
    }

    private external fun nativeFrameSize(): Int
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, pcm: ShortArray, count: Int): Float
}
