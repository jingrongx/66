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
    val dialectMode: Boolean = false, // 方言口音识别：赣语抚州话等本地说法（TeleSpeech-ASR 免费模型）
    val elderMode: Boolean = false,
    val autoSpeak: Boolean = false,   // 顶部开关：新回答自动朗读
)
