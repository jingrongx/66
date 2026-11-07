package com.lvyoubao.app.data

import android.content.Context
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

    private const val LEARNED_FILE = "learned_asr_fixes.jsonl"

    /**
     * 每 5 分钟重建一次词库；语料=会话消息·含AI回答 + 随手记（权重2）+ 记忆事实（权重5）+ 地点名称（权重6）。
     * 最近一个会话的消息权重 ×3：刚聊过的词最可能马上再说——第一次说的专有名词下一句就能纠。
     */
    suspend fun buildIfNeeded(
        sessions: List<ChatSession>,
        notes: List<QuickNote>,
        memoryFacts: List<String> = emptyList(),
    ) {
        val now = System.currentTimeMillis()
        if (lexicon.isNotEmpty() && now - builtAt < 5 * 60_000L) return
        builtAt = now
        lexicon = withContext(Dispatchers.Default) { buildLexicon(sessions, notes, memoryFacts) }
        android.util.Log.i(
            "lvyoubao.ai",
            "热词词库重建：${lexicon.size} 条，样例 ${lexicon.entries.take(8).joinToString { "${it.key}→${it.value}" }}",
        )
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
            // 用户消息与 AI 回答都喂：AI 回答里的地名/人名/药名是核对过的规范写法，
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
        // 最近会话加权：把刚才聊过的词重新按 ×3 记一次（刚说过的专有名词最可能马上再说）
        sessions.maxByOrNull { it.updatedAt }?.messages?.forEach { feed(it.text, 2) }

        val stop = setOf(
            "这个", "那个", "什么", "怎么", "哪里", "还有", "可以", "我们", "你们", "他们",
            "现在", "时候", "应该", "可能", "就是", "但是", "然后", "如果", "因为", "所以",
            "附近", "大概", "多少", "一个", "一下", "不了", "没有", "是不是", "怎么样",
        )
        // 拼音 → 中文热词。按「计数 × 长度²」打分占坑：高频大路词（我们/什么）计数虽高，
        // 但纯按计数取前 400 会把出现次数少的专有名词（药名/地名）挤出词库——
        // 长词几乎必然是有意义的术语，长度平方给它们加权；同拼音取分数最高的写法。
        // 门槛按长度分层：≥5字出现1次就进（首次说的专有名词下一句就能纠）、4字≥2次、≤3字≥4次。
        data class Cand(val word: String, val score: Int)
        val cands = counts.mapNotNull { (word, count) ->
            val len = word.length
            val need = when {
                len >= 5 -> 1
                len == 4 -> 2
                else -> 4
            }
            if (count < need || word in stop) return@mapNotNull null
            Cand(word, count * len * len)
        }.sortedWith(compareByDescending<Cand> { it.score }.thenByDescending { it.word.length })
        val lex = LinkedHashMap<String, String>()
        for (c in cands) {
            if (lex.size >= 400) break
            val py = pinyinOf(c.word) ?: continue
            lex.putIfAbsent(py, c.word)
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
                        android.util.Log.i("lvyoubao.ai", "热词纠正：$sub → $hit")
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

    // ---------- 用户纠正记录（纠错飞轮） ----------

    /** 语音稿原文 vs 用户实际发送的成对记录：识别错在哪一目了然，下次纠错（尤其 AI 段）直接照此改正 */
    @kotlinx.serialization.Serializable
    data class LearnedFix(val wrong: String, val right: String, val ts: Long)

    @Volatile
    private var learnedFixes: List<LearnedFix> = emptyList()
    private var learnedLoaded = false
    private val learnedJson = Json { ignoreUnknownKeys = true }

    /** 首次调用时从本地加载（应用私有目录 JSONL，最多 50 条，新的在前） */
    fun loadLearnedFixes(context: Context) {
        if (learnedLoaded) return
        learnedLoaded = true
        learnedFixes = runCatching {
            File(context.filesDir, LEARNED_FILE).readLines().filter { it.isNotBlank() }
                .mapNotNull { runCatching { learnedJson.decodeFromString<LearnedFix>(it) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /**
     * 记一条纠正对：用户改完点发送 = 认可了发送稿，语音稿与它的差异就是识别错在哪。
     * 护栏：长度差超过一倍的是「重写」不是「纠错」（如清空重打），不记——防污染；
     * 重复对不重复记。旧的盲替换不做：碎片规则（如 就是→酒石）会误伤正常句子，
     * 纠正记录只喂给 AI 纠错段做上下文相关的改正。
     */
    fun learnFix(context: Context, wrong: String, right: String) {
        val w = wrong.trim().take(120)
        val r = right.trim().take(120)
        if (w.isBlank() || r.isBlank() || w == r) return
        if (r.length > w.length * 2 || w.length > r.length * 2) return
        loadLearnedFixes(context)
        if (learnedFixes.any { it.wrong == w && it.right == r }) return
        learnedFixes = (listOf(LearnedFix(w, r, System.currentTimeMillis())) + learnedFixes).take(50)
        persistLearnedFixes(context)
    }

    /** 注入 AI 纠错提示词的纠正记录块（新的在前，取 8 条） */
    fun learnedFixBlock(limit: Int = 8): String {
        if (learnedFixes.isEmpty()) return ""
        return buildString {
            appendLine("【用户纠正记录】（此前语音识别错在哪、用户改成了什么；语音稿出现相同或高度相似的错误时，直接照此改正）")
            learnedFixes.take(limit).forEach { appendLine("- 「${it.wrong}」→ 用户实际发送「${it.right}」") }
        }
    }

    /** 当前全部纠正记录（新的在前）；未加载时先加载。管理页用 */
    fun learnedFixList(context: Context): List<LearnedFix> {
        loadLearnedFixes(context)
        return learnedFixes
    }

    /** 删除单条（按时间戳），返回删除后的记录 */
    fun removeLearnedFix(context: Context, ts: Long): List<LearnedFix> {
        loadLearnedFixes(context)
        learnedFixes = learnedFixes.filter { it.ts != ts }
        persistLearnedFixes(context)
        return learnedFixes
    }

    /** 清空全部纠正记录，返回空表 */
    fun clearLearnedFixes(context: Context): List<LearnedFix> {
        learnedFixes = emptyList()
        persistLearnedFixes(context)
        return learnedFixes
    }

    private fun persistLearnedFixes(context: Context) {
        runCatching {
            File(context.filesDir, LEARNED_FILE)
                .writeText(learnedFixes.joinToString("\n") { learnedJson.encodeToString(it) })
        }
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
