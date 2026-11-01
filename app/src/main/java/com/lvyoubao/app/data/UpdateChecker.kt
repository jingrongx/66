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
 * 注意：Release 检查要求仓库对匿名可读（公开仓库）。
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
                        if (response.isSuccessful) {
                            return@withContext Result.success(json.decodeFromString<ReleaseInfo>(text))
                        }
                        if (response.code == 404) {
                            throw ApiException("找不到更新信息：请确认仓库已公开且发布过 Release")
                        }
                        // 其他状态码换下一条线路
                    }
                } catch (e: ApiException) {
                    throw e
                } catch (e: IOException) {
                    // 换下一条线路
                }
            }
            throw ApiException("连接不上更新服务（代理与直连均失败），请检查网络")
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
