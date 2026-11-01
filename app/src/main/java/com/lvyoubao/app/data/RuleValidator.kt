package com.lvyoubao.app.data

object RuleValidator {

    /**
     * 规则校验层：对 AI 输出有一票否决权（推演方案 5.2 节）。
     * 快问快答要求 ≤2 句；攻略类（有 steps）不卡句数，但整体必须有数字。
     */
    fun validate(reply: AssistantReply): List<String> {
        val violations = mutableListOf<String>()
        if (reply.steps.isEmpty()) {
            val sentences = reply.answer
                .split('。', '！', '？', '；', '!', '?', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sentences.size > 2) violations += "超过两句"
        }
        val allText = (reply.answer + reply.steps.joinToString(""))
        if (allText.none { it.isDigit() }) violations += "缺少数字"
        return violations
    }
}
