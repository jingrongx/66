package com.lvyoubao.app

import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.duplex.DuplexCallSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** dialog_context 配对裁剪回归：官方要求数组偶数、user/assistant 严格成对、最多 20 轮 */
class DuplexCallSetupPairTest {

    private fun u(t: String) = OutMessage("user", t)
    private fun a(t: String) = OutMessage("assistant", t)

    @Test
    fun `成对保留`() {
        val turns = DuplexCallSetup.pairedTurns(listOf(u("一"), a("答一"), u("二"), a("答二")))
        assertEquals(4, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("一", turns[0].text)
        assertEquals("assistant", turns[1].role)
    }

    @Test
    fun `开头落单 assistant 丢弃`() {
        val turns = DuplexCallSetup.pairedTurns(listOf(a("残留"), u("一"), a("答一")))
        assertEquals(2, turns.size)
        assertEquals("一", turns[0].text)
    }

    @Test
    fun `结尾没等到回答的 user 丢弃`() {
        val turns = DuplexCallSetup.pairedTurns(listOf(u("一"), a("答一"), u("没答的")))
        assertEquals(2, turns.size)
    }

    @Test
    fun `空文本不进上下文`() {
        val turns = DuplexCallSetup.pairedTurns(listOf(u(" "), a("答"), u("一"), a("答一")))
        assertEquals(2, turns.size)
        assertEquals("一", turns[0].text)
    }

    @Test
    fun `最多 20 轮且时间戳严格递增`() {
        val history = (1..30).flatMap { listOf(u("问$it"), a("答$it")) }
        val turns = DuplexCallSetup.pairedTurns(history)
        assertEquals(40, turns.size) // 20 轮 × 2
        assertEquals("问11", turns[0].text) // 丢最老的 10 轮
        assertTrue(turns.zipWithNext().all { (x, y) -> y.ts > x.ts })
    }

    @Test
    fun `空历史返回空`() {
        assertTrue(DuplexCallSetup.pairedTurns(emptyList()).isEmpty())
    }
}
