package com.lvyoubao.app

import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.ConfigSyncCodec
import com.lvyoubao.app.data.DeviceVolume
import com.lvyoubao.app.data.LocalCommandRouter
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SyncableConfig
import com.lvyoubao.app.data.duplex.DuplexAudioEngine
import com.lvyoubao.app.data.duplex.DuplexCallController
import com.lvyoubao.app.ui.common.AUTO_SPEAK_FRESH_WINDOW_MS
import com.lvyoubao.app.ui.common.HoldTalkController
import com.lvyoubao.app.ui.common.WakeHub
import com.lvyoubao.app.ui.common.shouldAutoSpeakFallback
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

    @Test
    fun `shouldAutoSpeakFallback 全双工通话产物与挂断切回严禁重复播报`() {
        val now = 1_000_000L
        val normalMsg = SessionMessage(
            role = "assistant",
            text = "杭州明天晴，气温 22 度。",
            ts = now - 3_000L,
        )
        val duplexMsg = normalMsg.copy(fromDuplexCall = true)
        val userMsg = SessionMessage(role = "user", text = "天气如何", ts = now - 1_000L)

        // 普通文本/按住说话的新回复，且开着自动朗读 -> 正常播报
        assertTrue(
            shouldAutoSpeakFallback(
                msg = normalMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        // 经典通话中即使没开自动朗读也应当播报
        assertTrue(
            shouldAutoSpeakFallback(
                msg = normalMsg,
                autoSpeak = false,
                inClassicCall = true,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        // 豆包全双工通话中禁止插手
        assertFalse(
            shouldAutoSpeakFallback(
                msg = normalMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = true,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        // 全双工挂断后（inDuplexCall 变为 false），只要消息来源是实时通话，即便才过去 3 秒也严禁微软 TTS 复读
        assertFalse(
            shouldAutoSpeakFallback(
                msg = duplexMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        // 同一条 ts 只允许播报一次；超过新鲜窗口、非助手消息或有遮挡均不播
        assertFalse(
            shouldAutoSpeakFallback(
                msg = normalMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = normalMsg.ts,
                nowMs = now,
            )
        )
        assertFalse(
            shouldAutoSpeakFallback(
                msg = normalMsg.copy(ts = now - AUTO_SPEAK_FRESH_WINDOW_MS),
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        assertFalse(
            shouldAutoSpeakFallback(
                msg = userMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = false,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
        assertFalse(
            shouldAutoSpeakFallback(
                msg = normalMsg,
                autoSpeak = true,
                inClassicCall = false,
                inDuplexCall = false,
                suppressed = true,
                lastSpokenTs = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun `DuplexAudioEngine computeSyncedVoiceVolume 仅在通话最低底档时对齐媒体比例`() {
        // 默认外放通话音量只有 1/6，而媒体开到 17/30（57%）-> 对齐到 3/6
        assertEquals(3, DuplexAudioEngine.computeSyncedVoiceVolume(curVoice = 1, minVoice = 1, maxVoice = 6, curMusic = 17, maxMusic = 30))
        // 媒体开到满格 -> 对齐到 6/6
        assertEquals(6, DuplexAudioEngine.computeSyncedVoiceVolume(curVoice = 1, minVoice = 1, maxVoice = 6, curMusic = 30, maxMusic = 30))
        // 用户已自己调过通话音量（非最低档）-> 不覆写用户偏好
        assertNull(DuplexAudioEngine.computeSyncedVoiceVolume(curVoice = 2, minVoice = 1, maxVoice = 6, curMusic = 30, maxMusic = 30))
        // 媒体音量很低（<=25%）-> 不强行拉高
        assertNull(DuplexAudioEngine.computeSyncedVoiceVolume(curVoice = 1, minVoice = 1, maxVoice = 6, curMusic = 5, maxMusic = 30))
    }

    @Test
    fun `DeviceVolume computeTargetStep 兼容通话流非零下限`() {
        // 通话流 minVol=1, maxVol=6，静音命令钳制到 1，提升/最大正确缩放
        assertEquals(1 to 0, DeviceVolume.computeTargetStep(DeviceVolume.Action.MUTE, cur = 3, minVol = 1, maxVol = 6))
        assertEquals(6 to 100, DeviceVolume.computeTargetStep(DeviceVolume.Action.MAX, cur = 2, minVol = 1, maxVol = 6))
        assertEquals(4 to 65, DeviceVolume.computeTargetStep(DeviceVolume.Action.UP, cur = 3, minVol = 1, maxVol = 6))
        // 媒体流 minVol=0, maxVol=30 允许真正静音到 0
        assertEquals(0 to 0, DeviceVolume.computeTargetStep(DeviceVolume.Action.MUTE, cur = 15, minVol = 0, maxVol = 30))
    }

    @Test
    fun `DuplexCallController isLikelyEchoTranscript 精准识别自述回声且不误伤追问与打断`() {
        val weatherReply = "今天上海浦东是多云，气温14°C，西风不超过3级，湿度41%，整体挺舒服的，出门不用穿太厚。"
        val weatherEcho = "今天上海浦东是多云，气温 14 摄氏度，西风不超过 3 级，湿度 41%，整体挺舒服的，出门不用穿太厚。"
        assertTrue(DuplexCallController.isLikelyEchoTranscript(weatherEcho, weatherReply))

        val coatReply = "看你一直在翻东西，是在找出门要带的东西吗？今天多云，带件薄外套就行，不用备雨具。"
        val coatEcho = "看你一直在翻东西，是在找出门要带的东西吗？今天多云，带件薄外套就行，不用被雨具。"
        assertTrue(DuplexCallController.isLikelyEchoTranscript(coatEcho, coatReply))

        // 短应答与短打断绝不拦截
        assertFalse(DuplexCallController.isLikelyEchoTranscript("好的", weatherReply))
        assertFalse(DuplexCallController.isLikelyEchoTranscript("知道了", weatherReply))
        assertFalse(DuplexCallController.isLikelyEchoTranscript("停", weatherReply))
        // 带着部分关键词的真实追问不误伤
        assertFalse(DuplexCallController.isLikelyEchoTranscript("你说气温14度是不是太冷了，要不要穿羽绒服", weatherReply))
        assertFalse(DuplexCallController.isLikelyEchoTranscript("那世博文化公园开车怎么走", coatReply))
    }

    @Test
    fun `DuplexAudioEngine resolveSpeakerphoneTarget 默认与手动切换优先级`() {
        assertTrue(DuplexAudioEngine.resolveSpeakerphoneTarget(desired = true, hasExternal = false, userOverrode = false))
        assertFalse(DuplexAudioEngine.resolveSpeakerphoneTarget(desired = false, hasExternal = false, userOverrode = false))
        // 未手动切换且外接耳机在线时让位给耳机
        assertFalse(DuplexAudioEngine.resolveSpeakerphoneTarget(desired = true, hasExternal = true, userOverrode = false))
        // 通话中用户主动点免提时优先尊重手点状态
        assertTrue(DuplexAudioEngine.resolveSpeakerphoneTarget(desired = true, hasExternal = true, userOverrode = true))
    }

    @Test
    fun `ConfigSyncCodec 同步默认声音输出且兼容旧版空字段载荷`() {
        val synced = ConfigSyncCodec.fromSettings(AiSettings(callSpeakerphone = false))
        assertEquals(false, synced.callSpeakerphone)
        val merged = ConfigSyncCodec.merge(AiSettings(callSpeakerphone = true), synced)
        assertFalse(merged.callSpeakerphone)

        // 旧版二维码缺少 callSpeakerphone（null）时保留接收端原有配置
        val legacyMerged = ConfigSyncCodec.merge(AiSettings(callSpeakerphone = false), SyncableConfig())
        assertFalse(legacyMerged.callSpeakerphone)
    }
}
