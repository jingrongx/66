package com.lvyoubao.app.data.duplex

import com.lvyoubao.app.ui.common.DuplexTurnUi

/**
 * 全双工通话会话气泡与回声状态机（纯状态机，无 Android Context 依赖，便于纯 JVM 单元测试）。
 *
 * 职责：
 * 1. 管理 user / assistant 气泡流（最多保留 30 条）；
 * 2. 在新话头开始（[onAsrStarted]）、新建 user 气泡及每轮收口（[onTurnDone]）时严格清零 [answerBuf]，
 *    防止下一轮流式回答接在历史回答末尾后再突变；
 * 3. 在嘈杂环境中保护已识别出来的用户问句，不被空 [onAsrStarted] 误抹掉；
 * 4. 维护最近助手回复快照 [lastAssistantText]，配合自述回声窗口拦截回灌气泡。
 */
internal class DuplexTurnsTracker(
    private val maxTurns: Int = 30,
    private val echoGuardWindowMs: Long = 8_000L,
) {
    var turns: List<DuplexTurnUi> = emptyList()
        private set

    private var answerBuf = ""
    private var assistantTurnActive = false
    private var pendingNewUserTurn = false
    private var lastAssistantText = ""
    private var lastAssistantAt = 0L
    private var suppressedEchoAsr = ""
    private var suppressedEchoAt = 0L

    fun reset() {
        turns = emptyList()
        answerBuf = ""
        assistantTurnActive = false
        pendingNewUserTurn = false
        lastAssistantText = ""
        lastAssistantAt = 0L
        suppressedEchoAsr = ""
        suppressedEchoAt = 0L
    }

    /** 新一轮语音识别开始：清空未完成的回答缓冲；仅在非覆盖场景追加占位气泡 */
    fun onAsrStarted() {
        answerBuf = ""
        assistantTurnActive = false
        val last = turns.lastOrNull()
        if (last != null && last.role == "user" && last.text.isNotBlank()) {
            // 已有非空问句等待回答时，不被嘈杂环境的空起振清空成 "…"
            pendingNewUserTurn = true
            return
        }
        pendingNewUserTurn = false
        if (last != null && last.role == "user" && last.text.isBlank()) return
        appendTurn(DuplexTurnUi("user", "", streaming = true))
    }

    /** 识别失败或噪声音频未成句：清理末尾空占位气泡 */
    fun onAsrFailed() {
        pendingNewUserTurn = false
        removeTrailingBlankUserTurn()
    }

    /** 处理 ASR 识别增量或最终文本；返回 true 表示判定为自述回声并已拦截 */
    fun onAsr(text: String, isFinal: Boolean, nowMs: Long): Boolean {
        if (isFinal && text.isBlank()) {
            pendingNewUserTurn = false
            removeTrailingBlankUserTurn()
            return false
        }
        val recentAssistant = answerBuf.ifBlank { lastAssistantText }
        if (isFinal && text.isNotBlank() &&
            recentAssistant.isNotBlank() &&
            nowMs - lastAssistantAt <= echoGuardWindowMs &&
            DuplexCallController.isLikelyEchoTranscript(text, recentAssistant)
        ) {
            suppressedEchoAsr = text
            suppressedEchoAt = nowMs
            pendingNewUserTurn = false
            removeEchoTurns(text)
            return true
        }
        if (isFinal && text.isNotBlank()) {
            suppressedEchoAsr = ""
            suppressedEchoAt = 0L
        }
        updateUserTurn(text, isFinal)
        return false
    }

    /** 本地快路/干预直接确认答复：立即落成完成的助手气泡并复位增量缓冲 */
    fun onLocalConfirmAnswer(confirm: String, nowMs: Long) {
        answerBuf = confirm
        assistantTurnActive = true
        lastAssistantText = confirm
        lastAssistantAt = nowMs
        updateAssistantTurn(confirm, done = true)
        answerBuf = ""
        assistantTurnActive = false
    }

    /** 服务端助手文本增量 / 定稿事件 */
    fun onAnswer(text: String, isFinal: Boolean, nowMs: Long) {
        if (suppressedEchoAt > 0L && nowMs - suppressedEchoAt < 2_500L) {
            return
        }
        if (!assistantTurnActive) {
            answerBuf = ""
            assistantTurnActive = true
        }
        answerBuf = when {
            isFinal -> text
            answerBuf.isNotEmpty() && text.startsWith(answerBuf) -> text
            else -> answerBuf + text
        }
        if (answerBuf.isNotBlank()) {
            lastAssistantText = answerBuf
            lastAssistantAt = nowMs
        }
        updateAssistantTurn(answerBuf, done = isFinal)
    }

    /** 一轮交互结束收口；返回 true 表示本轮为回声已被丢弃 */
    fun onTurnDone(asr: String, answer: String, nowMs: Long): Boolean {
        val recentRef = lastAssistantText.ifBlank { answerBuf }
        val isSuppressedEcho = asr.isNotBlank() && (
            (asr == suppressedEchoAsr && nowMs - suppressedEchoAt <= echoGuardWindowMs) ||
                (recentRef.isNotBlank() && nowMs - lastAssistantAt <= echoGuardWindowMs &&
                    DuplexCallController.isLikelyEchoTranscript(asr, recentRef))
            )
        if (isSuppressedEcho) {
            removeEchoTurns(asr)
            answerBuf = ""
            assistantTurnActive = false
            return true
        }
        if (answer.isNotBlank()) {
            lastAssistantText = answer
            lastAssistantAt = nowMs
        }
        updateAssistantTurn(answer, done = true)
        answerBuf = ""
        assistantTurnActive = false
        return false
    }

    private fun appendTurn(turn: DuplexTurnUi) {
        val list = turns.toMutableList()
        list.add(turn)
        if (list.size > maxTurns) list.removeAt(0)
        turns = list
    }

    private fun removeTrailingBlankUserTurn() {
        val last = turns.lastOrNull() ?: return
        if (last.role == "user" && last.text.isBlank()) {
            turns = turns.dropLast(1)
        }
    }

    private fun updateUserTurn(text: String, isFinal: Boolean) {
        val list = turns.toMutableList()
        val i = list.lastIndex
        val last = if (i >= 0) list[i] else null
        when {
            last != null && last.role == "user" && last.streaming -> {
                pendingNewUserTurn = false
                list[i] = last.copy(text = text, streaming = !isFinal)
            }
            last != null && last.role == "user" && !last.streaming -> {
                if (isFinal && last.text == text) {
                    pendingNewUserTurn = false
                    return
                }
                if (text.isNotBlank()) {
                    pendingNewUserTurn = false
                    answerBuf = ""
                    assistantTurnActive = false
                    list.add(DuplexTurnUi("user", text, streaming = !isFinal))
                    if (list.size > maxTurns) list.removeAt(0)
                }
            }
            else -> {
                pendingNewUserTurn = false
                answerBuf = ""
                assistantTurnActive = false
                list.add(DuplexTurnUi("user", text, streaming = !isFinal))
                if (list.size > maxTurns) list.removeAt(0)
            }
        }
        turns = list
    }

    private fun updateAssistantTurn(text: String, done: Boolean) {
        val list = turns.toMutableList()
        val i = list.lastIndex
        if (i >= 0 && list[i].role == "assistant" && (assistantTurnActive || list[i].streaming)) {
            list[i] = list[i].copy(text = text, streaming = !done)
        } else {
            list.add(DuplexTurnUi("assistant", text, streaming = !done))
            if (list.size > maxTurns) list.removeAt(0)
        }
        turns = list
    }

    private fun removeEchoTurns(echoAsr: String) {
        val last = turns.lastOrNull() ?: return
        val normEcho = DuplexCallController.normalizeEchoText(echoAsr)
        turns = when {
            last.role == "user" -> turns.dropLast(1)
            turns.size >= 2 && last.role == "assistant" &&
                turns[turns.lastIndex - 1].role == "user" &&
                (turns[turns.lastIndex - 1].text.isBlank() ||
                    DuplexCallController.normalizeEchoText(turns[turns.lastIndex - 1].text) == normEcho) ->
                turns.dropLast(2)
            else -> turns
        }
    }
}
