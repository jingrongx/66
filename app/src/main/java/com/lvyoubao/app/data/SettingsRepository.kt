package com.lvyoubao.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ai_settings")
private val Context.sessionDataStore by preferencesDataStore(name = "session_prefs")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val preset = stringPreferencesKey("preset")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val thinking = booleanPreferencesKey("thinking")
        val search = booleanPreferencesKey("search_enabled")
        val themeMode = stringPreferencesKey("theme_mode")
        val amapKey = stringPreferencesKey("amap_key")
        val asrKey = stringPreferencesKey("asr_key")
        val dialectMode = booleanPreferencesKey("dialect_mode")
        // 存储键沿用旧名 elder_mode：应用内叫法已改「大字模式」，但用户已保存的开关不丢
        val largeTextMode = booleanPreferencesKey("elder_mode")
        val homeAddress = stringPreferencesKey("home_address")
        val autoSpeak = booleanPreferencesKey("auto_speak")
    }

    val settings: Flow<AiSettings> = context.dataStore.data.map { p ->
        val defaults = AiSettings()
        AiSettings(
            presetId = p[Keys.preset] ?: defaults.presetId,
            baseUrl = p[Keys.baseUrl] ?: defaults.baseUrl,
            apiKey = p[Keys.apiKey] ?: "",
            model = p[Keys.model] ?: defaults.model,
            thinking = p[Keys.thinking] ?: false,
            searchEnabled = p[Keys.search] ?: defaults.searchEnabled,
            themeMode = p[Keys.themeMode] ?: defaults.themeMode,
            amapKey = p[Keys.amapKey] ?: "",
            asrKey = p[Keys.asrKey] ?: "",
            dialectMode = p[Keys.dialectMode] ?: false,
            largeTextMode = p[Keys.largeTextMode] ?: false,
            homeAddress = p[Keys.homeAddress] ?: "",
            autoSpeak = p[Keys.autoSpeak] ?: false,
        )
    }

    suspend fun save(settings: AiSettings) {
        context.dataStore.edit { p ->
            p[Keys.preset] = settings.presetId
            p[Keys.baseUrl] = settings.baseUrl
            p[Keys.apiKey] = settings.apiKey
            p[Keys.model] = settings.model
            p[Keys.thinking] = settings.thinking
            p[Keys.search] = settings.searchEnabled
            p[Keys.themeMode] = settings.themeMode
            p[Keys.amapKey] = settings.amapKey
            p[Keys.asrKey] = settings.asrKey
            p[Keys.dialectMode] = settings.dialectMode
            p[Keys.largeTextMode] = settings.largeTextMode
            p[Keys.homeAddress] = settings.homeAddress
            p[Keys.autoSpeak] = settings.autoSpeak
        }
    }

    // 会话相关偏好挂在同一个仓库门面下，避免多个 DataStore 实例
    private val currentSessionKey = stringPreferencesKey("current_session_id")

    val currentSessionId: Flow<String?> = context.sessionDataStore.data.map { it[currentSessionKey] }

    suspend fun setCurrentSession(id: String) {
        context.sessionDataStore.edit { it[currentSessionKey] = id }
    }

    suspend fun clearCurrentSessionIf(id: String) {
        context.sessionDataStore.edit { prefs ->
            if (prefs[currentSessionKey] == id) prefs.remove(currentSessionKey)
        }
    }
}
