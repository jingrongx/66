package com.lvyoubao.app.data

object RuleValidator {

    /**
     * 规则校验层：对 AI 输出有一票否决权（推演方案 5.2 节）。
     * 违例的回答不是丢弃，而是标记“降级”后仍可展示——第一版先让用户看到校验结果。
     */
    fun validate(reply: AssistantReply): List<String> {
        val violations = mutableListOf<String>()
        val sentences = reply.answer
            .split('。', '！', '？', '；', '!', '?', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size > 2) violations += "超过两句"
        if (reply.answer.none { it.isDigit() }) violations += "缺少数字"
        return violations
    }
}
