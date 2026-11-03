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
)

/**
 * 会话存储：新建 / 删除 / 搜索 / 追加消息。
 * 全量 JSONL 持久化（会话量小），内存中维护 StateFlow。
 * “当前会话 ID”存在 SettingsRepository 的 DataStore 里。
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
        _sessions.value = _sessions.value.filter { it.id != id }
        persist()
        repo.clearCurrentSessionIf(id)
    }

    suspend fun appendMessage(sessionId: String, message: SessionMessage) = mutex.withLock {
        _sessions.value = _sessions.value.map { s ->
            if (s.id != sessionId) s
            else {
                val messages = (s.messages + message).takeLast(MAX_MESSAGES)
                val title = if (message.role == "user" && s.messages.none { it.role == "user" }) {
                    message.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "新的提问" } ?: "新的提问"
                } else s.title
                s.copy(messages = messages, title = title, updatedAt = System.currentTimeMillis())
            }
        }.sortedByDescending { it.updatedAt }
        persist()
    }

    fun search(query: String): List<ChatSession> {
        val q = query.trim()
        if (q.isEmpty()) return _sessions.value
        return _sessions.value.filter { s ->
            s.title.contains(q, true) || s.messages.any { it.text.contains(q, true) }
        }
    }

    fun get(id: String?): ChatSession? = _sessions.value.firstOrNull { it.id == id }

    private fun persist() {
        runCatching {
            file.writeText(_sessions.value.joinToString("\n") { json.encodeToString(it) })
        }
    }

    private companion object {
        const val MAX_SESSIONS = 100
        const val MAX_MESSAGES = 200
    }
}
