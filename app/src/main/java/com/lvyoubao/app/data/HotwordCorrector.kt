package com.lvyoubao.app.data

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语音输入热词纠正：从用户自己的聊天记录（含 AI 回答）、随手记、记忆画像、地图卡里挖词建词库，
 * 语音识别结果里同拼音的片段替换成熟词——越用越贴合用户习惯。
 * 拼音用 tinyPinyin（纯本地，无网络）；只有整段拼音完全一致才替换，误纠有限。
 * 注意：词库以「拼音 → 中文词」建立（此前误存成 中文→中文，拼音永远查不到，纠正从未生效）；
 * 语料必须包含 AI 回答——用户消息里的地名常是识别错误（说了"渝江"，AI 回答里是核对过的"余江"），
 * 只喂用户消息会把识别错误固化进词库。
 */
object HotwordCorrector {

    @Volatile
    private var lexicon: Map<String, String> = emptyMap()
    @Volatile
    private var builtAt = 0L

    /** 每 5 分钟重建一次词库；语料=会话消息·含AI回答（权重1）+ 随手记（权重2）+ 记忆事实（权重5）+ 地点名称（权重6） */
    suspend fun buildIfNeeded(
        sessions: List<ChatSession>,
        notes: List<QuickNote>,
        memoryFacts: List<String> = emptyList(),
    ) {
        val now = System.currentTimeMillis()
        if (lexicon.isNotEmpty() && now - builtAt < 5 * 60_000L) return
        builtAt = now
        lexicon = withContext(Dispatchers.Default) { buildLexicon(sessions, notes, memoryFacts) }
    }

    private fun buildLexicon(
        sessions: List<ChatSession>,
        notes: List<QuickNote>,
        memoryFacts: List<String>,
    ): Map<String, String> {
        val counts = HashMap<String, Int>()
        fun feed(text: String, weight: Int) {
            if (text.isBlank()) return
            for (run in Regex("[\u4e00-\u9fa5]{2,8}").findAll(text)) {
                val s = run.value
                for (len in 2..minOf(6, s.length)) {
                    for (i in 0..(s.length - len)) {
                        val sub = s.substring(i, i + len)
                        counts[sub] = (counts[sub] ?: 0) + weight
                    }
                }
            }
        }
        sessions.asSequence()
            .flatMap { it.messages.asSequence() }
            // 用户消息与 AI 回答都喂：AI 回答里的地名/人名是核对过的规范写法，
            // 用户消息里恰恰常带着识别错误的同音字，只喂后者会越纠越错
            .forEach { feed(it.text, 1) }
        notes.forEach { feed(it.text, 2) }
        memoryFacts.forEach { feed(it, 5) }
        sessions.asSequence()
            .flatMap { it.messages.asSequence() }
            .forEach { m ->
                m.mapCard?.let { card ->
                    feed(card.title, 6)
                    card.pois.forEach { feed(it.name, 6) }
                }
            }

        val stop = setOf(
            "这个", "那个", "什么", "怎么", "哪里", "还有", "可以", "我们", "你们", "他们",
            "现在", "时候", "应该", "可能", "就是", "但是", "然后", "如果", "因为", "所以",
            "附近", "大概", "多少", "一个", "一下", "不了", "没有", "是不是", "怎么样",
        )
        // 拼音 → 中文热词：按出现次数从高到低占坑，同一拼音取计数最高的写法；
        // 门槛 2 次（3 次会漏掉刚在本次会话里出现两回的地名），替换只在整段拼音完全一致时发生，噪音可控
        val lex = LinkedHashMap<String, String>()
        for (e in counts.entries.sortedByDescending { it.value }) {
            if (lex.size >= 400) break
            if (e.value < 2 || e.key in stop) continue
            val py = pinyinOf(e.key) ?: continue
            lex.putIfAbsent(py, e.key)
        }
        return lex
    }

    /** 识别结果热词纠正：滑窗 n-gram 同拼音替换成熟词 */
    fun correct(text: String): String {
        if (lexicon.isEmpty() || text.isEmpty()) return text
        val sb = StringBuilder(text)
        var i = 0
        loop@ while (i < sb.length) {
            val maxLen = minOf(6, sb.length - i)
            var len = maxLen
            while (len >= 2) {
                val sub = sb.substring(i, i + len)
                var allChinese = sub.isNotEmpty()
                for (ch in sub) {
                    if (ch !in '\u4e00'..'\u9fa5') {
                        allChinese = false
                        break
                    }
                }
                if (allChinese) {
                    val py = pinyinOf(sub)
                    val hit = py?.let { lexicon[it] }
                    if (hit != null && hit != sub) {
                        sb.replace(i, i + len, hit)
                        i += hit.length
                        continue@loop
                    }
                }
                len--
            }
            i++
        }
        return sb.toString()
    }

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    private fun pinyinOf(s: String): String? {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch < '\u4e00' || ch > '\u9fa5') return null
            val arr = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat) }.getOrNull()
                ?: return null
            val p = arr.firstOrNull() ?: return null
            if (p.isEmpty() || p[0] !in 'a'..'z') return null
            sb.append(p)
        }
        return sb.toString()
    }
}
