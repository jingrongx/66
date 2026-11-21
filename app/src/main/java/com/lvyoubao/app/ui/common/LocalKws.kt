package com.lvyoubao.app.ui.common

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.lvyoubao.app.data.LogStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 端侧唤醒词（KWS）：sherpa-onnx + zipformer 3M 中文模型，不经过云端文字识别。
 *
 * 为什么要有它：
 *  - 云端那条路必须「说完 → 上传 → 转写 → 比字符串」才响，实测 1.5 秒起步；
 *  - 转写还老把「小源」写成「小圆/小袁/小元」，词表再放宽也只是追着改；
 *  - 房间里一直有人说话（电视/直播）时，待机语音不断被切成段上传，每分钟 30 条配额
 *    常被吃满，真正那句反倒可能被丢掉。
 *
 * 本地 KWS 是声学匹配：喂进去的就是波形，命中即「小源小源」，200~400ms 出结果，
 * 待机阶段一条都不上云。
 *
 * 模型：sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20（int8，chunk-8 → 160ms 帧）
 * 词表：开放词表，拼音拼出来即可，改唤醒词不用重新训练、不用重新发版（改 KEYWORDS 即可）
 */
object LocalKws {
    private const val SAMPLE_RATE = 16000
    private const val QUEUE_FRAMES = 12 // 12 × 80ms ≈ 1 秒余量；短读/抖动靠它吸收

    /**
     * 词表放在 assets/kws/keywords.txt，**必须走 asset 相对路径**：
     * 这里用的是 `KeywordSpotter(AssetManager, config)`，sherpa 的 ReadFile 在
     * assetManager 非空时只认相对路径，传绝对路径会直接 abort 整个进程
     * （实测 0.9.27 首包就死在这：You are using an absolute path ... Read binary file failed）。
     *
     * 行语法（sherpa-onnx `EncodeBase`）：
     *   token token ... @显示文本 :加强分 #触发阈值
     * 每个 token 必须是模型 tokens.txt 里有的符号。小= x + iǎo，源= y + uán。
     * 加强分 1.5~3.0 往「更容易命中」推，阈值 0.1~0.35 越大越保守。
     */
    private const val KEYWORDS_ASSET = "kws/keywords.txt"

    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    @Volatile private var loading = false
    @Volatile private var failed = false

    private var queue: ArrayBlockingQueue<ShortArray>? = null
    private var worker: Thread? = null
    private var onHit: ((String) -> Unit)? = null

    fun isReady(): Boolean = spotter != null

    /** 初始化并常驻。失败（.so 缺失/模型损坏）只记一次日志，调用方自动退回云端唤醒 */
    @Synchronized
    fun start(context: Context, cb: (String) -> Unit) {
        onHit = cb
        if (spotter != null || loading || failed) return
        loading = true
        val app = context.applicationContext
        val q = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)
        queue = q
        worker = Thread({
            var sp: KeywordSpotter? = null
            var st: com.k2fsa.sherpa.onnx.OnlineStream? = null
            try {
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "kws/encoder.onnx",
                            decoder = "kws/decoder.onnx",
                            joiner = "kws/joiner.onnx",
                        ),
                        tokens = "kws/tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                        modelType = "zipformer2",
                        debug = false,
                    ),
                    keywordsFile = KEYWORDS_ASSET,
                    keywordsScore = 2.0f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 1,
                )

                val t0 = SystemClock.elapsedRealtime()
                sp = KeywordSpotter(app.assets, config)
                st = sp.createStream("")
                spotter = sp
                stream = st
                q.clear() // 加载期间攒的帧是旧音频，丢掉
                LogStore.log(
                    "kws",
                    "本地唤醒就绪：zipformer-3M int8（160ms 帧），加载 ${SystemClock.elapsedRealtime() - t0}ms，词表「小源小源 / 嗨小源」",
                )
            } catch (e: Throwable) {
                failed = true
                loading = false
                runCatching { st?.release() }
                runCatching { sp?.release() }
                LogStore.log("kws", "本地唤醒不可用（${e::class.java.simpleName}: ${e.message}），退回云端唤醒")
                return@Thread
            }

            val spotterLocal = sp
            val streamLocal = st
            var frames = 0
            var costMs = 0L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val f = FloatArray(frame.size) { frame[it] / 32768f }
                    val t = SystemClock.elapsedRealtime()
                    streamLocal.acceptWaveform(f, SAMPLE_RATE)
                    while (spotterLocal.isReady(streamLocal)) spotterLocal.decode(streamLocal)
                    val result = spotterLocal.getResult(streamLocal)
                    costMs += SystemClock.elapsedRealtime() - t
                    frames++
                    if (result.keyword.isNotEmpty()) {
                        spotterLocal.reset(streamLocal)
                        q.clear() // 命中后剩下的音频属于上一轮，别再喂给同一个 decoder
                        LogStore.log("kws", "本地命中「${result.keyword}」，触发唤醒")
                        onHit?.invoke(result.keyword)
                    }
                    // 每 200 帧（约 16 秒）报一次开销：超过 80ms/帧 就会丢帧，必须优化
                    if (frames >= 200) {
                        LogStore.log("kws", "解码开销：平均 ${costMs / frames}ms / 80ms 帧（占实时 ${costMs * 100 / (frames * 80)}%）")
                        frames = 0
                        costMs = 0
                    }
                }
            } catch (_: InterruptedException) {
                // 正常退出
            } catch (e: Throwable) {
                LogStore.log("kws", "本地唤醒线程退出：${e::class.java.simpleName} ${e.message}")
            } finally {
                runCatching { streamLocal.release() }
                runCatching { spotterLocal.release() }
                spotter = null
                stream = null
            }
        }, "kws-decoder").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /**
     * 喂一帧 16k/16bit 单声道（80ms）。必须在采集线程里足够便宜：
     * 这里只做一次装箱入队，模型前向在 kws-decoder 线程跑。
     */
    fun feed(frame: ShortArray) {
        if (spotter == null) return
        val q = queue ?: return
        if (!q.offer(frame)) {
            q.poll() // 落后了：丢最旧的，保证模型跟得上当下
            q.offer(frame)
        }
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        queue?.clear()
        queue = null
        spotter = null
        stream = null
    }
}
