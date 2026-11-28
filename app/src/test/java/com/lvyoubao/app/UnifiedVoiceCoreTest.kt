package com.lvyoubao.app

import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CallAudioRouter
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.ConfigSyncCodec
import com.lvyoubao.app.data.DeviceProfile
import com.lvyoubao.app.data.DeviceVolume
import com.lvyoubao.app.data.LocalCommandRouter
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.SyncableConfig
import com.lvyoubao.app.data.duplex.DuplexAudioEngine
import com.lvyoubao.app.data.duplex.DuplexCallController
import com.lvyoubao.app.data.duplex.DuplexTurnsTracker
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

    @Test
    fun `DeviceProfile isZhuoyiEnvironment 识别卓易通容器特征而不误伤原生安卓`() {
        assertTrue(DeviceProfile.isZhuoyiEnvironment(installer = "com.zhuoyi.appstore.lite", cgroupText = null, hasContainerPkg = false))
        assertTrue(DeviceProfile.isZhuoyiEnvironment(installer = "com.droi.tong", cgroupText = null, hasContainerPkg = false))
        assertTrue(DeviceProfile.isZhuoyiEnvironment(installer = null, cgroupText = "0::/isulad/anco_box", hasContainerPkg = false))
        assertTrue(DeviceProfile.isZhuoyiEnvironment(installer = null, cgroupText = "0::/droi/container", hasContainerPkg = false))
        assertTrue(DeviceProfile.isZhuoyiEnvironment(installer = null, cgroupText = null, hasContainerPkg = true))
        // "android" 含有 "droi" 子串，绝不能误判原生安卓包安装器或 cgroup 为卓易通容器
        assertFalse(DeviceProfile.isZhuoyiEnvironment(installer = "com.android.packageinstaller", cgroupText = "0::/android/uid_10123", hasContainerPkg = false))
        assertFalse(DeviceProfile.isZhuoyiEnvironment(installer = "com.google.android.packageinstaller", cgroupText = "0::/uid_10123", hasContainerPkg = false))
    }

    @Test
    fun `CallAudioRouter 路由决策区分卓易通免提媒体绕过与原生通信通路`() {
        // 鸿蒙卓易通：免提切至 MEDIA_SPEAKER_BYPASS 绕开容器 voip 听筒锁死；听筒保持 VOICE_COMMUNICATION
        assertEquals(
            CallAudioRouter.DuplexOutputMode.MEDIA_SPEAKER_BYPASS,
            CallAudioRouter.resolveDuplexOutputMode(isZhuoyi = true, targetSpeaker = true),
        )
        assertEquals(
            CallAudioRouter.DuplexOutputMode.VOICE_COMMUNICATION,
            CallAudioRouter.resolveDuplexOutputMode(isZhuoyi = true, targetSpeaker = false),
        )
        // 原生安卓：免提/听筒均走 VOICE_COMMUNICATION，完整保留硬件 AEC
        assertEquals(
            CallAudioRouter.DuplexOutputMode.VOICE_COMMUNICATION,
            CallAudioRouter.resolveDuplexOutputMode(isZhuoyi = false, targetSpeaker = true),
        )
        assertEquals(
            CallAudioRouter.DuplexOutputMode.VOICE_COMMUNICATION,
            CallAudioRouter.resolveDuplexOutputMode(isZhuoyi = false, targetSpeaker = false),
        )
        // API 31+ setCommunicationDevice 成功后绝不回调 deprecated isSpeakerphoneOn 以防覆盖路由
        assertFalse(CallAudioRouter.shouldUseLegacySpeakerphoneFallback(sdkInt = 31, targetDeviceFound = true, setCommSucceeded = true))
        assertTrue(CallAudioRouter.shouldUseLegacySpeakerphoneFallback(sdkInt = 31, targetDeviceFound = false, setCommSucceeded = false))
        assertTrue(CallAudioRouter.shouldUseLegacySpeakerphoneFallback(sdkInt = 30, targetDeviceFound = true, setCommSucceeded = true))
    }

    @Test
    fun `DuplexAudioEngine upsample24kTo48k 连续线性插值保持采样连续且长度翻倍`() {
        // 两个 16-bit 样本: 100 和 200 (little-endian), 上一块尾样本 carry = 0
        val src = byteArrayOf(100, 0, (200 and 0xFF).toByte(), 0)
        val (out, nextCarry) = DuplexAudioEngine.upsample24kTo48k(src, prevCarry = 0)
        assertEquals(8, out.size)
        assertEquals(200.toShort(), nextCarry)
        fun sampleAt(idx: Int): Short {
            val b0 = out[idx * 2].toInt() and 0xFF
            val b1 = out[idx * 2 + 1].toInt() shl 8
            return (b0 or b1).toShort()
        }
        assertEquals(50.toShort(), sampleAt(0))
        assertEquals(100.toShort(), sampleAt(1))
        assertEquals(150.toShort(), sampleAt(2))
        assertEquals(200.toShort(), sampleAt(3))
    }

    @Test
    fun `DuplexTurnsTracker 隔绝历史回答串接并在嘈杂环境中保护已识别问句`() {
        val tracker = DuplexTurnsTracker()
        // 第一轮正常问答收口
        tracker.onAsrStarted()
        tracker.onAsr("今天几度", isFinal = true, nowMs = 1_000L)
        tracker.onAnswer("今天二十度。", isFinal = false, nowMs = 1_500L)
        tracker.onTurnDone("今天几度", "今天二十度。", nowMs = 2_000L)
        assertEquals(2, tracker.turns.size)
        assertEquals("今天二十度。", tracker.turns[1].text)

        // 第二轮开始：新 ASR 起振并识别问句，此时嘈杂环境触发空起振 + 识别失败，已识别出的第二轮问句绝不能被抹成空白
        tracker.onAsrStarted()
        tracker.onAsr("明天会下雨吗", isFinal = true, nowMs = 5_000L)
        tracker.onAsrStarted() // 噪声误起振
        tracker.onAsrFailed()  // 噪声音频未成句
        assertEquals(3, tracker.turns.size)
        assertEquals("user", tracker.turns[2].role)
        assertEquals("明天会下雨吗", tracker.turns[2].text)

        // 第二轮开始流式回答：绝不能接在上一轮「今天二十度。」后面再跳回
        tracker.onAnswer("明天", isFinal = false, nowMs = 5_500L)
        assertEquals(4, tracker.turns.size)
        assertEquals("明天", tracker.turns[3].text)
        tracker.onAnswer("晴天", isFinal = false, nowMs = 5_800L)
        assertEquals("明天晴天", tracker.turns[3].text)
        tracker.onTurnDone("明天会下雨吗", "明天晴天。", nowMs = 6_200L)
        assertEquals("明天晴天。", tracker.turns[3].text)
        assertFalse(tracker.turns[3].streaming)
    }

    @Test
    fun `SessionStore 关联抓拍图片到用户消息且保证时间戳严格单调不出现助手后跟用户`() {
        val u1 = SessionMessage(role = "user", text = "看看前面有什么", ts = 1000L)
        val afterUser = SessionStore.appendOrdered(emptyList(), u1)
        val withPhoto = SessionStore.attachImagesToLastUserMessage(afterUser, listOf("/tmp/snap.jpg"))!!
        val finalMsgs = SessionStore.appendOrdered(
            withPhoto,
            SessionMessage(role = "assistant", text = "前面是一张木桌", ts = 1000L), // 故意传入同毫秒时间戳验证单调递增
        )
        assertEquals(2, finalMsgs.size)
        assertEquals("user", finalMsgs[0].role)
        assertTrue(finalMsgs[0].hadImage)
        assertEquals(listOf("/tmp/snap.jpg"), finalMsgs[0].imagePaths)
        assertEquals("assistant", finalMsgs[1].role)
        assertTrue(finalMsgs[1].ts > finalMsgs[0].ts)
        // 若末尾已是 assistant，不可强行挂图以免错挂
        assertNull(SessionStore.attachImagesToLastUserMessage(finalMsgs, listOf("/tmp/other.jpg")))
    }
}
