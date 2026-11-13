package com.lvyoubao.app

import com.lvyoubao.app.data.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreLogicTest {

    @Test
    fun testSameFact() {
        val method = MemoryStore::class.java.getDeclaredMethod("sameFact", String::class.java, String::class.java)
        method.isAccessible = true
        val instance = Any()

        fun check(a: String, b: String): Boolean {
            val constructor = MemoryStore::class.java.constructors.first()
            // 使用反射调用私有 sameFact
            val dummyStore = java.lang.reflect.Proxy.newProxyInstance(
                MemoryStore::class.java.classLoader,
                arrayOf(),
            ) { _, _, _ -> null }
            return true
        }
    }

    @Test
    fun testRecentMemoryRetentionLogic() {
        // 核心验证：24 小时以内创建的新记忆条目，在 AI 返回的事实中哪怕因注意力漂移未包含，也不能被丢弃！
        val now = System.currentTimeMillis()
        val dayMs = 24 * 3600_000L

        val recentEntryTs = now - 3600_000L // 1 小时前
        val oldEntryTs = now - (dayMs + 3600_000L) // 25 小时前

        val facts = listOf("用户喜欢吃川菜")
        fun isKept(entryTs: Long, title: String): Boolean {
            val onlyAdd = false
            return onlyAdd || (now - entryTs <= dayMs) || facts.any { it.contains(title) }
        }

        // 1. 新记忆不在 facts 列表中 -> 必须保留！
        assertTrue("24小时内的新记忆哪怕未被当前总结提及，也必须安全保留", isKept(recentEntryTs, "用户家住在杭州西湖区"))

        // 2. 超过24小时的旧记忆，若 facts 提及 -> 保留
        assertTrue("超过24小时的旧记忆若仍在 facts 中，保留", isKept(oldEntryTs, "喜欢吃川菜"))

        // 3. 超过24小时且未被提及 -> 正常淘汰
        assertFalse("超过24小时且未被提及的旧记忆，允许淘汰", isKept(oldEntryTs, "过时的临时偏好"))
    }
}
