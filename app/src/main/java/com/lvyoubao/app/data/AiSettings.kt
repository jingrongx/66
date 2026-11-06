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
    val largeTextMode: Boolean = false, // 大字模式：打开则启动直接进大字模式，关闭则进普通模式
    val autoSpeak: Boolean = false,   // 顶部开关：新回答自动朗读
)
