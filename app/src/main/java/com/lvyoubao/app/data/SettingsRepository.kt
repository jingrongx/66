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
        val screenOrientation = stringPreferencesKey("screen_orientation")
        val ttsReuseSocket = booleanPreferencesKey("tts_reuse_socket")
        val proactiveReminders = booleanPreferencesKey("proactive_reminders")
        val voiceBargeIn = intPreferencesKey("voice_barge_in")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val largeTextFontScale = floatPreferencesKey("large_text_font_scale")
        val sparkFontScale = floatPreferencesKey("spark_font_scale")
        val sparkScreenOffSec = intPreferencesKey("spark_screen_off_sec")
        val savedProviders = stringPreferencesKey("saved_providers")
        val duplexApiKey = stringPreferencesKey("duplex_api_key")
        val duplexVoice = stringPreferencesKey("duplex_voice")
        val duplexDialect = stringPreferencesKey("duplex_dialect")
        val duplexMusic = booleanPreferencesKey("duplex_music")
        val wakeAction = stringPreferencesKey("wake_action")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        val savedAddresses = stringPreferencesKey("saved_addresses")
        val voiceAsrModel = stringPreferencesKey("voice_asr_model")
        val voiceLlmModel = stringPreferencesKey("voice_llm_model")
        val voiceTtsModel = stringPreferencesKey("voice_tts_model")
        val asrAutoSendEnabled = booleanPreferencesKey("asr_auto_send_enabled")
        val callEngine = stringPreferencesKey("call_engine")
        val callSpeakerphone = booleanPreferencesKey("call_speakerphone")
        val callAsrModel = stringPreferencesKey("call_asr_model")
        val callLlmModel = stringPreferencesKey("call_llm_model")
        val callTtsModel = stringPreferencesKey("call_tts_model")
        val timeAutoSync = booleanPreferencesKey("time_auto_sync")
        val timeZoneMode = stringPreferencesKey("time_zone_mode")
        val timeSyncIntervalMin = intPreferencesKey("time_sync_interval_min")
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
            screenOrientation = p[Keys.screenOrientation] ?: DeviceProfile.defaultOrientation,
            ttsReuseSocket = p[Keys.ttsReuseSocket] ?: true,
            proactiveReminders = p[Keys.proactiveReminders] ?: true,
            voiceBargeIn = p[Keys.voiceBargeIn] ?: 1,
            wakeWordEnabled = p[Keys.wakeWordEnabled] ?: true,
            largeTextFontScale = p[Keys.largeTextFontScale] ?: 1.0f,
            sparkFontScale = p[Keys.sparkFontScale] ?: 1.0f,
            sparkScreenOffSec = p[Keys.sparkScreenOffSec] ?: 30,
            savedProviders = p[Keys.savedProviders]?.let { s ->
                runCatching { providerJson.decodeFromString<Map<String, ProviderCredentials>>(s) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap(),
            duplexApiKey = p[Keys.duplexApiKey] ?: "",
            duplexVoice = p[Keys.duplexVoice] ?: defaults.duplexVoice,
            duplexDialect = p[Keys.duplexDialect] ?: defaults.duplexDialect,
            duplexMusic = p[Keys.duplexMusic] ?: defaults.duplexMusic,
            wakeAction = p[Keys.wakeAction] ?: defaults.wakeAction,
            reasoningEffort = p[Keys.reasoningEffort] ?: "high",
            // 地址列表：新格式优先；旧版只有一个 homeAddress 时迁移成「家」一条，不丢老用户数据
            savedAddresses = p[Keys.savedAddresses]?.let { s ->
                runCatching { providerJson.decodeFromString<List<SavedAddress>>(s) }.getOrDefault(emptyList())
            } ?: p[Keys.homeAddress]?.takeIf { it.isNotBlank() }
                ?.let { listOf(SavedAddress("家", it)) } ?: emptyList(),
            voiceAsrModel = p[Keys.voiceAsrModel] ?: "",
            voiceLlmModel = p[Keys.voiceLlmModel] ?: "",
            voiceTtsModel = p[Keys.voiceTtsModel] ?: "",
            asrAutoSendEnabled = p[Keys.asrAutoSendEnabled] ?: false,
            callEngine = p[Keys.callEngine] ?: "doubao",
            callSpeakerphone = p[Keys.callSpeakerphone] ?: defaults.callSpeakerphone,
            callAsrModel = p[Keys.callAsrModel] ?: "",
            callLlmModel = p[Keys.callLlmModel] ?: "",
            callTtsModel = p[Keys.callTtsModel] ?: "",
            timeAutoSync = p[Keys.timeAutoSync] ?: defaults.timeAutoSync,
            timeZoneMode = p[Keys.timeZoneMode] ?: defaults.timeZoneMode,
            timeSyncIntervalMin = p[Keys.timeSyncIntervalMin] ?: defaults.timeSyncIntervalMin,
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
            p[Keys.screenOrientation] = settings.screenOrientation
            p[Keys.ttsReuseSocket] = settings.ttsReuseSocket
            p[Keys.proactiveReminders] = settings.proactiveReminders
            p[Keys.voiceBargeIn] = settings.voiceBargeIn
            p[Keys.wakeWordEnabled] = settings.wakeWordEnabled
            p[Keys.largeTextFontScale] = settings.largeTextFontScale
            p[Keys.sparkFontScale] = settings.sparkFontScale
            p[Keys.sparkScreenOffSec] = settings.sparkScreenOffSec
            p[Keys.savedProviders] = providerJson.encodeToString(settings.savedProviders)
            p[Keys.duplexApiKey] = settings.duplexApiKey
            p[Keys.duplexVoice] = settings.duplexVoice
            p[Keys.duplexDialect] = settings.duplexDialect
            p[Keys.duplexMusic] = settings.duplexMusic
            p[Keys.wakeAction] = settings.wakeAction
            p[Keys.reasoningEffort] = settings.reasoningEffort
            p[Keys.savedAddresses] = providerJson.encodeToString(settings.savedAddresses)
            // 兼容旧字段：把列表里「家」的地址同步回 homeAddress，老代码（AppTools/ChatViewModel）仍可读
            p[Keys.homeAddress] = settings.savedAddresses.firstOrNull { it.label == "家" }?.address
                ?: settings.savedAddresses.firstOrNull()?.address ?: ""
            p[Keys.voiceAsrModel] = settings.voiceAsrModel
            p[Keys.voiceLlmModel] = settings.voiceLlmModel
            p[Keys.voiceTtsModel] = settings.voiceTtsModel
            p[Keys.asrAutoSendEnabled] = settings.asrAutoSendEnabled
            p[Keys.callEngine] = settings.callEngine
            p[Keys.callSpeakerphone] = settings.callSpeakerphone
            p[Keys.callAsrModel] = settings.callAsrModel
            p[Keys.callLlmModel] = settings.callLlmModel
            p[Keys.callTtsModel] = settings.callTtsModel
            p[Keys.timeAutoSync] = settings.timeAutoSync
            p[Keys.timeZoneMode] = settings.timeZoneMode
            p[Keys.timeSyncIntervalMin] = settings.timeSyncIntervalMin
        }
        // 时间配置即时生效：不要让校时/报时等下一轮读取设置
        TimeSync.applySettings(settings)
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
