package com.lvyoubao.app.ui.spark

import com.lvyoubao.app.data.OutMessage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 灵动模式会话仓库（进程内单例）：
 * 低内存设备上系统重建 Activity/ViewModel 时会话不丢（曾出现按住说话发出去、
 * 界面重建后「聊天」页变空的情况——消息原来挂在 ViewModel 上，一重建就归零）。
 * 进程被杀则随进程消失（灵动模式会话是轻量即时的，不做磁盘持久化）。
 */
object SparkChatStore {

    val messages = MutableStateFlow<List<SparkMessage>>(emptyList())

    // 供 LLM 的完整对话历史（OutMessage），与界面消息分开维护
    private val history = mutableListOf<OutMessage>()

    // 全双工一轮的幂等标记：同一轮被重复下发时只落一次（防「两条重复卡片」）
    private var lastTurnSig: String = ""
    private var lastTurnAt: Long = 0L
    private var lastAnswerText: String = ""
    private var lastAnswerAt: Long = 0L

    fun append(msg: SparkMessage) {
        messages.value = (messages.value + msg).takeLast(40) // 上限防照片占内存
    }

    fun updateLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant") {
            list[list.lastIndex] = list.last().copy(text = text)
            messages.value = list
        }
    }

    /** 去掉空白再做比较：豆包下发常带尾随空格/换行，直接 == 去不了重 */
    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    /**
     * 全双工通话的一轮落地（用户识别 + 小源回答）：
     * 通话界面自己没有会话流，挂断后到「聊天」里能回看，LLM 下一轮也带得上。
     *
     * 去重口径（同一个问题出现过两种下发，都会造成两张一样的卡）：
     * 1) 同一轮被整体重发（ASR 二次 final、TextDone 重发）→ 按 (识别|回答) 指纹幂等吞掉；
     * 2) 本机能力（handleAssistantIntent）自己已 appendMessage 过确认话术 → 按文本去重；
     * 3) 回答分段下发（两段各自 done）→ 补全上一张卡，而不是再叠一张。
     */
    @Synchronized
    fun appendDuplexTurn(asr: String, answer: String) {
        if (asr.isBlank() && answer.isBlank()) return
        val now = System.currentTimeMillis()
        val sig = norm(asr) + "\u0001" + norm(answer)
        // 一次性下发会在一两秒内重复到达，6 秒足够覆盖，又不会误吞「真的又问了一遍」
        if (sig == lastTurnSig && now - lastTurnAt < 6_000L) return
        lastTurnSig = sig
        lastTurnAt = now

        if (asr.isNotBlank()) {
            val na = norm(asr)
            val dup = messages.value.takeLast(3).any { it.role == "user" && norm(it.text) == na }
            if (!dup) {
                append(SparkMessage(role = "user", text = asr))
                appendHistory(OutMessage(role = "user", text = asr))
            }
        }
        if (answer.isNotBlank()) appendAnswer(answer, now)
    }

    private fun appendAnswer(answer: String, now: Long) {
        val na = norm(answer)
        // 只跟「本轮刚落的回答」合并，避免把上一轮的回答误补全
        val recent = now - lastAnswerAt < 8_000L
        val prev = if (recent) norm(lastAnswerText) else ""
        when {
            recent && prev.isNotEmpty() && prev == na -> Unit            // 完全重复：只留一张卡
            recent && prev.isNotEmpty() && prev.startsWith(na) -> Unit   // 新的是旧的前缀：丢弃
            recent && prev.isNotEmpty() && na.startsWith(prev) -> {      // 分段下发：就地补全
                lastAnswerText = answer
                lastAnswerAt = now
                replaceLastAssistant(answer)
            }
            else -> {
                lastAnswerText = answer
                lastAnswerAt = now
                append(SparkMessage(role = "assistant", text = answer))
                appendHistory(OutMessage(role = "assistant", text = answer))
            }
        }
    }

    private fun replaceLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx < 0) return
        list[idx] = list[idx].copy(text = text)
        messages.value = list
    }

    @Synchronized
    fun appendHistory(msg: OutMessage) {
        history.add(msg)
    }

    /** 从尾部往前按字符预算挑历史（48,000 字符），头两条必带 */
    @Synchronized
    fun historyBudget(budget: Int): List<OutMessage> {
        if (history.isEmpty()) return emptyList()
        var used = 0
        var count = 0
        for (msg in history.asReversed()) {
            val cost = msg.text.length + 100 + msg.images.size * 2000
            if (count >= 2 && used + cost > budget) break
            used += cost
            count++
        }
        return history.takeLast(count)
    }
}
