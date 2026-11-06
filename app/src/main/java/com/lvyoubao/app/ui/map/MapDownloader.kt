package com.lvyoubao.app.ui.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * mapsforge 离线地图下载器：多段并行 + 断点续传 + 自动降级。
 *
 * 官方源 download.mapsforge.org 在德国，国内直连慢且单 TCP 吃不满带宽：
 * - 服务器支持 Range 时把文件切 16 段并行拉，每段独立连接各自推进；
 * - 读空闲 30 秒即视为断流，立刻从段内断点重试（快退避、次数多），不再傻等；
 * - 分段请求被拒（回 200/403/416，常见于个别自定义链接/CDN）自动降级单流整段下载；
 * - .meta 边车文件记录每段进度，取消/失败/杀进程后重试**接着传**，不再从头来。
 */
internal object MapDownloader {

    private const val UA = "lvyoubao-app"
    private const val SEGMENTS = 16
    private const val MIN_SIZE_FOR_SPLIT = 4L * 1024 * 1024 // 小于 4MB 不值得分段
    private const val MAX_ATTEMPTS = 8

    private val json = Json { ignoreUnknownKeys = true }

    /** 断点元数据：url+total 校验一致性，done 是每段已完成的字节数 */
    @Serializable
    internal data class Meta(val url: String, val total: Long, val done: List<Long>)

    /** 分段请求被拒：服务器不支持/不接受 Range，需要降级单流 */
    private class RangeUnsupported : IOException("range-unsupported")

    private class Probe(val ranges: Boolean, val total: Long)

    private fun metaFileOf(part: File) = File(part.parentFile, part.name + ".meta")

    private fun loadMeta(file: File): Meta? = runCatching {
        json.decodeFromString<Meta>(file.readText())
    }.getOrNull()

    private fun saveMeta(file: File, meta: Meta) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(meta))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(json.encodeToString(meta))
            }
        }
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 空闲 30 秒没有任何字节就断：慢速服务器拖死整个段不如赶紧断点重试
        .readTimeout(30, TimeUnit.SECONDS)
        // 单段请求总时长上限，防无限拖
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    /**
     * 下载 [url] 到 [part]（挂起， cancellable）。完成后 [part] 是完整文件。
     * [onProgress] 在 IO 线程按约 0.8 秒节流回调 (doneBytes, totalBytes)。
     * 取消/失败时保留 .part + .meta（断点），下次调用同参数自动续传。
     * 全程切到 IO 线程：探测/写盘都是网络与文件操作，跑到主线程会直接 NetworkOnMainThreadException 崩溃。
     */
    suspend fun download(url: String, part: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val client = client()
            // Range 探测：要 1 个字节，看回 206 还是 200，顺带拿总大小
            val probe = client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").get().build(),
            ).execute().use { r ->
                when {
                    r.code == 206 -> {
                        val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        if (total != null && total > 0) Probe(true, total) else Probe(false, -1)
                    }
                    r.code == 200 -> Probe(false, r.body?.contentLength()?.takeIf { it > 0 } ?: -1)
                    else -> throw com.lvyoubao.app.data.ApiException("下载失败（HTTP ${r.code}）")
                }
            }
            if (probe.total <= 0) throw com.lvyoubao.app.data.ApiException("服务器没有返回文件大小，无法下载")

            val metaFile = metaFileOf(part)
            try {
                runSegments(client, url, part, metaFile, probe, preferSplit = true, onProgress = onProgress)
            } catch (e: RangeUnsupported) {
                // 服务器不接受分段请求 → 单流整段重来（不设 Range，普通 GET 收全量）
                metaFile.delete()
                runSegments(client, url, part, metaFile, probe, preferSplit = false, onProgress = onProgress)
            }
        }

    private suspend fun runSegments(
        client: OkHttpClient,
        url: String,
        part: File,
        metaFile: File,
        probe: Probe,
        preferSplit: Boolean,
        onProgress: (Long, Long) -> Unit,
    ) = coroutineScope {
        val metaFile0 = metaFile
        val segCount = if (probe.ranges && preferSplit && probe.total >= MIN_SIZE_FOR_SPLIT) SEGMENTS else 1
        // 断点续传：url/总大小/段数都对得上才接着传，否则从头
        val resumed = if (probe.ranges) {
            loadMeta(metaFile0)?.takeIf { it.url == url && it.total == probe.total && it.done.size == segCount }
        } else {
            null
        }
        if (resumed == null) metaFile0.delete()
        val done = AtomicLongArray(resumed?.done?.toLongArray() ?: LongArray(segCount))

        // 预分配文件，各段 worker 用 RandomAccessFile 定位写自己那段
        RandomAccessFile(part, "rwd").use { it.setLength(probe.total) }

        val segLen = probe.total / segCount
        var lastPct = -1
        var lastReport = 0L
        var lastMetaSave = 0L

        /** 断点落盘节流：每 2 秒一次（取消/杀进程后还能接着传） */
        fun saveMetaThrottled() {
            val now = System.currentTimeMillis()
            if (now - lastMetaSave > 2_000) {
                lastMetaSave = now
                saveMeta(metaFile0, Meta(url, probe.total, (0 until segCount).map { done.get(it) }))
            }
        }

        fun reportProgress(force: Boolean = false) {
            var sum = 0L
            for (i in 0 until segCount) sum += done.get(i)
            val pct = (sum * 100 / probe.total).toInt()
            val now = System.currentTimeMillis()
            if (force || pct != lastPct || now - lastReport > 800) {
                lastPct = pct
                lastReport = now
                onProgress(sum.coerceAtMost(probe.total), probe.total)
            }
            saveMetaThrottled()
        }

        val jobs = (0 until segCount).map { i ->
            async(Dispatchers.IO) {
                val start = i * segLen
                val end = if (i == segCount - 1) probe.total - 1 else start + segLen - 1
                val segSize = end - start + 1
                var attempt = 0
                while (done.get(i) < segSize) {
                    attempt++
                    if (attempt > MAX_ATTEMPTS) {
                        throw com.lvyoubao.app.data.ApiException(
                            "网络不稳定，下载已暂停在 ${done.get(i) * 100 / segSize}%（再点下载会接着传）",
                        )
                    }
                    try {
                        val from = start + done.get(i)
                        if (from > end) break
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", UA)
                            .header("Range", "bytes=$from-$end")
                            .get()
                            .build()
                        client.newCall(request).execute().use { r ->
                            if (r.code == 206 || (segCount == 1 && r.code == 200)) {
                                // 正常：206 分段数据；单流模式服务器忽略 Range 回 200 也照收
                            } else if (segCount > 1 && (r.code == 200 || r.code == 403 || r.code == 416)) {
                                // 这台服务器不接受分段请求 → 整体降级单流
                                throw RangeUnsupported()
                            } else {
                                throw IOException("HTTP ${r.code}")
                            }
                            val input = r.body?.byteStream() ?: throw IOException("空响应")
                            RandomAccessFile(part, "rw").use { raf ->
                                raf.seek(from)
                                val buf = ByteArray(64 * 1024)
                                var off = from
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n == -1) break
                                    raf.write(buf, 0, n)
                                    off += n
                                    done.set(i, off - start)
                                }
                                reportProgress()
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: IOException) {
                        delay((500L * attempt).coerceAtMost(3_000)) // 退避后重试这一段（从段内断点继续）
                    }
                }
            }
        }
        jobs.awaitAll()
        saveMeta(metaFile0, Meta(url, probe.total, (0 until segCount).map { done.get(it) }))
        metaFile0.delete()
        reportProgress(force = true)
    }
}
