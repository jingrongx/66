package com.lvyoubao.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 朗读分段逻辑测试（纯本地，不依赖网络）：短回答必须整篇一段，长文每段不超限 */
class TtsPartsTest {

    @Test
    fun shortTextIsSinglePart() {
        val text = "大会岭红枫古道在浙江温州文成县，全程约5公里、4500多级台阶。1. 位置：温州市文成县大会岭脚村。"
        val parts = com.lvyoubao.app.ui.common.TtsPlayer.buildParts(text)
        assertEquals("短回答应整篇合成一段，实际被拆成 ${parts.size} 段", 1, parts.size)
        // 分段重组后字符不丢
        assertEquals(text.length, parts.joinToString("").length)
    }

    @Test
    fun longTextPartsStayWithinLimitAndPreserveAllText() {
        val text = buildString {
            repeat(120) { i -> append("这是第${i}句测试文本，用来把总长度撑过八百字上限。") }
        }
        val parts = com.lvyoubao.app.ui.common.TtsPlayer.buildParts(text)
        assertTrue("120 句长文应拆成多段", parts.size > 1)
        parts.forEachIndexed { i, p ->
            assertTrue("第 $i 段超长（${p.length} 字）", p.length <= 800)
        }
        assertEquals("分段不得丢字", text.length, parts.joinToString("").length)
    }

    @Test
    fun emptyAndSymbolOnlyTextProduceNoParts() {
        assertEquals(0, com.lvyoubao.app.ui.common.TtsPlayer.buildParts("").size)
        assertEquals(0, com.lvyoubao.app.ui.common.TtsPlayer.buildParts("。，！？").size)
    }
}
