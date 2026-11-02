package com.lvyoubao.app.data

data class AiSettings(
    val presetId: String = ProviderPresets.first().id,
    val baseUrl: String = ProviderPresets.first().baseUrl,
    val apiKey: String = "",
    val model: String = ProviderPresets.first().defaultModel,
    val thinking: Boolean = false,
    val searchEnabled: Boolean = true,
    val themeMode: String = "system",
    val amapKey: String = "",
    val asrKey: String = "",
    val elderMode: Boolean = false,
)
