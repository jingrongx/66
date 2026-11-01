package com.lvyoubao.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AssistantReply
import com.lvyoubao.app.data.RuleValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AskViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository

    data class UiState(
        val question: String = "",
        val loading: Boolean = false,
        val reply: AssistantReply? = null,
        val violations: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun updateQuestion(value: String) = _ui.update { it.copy(question = value) }

    fun clear() = _ui.update { UiState(question = it.question) }

    fun ask() {
        val question = _ui.value.question.trim()
        if (question.isEmpty() || _ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, reply = null, violations = emptyList()) }
            val settings = repo.settings.first()
            if (settings.apiKey.isBlank()) {
                _ui.update {
                    it.copy(loading = false, error = "还没有配置 API Key。点右上角设置，选好服务商（默认 DeepSeek）填入 Key 即可。")
                }
                return@launch
            }
            AiClient(settings).chat(question).fold(
                onSuccess = { reply ->
                    _ui.update {
                        it.copy(loading = false, reply = reply, violations = RuleValidator.validate(reply))
                    }
                },
                onFailure = { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "请求失败") }
                },
            )
        }
    }
}
