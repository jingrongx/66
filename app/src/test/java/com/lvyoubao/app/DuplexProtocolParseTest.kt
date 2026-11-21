package com.lvyoubao.app

import com.lvyoubao.app.data.duplex.DuplexProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DuplexProtocol.parse 回归测试——2026-11 真机抓包结论固化：
 * 服务端与官方文档不一致的字段/缺发的事件（completed 的转写在 text 字段、
 * 不发 response.created、delta 是累计快照）一旦服务端行为再变，这里第一时间红。
 */
class DuplexProtocolParseTest {

    private fun parseType(frame: String): DuplexProtocol.Downstream? = DuplexProtocol.parse(frame)

    @Test
    fun `completed 转写优先读 transcript 兼容 text`() {
        // 文档口径
        val doc = parseType("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"抬头"}""")
        assertEquals("抬头", (doc as DuplexProtocol.Downstream.AsrCompleted).transcript)
        // 真机实际下发（文档写 transcript、实际在 text）
        val real = parseType("""{"type":"conversation.item.input_audio_transcription.completed","text":"抬头，抬头。"}""")
        assertEquals("抬头，抬头。", (real as DuplexProtocol.Downstream.AsrCompleted).transcript)
    }

    @Test
    fun `FC 事件解析 items 数组`() {
        val ev = parseType(
            """{"type":"response.function_call_arguments.done","items":[{"call_id":"c1","name":"get_weather","arguments":"{}"},{"call_id":"c2","name":"search_poi","arguments":"{\"keyword\":\"面馆\"}"}]}""",
        ) as DuplexProtocol.Downstream.FunctionCalls
        assertEquals(2, ev.calls.size)
        assertEquals("c1", ev.calls[0].callId)
        assertEquals("get_weather", ev.calls[0].name)
        assertEquals("面馆", ev.calls[1].arguments.substringAfter("keyword\":\"").substringBefore("\""))
    }

    @Test
    fun `error 事件三字段`() {
        val ev = parseType("""{"type":"error","error":{"code":"50000000","message":"ServerError","type":"server"}}""")
        val err = ev as DuplexProtocol.Downstream.Error
        assertEquals("50000000", err.code)
        assertEquals("ServerError", err.message)
    }

    @Test
    fun `已知类型路由`() {
        assertTrue(parseType("""{"type":"session.created","session":{"id":"abc"}}""") is DuplexProtocol.Downstream.SessionCreated)
        assertTrue(parseType("""{"type":"response.canceled"}""") is DuplexProtocol.Downstream.Interrupted)
        assertTrue(parseType("""{"type":"conversation.item.added"}""") is DuplexProtocol.Downstream.ItemAdded)
        assertTrue(parseType("""{"type":"response.done"}""") is DuplexProtocol.Downstream.ResponseDone)
    }

    @Test
    fun `未知类型与坏 JSON 返回 null`() {
        assertEquals(null, parseType("""{"type":"future.event"}"""))
        assertEquals(null, parseType("not-json"))
        assertEquals(null, parseType("""{}"""))
    }

    @Test
    fun `sessionCreate 携带工具与成对上下文`() {
        val tools = listOf(
            com.lvyoubao.app.data.AppTools.ToolDef("t1", "测试工具", """{"type":"object","properties":{}}"""),
        )
        val ctx = listOf(
            com.lvyoubao.app.data.duplex.DialogTurn("user", "我叫老王", 1_700_000_000_000),
            com.lvyoubao.app.data.duplex.DialogTurn("assistant", "好的老王", 1_700_000_001_000),
        )
        val json = DuplexProtocol.sessionCreate(
            sessionId = "s1", instructions = "人设", voice = "v",
            tools = tools, dialogContext = ctx, dialect = "sichuan", music = true,
        )
        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("\"t1\""))
        assertTrue(json.contains("\"dialog_context\""))
        assertTrue(json.contains("我叫老王"))
        assertTrue(json.contains("\"explicit_dialect\":\"sichuan\""))
        assertTrue(json.contains("\"enable_music\":true"))
        // 时间戳按秒传
        assertTrue(json.contains("1700000000"))
    }

    @Test
    fun `工具结果回传事件结构`() {
        val json = DuplexProtocol.toolResult("call-1", "晴，25度")
        assertTrue(json.contains("\"call_id\":\"call-1\""))
        assertTrue(json.contains("\"role\":\"tool\""))
        assertTrue(json.contains("晴，25度"))
        assertTrue(json.contains("\"type\":\"input_text\""))
    }
}
