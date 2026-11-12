package com.lvyoubao.app.data

/**
 * 回答正文的结构化解析（v0.8.48）：把模型输出拆成 普通文本 / 表格 / 图表 三类块。
 * - 表格 = GFM 竖线行且带 |---| 分隔行（AiClient.stripMarkdown 已保结构不压平）；
 * - 图表 = ```mermaid / ```flowchart / ```mindmap 等图表类围栏块；
 * 解析是纯字符串逻辑、可单元测试；流式中途调用也安全（未闭合的围栏/表格按已到内容渲染）。
 */
object RichBlocks {

    sealed class Block {
        data class Text(val text: String) : Block()
        /** header=列头，rows=数据行；单元格都是 trim 过的纯文本 */
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        /** lang=围栏语言小写（mermaid/flowchart/mindmap…），code=围栏内代码 */
        data class Diagram(val lang: String, val code: String) : Block()
    }

    /** 图表围栏语言集合（mermaid 及常见图型别名；```flowchart TD 直接写图型也能识别） */
    val DIAGRAM_LANGS = setOf(
        "mermaid", "flowchart", "flow", "mindmap", "graph", "sequencediagram",
        "classdiagram", "statediagram", "erdiagram", "gantt", "pie", "journey",
        "gitgraph", "timeline", "quadrant",
    )

    /** 围栏语言行是否指向图表 */
    fun isDiagramFence(line: String): Boolean {
        val lang = fenceLang(line)
        return lang.isNotEmpty() && lang in DIAGRAM_LANGS
    }

    /** 表格行：竖线开头且竖线数 ≥2（GFM 允许不写收尾竖线） */
    fun isTableLine(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2

    /** 表头分隔行（| --- | :---: | 这类，全是横杠） */
    fun isTableSeparatorLine(trimmed: String): Boolean {
        if (!trimmed.startsWith("|")) return false
        val cells = trimmed.trimEnd('|').trimStart('|').split('|')
        return cells.isNotEmpty() && cells.all {
            it.isBlank() || Regex("[:\\s]*-{2,}[\\s]*:?").matches(it.trim())
        }
    }

    private fun fenceLang(line: String): String {
        val rest = line.trim().removePrefix("```").removePrefix("~~~").trim().lowercase()
        if (rest.isEmpty()) return ""
        return rest.split(Regex("[\\s{]"))[0]
    }

    /** 拆一行表格为单元格；单元格内 \| 转义不切断 */
    private fun splitRow(line: String): List<String> {
        val safe = line.trim().trimEnd('|').trimStart('|').replace("\\|", "\u0000")
        return safe.split('|').map { it.trim().replace("\u0000", "|") }
    }

    /**
     * 解析整段文本为块序列。Text 块保持原文（含换行）；相邻普通行合并成一块；
     * 图表围栏整块保留代码；表格取分隔行前一行作表头、其余数据行按序；无分隔行的孤立竖线行当普通文本。
     */
    fun parse(text: String): List<Block> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotBlank()) blocks.add(Block.Text(buf.toString().trim('\n')))
            buf.setLength(0)
        }

        val lines = normalized.split('\n')
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()

            // ① 图表围栏：整块收走；未闭合（流式中途）先按普通文本展示，闭合后自动变成图表卡
            if (trimmed.startsWith("```") && isDiagramFence(trimmed)) {
                val lang = fenceLang(trimmed)
                val fenceLine = lines[i]
                val code = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) {
                        closed = true
                        i++
                        break
                    }
                    code.appendLine(lines[i])
                    i++
                }
                if (closed && code.isNotBlank()) {
                    flushText()
                    blocks.add(Block.Diagram(lang, code.toString().trim()))
                } else if (!closed) {
                    buf.appendLine(fenceLine)
                    buf.append(code)
                }
                continue
            }

            // ② 表格组：连续竖线行，必须含分隔行才算表
            if (isTableLine(trimmed)) {
                val group = mutableListOf<String>()
                var j = i
                while (j < lines.size && isTableLine(lines[j].trim())) {
                    group.add(lines[j].trim())
                    j++
                }
                val sepIdx = group.indexOfFirst { isTableSeparatorLine(it) }
                if (sepIdx > 0) {
                    // 分隔行之前的零散竖线行（少见）按普通文本处理
                    for (k in 0 until sepIdx - 1) buf.appendLine(group[k])
                    val header = splitRow(group[sepIdx - 1])
                    val rows = mutableListOf<List<String>>()
                    for (k in sepIdx + 1 until group.size) {
                        if (isTableSeparatorLine(group[k])) continue
                        rows.add(splitRow(group[k]))
                    }
                    flushText()
                    blocks.add(Block.Table(header, rows))
                    i = j
                    continue
                }
            }

            // ③ 普通行（含无分隔行的假表格、普通代码块的残行）
            buf.appendLine(lines[i])
            i++
        }
        flushText()
        return blocks
    }

    /** 播报上限：表格最多读 8 行，多的报总行数引导看屏幕 */
    private const val SPEAK_MAX_TABLE_ROWS = 8

    /** 语音播报用：图表块跳过（读代码没意义）；表格压成「列头：…。第1行：…」；文本原样 */
    fun speakableText(text: String): String = buildString {
        for (b in parse(text)) {
            when (b) {
                is Block.Text -> append(b.text).append('\n')
                is Block.Diagram -> Unit
                is Block.Table -> {
                    append("表格。")
                    if (b.header.isNotEmpty()) {
                        append("列头：").append(b.header.joinToString("，")).append("。")
                    }
                    b.rows.take(SPEAK_MAX_TABLE_ROWS).forEachIndexed { idx, row ->
                        append("第").append(idx + 1).append("行：").append(row.joinToString("，")).append("。")
                    }
                    if (b.rows.size > SPEAK_MAX_TABLE_ROWS) {
                        append("表格共").append(b.rows.size).append("行，屏幕上点开可看全部。\n")
                    }
                }
            }
        }
    }.trim()
}
