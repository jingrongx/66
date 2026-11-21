package com.lvyoubao.app.data

/**
 * 语音控制机器人：把用户说的一句话解析成一条控制指令，两级解析：
 * 1. 本地急停快路 [matchStopFast]：「停」「别动」类词直接命中 STOP，零延迟抢占（急停不能等 AI 往返）；
 * 2. AI 解析 [parse]：chatPlain 单轮（思考关闭，所有 OpenAI 兼容服务商可用），
 *    模型按协议只回一行「动作 数值」，解析不出按 NONE 处理——回落正常聊天，绝不误动。
 */
object RobotIntent {

    /** 一条控制指令；None 表示不是在指挥机器人 */
    sealed class Command {
        data object Stop : Command()
        data class Move(val distCm: Int) : Command()
        data class Turn(val angleDeg: Int) : Command()
        data class Head(val angleDeg: Int) : Command()
        /** 成套动作流程：AI 只报流程名+次数，本地展开成指令链（流程起止都是平视/原朝向） */
        data class Flow(val kind: FlowKind, val reps: Int = 1) : Command()
        enum class FlowKind { NOD, SHAKE, BOW, LOOK_AROUND }
        data object Look : Command()
        data object Battery : Command()
        data class Volume(val action: DeviceVolume.Action, val percent: Int = 60) : Command()
        data class Timer(val totalSec: Int, val remindText: String = "") : Command()
        data object TimerStop : Command()
        data class Note(val content: String) : Command()
        data class MemorySearch(val keyword: String) : Command()
        data object AssistantOff : Command()
        data object None : Command()

        /** 时长话术：90→「1分半钟」太复杂，直接 X小时Y分钟Z秒 拼接 */
        fun durationText(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts += "${h}小时"
            if (m > 0) parts += "${m}分钟"
            if (s > 0 || parts.isEmpty()) parts += "${s}秒"
            return parts.joinToString("")
        }

        /** 执行成功后的语音确认话术（短、口语、老人友好） */
        val confirmText: String
            get() = when (this) {
                Stop -> "好的，已停下"
                is Move -> if (distCm >= 0) "好的，前进${distCm}厘米" else "好的，后退${-distCm}厘米"
                is Turn -> if (angleDeg >= 0) "好的，左转${angleDeg}度" else "好的，右转${-angleDeg}度"
                is Head -> when {
                    angleDeg > 0 -> "好的，抬头看看"
                    angleDeg == 0 -> "好的，平视了"
                    else -> "好的，低头啦"
                }
                is Flow -> when (kind) {
                    FlowKind.NOD -> if (reps > 1) "好的，点了${reps}下头" else "好的，点个头"
                    FlowKind.SHAKE -> "好的，摇摇头"
                    FlowKind.BOW -> "好的，鞠个躬"
                    FlowKind.LOOK_AROUND -> "好的，我看看四周"
                }
                Look -> "好的，我看看"
                Battery -> ""
                is Volume -> ""
                is Timer -> if (remindText.isBlank()) "好的，${durationText(totalSec)}倒计时开始"
                    else "好的，${durationText(totalSec)}后提醒你$remindText"
                TimerStop -> "好的，计时取消了"
                is Note -> "记好了"
                is MemorySearch -> ""
                AssistantOff -> "好的，下次再聊"
                None -> ""
            }

        /** 短标签：一句话多个动作时的播报拼接用（「好的，抬头，然后低头」） */
        val label: String
            get() = when (this) {
                Stop -> "停下"
                is Move -> if (distCm >= 0) "前进${distCm}厘米" else "后退${-distCm}厘米"
                is Turn -> if (angleDeg >= 0) "左转${angleDeg}度" else "右转${-angleDeg}度"
                is Head -> when {
                    angleDeg > 0 -> "抬头"
                    angleDeg == 0 -> "平视"
                    else -> "低头"
                }
                is Flow -> when (kind) {
                    FlowKind.NOD -> if (reps > 1) "点${reps}下头" else "点头"
                    FlowKind.SHAKE -> "摇头"
                    FlowKind.BOW -> "鞠躬"
                    FlowKind.LOOK_AROUND -> "看看四周"
                }
                Look -> "看看前面"
                Battery -> "报一下电量"
                is Volume -> "调音量"
                is Timer -> if (remindText.isBlank()) "开始${durationText(totalSec)}倒计时"
                    else "${durationText(totalSec)}后提醒你$remindText"
                TimerStop -> "取消计时"
                is Note -> "记一笔"
                is MemorySearch -> "查记忆"
                AssistantOff -> "道别"
                None -> ""
            }
    }

    /** 急停词表：整句短语匹配（「停下的时候提醒我」这类长句不会误伤，长句交给 AI） */
    private val stopPhrases = listOf(
        "停下", "停住", "停止", "停一下", "停一停", "停下来", "先停", "别动", "别走了", "别走",
        "站住", "不许动", "急停",
    )

    /** 本地急停快路：短句 + 命中停词即算。否定句（别停/不要停）绝不是急停，直接放行 */
    fun matchStopFast(text: String): Boolean {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 6) return false
        if (t == "停") return true
        if (t.startsWith("别停") || t.startsWith("不要停") || t.startsWith("不能停") ||
            t.startsWith("不许停") || t.startsWith("不准停") || t.startsWith("不停")
        ) return false
        return stopPhrases.any { t == it || t.startsWith(it) }
    }

    /** 头部动作词表：平视回正（HEAD 0）/ 抬头（15）/ 低头（-10），短句直接命中 */
    private val headLevelPhrases = listOf("平视", "正视", "回正", "头回正", "摆正", "头摆正", "平视一点", "头平一点")
    private val headUpPhrases = listOf("抬头", "头抬起来", "抬一下头", "抬头一点")
    private val headDownPhrases = listOf("低头", "头低下去", "低点头", "低头一点")

    /**
     * 头部动作快路：平视/抬头/低头类短句直接返回指令，不经 AI。
     * 零延迟之外更堵住误判：v0.8.88 前「平视」不在协议示例里，AI 往拍照（LOOK）方向猜过。
     */
    fun matchHeadFast(text: String): Command? {
        val t = text.trim().trimEnd('。', '！', '!', '.', '，', ',')
        if (t.isEmpty() || t.length > 6) return null
        if (t.startsWith("别") || t.startsWith("不要") || t.startsWith("不准") || t.startsWith("不能")) return null
        return when {
            headLevelPhrases.any { t == it || t.startsWith(it) } -> Command.Head(0)
            headUpPhrases.any { t == it || t.startsWith(it) } -> Command.Head(15)
            headDownPhrases.any { t == it || t.startsWith(it) } -> Command.Head(-10)
            else -> null
        }
    }

    /**
     * 动作流程展开：成套动作 = 清晰的流程定义（起点平视/原朝向 → 动作 → 终点回正），
     * AI 只报流程名+次数，这里负责转成控制指令序列。播报用流程名，不逐条念指令。
     */
    fun expandFlow(cmd: Command): List<Command> = when (cmd) {
        is Command.Flow -> when (cmd.kind) {
            // 点头：平视 → (抬头 → 低头)×次数 → 回平视
            Command.FlowKind.NOD -> buildList {
                add(Command.Head(0))
                repeat(cmd.reps.coerceIn(1, 3)) {
                    add(Command.Head(12))
                    add(Command.Head(-8))
                }
                add(Command.Head(0))
            }
            // 摇头：头部没有左右自由度，身体小幅左右转模拟，净转角回正
            Command.FlowKind.SHAKE -> buildList {
                repeat(cmd.reps.coerceIn(1, 3)) {
                    add(Command.Turn(20))
                    add(Command.Turn(-40))
                    add(Command.Turn(20))
                }
            }
            // 鞠躬：平视 → 低头到接近最低 → 回平视
            Command.FlowKind.BOW -> listOf(Command.Head(0), Command.Head(-20), Command.Head(0))
            // 东张西望：左右转看一圈，净转角回正
            Command.FlowKind.LOOK_AROUND -> listOf(Command.Turn(30), Command.Turn(-60), Command.Turn(30))
        }
        else -> listOf(cmd)
    }

    /** AI 解析协议：每行一个「动作 数值」，一句话可含多个动作按顺序输出；宁 NONE 勿动 */
    private val SYSTEM_PROMPT = """
        你是机器人语音指令解析器。把用户这句话解析成指令序列，每个动作单独一行「动作 数值」，绝不解释。
        可用动作（优必选小方头）：
        MOVE 厘米——底盘前进/后退：正数前进、负数后退，没说距离默认30。例：「向前走30厘米」→MOVE 30；「后退一点」→MOVE -15；「往前走走」→MOVE 30
        TURN 度——转向：正数左转、负数右转；没说角度按「左转/右转90、转个身180、一点30」。例：「左转90度」→TURN 90；「右转一点」→TURN -30；「转个身」→TURN 180
        HEAD 度——头部抬头/低头/摆正：范围-25到23，正抬头负低头，0是平视。例：「抬头」→HEAD 15；「低头」→HEAD -10；「平视」「正视」「头摆正」「头回正」→HEAD 0；「看天上」「看天花板」→HEAD 22；「看地上」→HEAD -18
        成套动作用「动作流程」，流程起止都有定义（起点平视/原朝向，终点回正），只输出「流程 次数」一行，不要自己拆成 HEAD/TURN：
        NOD 次数——点头流程（平视→抬头→低头→回平视）：「点头」「点点头」→NOD 1；「连续点头」「点两下头」→NOD 2；次数限1-3
        SHAKE 次数——摇头流程（头没有左右自由度，身体小幅左右转模拟、转角回正）：「摇头」「摇摇头」→SHAKE 1
        BOW——鞠躬流程（平视→低头到接近最低→回平视）：「鞠躬」「行个礼」→BOW
        LOOK_AROUND——环顾流程（左右转看一圈、转角回正）：「东张西望」「到处看看」「环顾四周」→LOOK_AROUND
        复合移动动作要分解组合：机器人只有三个原语（MOVE 前后、TURN 转向、HEAD 俯仰），把用户要的复合动作按执行顺序逐行拆成原语，拆解规则：
        ①朝向复原——为走斜向/侧移临时转的角度，走完必须转回来；只有用户明确要换朝向（转身/掉头/面向我）才保持
        ②安全小值——没说距离用20-30厘米，没说角度用30-45度，绝不超过100厘米
        ③来回类=去+回；绕圈类=「走一段+转一段」重复拼出圆形；最多10行
        例：「向左前方走一点」→ 三行：TURN 45、MOVE 20、TURN -45；「来回走两趟」→ 四行：MOVE 30、MOVE -30、MOVE 30、MOVE -30；「原地转个圈」→TURN 360；「画个圈」→ 八行：MOVE 20、TURN 45 重复4次；「走个正方形」→ 八行：MOVE 30、TURN 90 重复4次；「后退着左转」→ 三行：TURN 30、MOVE -20、TURN -30；「扭一扭」「跳个舞」→ 四行：TURN 20、TURN -40、TURN 20、HEAD 0
        LOOK——用摄像头拍一张照片看看眼前。只要用户想让小源当场用摄像头看一眼（说法五花八门，不限于特定词），都算 LOOK：如「看看前面有什么」「前面是什么」「看看这是什么」「你看到了什么」「看看周围」「帮我看看眼前的」「拍张照看看」「瞅一眼」。判断标准：动作是「当场拍照看环境」，不是查资料问知识。注意「平视/正视/头摆正」是头部动作（HEAD 0），「抬头看看你」是抬头动作（HEAD），都不是 LOOK。
        BATTERY——报一下小源自己这台设备的电量。例：「你电量还剩多少」「快没电了吗」「电量多少」→BATTERY。注意问的是小源本机，不是机器人；只单发，不与其他动作同句。
        TIMER 秒数 要提醒的事——开始一个倒计时，把时长换算成总秒数；带提醒事项时把事项写在秒数后面：「定个5分钟的倒计时」→TIMER 300；「计时10分钟」→TIMER 600；「15分钟后提醒我去看锅」→TIMER 900 看锅；支持小时/分钟/秒组合（1小时30分=5400）
        TIMER_STOP——取消正在跑的倒计时：「取消计时」「关掉倒计时」→TIMER_STOP。注意「停下/别说了」是停止播报（STOP），别混
        NOTE 记事内容——帮用户记一笔随手记，内容保留用户原话要点：「记一下，明天九点开会」→NOTE 明天九点开会；「帮我记着这事：后天交材料」→NOTE 后天交材料
        MEMORY_SEARCH 关键词——在用户的记忆和随手记里找东西，提炼一两个检索词，别照抄整句：「记忆里有没有火锅店」→MEMORY_SEARCH 火锅；「我之前记过什么药」→MEMORY_SEARCH 药；「上次说的那个馆子叫啥」→MEMORY_SEARCH 馆子
        CONTINUOUS_OFF——道别/结束对话（用户想收尾了）：「再见」「拜拜」「我走了」「先这样吧」「不聊了」「退出对话」→CONTINUOUS_OFF。注意讨论「再见怎么写」这类话题不是道别；「别响了」是停提醒音（响铃时才有），别混
        VOLUME_UP——把小源的说话音量调大：「大点声」「大声点」「听不清，声音大些」→VOLUME_UP
        VOLUME_DOWN——调小：「小点声」「小声点」「太吵了」→VOLUME_DOWN
        VOLUME_MUTE——静音不出声：「静音」「别出声了」「把声音关掉」→VOLUME_MUTE。注意「停下/别说了」是停止播报（STOP），「别那么大声」是调小（VOLUME_DOWN），都跟静音不同
        VOLUME_MAX——音量调到最大：「最大声」「音量调到最大」→VOLUME_MAX
        VOLUME_SET 百分比——直接定到某音量：「音量调到50」「声音百分之三十」→VOLUME_SET 50（0-100 的整数）
        STOP——立即停止。例：「停下」「别动了」→STOP
        NONE——这句话不是在指挥机器人（闲聊、问天气、问路线、查资料等），交给正常聊天。判断按语义不按字面：问「附近有什么好吃的」是 NONE，「帮我看看前面有什么」是 LOOK，「点头」是动作链（HEAD 12 和 HEAD -8）。
        用户一句话里有几个动作就输出几行（复合移动拆解后最多10行），按说话的先后顺序排：
        例：「点头然后摇头」→ 两行：NOD 1 和 SHAKE 1；「抬头然后低头」→ 两行：HEAD 15 和 HEAD -10；「先前进30再左转90度」→ MOVE 30 和 TURN 90；「转个身看看前面」→ TURN 180 和 LOOK。
        单个动作就输出一行。只输出「动作 数值」行（如 MOVE 30、TURN -90、HEAD 15、NOD 2、SHAKE 1、BOW、LOOK_AROUND、LOOK、BATTERY、TIMER 900 关火、NOTE 明天九点开会、MEMORY_SEARCH 火锅、VOLUME_SET 50、CONTINUOUS_OFF、STOP），绝不输出其他任何内容；拿不准一律输出一行 NONE。
    """.trimIndent()

    /**
     * FC 工具的 actions JSON → 文本指令行（复用 parseReply 的解析/钳制/展开管线）。
     * part 映射：nod→NOD 次数、shake→SHAKE 次数、bow→BOW、look_around→LOOK_AROUND、
     * head/move/turn→HEAD/MOVE/TURN 数值、stop→STOP。
     */
    fun actionsToJsonCommands(argumentsJson: String): String {
        val out = mutableListOf<String>()
        runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
                .let { it as? kotlinx.serialization.json.JsonObject }?.get("actions")
                as? kotlinx.serialization.json.JsonArray ?: return ""
            for (el in arr) {
                val obj = el as? kotlinx.serialization.json.JsonObject ?: continue
                fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                fun n(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                when (s("part")?.lowercase()) {
                    "nod" -> out += "NOD ${(n("reps") ?: 1.0).toInt()}"
                    "shake" -> out += "SHAKE ${(n("reps") ?: 1.0).toInt()}"
                    "bow" -> out += "BOW"
                    "look_around" -> out += "LOOK_AROUND"
                    "head" -> out += "HEAD ${(n("value") ?: 0.0).toInt()}"
                    "move" -> out += "MOVE ${(n("value") ?: 30.0).toInt()}"
                    "turn" -> out += "TURN ${(n("value") ?: 90.0).toInt()}"
                    "stop" -> out += "STOP"
                }
            }
        }
        return out.joinToString("\n")
    }

    /** AI 解析：超 8 秒或网络/服务失败一律返回空表（回落聊天，控制不能卡死语音主流程） */
    suspend fun parse(text: String, settings: AiSettings): List<Command> {
        val reply = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            AiClient(settings).chatPlain(
                messages = listOf(
                    OutMessage("system", SYSTEM_PROMPT),
                    OutMessage("user", text),
                ),
                thinking = false,
            ).getOrNull()
        } ?: return emptyList()
        return parseReply(reply)
    }

    /**
     * 从模型回复里逐行抠指令，按顺序最多取 4 条：
     * - NOTE/MEMORY_SEARCH 带内容（「NOTE 明天九点开会」），内容保留原大小写
     * - 其余「动作 数字」（TIMER 300 / MOVE 30 / …），容忍一行里多个动作与前后杂讯
     */
    fun parseReply(reply: String): List<Command> {
        val out = mutableListOf<Command>()
        for (raw in reply.trim().lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            // 带内容的指令（关键字在前，内容保留原样）
            val contentM = Regex("^(NOTE|MEMORY_SEARCH)\\s*[:：]?\\s*(.+)$").find(upper)
            if (contentM != null) {
                val content = line.substring(contentM.groupValues[1].length).trimStart(' ', ':', '：', '，')
                if (content.isNotBlank()) {
                    out += if (contentM.groupValues[1] == "NOTE") Command.Note(content) else Command.MemorySearch(content)
                }
                continue
            }
            // TIMER 带提醒事项（「TIMER 900 关火」）：先于通用动作行单独抠。
            // 注意取「秒数组之后」的余文：整 match 的 range 会吞到行尾，label 永远空串
            val timerM = Regex("^TIMER\\s+(\\d{1,6})\\s*(.*)$").find(upper)
            if (timerM != null) {
                val sec = timerM.groupValues[1].toIntOrNull() ?: 60
                val label = timerM.groups[1]?.range?.last
                    ?.let { upper.substring(it + 1) }?.trim().orEmpty()
                out += Command.Timer(sec, label)
                continue
            }
            // 「动作 数字」指令（一行内可能塞多个）。LOOK_AROUND 必须排在 LOOK 前，否则会被截短
            Regex("(TIMER_STOP|CONTINUOUS_OFF|LOOK_AROUND|NOD|SHAKE|BOW|MOVE|TURN|HEAD|STOP|LOOK|BATTERY|VOLUME_SET|VOLUME_UP|VOLUME_DOWN|VOLUME_MUTE|VOLUME_MAX|VOLUME_RESTORE|VOLUME)\\s*(-?\\d+)?")
                .findAll(upper)
                .forEach { m ->
                    val value = m.groupValues[2].toIntOrNull()
                    when (m.groupValues[1]) {
                        "STOP" -> out += Command.Stop
                        "CONTINUOUS_OFF" -> out += Command.AssistantOff
                        "NOD" -> out += Command.Flow(Command.FlowKind.NOD, (value ?: 1).coerceIn(1, 3))
                        "SHAKE" -> out += Command.Flow(Command.FlowKind.SHAKE, (value ?: 1).coerceIn(1, 3))
                        "BOW" -> out += Command.Flow(Command.FlowKind.BOW)
                        "LOOK_AROUND" -> out += Command.Flow(Command.FlowKind.LOOK_AROUND)
                        // 物理量钳制：AI 偶发输出离谱值时兜底，别让机器人疯跑
                        "MOVE" -> out += Command.Move((value ?: 30).coerceIn(-100, 100))
                        "TURN" -> out += Command.Turn((value ?: 90).coerceIn(-360, 360))
                        "HEAD" -> out += Command.Head((value ?: 15).coerceIn(-25, 23))
                        "LOOK" -> out += Command.Look
                        "BATTERY" -> out += Command.Battery
                        "VOLUME_UP" -> out += Command.Volume(DeviceVolume.Action.UP)
                        "VOLUME_DOWN" -> out += Command.Volume(DeviceVolume.Action.DOWN)
                        "VOLUME_MUTE" -> out += Command.Volume(DeviceVolume.Action.MUTE)
                        "VOLUME_MAX" -> out += Command.Volume(DeviceVolume.Action.MAX)
                        "VOLUME_RESTORE" -> out += Command.Volume(DeviceVolume.Action.RESTORE)
                        "VOLUME_SET" -> out += Command.Volume(DeviceVolume.Action.SET, value ?: 60)
                        "VOLUME" -> out += if (value != null) Command.Volume(DeviceVolume.Action.SET, value) else Command.Volume(DeviceVolume.Action.UP)
                        else -> {}
                    }
                }
        }
        return out.take(10)
    }
}
