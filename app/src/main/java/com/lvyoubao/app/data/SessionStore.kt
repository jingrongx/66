package com.lvyoubao.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 会话里的一条消息（不持久化图片字节，只标记“带图” */
@kotlinx.serialization.Serializable
data class SessionMessage(
    val role: String,
    val text: String,
    val steps: List<String> = emptyList(),
    val action: String? = null,
    val violations: List<String> = emptyList(),
    val hadImage: Boolean = false,
    // 发送时压缩落盘的图片文件路径（filesDir/sent_images），供缩略图/看大图与下一轮随历史重发；旧消息为空
    val imagePaths: List<String> = emptyList(),
    val mapCard: MapCardData? = null,   // 地图卡：问路线/地点时由工具层捕获，会话里直接渲染
    val ts: Long,
)

@kotlinx.serialization.Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<SessionMessage> = emptyList(),
    // 长会话滚动摘要（持久化，重启/杀进程不丢失）
    val summaryDroppedCount: Int = 0,
    val summaryText: String = "",
    // 已提取画像事实的用户提问条数（持久化，防重启后第1句就误触发总结）
    val summarizedUserCount: Int = 0,
)

/**
 * 会话存储：新建 / 删除 / 搜索 / 追加消息。
 * 全量 JSONL 持久化（会话量小），内存中维护 StateFlow。
 * “当前会话 ID”存在 SettingsRepository 的 DataStore 里。
 * 联动图片垃圾回收（GC）：删除会话/撤回截断/消息超限时清理对应的 sent_images 文件。
 */
class SessionStore(private val context: Context, private val repo: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "sessions.jsonl")
    private val mutex = Mutex()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    val currentSessionId: Flow<String?> = repo.currentSessionId

    init {
        _sessions.value = runCatching {
            file.readLines().filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString<ChatSession>(it) }.getOrNull() }
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    suspend fun create(): ChatSession = mutex.withLock {
        val now = System.currentTimeMillis()
        val session = ChatSession(id = UUID.randomUUID().toString(), title = "新的提问", createdAt = now, updatedAt = now)
        _sessions.value = (listOf(session) + _sessions.value).take(MAX_SESSIONS)
        persist()
        repo.setCurrentSession(session.id)
        session
    }

    suspend fun setCurrent(id: String) {
        repo.setCurrentSession(id)
    }

    suspend fun delete(id: String) = mutex.withLock {
        val target = _sessions.value.firstOrNull { it.id == id }
        val deadImages = target?.messages?.flatMap { it.imagePaths } ?: emptyList()
        _sessions.value = _sessions.value.filter { it.id != id }
        persist()
        repo.clearCurrentSessionIf(id)
        deleteImageFiles(deadImages)
    }

    suspend fun appendMessage(sessionId: String, message: SessionMessage) = mutex.withLock {
        var evictedImages = emptyList<String>()
        _sessions.value = _sessions.value.map { s ->
            if (s.id != sessionId) s
            else {
                val all = s.messages + message
                if (all.size > MAX_MESSAGES) {
                    val evicted = all.take(all.size - MAX_MESSAGES)
                    evictedImages = evicted.flatMap { it.imagePaths }
                }
                val messages = all.takeLast(MAX_MESSAGES)
                val title = if (message.role == "user" && s.messages.none { it.role == "user" }) {
                    message.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "新的提问" } ?: "新的提问"
                } else s.title
                s.copy(messages = messages, title = title, updatedAt = System.currentTimeMillis())
            }
        }.sortedByDescending { it.updatedAt }
        persist()
        deleteImageFiles(evictedImages)
    }

    fun search(query: String): List<ChatSession> {
        val q = query.trim()
        if (q.isEmpty()) return _sessions.value
        return _sessions.value.filter { s ->
            s.title.contains(q, true) || s.messages.any { it.text.contains(q, true) }
        }
    }

    /**
     * 撤回：删除某条消息「及其后」的所有消息（用户消息卡片的编辑/重发用），
     * 按时间戳定位该条，保留它之前的部分。联动清理被丢弃消息附带的图片文件。
     */
    suspend fun truncateFrom(sessionId: String, ts: Long) = mutex.withLock {
        var deadImages = emptyList<String>()
        _sessions.value = _sessions.value.map { s ->
            if (s.id != sessionId) s
            else {
                val idx = s.messages.indexOfFirst { it.ts == ts }
                if (idx < 0) s
                else {
                    val dropped = s.messages.drop(idx)
                    deadImages = dropped.flatMap { it.imagePaths }
                    s.copy(messages = s.messages.take(idx), updatedAt = System.currentTimeMillis())
                }
            }
        }.sortedByDescending { it.updatedAt }
        persist()
        deleteImageFiles(deadImages)
    }

    /** 更新并持久化会话滚动摘要 */
    suspend fun updateSummary(sessionId: String, droppedCount: Int, summaryText: String) = mutex.withLock {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == sessionId) s.copy(summaryDroppedCount = droppedCount, summaryText = summaryText) else s
        }
        persist()
    }

    /** 更新并持久化已提取画像事实的用户提问条数 */
    suspend fun updateSummarizedUserCount(sessionId: String, count: Int) = mutex.withLock {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == sessionId) s.copy(summarizedUserCount = count) else s
        }
        persist()
    }

    fun get(id: String?): ChatSession? = _sessions.value.firstOrNull { it.id == id }

    /** 清理孤立图片：遍历 sent_images 目录，删除未被任何存活会话引用的文件 */
    fun cleanOrphanImages() {
        runCatching {
            val dir = File(context.filesDir, "sent_images")
            if (!dir.exists() || !dir.isDirectory) return@runCatching
            val referenced = _sessions.value.asSequence()
                .flatMap { it.messages.asSequence() }
                .flatMap { it.imagePaths.asSequence() }
                .map { File(it).name }
                .toSet()
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                // 保留 30 分钟内创建的文件（防刚选好还未发送的图片被误删）
                if (f.isFile && f.name !in referenced && (now - f.lastModified() > 30 * 60_000L)) {
                    f.delete()
                }
            }
        }
    }

    private fun deleteImageFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            paths.forEach { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
        }
    }

    private fun persist() {
        runCatching {
            val text = _sessions.value.joinToString("\n") { json.encodeToString(it) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX_SESSIONS = 100
        const val MAX_MESSAGES = 200
    }
}
