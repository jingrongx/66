package com.lvyoubao.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 随手记：一句话 + 记录时间 + 当时位置（个人飞轮的叙事素材） */
@Serializable
data class QuickNote(
    val ts: Long,
    val text: String,
    val place: String = "",
)

/** 随手记本地存储（JSONL，只存本机） */
class NoteStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(File(context.filesDir, "notes"), "notes.jsonl")
    private val mutex = Mutex()

    private val _notes = MutableStateFlow<List<QuickNote>>(emptyList())
    val notes: StateFlow<List<QuickNote>> = _notes.asStateFlow()

    private var lastTs = 0L

    /** 唯一递增时间戳：随手记页用 ts 做 LazyColumn key，同毫秒重复 key 会崩 */
    private fun nextTs(): Long {
        var t = System.currentTimeMillis()
        if (t <= lastTs) t = lastTs + 1
        lastTs = t
        return t
    }

    init {
        _notes.value = load()
    }

    private fun load(): List<QuickNote> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString<QuickNote>(line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun add(text: String, place: String = ""): QuickNote? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val note = QuickNote(ts = nextTs(), text = trimmed, place = place)
        mutex.withLock {
            _notes.value = (_notes.value + note).takeLast(MAX_NOTES)
            runCatching {
                if (!file.exists()) file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(note) + "\n")
            }
        }
        return note
    }

    suspend fun delete(ts: Long) = mutex.withLock {
        _notes.value = _notes.value.filterNot { it.ts == ts }
        rewrite()
    }

    private fun rewrite() {
        runCatching {
            val tmp = File(file.parentFile, "notes.jsonl.tmp")
            tmp.writeText(_notes.value.joinToString("") { json.encodeToString(it) + "\n" })
            tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
        }
    }

    companion object {
        const val MAX_NOTES = 2000
    }
}
