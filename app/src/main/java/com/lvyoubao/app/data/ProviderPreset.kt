package com.lvyoubao.app.data

/**
 * OpenAI 兼容的服务商预设。defaultModel 只是建议值，用户可在设置里修改或通过
 * “测试连接”拉取 /models 实时列表。DeepSeek 模型名以 2026-11 官方文档为准：
 * deepseek-flash（DeepSeek-V4.1-Flash）/ deepseek-v4-pro（DeepSeek-V4-Pro）。
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsThinkingToggle: Boolean = false,
)

val ProviderPresets = listOf(
    ProviderPreset(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        supportsThinkingToggle = true,
    ),
    ProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
    ),
    ProviderPreset(
        id = "moonshot",
        label = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
    ),
    ProviderPreset(
        id = "qwen",
        label = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-plus",
    ),
    ProviderPreset(
        id = "custom",
        label = "自定义",
        baseUrl = "",
        defaultModel = "",
    ),
)

fun presetById(id: String): ProviderPreset =
    ProviderPresets.firstOrNull { it.id == id } ?: ProviderPresets.first()
