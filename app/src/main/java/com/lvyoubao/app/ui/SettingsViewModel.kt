package com.lvyoubao.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.ConfigSyncCodec
import com.lvyoubao.app.data.ConfigSyncServer
import com.lvyoubao.app.data.ProviderCredentials
import com.lvyoubao.app.data.ProviderPreset
import com.lvyoubao.app.data.SyncableConfig
import com.lvyoubao.app.data.presetById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SettingsViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository

    data class UiState(
        val loading: Boolean = true,
        val draft: AiSettings = AiSettings(),
        val testing: Boolean = false,
        val testResult: String? = null,
        val testFailed: Boolean = false,
        val fetchedModels: List<String> = emptyList(),
        // 扫码同步：接收端（显示二维码）
        val syncQrContent: String? = null,
        val syncReceived: Boolean = false,
        // 扫码同步：发送端（扫码推送）
        val syncPushing: Boolean = false,
        val syncPushResult: String? = null,
        val syncPushFailed: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val syncServer = ConfigSyncServer { config -> onSyncConfigReceived(config) }
    private val pushClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            val saved = repo.settings.first()
            _ui.update { it.copy(loading = false, draft = saved) }
        }
    }

    override fun onCleared() {
        syncServer.stop()
        super.onCleared()
    }

    fun updateDraft(transform: (AiSettings) -> AiSettings) =
        _ui.update { it.copy(draft = transform(it.draft), testResult = null) }

    /**
     * 切换供应商：先把当前供应商的接口配置存档（配过 Key、或自定义填过地址才算配置过），
     * 再载入目标供应商的配置——配过的原样恢复自己的，没配过的给干净默认（自定义=三项全空）。
     * 绝不把上一个供应商的 Key/地址/模型带到下一个头上。
     */
    fun selectPreset(preset: ProviderPreset) {
        _ui.update { state ->
            val current = state.draft
            val currentPreset = presetById(current.presetId)
            val configured = current.apiKey.isNotBlank() ||
                (currentPreset.id == "custom" && current.baseUrl.isNotBlank())
            val saved = if (configured) {
                current.savedProviders + (currentPreset.id to ProviderCredentials(
                    baseUrl = current.baseUrl,
                    apiKey = current.apiKey,
                    model = current.model,
                ))
            } else {
                current.savedProviders
            }
            val restored = saved[preset.id]
            state.copy(
                draft = current.copy(
                    savedProviders = saved,
                    presetId = preset.id,
                    baseUrl = restored?.baseUrl?.ifBlank { preset.baseUrl } ?: preset.baseUrl,
                    apiKey = restored?.apiKey.orEmpty(),
                    model = restored?.model?.ifBlank { preset.defaultModel } ?: preset.defaultModel,
                    thinking = if (preset.supportsThinkingToggle) current.thinking else false,
                ),
                // 可用模型列表/测试结果是上一个供应商的，切走即作废
                testResult = null,
                fetchedModels = emptyList(),
            )
        }
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

    // ---------- 扫码同步 ----------

    /** 接收端：开启本地服务并返回二维码内容 */
    fun startSyncServer() {
        _ui.update { it.copy(syncReceived = false, syncPushResult = null) }
        val endpoint = syncServer.start()
        _ui.update { state ->
            if (endpoint == null) {
                state.copy(
                    syncQrContent = null,
                    syncPushFailed = true,
                    syncPushResult = "开启同步失败：本机没有可用的 Wi-Fi 局域网地址。请先连接 Wi-Fi（或让手机开热点、车机连上热点）。",
                )
            } else {
                state.copy(syncQrContent = endpoint.qrContent, syncPushFailed = false, syncPushResult = null)
            }
        }
    }

    fun stopSyncServer() {
        syncServer.stop()
        _ui.update { it.copy(syncQrContent = null) }
    }

    /** 接收端：收到手机推送的配置 → 合入草稿并直接保存 */
    private fun onSyncConfigReceived(config: SyncableConfig) {
        viewModelScope.launch {
            val current = _ui.value.draft
            val merged = ConfigSyncCodec.merge(current, config)
            repo.save(merged)
            _ui.update {
                it.copy(draft = merged, syncReceived = true)
            }
        }
    }

    /** 发送端：分派扫码结果（局域网地址 → 推送本机配置；完整配置二维码 → 直接导入） */
    fun onQrScanned(content: String) {
        val lan = ConfigSyncCodec.parseLanQr(content)
        if (lan != null) {
            pushConfigTo(lan.host, lan.port, lan.token)
            return
        }
        val config = ConfigSyncCodec.parseConfigQr(content)
        if (config != null) {
            val merged = ConfigSyncCodec.merge(_ui.value.draft, config)
            viewModelScope.launch {
                repo.save(merged)
                _ui.update {
                    it.copy(draft = merged, syncPushFailed = false, syncPushResult = "已导入二维码中的配置 ✓")
                }
            }
            return
        }
        _ui.update {
            it.copy(syncPushFailed = true, syncPushResult = "这不是旅游宝的同步二维码")
        }
    }

    /** 发送端：把本机当前配置经局域网推给对端（车机） */
    fun pushConfigTo(host: String, port: Int, token: String) {
        if (_ui.value.syncPushing) return
        viewModelScope.launch {
            _ui.update { it.copy(syncPushing = true, syncPushResult = null, syncPushFailed = false) }
            val payload = ConfigSyncCodec.encodeJson(ConfigSyncCodec.fromSettings(_ui.value.draft))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url("http://$host:$port/sync?t=$token")
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    pushClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) "ok" else "http-${resp.code}"
                    }
                }.getOrElse { e ->
                    "fail: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _ui.update { it.copy(syncPushing = false) }
            when {
                result == "ok" -> _ui.update {
                    it.copy(syncPushFailed = false, syncPushResult = "已推送到车机 ✓ 车机上的配置已自动保存")
                }
                result.startsWith("http-") -> _ui.update {
                    it.copy(syncPushFailed = true, syncPushResult = "推送被拒绝（口令校验未通过或内容无效），请重新扫一次车机上的二维码")
                }
                else -> _ui.update {
                    val why = result.removePrefix("fail: ")
                    it.copy(
                        syncPushFailed = true,
                        syncPushResult = "推送失败：连不上车机。请确认两台设备连的是同一个 Wi-Fi（或车机已连上手机热点），再重试。（技术原因：$why）",
                    )
                }
            }
        }
    }

    fun clearSyncResult() = _ui.update { it.copy(syncPushResult = null) }
}
