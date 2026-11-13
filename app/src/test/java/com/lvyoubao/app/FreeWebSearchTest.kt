package com.lvyoubao.app

import com.lvyoubao.app.data.FreeWebSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWebSearchTest {

    @Test
    fun testExtractFirstUrl() {
        val text1 = "看看这篇文章 https://mp.weixin.qq.com/s/78KBHc4-4N5b2vfL9SkutQ 怎么样"
        assertEquals("https://mp.weixin.qq.com/s/78KBHc4-4N5b2vfL9SkutQ", FreeWebSearch.extractFirstUrl(text1))

        val text2 = "请阅读 http://example.com/test?a=1&b=2 里的内容"
        assertEquals("http://example.com/test?a=1&b=2", FreeWebSearch.extractFirstUrl(text2))

        val text3 = "纯文字没有任何网址"
        assertEquals(null, FreeWebSearch.extractFirstUrl(text3))
    }

    @Test
    fun testHtmlToMarkdown() {
        val html = """
            <html>
                <head><title>测试标题</title></head>
                <body>
                    <script>var a = 1;</script>
                    <style>.test { color: red; }</style>
                    <h1>大标题</h1>
                    <p>这是第一段文字 &amp; 带实体符号&nbsp;内容。</p>
                    <p>这是第二段<b>加粗</b>内容。</p>
                    <ul>
                        <li>列表项 1</li>
                        <li>列表项 2</li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val md = FreeWebSearch.htmlToMarkdown(html)
        assertTrue(md.contains("大标题"))
        assertTrue(md.contains("这是第一段文字 & 带实体符号 内容。"))
        assertTrue(md.contains("这是第二段加粗内容。"))
        assertTrue(md.contains("- 列表项 1"))
        assertTrue(!md.contains("<script>"))
        assertTrue(!md.contains("<style>"))
    }

    @Test
    fun testParseBingHtml() {
        val bingSample = """
            <ol id="b_results">
                <li class="b_algo">
                    <h2><a href="https://example.com/article/1"><strong>DeepSeek</strong> 全家桶 API 发布</a></h2>
                    <div class="b_caption">
                        <p>11月13日，DeepSeek正式宣布开放全家桶应用API，包括OCR、ASR、TTS、Search等独立接口...</p>
                    </div>
                </li>
            </ol>
        """.trimIndent()

        val results = FreeWebSearch.parseBingHtml(bingSample, 5)
        assertEquals(1, results.size)
        assertEquals("DeepSeek 全家桶 API 发布", results[0].title)
        assertEquals("https://example.com/article/1", results[0].url)
        assertTrue(results[0].snippet.contains("全家桶应用API"))
    }

    @Test
    fun testWeChatArticleParsing() {
        val wxHtml = """
            <html>
                <body>
                    <h1 class="rich_media_title" id="activity-name">DeepSeek 发布全家桶应用API</h1>
                    <div id="js_content">
                        <p>今天，DeepSeek 带来了一系列全新的应用接口。</p>
                        <p>包括深度搜索、链接阅读、语音等全面能力开放。</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val (title, content) = FreeWebSearch.parseWebpageArticle(wxHtml, "https://mp.weixin.qq.com/s/sample")
        assertEquals("DeepSeek 发布全家桶应用API", title)
        assertTrue(content.contains("全新的应用接口"))
        assertTrue(content.contains("深度搜索、链接阅读"))
    }
}
