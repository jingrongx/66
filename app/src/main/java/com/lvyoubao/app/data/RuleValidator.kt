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
            if (sentences.size > 2) violations += "这条回答有点长，想省事可以让它再简短一点"
        }
        val allText = (reply.answer + reply.steps.joinToString(""))
        if (allText.none { it.isDigit() }) {
            violations += "这条回答没给出具体数字（时间、距离、价格等），建议追问一句"
        }
        return violations
    }

    /** 全部通过时给用户看的一句话 */
    const val PASS_LABEL: String = "已检查：简短、有数字，可以直接照做"
}
