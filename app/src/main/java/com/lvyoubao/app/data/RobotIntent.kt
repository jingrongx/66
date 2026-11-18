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
        data object Look : Command()
        data object Battery : Command()
        data object None : Command()

        /** 执行成功后的语音确认话术（短、口语、老人友好） */
        val confirmText: String
            get() = when (this) {
                Stop -> "好的，已停下"
                is Move -> if (distCm >= 0) "好的，前进${distCm}厘米" else "好的，后退${-distCm}厘米"
                is Turn -> if (angleDeg >= 0) "好的，左转${angleDeg}度" else "好的，右转${-angleDeg}度"
                is Head -> if (angleDeg >= 0) "好的，抬头看看" else "好的，低头啦"
                Look -> "好的，我看看"
                Battery -> ""
                None -> ""
            }

        /** 短标签：一句话多个动作时的播报拼接用（「好的，抬头，然后低头」） */
        val label: String
            get() = when (this) {
                Stop -> "停下"
                is Move -> if (distCm >= 0) "前进${distCm}厘米" else "后退${-distCm}厘米"
                is Turn -> if (angleDeg >= 0) "左转${angleDeg}度" else "右转${-angleDeg}度"
                is Head -> if (angleDeg >= 0) "抬头" else "低头"
                Look -> "看看前面"
                Battery -> "报一下电量"
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

    /** AI 解析协议：每行一个「动作 数值」，一句话可含多个动作按顺序输出；宁 NONE 勿动 */
    private val SYSTEM_PROMPT = """
        你是机器人语音指令解析器。把用户这句话解析成指令序列，每个动作单独一行「动作 数值」，绝不解释。
        可用动作（优必选小方头）：
        MOVE 厘米——底盘前进/后退：正数前进、负数后退，没说距离默认30。例：「向前走30厘米」→MOVE 30；「后退一点」→MOVE -15；「往前走走」→MOVE 30
        TURN 度——转向：正数左转、负数右转；没说角度按「左转/右转90、转个身180、一点30」。例：「左转90度」→TURN 90；「右转一点」→TURN -30；「转个身」→TURN 180
        HEAD 度——头部抬头/低头：范围-25到23，正抬头负低头，没说默认15。例：「抬头」→HEAD 15；「低头」→HEAD -10
        LOOK——用摄像头拍一张照片看看眼前。只要用户想让小源当场用摄像头看一眼（说法五花八门，不限于特定词），都算 LOOK：如「看看前面有什么」「前面是什么」「看看这是什么」「你看到了什么」「看看周围」「帮我看看眼前的」「拍张照看看」「瞅一眼」。判断标准：动作是「当场拍照看环境」，不是查资料问知识。
        BATTERY——报一下小源自己这台设备的电量。例：「你电量还剩多少」「快没电了吗」「电量多少」→BATTERY。注意问的是小源本机，不是机器人；只单发，不与其他动作同句。
        STOP——立即停止。例：「停下」「别动了」→STOP
        NONE——这句话不是在指挥机器人（闲聊、问天气、问路线、查资料等），交给正常聊天。注意「抬头看看你」是抬头的动作（HEAD），问「附近有什么好吃的」是 NONE，都不是 LOOK。
        用户一句话里有几个动作就输出几行（最多4行），按说话的先后顺序排：
        例：「抬头然后低头」→ 两行：HEAD 15 和 HEAD -10；「先前进30再左转90度」→ MOVE 30 和 TURN 90；「转个身看看前面」→ TURN 180 和 LOOK。
        单个动作就输出一行。只输出「动作 数值」行（如 MOVE 30、TURN -90、HEAD 15、LOOK、BATTERY、STOP），绝不输出其他任何内容；拿不准一律输出一行 NONE。
    """.trimIndent()

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

    /** 从模型回复里抠所有「动作 数字」行（容忍分隔符/大小写杂讯），按顺序最多取 4 条 */
    fun parseReply(reply: String): List<Command> {
        val s = reply.trim().uppercase()
        return Regex("(MOVE|TURN|HEAD|STOP|LOOK|BATTERY)\\s*(-?\\d+)?").findAll(s).take(4).mapNotNull { m ->
            val value = m.groupValues[2].toIntOrNull()
            when (m.groupValues[1]) {
                "STOP" -> Command.Stop
                "MOVE" -> Command.Move(value ?: 30)
                "TURN" -> Command.Turn(value ?: 90)
                "HEAD" -> Command.Head(value ?: 15)
                "LOOK" -> Command.Look
                "BATTERY" -> Command.Battery
                else -> null
            }
        }.toList()
    }
}
