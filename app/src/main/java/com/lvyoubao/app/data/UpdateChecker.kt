package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：检查 GitHub Releases + 下载 APK。
 * 国内网络下代理优先：按 [proxies] 顺序逐个尝试（前缀式加速镜像），末尾空串表示直连。
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

    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        guard {
            val viaApi = runCatching { checkViaApi() }.getOrNull()
            if (viaApi != null) return@withContext Result.success(viaApi)
            val viaAtom = runCatching { checkViaAtom() }.getOrNull()
            if (viaAtom != null) return@withContext Result.success(viaAtom)
            throw ApiException("连接不上更新服务（代理与直连均失败），请检查网络")
        }
    }

    private fun checkViaApi(): ReleaseInfo {
        var sawReal404 = false
        for (proxy in proxies) {
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
    private fun checkViaAtom(): ReleaseInfo {
        var lastError: Exception? = null
        for (proxy in proxies) {
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
                val info = runCatching { fetchReleaseByTag(tag) }.getOrNull()
                    ?: runCatching { fetchAssetsFromExpandedPage(tag) }.getOrNull()
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

    private fun fetchReleaseByTag(tag: String): ReleaseInfo {
        var lastError: Exception? = null
        for (proxy in proxies) {
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

    private fun fetchAssetsFromExpandedPage(tag: String): ReleaseInfo {
        var lastError: Exception? = null
        for (proxy in proxies) {
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
            for (proxy in proxies) {
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
