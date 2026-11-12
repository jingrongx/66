package com.lvyoubao.app

import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.RichBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表格/图表结构解析与 stripMarkdown 保结构（纯本地，不依赖网络） */
class RichBlocksTest {

    @Test
    fun parsesMarkdownTableWithHeaderAndRows() {
        val text = """
            前面一段说明文字。

            | 景点 | 门票 | 建议时长 |
            |---|---|---|
            | 黄山 | 190元 | 2天 |
            | 宏村 | 94元 | 半天 |

            结尾一句话。
        """.trimIndent()
        val blocks = RichBlocks.parse(text)
        assertEquals(3, blocks.size)
        val table = blocks[1] as RichBlocks.Block.Table
        assertEquals(listOf("景点", "门票", "建议时长"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("黄山", "190元", "2天"), table.rows[0])
        assertEquals(listOf("宏村", "94元", "半天"), table.rows[1])
        assertEquals("前面一段说明文字。", (blocks[0] as RichBlocks.Block.Text).text.trim())
        assertEquals("结尾一句话。", (blocks[2] as RichBlocks.Block.Text).text.trim())
    }

    @Test
    fun parsesDiagramFenceAndKeepsCode() {
        val text = "行程安排如下：\n```mermaid\nflowchart TD\nA[出发] --> B[景区]\nB --> C[返程]\n```\n祝玩得开心。"
        val blocks = RichBlocks.parse(text)
        assertEquals(3, blocks.size)
        val d = blocks[1] as RichBlocks.Block.Diagram
        assertEquals("mermaid", d.lang)
        assertTrue(d.code.contains("flowchart TD"))
        assertTrue(d.code.contains("A[出发] --> B[景区]"))
    }

    @Test
    fun fenceLangAliases() {
        assertTrue(RichBlocks.isDiagramFence("```flowchart TD"))
        assertTrue(RichBlocks.isDiagramFence("```mindmap"))
        assertTrue(RichBlocks.isDiagramFence("```mermaid\n"))
        assertTrue(!RichBlocks.isDiagramFence("```python"))
        assertTrue(!RichBlocks.isDiagramFence("```"))
    }

    @Test
    fun unclosedDiagramFenceWhileStreamingIsTextNotCard() {
        // 流式中途：围栏还没闭合——半张图不当图表卡渲染
        val text = "安排如下：\n```mermaid\nflowchart TD\nA -->"
        val blocks = RichBlocks.parse(text)
        assertTrue(blocks.all { it is RichBlocks.Block.Text })
    }

    @Test
    fun pipeLineWithoutSeparatorStaysText() {
        val blocks = RichBlocks.parse("选项A | 选项B 各有优劣")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is RichBlocks.Block.Text)
    }

    @Test
    fun escapedPipeInCellDoesNotSplit() {
        val text = "| 名称 | 备注 |\n|---|---|\n| A\\|B | 含竖线 |"
        val blocks = RichBlocks.parse(text)
        val table = blocks[0] as RichBlocks.Block.Table
        assertEquals(listOf("A|B", "含竖线"), table.rows[0])
    }

    @Test
    fun crlfTableStillParsed() {
        val text = "| A | B |\r\n|---|---|\r\n| 1 | 2 |\r\n"
        val blocks = RichBlocks.parse(text)
        assertEquals(1, blocks.size)
        assertEquals(listOf("1", "2"), (blocks[0] as RichBlocks.Block.Table).rows[0])
    }

    @Test
    fun speakableDropsDiagramAndFlattensTable() {
        val text = "对比：\n\n| 景点 | 票价 |\n|---|---|\n| 黄山 | 190元 |\n\n```mermaid\nflowchart TD\nA-->B\n```"
        val s = RichBlocks.speakableText(text)
        assertTrue(s.contains("列头"))
        assertTrue(s.contains("第1行：黄山，190元"))
        assertTrue(!s.contains("flowchart"))
        assertTrue(!s.contains("```"))
    }

    @Test
    fun stripMarkdownKeepsTableStructureAndDiagramFence() {
        val answer = "对比：\n| **景点** | 门票 |\n|---|---|\n| 黄山 | 190元 |\n\n```mermaid\nflowchart TD\nA[出发]-->B[到达]\n```\n"
        val stripped = AiClient.stripMarkdown(answer)
        val blocks = RichBlocks.parse(stripped)
        val table = blocks.filterIsInstance<RichBlocks.Block.Table>().firstOrNull()
        assertTrue("stripMarkdown 后表格必须还能解析出来", table != null)
        assertEquals(listOf("景点", "门票"), table!!.header)
        val diagram = blocks.filterIsInstance<RichBlocks.Block.Diagram>().firstOrNull()
        assertTrue("stripMarkdown 后图表围栏必须完整保留", diagram != null && diagram.code.contains("A[出发]-->B[到达]"))
    }

    @Test
    fun stripMarkdownStillCleansPlainMarks() {
        val stripped = AiClient.stripMarkdown("**加粗** 和 `代码` 以及\n- 圆点行\n# 标题行")
        assertEquals("加粗 和 代码 以及\n圆点行\n标题行", stripped)
    }

    @Test
    fun stripMarkdownRemovesPlainFenceMarkersButKeepsContent() {
        val stripped = AiClient.stripMarkdown("看代码：\n```\nprint(1)\nprint(2)\n```\n完事")
        assertTrue(stripped.contains("print(1)"))
        assertTrue(!stripped.contains("```"))
    }
}
