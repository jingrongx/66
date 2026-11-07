package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

/** 每个供应商各自记一份接口配置：切换供应商时原样恢复自己配过的，互不带Key */
@Serializable
data class ProviderCredentials(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

/**
 * OpenAI 兼容的服务商预设。defaultModel 只是建议值，用户可在设置里修改或通过
 * “测试连接”拉取 /models 实时列表。
 * 模型名以 2026-11 各官方文档为准，取当前性价比最高、最快的多模态型号：
 * - DeepSeek：deepseek-flash（DeepSeek-V4.1-Flash）
 * - 智谱 GLM：glm-5.3-flash（GLM-5.3-Flash，普惠多模态旗舰，原生图片/视频理解，1M 上下文）
 * - Kimi：kimi-k2.6（多模态，思考/非思考自由切换）
 * - 通义千问：qwen3-vl-plus（兼容模式视觉旗舰，Qwen3-VL 系列）
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsThinkingToggle: Boolean = false,
    /** 一次消息最多带几张图；0 = 该供应商模型不支持看图（预设里支持视觉的都给 4） */
    val maxImages: Int = 4,
)

val ProviderPresets = listOf(
    ProviderPreset(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        supportsThinkingToggle = true,
        maxImages = 4,   // DeepSeek 最新的 flash 模型已支持视觉输入
    ),
    ProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5.3-flash",
    ),
    ProviderPreset(
        id = "moonshot",
        label = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-k2.6",
    ),
    ProviderPreset(
        id = "qwen",
        label = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3-vl-plus",
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
