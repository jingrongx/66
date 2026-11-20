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
    /** Key 申请官网地址 */
    val apiKeyUrl: String = "",
    /** 申请指引 */
    val applyGuide: String = "",
)

val ProviderPresets = listOf(
    ProviderPreset(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        supportsThinkingToggle = true,
        maxImages = 4,   // DeepSeek 最新的 flash 模型已支持视觉输入
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
        applyGuide = "登录 DeepSeek 开放平台 → 左侧「API keys」→ 点击「创建 API key」复制即可。",
    ),
    ProviderPreset(
        id = "zhipu",
        label = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5.3-flash",
        apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
        applyGuide = "登录智谱开放平台 → 进入右上角「API Keys」→ 创建 API Key 复制（新用户送免费包）。",
    ),
    ProviderPreset(
        id = "moonshot",
        label = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-k2.6",
        apiKeyUrl = "https://platform.moonshot.cn/console/api-keys",
        applyGuide = "登录 Moonshot 开放平台 → 控制台「API Key 管理」新建 API Key 复制即可。",
    ),
    ProviderPreset(
        id = "qwen",
        label = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3-vl-plus",
        apiKeyUrl = "https://dashscope.console.aliyun.com/apiKey",
        applyGuide = "登录阿里云百炼（DashScope）控制台 →「我的 API-KEY」创建并复制。",
    ),
    ProviderPreset(
        id = "custom",
        label = "自定义",
        baseUrl = "",
        defaultModel = "",
        apiKeyUrl = "",
        applyGuide = "填入兼容 OpenAI 规范的服务商接口地址（Base URL）与 API Key。",
    ),
    // 豆包（火山方舟）：OpenAI 兼容 Ark 接口，可走文本/图片对话。注意与「实时语音」的 duplexApiKey 是两把不同的 Key。
    ProviderPreset(
        id = "doubao",
        label = "豆包",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "doubao-1.5-pro-32k",
        maxImages = 4,
        apiKeyUrl = "https://console.volcengine.com/ark",
        applyGuide = "火山方舟控制台 →「API Key 管理」创建并复制。此 Key 供文本/图片对话；实时全双工语音需在「豆包语音」产品另建 Key。",
    ),
    // 硅基流动：OpenAI 兼容，主要用免费的 SenseVoice/TeleSpeech 语音识别与 CosyVoice 朗读，也可跑文本模型。
    ProviderPreset(
        id = "silicon",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen3-8B",
        apiKeyUrl = "https://cloud.siliconflow.cn/account/ak",
        applyGuide = "硅基流动云平台 →「API 密钥」新建复制。用于免费 SenseVoice / TeleSpeech 语音识别与 CosyVoice2 朗读。",
    ),
)

fun presetById(id: String): ProviderPreset =
    ProviderPresets.firstOrNull { it.id == id } ?: ProviderPresets.first()
