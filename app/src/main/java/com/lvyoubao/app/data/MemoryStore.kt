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
 * 个人信息飞轮 v1（本地）：收藏的证据 + 偏好记录。
 * - 用户在回答卡上点「收藏」→ 存为证据（EVIDENCE）
 * - 观测版里标定的事实后续也进入这里
 * - 提问时按关键词挑最相关的 3 条注入上下文
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
    }
}
