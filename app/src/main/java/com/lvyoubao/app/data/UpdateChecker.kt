package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：检查 GitHub Releases + 下载 APK。
 * 国内网络下代理优先，且优先走【最快的】线路：并发探测所有候选的首包延迟，
 * 可用的按延迟升序排，探测失败的保持原顺序垫底，下载/检查都先打最快的线路（见 [fastFirst]）。
 *
 * 检查策略（两层，都是匿名可读）：
 *  1) API：api.github.com/repos/<repo>/releases/latest
 *     —— 注意多数加速镜像不转发 api.github.com，镜像线路上的 404 视为线路失败而非“无版本”；
 *        只有直连线路返回 404 才判定“仓库不可读/没有 Release”。
 *  2) 兜底：releases.atom + expanded_assets（github.com 路径，镜像均支持）。
 */
class UpdateChecker(private val repo: String = "jingrongx/66") {

    private val json = Json { ignoreUnknownKeys = true }

    private val proxies = listOf(
        "https://gh-proxy.com",
        "https://ghfast.top",
        "",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 探测专用：短超时，只为了量「哪条线路先应答」 */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * 国内优先走最快的线路：并发探测目标地址在各候选线路上的应答延迟，
     * 成功的按延迟升序排前面，失败的按原顺序垫底；探测异常不影响兜底顺序。
     */
    private suspend fun fastFirst(target: String, headers: Map<String, String> = emptyMap(), range: Boolean = false): List<String> =
        coroutineScope {
            proxies.map { proxy ->
                async {
                    val url = if (proxy.isEmpty()) target else "$proxy/$target"
                    val started = System.currentTimeMillis()
                    var ok = false
                    try {
                        val rb = Request.Builder().url(url).header("User-Agent", "lvyoubao-app")
                        headers.forEach { (k, v) -> rb.header(k, v) }
                        if (range) rb.header("Range", "bytes=0-0")
                        probeClient.newCall(rb.get().build()).execute().use { resp ->
                            ok = resp.isSuccessful
                        }
                    } catch (_: IOException) {
                        ok = false
                    }
                    Probe(proxy, ok, System.currentTimeMillis() - started)
                }
            }.awaitAll().let { results ->
                results.filter { it.ok }.sortedBy { it.ms }.map { it.proxy } +
                    results.filter { !it.ok }.map { it.proxy }
            }
        }

    private data class Probe(val proxy: String, val ok: Boolean, val ms: Long)

    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        guard {
            val viaApi = runCatching { checkViaApi() }.getOrNull()
            if (viaApi != null) return@withContext Result.success(viaApi)
            val viaAtom = runCatching { checkViaAtom() }.getOrNull()
            if (viaAtom != null) return@withContext Result.success(viaAtom)
            throw ApiException("连接不上更新服务（代理与直连均失败），请检查网络")
        }
    }

    private suspend fun checkViaApi(): ReleaseInfo {
        var sawReal404 = false
        // 探测 api 目标本身：哪条线路真能应答 API（镜像多数不转发 api.github.com，探测失败自动垫底）
        val ordered = fastFirst(
            "https://api.github.com/repos/$repo/releases/latest",
            headers = mapOf("Accept" to "application/vnd.github+json"),
        )
        for (proxy in ordered) {
            val target = "https://api.github.com/repos/$repo/releases/latest"
            val url = if (proxy.isEmpty()) target else "$proxy/$target"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "lvyoubao-app")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return json.decodeFromString<ReleaseInfo>(text)
                    if (response.code == 404 && proxy.isEmpty()) {
                        // 直连线路的 404 才是 GitHub 的真实回答
                        sawReal404 = true
                    }
                }
            } catch (e: IOException) {
                // 换下一条线路
            }
        }
        throw ApiException(
            if (sawReal404) "找不到更新信息：请确认仓库已公开且发布过 Release"
            else "连接不上更新服务（代理与直连均失败），请检查网络",
        )
    }

    /** 兜底：atom feed 拿最新 tag，再从 expanded_assets 抓 APK 下载链接 */
    private suspend fun checkViaAtom(): ReleaseInfo {
        var lastError: Exception? = null
        val ordered = fastFirst("https://github.com/$repo/releases.atom")
        for (proxy in ordered) {
            try {
                val feedUrl = "https://github.com/$repo/releases.atom"
                val feed = fetchText(if (proxy.isEmpty()) feedUrl else "$proxy/$feedUrl")
                val firstEntry = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
                    .find(feed)?.value
                    ?: throw ApiException("更新源里还没有任何版本")
                val tag = Regex("releases/tag/([^\"]+)")
                    .find(firstEntry)?.groupValues?.get(1)
                    ?: throw ApiException("更新源解析失败")
                // 资产解析：优先按 tag 走 API（部分镜像可转发 api.github.com），失败再试 expanded_assets
                val info = runCatching { fetchReleaseByTag(tag, ordered) }.getOrNull()
                    ?: runCatching { fetchAssetsFromExpandedPage(tag, ordered) }.getOrNull()
                val notes = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                    .find(firstEntry)?.groupValues?.get(1)
                    ?.let { html ->
                        html.replace(Regex("<[^>]+>"), "\n")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&#39;", "'")
                            .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
                    }
                return ReleaseInfo(
                    tag_name = tag,
                    name = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                        .find(firstEntry)?.groupValues?.get(1)?.trim(),
                    body = notes,
                    assets = info?.assets ?: emptyList(),
                )
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: ApiException("无法连接更新服务")
    }

    private suspend fun fetchReleaseByTag(tag: String, ordered: List<String>): ReleaseInfo {
        var lastError: Exception? = null
        for (proxy in ordered) {
            try {
                val target = "https://api.github.com/repos/$repo/releases/tags/$tag"
                val url = if (proxy.isEmpty()) target else "$proxy/$target"
                val text = fetchText(url)
                return json.decodeFromString<ReleaseInfo>(text)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("无法获取版本详情")
    }

    private suspend fun fetchAssetsFromExpandedPage(tag: String, ordered: List<String>): ReleaseInfo {
        var lastError: Exception? = null
        for (proxy in ordered) {
            try {
                val assetsUrl = "https://github.com/$repo/expanded_assets/$tag"
                val html = fetchText(if (proxy.isEmpty()) assetsUrl else "$proxy/$assetsUrl")
                val apkHref = Regex("href=\"(/?[^\"]*?/releases/download/[^\"]*?\\.apk)\"")
                    .find(html)?.groupValues?.get(1) ?: throw IOException("没有 APK 附件")
                val absolute = if (apkHref.startsWith("http")) apkHref else "https://github.com$apkHref"
                return ReleaseInfo(
                    tag_name = tag,
                    assets = listOf(ReleaseAsset(name = absolute.substringAfterLast('/'), browser_download_url = absolute)),
                )
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("无法获取附件列表")
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "lvyoubao-app")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("空响应")
        }
    }

    suspend fun downloadApk(
        asset: ReleaseAsset,
        destDir: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        guard {
            destDir.mkdirs()
            val dest = File(destDir, asset.name)
            // 下载前对真实 APK 地址做 Range 探测排序：谁先吐字节谁先下，失败垫底
            val ordered = fastFirst(asset.browser_download_url, range = true)
            for (proxy in ordered) {
                val url = if (proxy.isEmpty()) asset.browser_download_url else "$proxy/${asset.browser_download_url}"
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "lvyoubao-app")
                        .get()
                        .build()
                    downloadClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body ?: return@use
                        val total = body.contentLength()
                        var done = 0L
                        var lastPct = -1
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            onProgress(pct)
                                        }
                                    }
                                }
                            }
                        }
                        return@withContext Result.success(dest)
                    }
                } catch (e: IOException) {
                    dest.delete()
                }
            }
            throw ApiException("下载失败（代理与直连均失败），请稍后重试")
        }
    }

    private inline fun <T> guard(block: () -> T): Result<T> = runCatching(block).recoverCatching { e ->
        throw when (e) {
            is ApiException -> e
            is IOException -> ApiException(if (e.message?.contains("timeout", true) == true) "网络超时，请检查网络后重试" else "网络异常，请检查网络后重试")
            else -> ApiException("出错了：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
