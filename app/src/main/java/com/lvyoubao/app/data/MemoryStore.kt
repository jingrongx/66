package com.lvyoubao.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 个人信息飞轮 v2（本地）：画像记忆 + 收藏的证据 + 偏好记录。
 * - 会话自动总结：攒够几句用户消息后由 AI 提取长期事实（住址、家人、口味、习惯、常用地点等），
 *   存为画像（PROFILE），提问时注入上下文——越用越懂用户
 * - 用户在回答卡上点「收藏」→ 存为证据（EVIDENCE）
 * - 观测版里标定的事实后续也进入这里
 * - 证据按关键词挑最相关的 3 条注入；画像整体注入
 * 公共飞轮（匿名贡献公共池）需要用户明示同意，v1 先只做本地层。
 */
class MemoryStore(context: Context) {

    @kotlinx.serialization.Serializable
    data class MemoryEntry(
        val ts: Long,
        val kind: String,
        val title: String,
        val detail: String,
        val source: String = "",
    ) {
        companion object {
            const val PROFILE = "PROFILE"
            const val EVIDENCE = "EVIDENCE"
            const val PREFERENCE = "PREFERENCE"
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "memory.jsonl")
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    init {
        _entries.value = runCatching {
            file.readLines().filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString<MemoryEntry>(it) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    suspend fun add(kind: String, title: String, detail: String, source: String = "") = mutex.withLock {
        val entry = MemoryEntry(
            ts = System.currentTimeMillis(),
            kind = kind,
            title = title.take(60),
            detail = detail.take(2000),
            source = source,
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        persist()
        entry
    }

    suspend fun remove(ts: Long) = mutex.withLock {
        _entries.value = _entries.value.filter { it.ts != ts }
        persist()
    }

    /** 按与查询的关键词重叠度挑最相关的若干条，拼成注入上下文 */
    fun relevantContext(query: String, limit: Int = 3): String {
        if (_entries.value.isEmpty() || query.isBlank()) return ""
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return ""
        val scored = _entries.value
            .map { e ->
                val text = e.title + e.detail
                val hits = tokens.count { text.contains(it) }
                e to hits
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.ts })
            .take(limit)
        if (scored.isEmpty()) return ""
        return buildString {
            appendLine("【本地记忆/收藏的证据】（用户此前确认过的信息，可参考但注意时效）")
            scored.forEach { (e, _) ->
                appendLine("- ${e.title}：${e.detail.take(300)}${e.source.takeIf { it.isNotBlank() }?.let { "（来源：$it）" } ?: ""}")
            }
        }
    }

    /** 注入上下文的画像块：长期事实整块给（按新旧排序），和证据的按需挑选互补 */
    fun profileContext(limit: Int = 10): String {
        val facts = _entries.value.filter { it.kind == MemoryEntry.PROFILE }
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("【关于用户】（长期记忆，来自用户历史会话总结；与问题相关时自然利用，不必点破来源）")
            facts.take(limit).forEach { appendLine("- ${it.title}") }
        }
    }

    /** 画像事实（热词词库的高权重语料：用户亲口说过的人名、地名、习惯叫法） */
    fun profileFacts(): List<String> = _entries.value.filter { it.kind == MemoryEntry.PROFILE }.map { it.title }

    /**
     * 会话总结：把一段对话交给 AI，提取/合并关于用户的长期事实。
     * 成功返回 true（无论是否有更新）；没配 Key、网络失败或提取不出内容返回 false。
     * AI 负责合并去重删过时；本地加两道保险——明显缩水时只增不删，24 小时内的新记忆不删。
     */
    suspend fun updateProfileFromSession(session: ChatSession, settings: AiSettings): Boolean {
        if (settings.apiKey.isBlank()) return false
        val transcript = buildString {
            session.messages.filter { it.text.isNotBlank() }.takeLast(24).forEach { m ->
                when (m.role) {
                    "user" -> appendLine("用户：" + m.text.take(200))
                    "assistant" -> appendLine("助手：" + m.text.take(120))
                }
            }
        }
        if (transcript.isBlank()) return false

        val existing = _entries.value.filter { it.kind == MemoryEntry.PROFILE }
        val prompt = buildString {
            if (existing.isNotEmpty()) {
                appendLine("【已有记忆】")
                existing.forEach { appendLine("- ${it.title}") }
                appendLine()
            }
            appendLine("【新对话】")
            append(transcript)
            appendLine()
            append(
                "任务：输出更新后的完整记忆清单。仍成立的旧记忆原样保留，重复的合并，" +
                    "过时或被用户推翻的删除，对话里体现的新事实加进去。" +
                    "只提取对话里用户明确表达的长期事实（住址城市、家人称呼、出行习惯、饮食口味、" +
                    "身体状况、常用地点、宠物车房、兴趣偏好等），不要编造、不要存一次性信息" +
                    "（比如本次去哪玩、临时问的问题）。每条一句话不超过30字。最多15条。" +
                    "只输出 JSON 字符串数组，不要任何解释。",
            )
        }
        val body = AiClient(settings).chatPlain(
            listOf(
                OutMessage(
                    "system",
                    "你是用户的私人记忆管家，负责从对话里维护一份关于用户的长期事实清单，" +
                        "让助手越用越懂用户。只输出 JSON 字符串数组。",
                ),
                OutMessage("user", prompt),
            ),
            thinking = false,
        ).getOrElse { return false }

        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return false
        val facts = runCatching { json.decodeFromString<List<String>>(body.substring(start, end + 1)) }
            .getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(15)
            ?: return false
        if (facts.isEmpty()) return false

        mergeProfile(facts, existing)
        return true
    }

    /** 合并画像：保留仍然成立的（原时间戳），新增没有的；明显缩水或太新的不删 */
    private suspend fun mergeProfile(facts: List<String>, existing: List<MemoryEntry>) = mutex.withLock {
        val now = System.currentTimeMillis()
        val onlyAdd = existing.size >= 4 && facts.size * 2 < existing.size
        val kept = existing.filter { e ->
            onlyAdd || (now - e.ts > DAY_MS && facts.any { sameFact(it, e.title) })
        }
        val keptTitles = kept.map { it.title }
        val toAdd = facts.asSequence()
            .filter { f -> keptTitles.none { sameFact(f, it) } }
            .distinct()
            .map { MemoryEntry(now, MemoryEntry.PROFILE, it.take(60), "", "会话自动总结") }
            .toList()
        if (toAdd.isEmpty() && kept.size == existing.size) return@withLock
        _entries.value = (kept + toAdd + _entries.value.filter { it.kind != MemoryEntry.PROFILE })
            .let { list ->
                val profile = list.filter { it.kind == MemoryEntry.PROFILE }.take(PROFILE_MAX)
                (list.filter { it.kind != MemoryEntry.PROFILE } + profile).sortedByDescending { it.ts }
            }
        persist()
    }

    /** 同一条事实判断：去标点空白后相等，或短的被长的完整包含（"用户住在抚州" vs "用户住在抚州市临川区"） */
    private fun sameFact(a: String, b: String): Boolean {
        val na = a.replace(Regex("[\\s，。、,.]"), "")
        val nb = b.replace(Regex("[\\s，。、,.]"), "")
        if (na == nb) return true
        return if (na.length <= nb.length) na.isNotEmpty() && nb.contains(na) else nb.isNotEmpty() && na.contains(nb)
    }

    private fun tokenize(query: String): List<String> {
        val cleaned = query.replace(Regex("[？?！!，,。.\\s（）()\"']"), " ")
        val words = cleaned.split(' ').filter { it.length >= 2 }.toMutableList()
        // 中文无空格分词：滑窗 2-gram 兜底
        val compact = cleaned.replace(" ", "")
        if (compact.length >= 2 && words.size < 3) {
            words.addAll((0..compact.length - 2).map { compact.substring(it, it + 2) })
        }
        return words.distinct().take(20)
    }

    private fun persist() {
        runCatching {
            file.writeText(_entries.value.joinToString("\n") { json.encodeToString(it) })
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val PROFILE_MAX = 60
        const val DAY_MS = 24 * 3600_000L
    }
}
