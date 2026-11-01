package com.lvyoubao.app.data

data class AiSettings(
    val presetId: String = ProviderPresets.first().id,
    val baseUrl: String = ProviderPresets.first().baseUrl,
    val apiKey: String = "",
    val model: String = ProviderPresets.first().defaultModel,
    val thinking: Boolean = false,
)
