package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 免费国内联网搜索与网页正文提取（无需任何 API Key，优先国内线路）：
 * 1. 必应国内站（cn.bing.com）：国内直连免梯、响应迅速、格式结构清晰、零配置零费用；
 * 2. 百度搜索兜底：国内最大中文搜索引擎，作为双通道备份；
 * 3. 网页/公众号文章正文抓取（阅读链接）：针对微信公众号（mp.weixin.qq.com）及普通网页，
 *    自动剥离脚本、样式、导航与广告，提取标题与 Markdown 正文，1:1 对标 DeepSeek App「阅读链接」能力。
 */
object FreeWebSearch {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 聚合免费搜索：Bing China 优先，Baidu 兜底 */
    suspend fun search(query: String, count: Int = 5): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim().take(128)
        if (q.isBlank()) return@withContext emptyList()

        // 首选必应国内通道
        val bingResults = runCatching { searchBingChina(q, count) }.getOrDefault(emptyList())
        if (bingResults.isNotEmpty()) return@withContext bingResults.take(count)

        // 备选百度通道
        val baiduResults = runCatching { searchBaidu(q, count) }.getOrDefault(emptyList())
        if (baiduResults.isNotEmpty()) return@withContext baiduResults.take(count)

        emptyList()
    }

    /** 必应国内搜索（https://cn.bing.com） */
    fun searchBingChina(query: String, count: Int = 5): List<SearchResult> {
        val url = "https://cn.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&mkt=zh-CN"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            return parseBingHtml(html, count)
        }
    }

    /** 解析 Bing 搜索结果 HTML */
    internal fun parseBingHtml(html: String, count: Int): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        // 匹配 <li class="b_algo"...> ... </li>
        val blockRegex = Regex("(?s)<li class=\"b_algo\"[^>]*>(.*?)</li>")
        val titleRegex = Regex("(?s)<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>")
        val captionRegex = Regex("(?s)<p[^>]*>(.*?)</p>")

        for (match in blockRegex.findAll(html)) {
            val block = match.groupValues[1]
            val titleMatch = titleRegex.find(block) ?: continue
            val link = titleMatch.groupValues[1].trim()
            val rawTitle = titleMatch.groupValues[2]
            val cleanTitle = cleanHtmlText(rawTitle)
            if (cleanTitle.isBlank() || link.isBlank() || !link.startsWith("http")) continue

            val snippet = captionRegex.find(block)?.groupValues?.get(1)?.let { cleanHtmlText(it) }.orEmpty()
            val siteName = runCatching { URI(link).host?.removePrefix("www.") }.getOrNull()

            list.add(
                SearchResult(
                    title = cleanTitle,
                    url = link,
                    snippet = snippet,
                    summary = snippet,
                    site_name = siteName,
                )
            )
            if (list.size >= count) break
        }
        return list
    }

    /** 百度搜索（https://www.baidu.com） */
    fun searchBaidu(query: String, count: Int = 5): List<SearchResult> {
        val url = "https://www.baidu.com/s?wd=${URLEncoder.encode(query, "UTF-8")}&rn=$count"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            return parseBaiduHtml(html, count)
        }
    }

    /** 解析百度搜索结果 HTML */
    internal fun parseBaiduHtml(html: String, count: Int): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        val blockRegex = Regex("(?s)<div[^>]*class=\"[^\"]*(?:c-container|result)[^\"]*\"[^>]*>(.*?)</div>\\s*(?=<div[^>]*class=\"[^\"]*(?:c-container|result)|$)")
        val titleRegex = Regex("(?s)<h3[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h3>")

        for (match in blockRegex.findAll(html)) {
            val block = match.groupValues[1]
            val titleMatch = titleRegex.find(block) ?: continue
            val link = titleMatch.groupValues[1].trim()
            val cleanTitle = cleanHtmlText(titleMatch.groupValues[2])
            if (cleanTitle.isBlank() || link.isBlank()) continue

            // 提取摘要文本
            val cleanBlock = cleanHtmlText(block)
            val snippet = cleanBlock.removePrefix(cleanTitle).trim().take(200)

            list.add(
                SearchResult(
                    title = cleanTitle,
                    url = link,
                    snippet = snippet,
                    summary = snippet,
                    site_name = "baidu.com",
                )
            )
            if (list.size >= count) break
        }
        return list
    }

    /**
     * 抓取网页正文（阅读链接）：
     * 自动识别微信公众号（mp.weixin.qq.com）与各类普通网页，返回 (页面标题, Markdown 正文)。
     */
    suspend fun fetchUrlContent(url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) return@runCatching null
                parseWebpageArticle(html, url)
            }
        }.getOrNull()
    }

    /** 从 HTML 中提取文章标题和正文文本（转换为干净 Markdown） */
    internal fun parseWebpageArticle(html: String, originUrl: String): Pair<String, String> {
        // 1. 提取标题
        val title = extractTitle(html).ifBlank { originUrl }

        // 2. 微信公众号专用解析 (mp.weixin.qq.com)
        if (originUrl.contains("mp.weixin.qq.com")) {
            val wxContentRegex = Regex("(?s)<div[^>]*id=\"js_content\"[^>]*>(.*?)</div>\\s*(?=<script|<div class=\"rich_media_area_primary\"|$)")
            val wxMatch = wxContentRegex.find(html)
            if (wxMatch != null) {
                val clean = htmlToMarkdown(wxMatch.groupValues[1])
                if (clean.length > 50) return title to clean.take(15000)
            }
        }

        // 3. 通用网页解析：优先匹配 <article> 或主要主体
        val articleRegex = Regex("(?s)<article[^>]*>(.*?)</article>")
        val articleMatch = articleRegex.find(html)
        if (articleMatch != null) {
            val clean = htmlToMarkdown(articleMatch.groupValues[1])
            if (clean.length > 80) return title to clean.take(15000)
        }

        // 4. 兜底：剥离噪音标签后全文段落提取
        val clean = htmlToMarkdown(html)
        return title to clean.take(15000)
    }

    private fun extractTitle(html: String): String {
        // 优先微信公众号标题 <h1 class="rich_media_title" ...>
        val wxTitle = Regex("(?s)<h1[^>]*class=\"[^\"]*rich_media_title[^\"]*\"[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)
        if (!wxTitle.isNullOrBlank()) return cleanHtmlText(wxTitle)

        // 通用 <title>
        val titleMatch = Regex("(?si)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        if (!titleMatch.isNullOrBlank()) {
            return cleanHtmlText(titleMatch).replace(Regex("\\s*[-_|].*"), "").trim()
        }
        return ""
    }

    /** 将 HTML 片段转换为排版规整的 Markdown 文本 */
    internal fun htmlToMarkdown(html: String): String {
        var text = html
        // 剔除无用标签块（脚本、样式、导航、头部、页脚、SVG等）
        text = text.replace(Regex("(?si)<script[^>]*>.*?</script>"), "")
        text = text.replace(Regex("(?si)<style[^>]*>.*?</style>"), "")
        text = text.replace(Regex("(?si)<nav[^>]*>.*?</nav>"), "")
        text = text.replace(Regex("(?si)<header[^>]*>.*?</header>"), "")
        text = text.replace(Regex("(?si)<footer[^>]*>.*?</footer>"), "")
        text = text.replace(Regex("(?si)<svg[^>]*>.*?</svg>"), "")
        text = text.replace(Regex("(?si)<!--.*?-->"), "")

        // 标题与分段换行转换
        text = text.replace(Regex("(?i)</?h[1-6][^>]*>"), "\n\n")
        text = text.replace(Regex("(?i)</?p[^>]*>"), "\n\n")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</?li[^>]*>"), "\n- ")
        text = text.replace(Regex("(?i)</?(?:div|section|blockquote)[^>]*>"), "\n")

        // 剥离剩余所有 HTML 标签
        text = text.replace(Regex("<[^>]+>"), "")

        // HTML 实体解码
        text = decodeHtmlEntities(text)

        // 规范化空行
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return lines.joinToString("\n\n")
    }

    internal fun cleanHtmlText(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags).trim().replace(Regex("\\s+"), " ")
    }

    /** 常用 HTML 实体字符还原 */
    internal fun decodeHtmlEntities(input: String): String {
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&middot;", "·")
            .replace("&mdash;", "—")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")

        // 匹配 &#1234; 或 &#x1a; 进制编码
        s = Regex("&#(\\d+);").replace(s) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrDefault(m.value)
        }
        s = Regex("&#[xX]([0-9a-fA-F]+);").replace(s) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(m.value)
        }
        return s
    }

    /** 从用户输入文本中探测第一个有效 HTTP/HTTPS 链接（例如公众号/文章链接） */
    fun extractFirstUrl(text: String): String? {
        val pattern = Pattern.compile("https?://[\\w\\-_]+(?:\\.[\\w\\-_]+)+[\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#]")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }
}
