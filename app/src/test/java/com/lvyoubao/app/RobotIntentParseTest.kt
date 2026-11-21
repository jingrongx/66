package com.lvyoubao.app

import com.lvyoubao.app.data.RobotIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RobotIntent.parseReply / expandFlow / actionsToJsonCommands 回归测试。
 * 这些是「AI 输出 → 机器人指令」的安全防线：换模型、改提示词后必须仍然全绿。
 */
class RobotIntentParseTest {

    private fun cmds(reply: String) = RobotIntent.parseReply(reply)

    @Test
    fun `基础动作解析`() {
        assertEquals(listOf(RobotIntent.Command.Move(30)), cmds("MOVE 30"))
        assertEquals(listOf(RobotIntent.Command.Turn(-90)), cmds("TURN -90"))
        assertEquals(listOf(RobotIntent.Command.Head(15)), cmds("HEAD 15"))
        assertEquals(listOf(RobotIntent.Command.Stop), cmds("STOP"))
    }

    @Test
    fun `动作流程解析`() {
        assertEquals(listOf(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.NOD, 1)), cmds("NOD 1"))
        assertEquals(listOf(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.NOD, 2)), cmds("NOD 2"))
        assertEquals(listOf(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.SHAKE, 1)), cmds("SHAKE"))
        assertEquals(listOf(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.BOW, 1)), cmds("BOW"))
        assertEquals(
            listOf(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.LOOK_AROUND, 1)),
            cmds("LOOK_AROUND"),
        )
    }

    @Test
    fun `LOOK_AROUND 不被 LOOK 截短`() {
        val out = cmds("LOOK_AROUND")
        assertEquals(1, out.size)
        assertEquals(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.LOOK_AROUND, 1), out[0])
    }

    @Test
    fun `多行按顺序解析`() {
        val out = cmds("NOD 1\nSHAKE 1")
        assertEquals(2, out.size)
    }

    @Test
    fun `物理量钳制`() {
        assertEquals(100, (cmds("MOVE 999")[0] as RobotIntent.Command.Move).distCm)
        assertEquals(-100, (cmds("MOVE -9999")[0] as RobotIntent.Command.Move).distCm)
        assertEquals(360, (cmds("TURN 720")[0] as RobotIntent.Command.Turn).angleDeg)
        assertEquals(23, (cmds("HEAD 80")[0] as RobotIntent.Command.Head).angleDeg)
        assertEquals(-25, (cmds("HEAD -99")[0] as RobotIntent.Command.Head).angleDeg)
    }

    @Test
    fun `次数钳制 1 到 3`() {
        assertEquals(3, (cmds("NOD 9")[0] as RobotIntent.Command.Flow).reps)
        assertEquals(1, (cmds("NOD 0")[0] as RobotIntent.Command.Flow).reps)
    }

    @Test
    fun `超过 10 行截断`() {
        val out = cmds(List(15) { "MOVE 10" }.joinToString("\n"))
        assertEquals(10, out.size)
    }

    @Test
    fun `带内容指令`() {
        val note = cmds("NOTE 明天九点开会")[0]
        assertTrue(note is RobotIntent.Command.Note)
        assertEquals("明天九点开会", (note as RobotIntent.Command.Note).content)
        val timer = cmds("TIMER 900 关火")[0]
        assertEquals(900, (timer as RobotIntent.Command.Timer).totalSec)
        assertEquals("关火", timer.remindText)
    }

    @Test
    fun `点头流程展开为 平视-抬头-低头-平视`() {
        val expanded = RobotIntent.expandFlow(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.NOD, 1))
        assertEquals(
            listOf(RobotIntent.Command.Head(0), RobotIntent.Command.Head(12), RobotIntent.Command.Head(-8), RobotIntent.Command.Head(0)),
            expanded,
        )
        // 连续点头：中间段重复，首尾仍平视
        val twice = RobotIntent.expandFlow(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.NOD, 2))
        assertEquals(6, twice.size)
        assertEquals(RobotIntent.Command.Head(0), twice.first())
        assertEquals(RobotIntent.Command.Head(0), twice.last())
    }

    @Test
    fun `摇头流程净转角回正`() {
        val turns = RobotIntent.expandFlow(RobotIntent.Command.Flow(RobotIntent.Command.FlowKind.SHAKE, 1))
            .filterIsInstance<RobotIntent.Command.Turn>()
        assertEquals(0, turns.sumOf { it.angleDeg })
    }

    @Test
    fun `非流程命令原样通过`() {
        val move = RobotIntent.Command.Move(20)
        assertEquals(listOf(move), RobotIntent.expandFlow(move))
    }

    @Test
    fun `FC actions JSON 转指令行`() {
        val json = """
            {"actions":[{"part":"nod","reps":2},{"part":"move","value":-30},{"part":"stop"}]}
        """.trimIndent()
        assertEquals("NOD 2\nMOVE -30\nSTOP", RobotIntent.actionsToJsonCommands(json))
        assertEquals("", RobotIntent.actionsToJsonCommands("{}"))
        assertEquals("", RobotIntent.actionsToJsonCommands("not json"))
    }

    @Test
    fun `急停词表`() {
        assertTrue(RobotIntent.matchStopFast("停"))
        assertTrue(RobotIntent.matchStopFast("停下。"))
        assertTrue(RobotIntent.matchStopFast("别动"))
        // 否定句不是急停
        assertEquals(false, RobotIntent.matchStopFast("别停"))
        assertEquals(false, RobotIntent.matchStopFast("不要停下来"))
        // 长句交给 AI，不走快路
        assertEquals(false, RobotIntent.matchStopFast("停下的时候提醒我吃药"))
    }
}
