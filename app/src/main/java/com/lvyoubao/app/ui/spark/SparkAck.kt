package com.lvyoubao.app.ui.spark

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.lvyoubao.app.data.LogStore
import kotlin.random.Random

/**
 * 唤醒回应：确认喊中唤醒词后，用一句真人声「我在」代替原来的电子提示音。
 *
 * 三条变体随机轮换，避免每次同一个调子显得机械。
 * 用 SoundPool 而不是 MediaPlayer：短音效预加载后播放延迟 <50ms；
 * MediaPlayer 现场 prepare 要 100~300ms，等于把刚刚抢回来的时间又还回去。
 */
object SparkAck {

    /** 回应声的大致时长（毫秒）：开麦要等它播完，否则「我在」会被录进问题里 */
    const val DURATION_MS = 420L

    private val files = listOf("ack/zai.wav", "ack/zaine.wav", "ack/laile.wav")
    private var pool: SoundPool? = null
    private var ids: IntArray = IntArray(0)
    private var tried = false
    private var lastIndex = -1

    /** 预热：在唤醒引擎启动时调用，等真唤醒时就不用现加载（首次 load 是异步的） */
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
            val loaded = files.mapNotNull { path ->
                runCatching { ctx.assets.openFd(path).use { p.load(it, 1) } }.getOrNull()
            }
            pool = p
            ids = loaded.toIntArray()
            LogStore.log("wake", "回应语音就绪（${ids.size} 条）")
        } catch (t: Throwable) {
            LogStore.log("wake", "回应语音不可用：${t.javaClass.simpleName}，退回提示音")
        }
    }

    /** 播一句「我在」；没准备好就退回原来的双音提示音，绝不让唤醒没有任何反馈 */
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
