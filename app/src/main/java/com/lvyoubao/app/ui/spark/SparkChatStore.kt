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

    /**
     * 全双工通话的一轮落地（用户识别 + 小源回答）：
     * 通话界面自己没有会话流，挂断后到「聊天」里能回看，LLM 下一轮也带得上。
     */
    fun appendDuplexTurn(asr: String, answer: String) {
        if (asr.isBlank() && answer.isBlank()) return
        // 本机能力那条链路（handleAssistantIntent）自己也会 appendMessage，
        // 这里按「最近两条里已有同角色同文本」去重，避免聊天里出现两条一样的确认
        fun alreadyThere(role: String, text: String): Boolean =
            messages.value.takeLast(2).any { it.role == role && it.text == text }

        if (asr.isNotBlank() && !alreadyThere("user", asr)) {
            append(SparkMessage(role = "user", text = asr))
            appendHistory(OutMessage(role = "user", text = asr))
        }
        if (answer.isNotBlank() && !alreadyThere("assistant", answer)) {
            append(SparkMessage(role = "assistant", text = answer))
            appendHistory(OutMessage(role = "assistant", text = answer))
        }
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
