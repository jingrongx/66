package com.lvyoubao.app.data

object RuleValidator {

    /**
     * 规则校验层：对 AI 输出有一票否决权（推演方案 5.2 节）。
     * 快问快答要求 ≤2 句；攻略类（有 steps）不卡句数，但整体必须有数字。
     * 返回给用户看的是直白提醒，不是内部术语。
     */
    fun validate(reply: AssistantReply): List<String> {
        val violations = mutableListOf<String>()
        if (reply.steps.isEmpty()) {
            val sentences = reply.answer
                .split('。', '！', '？', '；', '!', '?', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sentences.size > 2) violations += "回答有点长，可以让它再简短些"
        }
        val allText = (reply.answer + reply.steps.joinToString(""))
        if (allText.none { it.isDigit() }) {
            violations += "回答里没有具体数字，可以追问一句"
        }
        return violations
    }

    /** 全部通过时给用户看的一句话（只陈述事实，不打包票） */
    const val PASS_LABEL: String = "已自动检查：简短、含数字"
}
