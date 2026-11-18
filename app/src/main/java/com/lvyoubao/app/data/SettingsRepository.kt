package com.lvyoubao.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "ai_settings")
private val Context.sessionDataStore by preferencesDataStore(name = "session_prefs")

/** 每供应商接口配置存档的 JSON 编解码 */
private val providerJson = Json { ignoreUnknownKeys = true }

class SettingsRepository(private val context: Context) {

    private object Keys {
        val preset = stringPreferencesKey("preset")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val thinking = booleanPreferencesKey("thinking")
        val search = booleanPreferencesKey("search_enabled")
        val safetyCheck = booleanPreferencesKey("safety_check_enabled")
        val answerStyle = stringPreferencesKey("answer_style")
        val themeMode = stringPreferencesKey("theme_mode")
        val amapKey = stringPreferencesKey("amap_key")
        val asrKey = stringPreferencesKey("asr_key")
        val mapMirrorUrl = stringPreferencesKey("map_mirror_url")
        val deviceName = stringPreferencesKey("device_name")
        val dialectMode = booleanPreferencesKey("dialect_mode")
        val asrAutoSendSeconds = intPreferencesKey("asr_auto_send_seconds")
        // 存储键沿用旧名 elder_mode：应用内叫法已改「大字模式」，但用户已保存的开关不丢
        val largeTextMode = booleanPreferencesKey("elder_mode")
        val sparkMode = booleanPreferencesKey("spark_mode")
        val homeAddress = stringPreferencesKey("home_address")
        val autoSpeak = booleanPreferencesKey("auto_speak")
        val keepAliveEnabled = booleanPreferencesKey("keep_alive_enabled")
        val bootStartEnabled = booleanPreferencesKey("boot_start_enabled")
        val robotEnabled = booleanPreferencesKey("robot_enabled")
        val robotHost = stringPreferencesKey("robot_host")
        val proactiveReminders = booleanPreferencesKey("proactive_reminders")
        val voiceBargeIn = intPreferencesKey("voice_barge_in")
        val largeTextFontScale = floatPreferencesKey("large_text_font_scale")
        val sparkFontScale = floatPreferencesKey("spark_font_scale")
        val savedProviders = stringPreferencesKey("saved_providers")
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
            safetyCheckEnabled = p[Keys.safetyCheck] ?: true,
            answerStyle = p[Keys.answerStyle] ?: defaults.answerStyle,
            themeMode = p[Keys.themeMode] ?: defaults.themeMode,
            amapKey = p[Keys.amapKey] ?: "",
            asrKey = p[Keys.asrKey] ?: "",
            mapMirrorUrl = p[Keys.mapMirrorUrl] ?: "",
            deviceName = p[Keys.deviceName] ?: "",
            dialectMode = p[Keys.dialectMode] ?: false,
            asrAutoSendSeconds = p[Keys.asrAutoSendSeconds] ?: defaults.asrAutoSendSeconds,
            largeTextMode = p[Keys.largeTextMode] ?: false,
            sparkMode = p[Keys.sparkMode] ?: false,
            homeAddress = p[Keys.homeAddress] ?: "",
            autoSpeak = p[Keys.autoSpeak] ?: false,
            keepAliveEnabled = p[Keys.keepAliveEnabled] ?: false,
            bootStartEnabled = p[Keys.bootStartEnabled] ?: false,
            robotEnabled = p[Keys.robotEnabled] ?: false,
            robotHost = p[Keys.robotHost] ?: "",
            proactiveReminders = p[Keys.proactiveReminders] ?: true,
            voiceBargeIn = p[Keys.voiceBargeIn] ?: 1,
            largeTextFontScale = p[Keys.largeTextFontScale] ?: 1.0f,
            sparkFontScale = p[Keys.sparkFontScale] ?: 1.0f,
            savedProviders = p[Keys.savedProviders]?.let { s ->
                runCatching { providerJson.decodeFromString<Map<String, ProviderCredentials>>(s) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap(),
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
            p[Keys.safetyCheck] = settings.safetyCheckEnabled
            p[Keys.answerStyle] = settings.answerStyle
            p[Keys.themeMode] = settings.themeMode
            p[Keys.amapKey] = settings.amapKey
            p[Keys.asrKey] = settings.asrKey
            p[Keys.mapMirrorUrl] = settings.mapMirrorUrl
            p[Keys.deviceName] = settings.deviceName
            p[Keys.dialectMode] = settings.dialectMode
            p[Keys.asrAutoSendSeconds] = settings.asrAutoSendSeconds
            p[Keys.largeTextMode] = settings.largeTextMode
            p[Keys.sparkMode] = settings.sparkMode
            p[Keys.homeAddress] = settings.homeAddress
            p[Keys.autoSpeak] = settings.autoSpeak
            p[Keys.keepAliveEnabled] = settings.keepAliveEnabled
            p[Keys.bootStartEnabled] = settings.bootStartEnabled
            p[Keys.robotEnabled] = settings.robotEnabled
            p[Keys.robotHost] = settings.robotHost
            p[Keys.proactiveReminders] = settings.proactiveReminders
            p[Keys.voiceBargeIn] = settings.voiceBargeIn
            p[Keys.largeTextFontScale] = settings.largeTextFontScale
            p[Keys.sparkFontScale] = settings.sparkFontScale
            p[Keys.savedProviders] = providerJson.encodeToString(settings.savedProviders)
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
