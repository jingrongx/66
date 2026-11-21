package com.lvyoubao.app.data.duplex

import android.content.Context
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.TravelApp

/**
 * 豆包全双工通话的开工配置：把「历史会话记忆 + 项目能力」一次性塞进 session.create。
 *
 * 记忆分两层：
 * - instructions：人设 + 长期记忆画像 + 时间/位置情境（问天气不知道在哪，就是缺这块）；
 * - dialog_context：共享受话数据的最近成对 QA，跨通话/跨模式（普通模式聊过的，打电话也记得）。
 *
 * 项目能力走 FC：AppTools.definitions（天气/附近/路线），模型语音里自己决定调用，
 * 本地执行完由 DoubaoDuplexClient.sendToolResult 回传。
 */
object DuplexCallSetup {

    /** 服务端上下文上限：20 轮 = 40 条 */
    private const val MAX_TURNS = 20

    /** 大字/普通模式通话人设（与 DoubaoDuplexClient 默认 instructions 同口径） */
    const val PERSONA_TRAVEL = "你是旅游宝，一个简洁、口语化、带数字的随身助手。回答尽量短，像朋友聊天。"

    /** 灵动模式通话人设（与 SparkViewModel 文本链路的 systemPrompt 保持一致，改动两处同步） */
    const val PERSONA_SPARK = "你是灵动模式智能伴侣「小源」，搭载于小屏随身智能设备。" +
        "语气亲切生动、自然口语化，适合小屏阅读与语音朗读；" +
        "回答要极其简练扼要，直接说出答案或关键要点，通常在2至4句话以内。"

    data class Built(
        val instructions: String,
        val tools: List<AppTools.ToolDef>,
        val dialogContext: List<DialogTurn>,
    )

    /**
     * @param history 共享受话的历史（普通/大字取 SessionStore 当前会话；灵动取 SparkChatStore），
     * 内部裁成成对 QA；为空则不带 dialog_context（纯新开场）
     */
    fun build(context: Context, persona: String, history: List<OutMessage>): Built {
        val ctx = context.applicationContext
        val situation = buildString {
            CalendarContext.block()?.let { appendLine(it) }
            // 位置用缓存快照，别在通话起接时等定位（真要查天气/导航时工具里会 freshBlocking）
            LocationContext.current(ctx)?.let { appendLine("当前位置：${it.description}") }
        }
        val facts = runCatching {
            (ctx as? TravelApp)?.memoryStore?.profileFacts().orEmpty()
        }.getOrDefault(emptyList())

        val instructions = buildString {
            appendLine(persona)
            if (situation.isNotBlank()) {
                appendLine("【情境】")
                append(situation.trimEnd())
                appendLine()
            }
            if (facts.isNotEmpty()) {
                appendLine("【关于用户】")
                facts.take(10).forEach { appendLine("- $it") }
            }
            appendLine(
                "你可以调用工具查询实时天气、附近地点和步行/驾车路线。" +
                    "用户问这类问题时直接调用工具拿真实数据，再用一两句口语总结，不要凭空猜。",
            )
        }.trim()

        return Built(
            instructions = instructions,
            tools = AppTools.definitions,
            dialogContext = pairedTurns(history),
        )
    }

    /**
     * 裁成 dialog_context 要的形状：user/assistant 严格成对（官方要求数组长度为偶数），
     * 只保留完整问答对——开头的落单 assistant 和结尾没等到回答的 user 都丢掉。
     * 时间戳合成严格递增（服务端要求），从当前时刻往回排。
     */
    fun pairedTurns(history: List<OutMessage>): List<DialogTurn> {
        val usable = history
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.text.isNotBlank() }
            .dropWhile { it.role == "assistant" }
        val pairs = usable.zipWithNext()
            .filter { (a, b) -> a.role == "user" && b.role == "assistant" }
            .takeLast(MAX_TURNS)
        val startTs = System.currentTimeMillis() - pairs.size * 60_000L
        return pairs.flatMapIndexed { i, (u, a) ->
            listOf(
                DialogTurn("user", u.text, startTs + i * 60_000L),
                DialogTurn("assistant", a.text, startTs + i * 60_000L + 1_000),
            )
        }
    }
}
