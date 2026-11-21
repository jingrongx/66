package com.lvyoubao.app.data

import android.content.Context
import com.lvyoubao.app.TravelApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 本机能力 FC 工具：豆包全双工通话里，模型直接调用设备/机器人能力。
 *
 * 为什么不只在客户端拦截：本机命令链（handleAssistantIntent）只挂在灵动模式，
 * 大字/普通模式的通话说「抬头」「定个计时」一直无人响应——把能力做成 FC 工具挂进
 * session.create 的 tools，三个模式自动对齐（灵动模式仍走本地链，不重复挂载）。
 */
object DeviceTools {

    const val ROBOT = "control_robot"
    const val TIMER = "set_timer"
    const val TIMER_CANCEL = "cancel_timer"
    const val NOTE = "take_note"
    const val MEMORY = "search_memory"
    const val VOLUME = "set_volume"
    const val BATTERY = "report_battery"

    val definitions = listOf(
        AppTools.ToolDef(
            name = ROBOT,
            description = "指挥机器人做动作（需已连接机器人控制中枢）。actions 是按顺序执行的动作数组，" +
                "每个动作 part 取值：nod=点头(平视→抬头→低头→回平视)、shake=摇头(身体小幅模拟)、bow=鞠躬、" +
                "look_around=环顾四周、head=抬头/低头(value 为角度，抬头 10~23、低头 -10~-25、0=平视)、" +
                "move=前进/后退(value 为厘米，后退用负数)、turn=左转/右转(value 为度，右转用负数)、stop=急停；" +
                "nod/shake 可带 reps(次数,1-3)。复合动作（斜走/来回/绕圈）自己拆成数组按序给出，临时转向记得转回来",
            parameters = """{"type":"object","properties":{"actions":{"type":"array","description":"按顺序执行的动作列表","items":{"type":"object","properties":{"part":{"type":"string","description":"nod|shake|bow|look_around|head|move|turn|stop"},"value":{"type":"number","description":"head 角度(-25~23)/move 厘米(-100~100)/turn 角度(-360~360)"},"reps":{"type":"number","description":"nod/shake 的重复次数 1-3"}},"required":["part"]}}},"required":["actions"]}""",
        ),
        AppTools.ToolDef(
            name = TIMER,
            description = "给用户定一个倒计时，时间到了会用语音提醒",
            parameters = """{"type":"object","properties":{"seconds":{"type":"number","description":"总秒数（5分钟=300）"},"label":{"type":"string","description":"要提醒的事，如'关火'，可空"}},"required":["seconds"]}""",
        ),
        AppTools.ToolDef(
            name = TIMER_CANCEL,
            description = "取消正在进行的倒计时",
            parameters = """{"type":"object","properties":{},"required":[]}""",
        ),
        AppTools.ToolDef(
            name = NOTE,
            description = "帮用户记一笔随手记（备忘）",
            parameters = """{"type":"object","properties":{"content":{"type":"string","description":"要记的内容要点"}},"required":["content"]}""",
        ),
        AppTools.ToolDef(
            name = MEMORY,
            description = "在用户的长期记忆和随手记里查找信息（用户问「我之前说过什么」「记没记过X」时用）",
            parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"一两个检索词"}},"required":["keyword"]}""",
        ),
        AppTools.ToolDef(
            name = VOLUME,
            description = "调节设备音量",
            parameters = """{"type":"object","properties":{"action":{"type":"string","description":"up=调大 down=调小 mute=静音 max=最大 set=直接设定"},"percent":{"type":"number","description":"action=set 时的目标音量 0-100"}},"required":["action"]}""",
        ),
        AppTools.ToolDef(
            name = BATTERY,
            description = "查询本机设备电量",
            parameters = """{"type":"object","properties":{},"required":[]}""",
        ),
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 时长话术（与 RobotIntent.Command.durationText 同口径） */
    private fun durationText(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "${h}小时"
        if (m > 0) parts += "${m}分钟"
        if (s > 0 || parts.isEmpty()) parts += "${s}秒"
        return parts.joinToString("")
    }

    /** 工具执行：返回给模型的文本（成功摘要或失败原因，模型据此口头回应） */
    suspend fun execute(context: Context, settings: AiSettings, name: String, argumentsJson: String): String {
        val args: kotlinx.serialization.json.JsonObject? = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        val arg = { key: String ->
            args?.get(key)?.let { runCatching { it.toString().trim('"') }.getOrNull() }
        }
        val argNum = { key: String -> args?.get(key)?.toString()?.trim('"')?.toDoubleOrNull() }
        return try {
            when (name) {
                ROBOT -> executeRobot(context, settings, argumentsJson)
                TIMER -> {
                    val sec = (argNum("seconds") ?: 60.0).toInt().coerceIn(3, 24 * 3600)
                    val label = arg("label").orEmpty()
                    CallTimer.start(sec, label)
                    "倒计时已开始：${durationText(sec)}" + (if (label.isNotBlank()) "，到点提醒：$label" else "")
                }
                TIMER_CANCEL -> if (CallTimer.cancel()) "倒计时已取消" else "当前没有在计时的任务"
                NOTE -> {
                    val content = arg("content") ?: throw ApiException("缺少 content")
                    val note = (context.applicationContext as? TravelApp)?.noteStore?.add(content)
                    if (note != null) "已记好：$content" else "记事保存失败"
                }
                MEMORY -> {
                    val kw = arg("keyword") ?: throw ApiException("缺少 keyword")
                    val store = (context.applicationContext as? TravelApp)?.memoryStore
                    val hit = store?.relevantContext(kw, limit = 3).orEmpty()
                    if (hit.isBlank()) "记忆和随手记里没找到相关内容" else hit
                }
                VOLUME -> {
                    val action = when (arg("action")?.lowercase()) {
                        "up" -> DeviceVolume.Action.UP
                        "down" -> DeviceVolume.Action.DOWN
                        "mute" -> DeviceVolume.Action.MUTE
                        "max" -> DeviceVolume.Action.MAX
                        "set" -> DeviceVolume.Action.SET
                        else -> DeviceVolume.Action.UP
                    }
                    DeviceVolume.apply(context, action, (argNum("percent") ?: 60.0).toInt())
                }
                BATTERY -> DeviceBattery.read(context)
                    ?.let { "当前电量约 ${it.percent}%${if (it.charging) "，正在充电" else ""}" }
                    ?: "暂时读不到电量"
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.message ?: "未知原因"}。请如实告知用户。"
        }
    }

    /** 机器人动作执行：解析 actions 数组 → 展开流程 → 中枢逐条下发 */
    private suspend fun executeRobot(context: Context, settings: AiSettings, argumentsJson: String): String {
        if (!settings.robotEnabled) return "机器人控制未开启（设置 → 机器人控制），请口头告知用户。"
        val host = RobotClient.normalizeHost(settings.robotHost)
            ?: return "机器人地址未配置，请口头告知用户去设置里填写。"
        val robot = RobotClient(host)
        robot.ping().fold(
            onSuccess = { },
            onFailure = { return "机器人中枢连不上（${it.message ?: "网络错误"}），本次动作没执行。" },
        )
        // 解析 actions 数组为指令（复用 RobotIntent 的展开与钳制）
        val text = RobotIntent.actionsToJsonCommands(argumentsJson)
        if (text.isBlank()) return "没有解析出可执行的动作"
        val commands = RobotIntent.parseReply(text).flatMap { RobotIntent.expandFlow(it) }
        if (commands.isEmpty()) return "没有解析出可执行的动作"
        val executed = mutableListOf<String>()
        loop@ for (cmd in commands.take(12)) {
            if (cmd is RobotIntent.Command.Stop) {
                robot.stop()
                executed.add("急停")
                break@loop
            }
            val result: Result<Unit> = when (cmd) {
                is RobotIntent.Command.Move -> robot.move(cmd.distCm)
                is RobotIntent.Command.Turn -> robot.turn(cmd.angleDeg)
                is RobotIntent.Command.Head -> robot.head(cmd.angleDeg)
                else -> Result.success(Unit)
            }
            result.fold(
                onSuccess = { if (cmd.label.isNotBlank()) executed.add(cmd.label) },
                onFailure = {
                    return "动作执行中断：${it.message ?: "连接失败"}（已完成：${executed.joinToString("、")}）"
                },
            )
            delay(
                when (cmd) {
                    is RobotIntent.Command.Move -> ((kotlin.math.abs(cmd.distCm) / 8f).coerceIn(1f, 8f) * 1000).toLong()
                    is RobotIntent.Command.Turn -> ((kotlin.math.abs(cmd.angleDeg) / 60f).coerceIn(1f, 6f) * 1000).toLong()
                    is RobotIntent.Command.Head -> 700L
                    else -> 300L
                },
            )
        }
        return "已执行：" + executed.joinToString("、")
    }

    /** 通话中的即时急停：识别流里蹦出停类词时立刻停机器人（不等句尾） */
    suspend fun stopRobot(context: Context, settings: AiSettings) {
        if (!settings.robotEnabled) return
        val host = RobotClient.normalizeHost(settings.robotHost) ?: return
        withContext(Dispatchers.IO) { runCatching { RobotClient(host).stop() } }
    }
}
