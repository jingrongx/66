package com.lvyoubao.app.data

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

/**
 * 语音纠错声学拼音护栏（Phonetic Distance Check）：
 * 语音识别（ASR）的本质是"声音是对的，字可能打错了"——错字必然落在同音、近音、方言口音、数字混写的声学空间里。
 * 大模型纠错若根据上下文脑补（例如把「酸奶也有甜」篡改为「红的也有白的」），改动前后的拼音骨架会发生断崖式偏离。
 * 此护栏对比改动前后的模糊拼音编辑距离：相似度低于阈值（默认 75%）一票否决，坚决防止语义漂移与矫枉过正。
 */
object PhoneticGuard {

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    private val digitPinyin = arrayOf(
        "ling", "yi", "er", "san", "si", "wu", "liu", "qi", "ba", "jiu",
    )

    /**
     * 校验原稿与纠正稿是否具备声学/发音相似性。
     * @param draft 原始识别稿
     * @param polished AI 纠错后的稿件
     * @param threshold 拼音相似度阈值（0.0 ~ 1.0，默认 0.75）
     * @return true 表示符合语音错别字声学特征，允许采纳；false 表示发音偏离过大（语义篡改），坚决拦截
     */
    fun isPhoneticallyPlausible(draft: String, polished: String, threshold: Float = 0.75f): Boolean {
        val d = draft.trim()
        val p = polished.trim()
        if (d.isEmpty() || p.isEmpty() || d == p) return true

        val pyDraft = toFuzzyPinyin(d)
        val pyPolished = toFuzzyPinyin(p)

        // 无法提取有效拼音（如全是英文单词或纯符号），不以拼音拦
        if (pyDraft.isEmpty() || pyPolished.isEmpty()) return true

        val maxLen = maxOf(pyDraft.length, pyPolished.length)
        if (maxLen == 0) return true

        val dist = levenshtein(pyDraft, pyPolished)
        val similarity = 1f - (dist.toFloat() / maxLen)

        runCatching {
            android.util.Log.i(
                "lvyoubao.ai",
                "纠错拼音护栏校验：相似度=${"%.2f".format(similarity)}（阈值 $threshold）\n" +
                    "  原稿:「$d」→ py: $pyDraft\n" +
                    "  纠错:「$p」→ py: $pyPolished",
            )
        }

        return similarity >= threshold
    }

    /**
     * 提取模糊拼音串：小写、去声调、数字转汉字拼音、抹平常见方言口音混淆。
     */
    fun toFuzzyPinyin(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fa5' -> {
                    val arr = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat) }.getOrNull()
                    val p = arr?.firstOrNull()
                    if (!p.isNullOrEmpty() && p[0] in 'a'..'z') {
                        sb.append(normalizeFuzzy(p))
                    }
                }
                ch.isDigit() -> {
                    val idx = ch - '0'
                    if (idx in 0..9) {
                        sb.append(normalizeFuzzy(digitPinyin[idx]))
                    }
                }
                ch in 'a'..'z' -> sb.append(ch)
                ch in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                // 标点、空白和其余特殊字符忽略
            }
        }
        return sb.toString()
    }

    /**
     * 模糊音归一化：
     * 1. 平翘舌合并：zh/ch/sh → z/c/s
     * 2. 前后鼻音合并：ing/eng/ang → in/en/an
     * 3. 常见方言混淆：l → n, f → h
     */
    private fun normalizeFuzzy(py: String): String {
        return py
            .replace("zh", "z")
            .replace("ch", "c")
            .replace("sh", "s")
            .replace("ing", "in")
            .replace("eng", "en")
            .replace("ang", "an")
            .replace("l", "n")
            .replace("f", "h")
    }

    /**
     * 计算两串的 Levenshtein 编辑距离
     */
    fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n
        if (n == 0) return m

        // 使用单行滚动数组优化空间
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // 删除
                    curr[j - 1] + 1,   // 插入
                    prev[j - 1] + cost, // 替换
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }
}
