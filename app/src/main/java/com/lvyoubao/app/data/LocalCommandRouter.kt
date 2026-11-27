package com.lvyoubao.app.data

import android.content.Context
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地意图快路与本机动作统一路由器（普通 / 大字 / 灵动 / 通话共用）：
 * 过去只在 SparkViewModel 内维护，导致普通与大字模式说「定时5分钟」「调大音量」「记一下…」
 * 「前进30厘米」全部绕道大模型或无响应；抽到共享层后三模式具备一致的本机执行体验。
 */
object LocalCommandRouter {

    /** 不命中本地逻辑的句子才走 AI；不含以下动词或名词直接放行聊天，避免每次对话空等一次指令解析 */
    private val ACTION_HINTS = listOf(
        "过来", "回去", "前进", "往前", "向前", "后退", "往后",
        "左转", "右转", "转圈", "转个圈", "转身", "掉头", "转过来", "转个身",
        "抬头", "低头", "抬起来", "低下来", "看上面", "看下面",
        "点头", "点个头", "点点头", "摇摇头", "摇头", "歪头", "歪着看",
        "看天上", "看看天上", "天花板", "抬头看天", "看地上", "看看地上", "低头找",
        "抬头看看", "低头看看", "东张西望", "到处看看", "环顾", "鞠躬",
        "平视", "正视", "回正", "摆正",
        "停下", "别动", "停下来", "站住",
        "跳舞", "跳个", "跟着我", "看着我", "面向我", "面向",
        "拍张", "拍个", "拍照", "拍一下", "看看", "看一下", "瞧一瞧",
        "这是什么", "前面是什么", "前面有", "帮我看看",
    )

    fun needsAiCommandCheck(text: String): Boolean = ACTION_HINTS.any { text.contains(it) }

    /** 本地计时短句匹配：「15分钟后提醒我关火」→ (900, "关火")；否定或取消交给后续分支 */
    fun matchTimerFast(text: String): Pair<Int, String>? {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 20) return null
        if (t.startsWith("别") || t.startsWith("不要") || t.contains("取消") || t.contains("停止")) return null

        fun toSec(num: Int, unit: String) = when (unit) {
            "小时", "时" -> num * 3600
            "分钟", "分" -> num * 60
            else -> num
        }

        Regex("(提醒我|计时|倒计时|闹钟|定个?)[约]?\\s*(\\d{1,3})\\s*(?:个)?(小时|时|分钟|分|秒)\\s*(?:之?后|以后?)?(.*)")
            .find(t)?.let { a ->
                val sec = toSec(a.groupValues[2].toIntOrNull() ?: return null, a.groupValues[3])
                if (sec !in 1..86_400) return null
                var label = a.groupValues[4].trim()
                if (label.startsWith("提醒我")) {
                    label = label.removePrefix("提醒我").trim().removePrefix("后").trim()
                }
                label = label.removeSuffix("闹钟").trim()
                val isReminder = a.groupValues[1] == "提醒我" || (label.isNotBlank() && label != "闹钟")
                return sec to if (isReminder) label else ""
            }

        Regex("(\\d{1,3})\\s*(?:个)?(小时|时|分钟|分|秒)(?:之?后|以后?)提醒我\\s*(.*)")
            .find(t)?.let { b ->
                val sec = toSec(b.groupValues[1].toIntOrNull() ?: return null, b.groupValues[2])
                if (sec !in 1..86_400) return null
                return sec to b.groupValues[3].trim()
            }
        return null
    }

    /** 记事短句匹配：「记一下，明天九点开会」→ "明天九点开会" */
    fun matchNoteFast(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 60) return null
        val m = Regex("^(?:帮我)?记(?:一下|一笔|个事|着|录)[：:，,]?\\s*(.+)$").find(t) ?: return null
        return m.groupValues[1].trim().takeIf { it.length >= 2 }
    }

    /** 音量短句匹配；含否定前缀（「别那么大声」）不拦截 */
    fun matchVolumeFast(text: String): DeviceVolume.Action? {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 8) return null
        if (t.startsWith("别") || t.startsWith("不要")) return null
        return when {
            t.contains("静音") || t.contains("不出声") || t.contains("关掉声音") || t.contains("关声音") -> DeviceVolume.Action.MUTE
            t.contains("最大") -> DeviceVolume.Action.MAX
            t.contains("恢复音量") || t.contains("音量正常") || t.contains("正常音量") -> DeviceVolume.Action.RESTORE
            t.contains("大点声") || t.contains("大声点") || t.contains("大声些") || t.contains("声音大") ||
                t.contains("音量大") || t.contains("太轻了") || t.contains("听不清") -> DeviceVolume.Action.UP
            t.contains("小点声") || t.contains("小声点") || t.contains("小声些") || t.contains("声音小") ||
                t.contains("音量小") || t.contains("太吵") || t.contains("太大声") -> DeviceVolume.Action.DOWN
            else -> null
        }
    }

    /** 电量短句匹配 */
    fun matchBatteryQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        if (t.contains("电量") || t.contains("多少电") || t.contains("几度电") || t.contains("还有电") || t.contains("电池")) return true
        return t.contains("电") && (t.contains("剩") || t.contains("够不够"))
    }

    /** 道别短句（连续/语音会话快速收口） */
    fun matchByeFast(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 6) return false
        return listOf("再见", "拜拜", "退出", "不聊了", "先这样", "告辞", "撤了").any { t == it || t.startsWith(it) }
    }

    sealed class Outcome {
        data object NotHandled : Outcome()
        data class Replied(
            val displayText: String,
            val spokenText: String = displayText,
            val shouldSpeak: Boolean = true,
        ) : Outcome()
        data class LookRequested(val userAsk: String) : Outcome()
        data class StopConversationRequested(val replyText: String) : Outcome()
    }

    interface Host {
        fun onStatusHint(hint: String?) {}
        fun onTimerStarted(sec: Int, label: String, confirmText: String) {
            CallTimer.start(sec, label)
        }
        fun onTimerCancelled() {
            CallTimer.cancel()
        }
    }

    /** 执行电量查询并返回友好中文 */
    fun formatBatteryAnswer(context: Context): String {
        val state = DeviceBattery.read(context) ?: return "这台设备读不到电量"
        return when {
            state.charging && state.percent >= 99 -> "正在充电，已经充满啦"
            state.charging -> "正在充电，电量还有百分之${state.percent}"
            state.percent <= 15 -> "电量只剩百分之${state.percent}了，快去充电吧"
            else -> "电量还有百分之${state.percent}"
        }
    }

    /**
     * 尝试在本地或经由轻量意图解析执行一条语音指令；
     * 未命中返回 [Outcome.NotHandled]，由上层走正常 AI 对话流。
     */
    suspend fun tryHandle(
        context: Context,
        settings: AiSettings,
        text: String,
        noteStore: NoteStore,
        memoryStore: MemoryStore,
        isContinuousActive: Boolean = false,
        host: Host = object : Host {},
    ): Outcome {
        if (CallTimer.alarmActive.value) {
            if (CallTimer.matchAlarmStop(text)) {
                CallTimer.stopAlarm()
                host.onStatusHint(null)
                return Outcome.Replied("好的，不响了")
            }
            CallTimer.stopAlarm()
        }

        if (settings.robotEnabled) {
            if (RobotIntent.matchStopFast(text)) {
                val robot = robotClientOrError(settings)
                    ?: return Outcome.Replied("机器人地址还没填，请到「设置 → 机器人控制」填写机器人 IP。", "机器人地址还没填，请到设置里填写")
                return runSingleRobot(robot, RobotIntent.Command.Stop, host)
            }
            RobotIntent.matchHeadFast(text)?.let { headCmd ->
                val robot = robotClientOrError(settings)
                    ?: return Outcome.Replied("机器人地址还没填，请到「设置 → 机器人控制」填写机器人 IP。", "机器人地址还没填，请到设置里填写")
                return runSingleRobot(robot, headCmd, host)
            }
        }

        if (isContinuousActive && matchByeFast(text)) {
            host.onStatusHint(null)
            return Outcome.StopConversationRequested("好的，下次再聊")
        }

        if (matchBatteryQuestion(text)) {
            host.onStatusHint(null)
            return Outcome.Replied(formatBatteryAnswer(context))
        }

        matchVolumeFast(text)?.let { vol ->
            val label = DeviceVolume.apply(context, vol, 60)
            host.onStatusHint(if (vol == DeviceVolume.Action.MUTE) label else null)
            return Outcome.Replied(
                displayText = label,
                spokenText = label,
                shouldSpeak = vol != DeviceVolume.Action.MUTE,
            )
        }

        matchTimerFast(text)?.let { (sec, label) ->
            val cmd = RobotIntent.Command.Timer(sec, label)
            host.onTimerStarted(sec, label, cmd.confirmText)
            host.onStatusHint(null)
            return Outcome.Replied(cmd.confirmText)
        }

        matchNoteFast(text)?.let { content ->
            return executeNote(noteStore, content, host)
        }

        QuickAnswers.answer(context, text)?.let { quick ->
            host.onStatusHint(null)
            return Outcome.Replied(quick)
        }

        if (!needsAiCommandCheck(text)) return Outcome.NotHandled

        val commands = RobotIntent.parse(text, settings)
        if (commands.isEmpty()) {
            host.onStatusHint(null)
            return Outcome.NotHandled
        }
        host.onStatusHint("正在理解指令…")

        val lookIdx = commands.indexOf(RobotIntent.Command.Look)
        val chain = if (lookIdx >= 0) commands.take(lookIdx + 1) else commands

        if (chain.contains(RobotIntent.Command.Battery)) {
            host.onStatusHint(null)
            return Outcome.Replied(formatBatteryAnswer(context))
        }
        chain.filterIsInstance<RobotIntent.Command.Volume>().firstOrNull()?.let { volCmd ->
            val label = DeviceVolume.apply(context, volCmd.action, volCmd.percent)
            host.onStatusHint(if (volCmd.action == DeviceVolume.Action.MUTE) label else null)
            return Outcome.Replied(label, label, shouldSpeak = volCmd.action != DeviceVolume.Action.MUTE)
        }
        chain.filterIsInstance<RobotIntent.Command.Timer>().firstOrNull()?.let { t ->
            host.onTimerStarted(t.totalSec, t.remindText, t.confirmText)
            host.onStatusHint(null)
            return Outcome.Replied(t.confirmText)
        }
        if (chain.contains(RobotIntent.Command.TimerStop)) {
            host.onTimerCancelled()
            host.onStatusHint(null)
            return Outcome.Replied(RobotIntent.Command.TimerStop.confirmText)
        }
        chain.filterIsInstance<RobotIntent.Command.Note>().firstOrNull()?.let { n ->
            return executeNote(noteStore, n.content, host)
        }
        chain.filterIsInstance<RobotIntent.Command.MemorySearch>().firstOrNull()?.let { ms ->
            return executeMemorySearch(memoryStore, noteStore, ms.keyword, host)
        }
        if (chain.contains(RobotIntent.Command.AssistantOff)) {
            host.onStatusHint(null)
            return Outcome.StopConversationRequested(RobotIntent.Command.AssistantOff.confirmText)
        }

        if (chain.size == 1 && chain[0] == RobotIntent.Command.Look) {
            host.onStatusHint(null)
            return Outcome.LookRequested(text)
        }

        if (!settings.robotEnabled) {
            host.onStatusHint(null)
            return Outcome.NotHandled
        }

        val robot = robotClientOrError(settings)
            ?: return Outcome.Replied("机器人地址还没填，请到「设置 → 机器人控制」填写机器人 IP。", "机器人地址还没填，请到设置里填写")

        val steps = chain.map { it.label to RobotIntent.expandFlow(it) }
        val executed = mutableListOf<String>()
        val total = steps.sumOf { it.second.size }
        var done = 0
        for ((label, cmds) in steps) {
            for (cmd in cmds) {
                if (cmd == RobotIntent.Command.Look) {
                    host.onStatusHint(null)
                    return Outcome.LookRequested(text)
                }
                val err = executeRobot(robot, cmd).exceptionOrNull()
                if (err != null) {
                    val msg = "机器人没动起来：${err.message ?: "连接失败"}"
                    host.onStatusHint(err.message ?: "机器人没动起来")
                    return Outcome.Replied(msg, err.message ?: "机器人没动起来")
                }
                done++
                if (done < total) {
                    host.onStatusHint("执行中（$done/$total）…")
                    delay(estimateMillis(cmd))
                }
            }
            executed.add(label)
        }
        val spoken = "好的，" + executed.joinToString("，然后")
        host.onStatusHint(null)
        return Outcome.Replied(spoken)
    }

    private suspend fun executeNote(noteStore: NoteStore, content: String, host: Host): Outcome {
        val ok = runCatching { noteStore.add(content) }.getOrNull() != null
        return if (ok) {
            host.onStatusHint(null)
            Outcome.Replied("记好了：$content", "记好了")
        } else {
            host.onStatusHint("记事失败")
            Outcome.Replied("没记下来，请再说一次")
        }
    }

    private fun executeMemorySearch(
        memory: MemoryStore,
        notes: NoteStore,
        keyword: String,
        host: Host,
    ): Outcome {
        val k = keyword.trim()
        if (k.isBlank()) return Outcome.NotHandled
        val sdf = SimpleDateFormat("M月d日", Locale.CHINA)
        val lines = mutableListOf<String>()
        memory.entries.value
            .filter { it.title.contains(k) || it.detail.contains(k) }
            .take(3)
            .forEach { e ->
                val title = e.title.ifBlank { e.detail.take(30) }
                lines += "▪ $title（${sdf.format(Date(e.ts))}）"
            }
        notes.notes.value
            .filter { it.text.contains(k) }
            .take(3)
            .forEach { n ->
                lines += "▪ 📝 ${n.text.take(40)}（${sdf.format(Date(n.ts))}）"
            }
        val spoken = if (lines.isEmpty()) {
            "记忆里没有找到「$k」相关的内容"
        } else {
            val first = lines.first().removePrefix("▪ ").removePrefix("📝 ")
            "找到了${lines.size}条，最相关的是：$first"
        }
        val bubble = if (lines.isEmpty()) spoken
        else "在记忆和随手记里找到「$k」相关 ${lines.size} 条：\n" + lines.joinToString("\n")
        host.onStatusHint(null)
        return Outcome.Replied(bubble, spoken)
    }

    private fun robotClientOrError(settings: AiSettings): RobotClient? {
        val host = RobotClient.normalizeHost(settings.robotHost) ?: return null
        return RobotClient(host)
    }

    private suspend fun executeRobot(robot: RobotClient, cmd: RobotIntent.Command): Result<Unit> = when (cmd) {
        RobotIntent.Command.Stop -> robot.stop()
        is RobotIntent.Command.Move -> robot.move(cmd.distCm)
        is RobotIntent.Command.Turn -> robot.turn(cmd.angleDeg)
        is RobotIntent.Command.Head -> robot.head(cmd.angleDeg)
        else -> Result.success(Unit)
    }

    private suspend fun runSingleRobot(
        robot: RobotClient,
        cmd: RobotIntent.Command,
        host: Host,
    ): Outcome = executeRobot(robot, cmd).fold(
        onSuccess = {
            host.onStatusHint(null)
            Outcome.Replied(cmd.confirmText)
        },
        onFailure = { err ->
            val msg = "机器人没动起来：${err.message ?: "连接失败"}"
            host.onStatusHint(err.message ?: "机器人没动起来")
            Outcome.Replied(msg, err.message ?: "机器人没动起来")
        },
    )

    private fun estimateMillis(cmd: RobotIntent.Command): Long = when (cmd) {
        is RobotIntent.Command.Move -> ((kotlin.math.abs(cmd.distCm) / 8f).coerceIn(1f, 8f) * 1000).toLong()
        is RobotIntent.Command.Turn -> ((kotlin.math.abs(cmd.angleDeg) / 60f).coerceIn(1f, 6f) * 1000).toLong()
        is RobotIntent.Command.Head -> 700L
        else -> 500L
    }
}
