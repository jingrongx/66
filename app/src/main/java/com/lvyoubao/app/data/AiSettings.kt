package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

data class AiSettings(
    val presetId: String = ProviderPresets.first().id,
    val baseUrl: String = ProviderPresets.first().baseUrl,
    val apiKey: String = "",
    val model: String = ProviderPresets.first().defaultModel,
    val thinking: Boolean = false,
    val searchEnabled: Boolean = true,
    val answerStyle: String = "concise", // 回答风格：concise 简洁（默认）/ medium 中等 / detailed 详尽；提问里明确要详细时仍会给详细
    val themeMode: String = "system",
    val amapKey: String = "",
    val asrKey: String = "",
    val dialectMode: Boolean = false, // 方言口音识别：赣语抚州话等本地说法（TeleSpeech-ASR 免费模型）
    val largeTextMode: Boolean = false, // 大字模式：打开则启动直接进大字模式，关闭则进普通模式
    val homeAddress: String = "",     // 常用地址（家）：问「回家的路」直接导航回家，不用每次问地址
    val autoSpeak: Boolean = false,   // 顶部开关：新回答自动朗读
    val savedProviders: Map<String, ProviderCredentials> = emptyMap(), // 每个供应商各自记住的接口配置，切换时恢复自己的，互不带Key
)
