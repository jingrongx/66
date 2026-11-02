package com.lvyoubao.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.QuickNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NotesViewModel(app: TravelApp) : ViewModel() {

    private val store = app.noteStore
    private val memory = app.memoryStore
    private val repo = app.settingsRepository

    val notes: StateFlow<List<QuickNote>> =
        store.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class UiState(
        val input: String = "",
        val generating: Boolean = false,
        val travelogue: String? = null,
        val travelogueSaved: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun updateInput(value: String) = _ui.update { it.copy(input = value) }

    fun clearError() = _ui.update { it.copy(error = null) }

    /** 记一笔：place 为当时的位置描述（可空） */
    fun addNote(place: String) {
        val text = _ui.value.input
        if (text.isBlank()) return
        viewModelScope.launch {
            store.add(text, place)
            _ui.update { it.copy(input = "", error = null) }
        }
    }

    fun deleteNote(ts: Long) {
        viewModelScope.launch { store.delete(ts) }
    }

    /** 自动游记：把随手记碎片交给 AI 合成第一人称短文 */
    fun generateTravelogue() {
        if (_ui.value.generating) return
        viewModelScope.launch {
            _ui.update { it.copy(generating = true, error = null) }
            val settings = repo.settings.firstOrNull() ?: AiSettings()
            if (settings.apiKey.isBlank()) {
                _ui.update { it.copy(generating = false, error = "先到设置里配好 AI 服务 Key，再来生成游记") }
                return@launch
            }
            val all = store.notes.value
            if (all.isEmpty()) {
                _ui.update { it.copy(generating = false, error = "还没有随手记，先记几句见闻吧") }
                return@launch
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val lines = all.takeLast(60).joinToString("\n") { n ->
                "- ${fmt.format(Date(n.ts))}${if (n.place.isNotBlank()) "（在${n.place}）" else ""}：${n.text}"
            }
            val messages = listOf(
                OutMessage("system", TRAVELOGUE_PROMPT),
                OutMessage("user", "我的随手记：\n$lines"),
            )
            AiClient(settings).chatPlain(messages, thinking = false).fold(
                onSuccess = { text -> _ui.update { it.copy(generating = false, travelogue = text.trim(), travelogueSaved = false) } },
                onFailure = { e -> _ui.update { it.copy(generating = false, error = e.message ?: "生成失败，请重试") } },
            )
        }
    }

    /** 把生成的游记存进个人记忆（后续提问会自动带上） */
    fun saveTravelogueToMemory() {
        val text = _ui.value.travelogue ?: return
        if (_ui.value.travelogueSaved) return
        viewModelScope.launch {
            memory.add(
                kind = MemoryStore.MemoryEntry.EVIDENCE,
                title = "我的游记（${SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())}）",
                detail = text,
                source = "随手记自动生成",
            )
            _ui.update { it.copy(travelogueSaved = true) }
        }
    }

    companion object {
        private val TRAVELOGUE_PROMPT = """
            你是帮用户记录旅程的写手。根据用户随手记的碎片（时间、地点、见闻），
            用用户的第一人称视角写一篇 200-400 字的游记短文：
            - 保留随手记里具体的时间、数字、地名，不编造新事实
            - 语气自然，像本人随手写的朋友圈长文，不要客套开场白
            - 结尾可以给一句下次行程的小建议
            直接输出正文。
        """.trimIndent()
    }
}
