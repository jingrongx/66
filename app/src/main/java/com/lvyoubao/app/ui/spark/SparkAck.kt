package com.lvyoubao.app.ui.spark

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.ui.common.EdgeTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/**
 * 唤醒回应：确认喊中唤醒词后，一句「我在/在呢/来啦」随机轮换（避免一个调子显机械）。
 *
 * 声音来源两档：
 * 1. **EdgeTts 合成缓存**（主）：用与会话播报完全相同的微软在线音色（zh-CN-XiaoxiaoNeural）
 *    现场合成三句，存 filesDir 固定播放——「我在」和后续回答就是同一个人在说话；
 * 2. **内置 wav 兜底**：断网/合成失败时用打包的录音，绝不让唤醒没有反馈。
 *
 * SoundPool 而不是 MediaPlayer：短音效预加载后播放延迟 <50ms；
 * MediaPlayer 现场 prepare 要 100~300ms，等于把刚抢回来的时间又还回去。
 */
object SparkAck {

    /** 回应声的大致时长（毫秒）：开麦要等它播完，否则「我在」会被录进问题里 */
    const val DURATION_MS = 420L

    private val texts = listOf("我在。", "在呢。", "来啦。")
    private val bundledFiles = listOf("ack/zai.wav", "ack/zaine.wav", "ack/laile.wav")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pool: SoundPool? = null
    private var ids: IntArray = IntArray(0)
    private var tried = false
    private var lastIndex = -1

    /** 预热：唤醒引擎启动时调用。内置 wav 立即可用；EdgeTts 同款音色版在后台合成，好了就换上 */
    @Synchronized
    fun preload(context: Context) {
        if (tried) return
        tried = true
        val ctx = context.applicationContext
        try {
            val p = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            pool = p
            // 先加载内置录音兜底（同步可用）
            ids = bundledFiles.mapNotNull { path ->
                runCatching { ctx.assets.openFd(path).use { p.load(it, 1) } }.getOrNull()
            }.toIntArray()
            LogStore.log("wake", "回应语音就绪（内置 ${ids.size} 条）")
            // 再后台合成同款音色版，成功后替换
            scope.launch { synthesizeTts(ctx, p) }
        } catch (t: Throwable) {
            LogStore.log("wake", "回应语音不可用：${t.javaClass.simpleName}，退回提示音")
        }
    }

    /**
     * 用会话播报同款微软音色合成三句回应，存 filesDir 后加载进池。
     * 已有缓存（如离线重启）直接加载，不发网络请求。
     */
    private suspend fun synthesizeTts(ctx: Context, p: SoundPool) {
        runCatching {
            val dir = File(ctx.filesDir, "spark_ack").apply { mkdirs() }
            val files = texts.mapIndexedNotNull { i, text ->
                val f = File(dir, "ack_$i.mp3")
                if (!f.exists() || f.length() == 0L) {
                    val tmpDir = File(ctx.cacheDir, "spark_ack_tmp").apply { mkdirs() }
                    EdgeTts.synthesize(text, tmpDir)?.let { synthesized ->
                        synthesized.copyTo(f, overwrite = true)
                        synthesized.delete()
                    }
                }
                f.takeIf { it.exists() && it.length() > 0L }
            }
            if (files.size != texts.size) {
                LogStore.log("wake", "同款音色回应合成不完整（${files.size}/${texts.size}），继续用内置录音")
                return@runCatching
            }
            val ttsIds = files.mapNotNull { runCatching { p.load(it.absolutePath, 1) }.getOrNull() }.toIntArray()
            if (ttsIds.size == files.size) {
                ids = ttsIds
                LogStore.log("wake", "回应语音已换成会话同款音色（EdgeTts 合成 3 条）")
            }
        }.onFailure {
            LogStore.log("wake", "同款音色回应合成失败：${it.message ?: it.javaClass.simpleName}，用内置录音")
        }
    }

    /** 播一句「我在」；没准备好就退回双音提示音，绝不让唤醒没有任何反馈 */
    fun play(context: Context) {
        preload(context)
        val p = pool
        if (p == null || ids.isEmpty()) {
            SparkChime.play(context)
            return
        }
        var idx = Random.nextInt(ids.size)
        if (ids.size > 1 && idx == lastIndex) idx = (idx + 1) % ids.size
        lastIndex = idx
        runCatching { p.play(ids[idx], 1f, 1f, 1, 0, 1f) }
    }
}
