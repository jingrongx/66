package com.lvyoubao.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.ProviderPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository

    data class UiState(
        val loading: Boolean = true,
        val draft: AiSettings = AiSettings(),
        val testing: Boolean = false,
        val testResult: String? = null,
        val testFailed: Boolean = false,
        val fetchedModels: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repo.settings.first()
            _ui.update { it.copy(loading = false, draft = saved) }
        }
    }

    fun updateDraft(transform: (AiSettings) -> AiSettings) =
        _ui.update { it.copy(draft = transform(it.draft), testResult = null) }

    fun selectPreset(preset: ProviderPreset) = updateDraft { current ->
        current.copy(
            presetId = preset.id,
            baseUrl = preset.baseUrl.ifEmpty { current.baseUrl },
            model = preset.defaultModel.ifEmpty { current.model },
            thinking = if (preset.supportsThinkingToggle) current.thinking else false,
        )
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            repo.save(_ui.value.draft)
            onSaved()
        }
    }

    fun testConnection() {
        if (_ui.value.testing) return
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, testResult = null, testFailed = false, fetchedModels = emptyList()) }
            val result = AiClient(_ui.value.draft).listModels()
            _ui.update { state ->
                result.fold(
                    onSuccess = { models ->
                        state.copy(
                            testing = false,
                            fetchedModels = models,
                            testResult = if (models.isEmpty()) "连接成功，但服务端没有返回模型列表" else "连接成功，可用模型：${models.joinToString("、")}",
                        )
                    },
                    onFailure = { e ->
                        state.copy(testing = false, testFailed = true, testResult = "连接失败：${e.message}")
                    },
                )
            }
        }
    }
}
