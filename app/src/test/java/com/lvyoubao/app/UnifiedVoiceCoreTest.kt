package com.lvyoubao.app

import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.DeviceVolume
import com.lvyoubao.app.data.LocalCommandRouter
import com.lvyoubao.app.ui.common.HoldTalkController
import com.lvyoubao.app.ui.common.WakeHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一语音核心层纯 JVM 单元测试：
 * 覆盖普通 / 大字 / 灵动三模式共用的唤醒词解析、闹钟停铃判定与本地快路意图路由，
 * 确保任意模式重构均不破坏语音前端快速裁决口径。
 */
class UnifiedVoiceCoreTest {

    @Test
    fun `WakeHub 唤醒词连说匹配与叠字消费`() {
        assertEquals("", WakeHub.extractWakeRemainder("小源小源"))
        assertEquals("今天天气怎么样", WakeHub.extractWakeRemainder("小源小源，今天天气怎么样"))
        assertEquals("附近有什么好吃的", WakeHub.extractWakeRemainder("哎晓元！附近有什么好吃的"))
        assertEquals("明天几度", WakeHub.extractWakeRemainder("小雨小语 明天几度"))
        assertNull(WakeHub.extractWakeRemainder("今天下午去哪玩"))
        // 超过前 8 字保护窗不再误触发
        assertNull(WakeHub.extractWakeRemainder("前面这句话一共有九个字然后才说小源小源"))
    }

    @Test
    fun `WakeHub 碎片词与空白句过滤`() {
        assertTrue(WakeHub.isFragmentUtterance(""))
        assertTrue(WakeHub.isFragmentUtterance("   "))
        assertTrue(WakeHub.isFragmentUtterance("啊"))
        assertTrue(WakeHub.isFragmentUtterance("嗯嗯"))
        assertTrue(WakeHub.isFragmentUtterance("好的"))
        assertFalse(WakeHub.isFragmentUtterance("明天天气"))
        assertFalse(WakeHub.isFragmentUtterance("电量"))
    }

    @Test
    fun `CallTimer 停铃短语识别正反例`() {
        assertTrue(CallTimer.matchAlarmStop("别响了！"))
        assertTrue(CallTimer.matchAlarmStop("关掉闹钟"))
        assertTrue(CallTimer.matchAlarmStop("停止提醒。"))
        assertTrue(CallTimer.matchAlarmStop("小源小源"))
        assertFalse(CallTimer.matchAlarmStop(""))
        assertFalse(CallTimer.matchAlarmStop("明天早上八点记得叫我起床别忘了这件事"))
        assertFalse(CallTimer.matchAlarmStop("今天去哪里吃晚饭"))
    }

    @Test
    fun `LocalCommandRouter 计时快路解析与否定放行`() {
        assertEquals(900 to "关火", LocalCommandRouter.matchTimerFast("15分钟后提醒我关火！"))
        assertEquals(300 to "", LocalCommandRouter.matchTimerFast("倒计时5分钟"))
        assertEquals(7200 to "喝水", LocalCommandRouter.matchTimerFast("提醒我2小时后喝水"))
        assertNull(LocalCommandRouter.matchTimerFast("不要定时5分钟"))
        assertNull(LocalCommandRouter.matchTimerFast("取消提醒"))
        assertNull(LocalCommandRouter.matchTimerFast("99999小时后提醒我"))
    }

    @Test
    fun `LocalCommandRouter 记事快路匹配`() {
        assertEquals("明天九点开会", LocalCommandRouter.matchNoteFast("记一下，明天九点开会"))
        assertEquals("带身份证和充电器", LocalCommandRouter.matchNoteFast("帮我记个事：带身份证和充电器"))
        assertNull(LocalCommandRouter.matchNoteFast("记"))
        assertNull(LocalCommandRouter.matchNoteFast("记住一"))
        assertNull(LocalCommandRouter.matchNoteFast("你帮我看看这是什么"))
    }

    @Test
    fun `LocalCommandRouter 音量快路与否定保护`() {
        assertEquals(DeviceVolume.Action.MUTE, LocalCommandRouter.matchVolumeFast("静音！"))
        assertEquals(DeviceVolume.Action.MAX, LocalCommandRouter.matchVolumeFast("音量最大"))
        assertEquals(DeviceVolume.Action.RESTORE, LocalCommandRouter.matchVolumeFast("恢复音量"))
        assertEquals(DeviceVolume.Action.UP, LocalCommandRouter.matchVolumeFast("大点声"))
        assertEquals(DeviceVolume.Action.DOWN, LocalCommandRouter.matchVolumeFast("太吵了"))
        assertNull(LocalCommandRouter.matchVolumeFast("别那么大声。"))
        assertNull(LocalCommandRouter.matchVolumeFast("不要静音"))
    }

    @Test
    fun `LocalCommandRouter 电量与道别快路匹配`() {
        assertTrue(LocalCommandRouter.matchBatteryQuestion("还有多少电量"))
        assertTrue(LocalCommandRouter.matchBatteryQuestion("电池还剩多少"))
        assertFalse(LocalCommandRouter.matchBatteryQuestion("电动车在哪里充比较方便一点"))

        assertTrue(LocalCommandRouter.matchByeFast("拜拜！"))
        assertTrue(LocalCommandRouter.matchByeFast("先这样吧"))
        assertFalse(LocalCommandRouter.matchByeFast("今天不想再见那个老朋友了"))
    }

    @Test
    fun `LocalCommandRouter AI指令预筛只放行动作与视觉词`() {
        assertTrue(LocalCommandRouter.needsAiCommandCheck("往前走两步"))
        assertTrue(LocalCommandRouter.needsAiCommandCheck("帮我看看前面是什么"))
        assertTrue(LocalCommandRouter.needsAiCommandCheck("停下来"))
        assertFalse(LocalCommandRouter.needsAiCommandCheck("故宫门票多少钱"))
        assertFalse(LocalCommandRouter.needsAiCommandCheck("明天杭州天气如何"))
    }

    @Test
    fun `HoldTalkController 状态判定按住即处于采集与占用期`() {
        val idle = HoldTalkController.State()
        assertFalse(idle.isRecording)
        assertFalse(idle.isBusy)

        val pressing = HoldTalkController.State(pressing = true, amplitude = 0.4f)
        assertTrue(pressing.isRecording)
        assertTrue(pressing.isBusy)

        val transcribing = HoldTalkController.State(pressing = false, listening = true)
        assertFalse(transcribing.isRecording)
        assertTrue(transcribing.isBusy)
    }
}
