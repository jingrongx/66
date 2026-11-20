package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

/**
 * 常用地址：支持任意个（家 / 公司 / 自定义）。
 * 问「回XX」时按 label 匹配，命中即用该地址规划导航。
 */
@Serializable
data class SavedAddress(
    val label: String = "",
    val address: String = "",
)

/**
 * 语音链路的模型选项注册表。
 * id 存进 AiSettings，label 用于设置页展示；空 id 表示"跟随全局/默认"，不强制覆盖。
 */
object VoiceModelOptions {
    /** 语音识别 ASR */
    data class Option(val id: String, val label: String)

    val ASR = listOf(
        Option("sensevoice", "SenseVoice ｜快速多语言转写·免费（硅基流动）"),
        Option("telespeech", "TeleSpeech ｜23种方言口音自适应·免费（硅基流动）"),
        Option("deepseek_asr", "DeepSeek ASR ｜官方识别·准确度高"),
        Option("system_asr", "系统自带识别 ｜离线可用·无网兜底"),
    )

    /** 对话大模型 LLM（与文本对话共用同一批 OpenAI 兼容服务商模型） */
    val LLM = listOf(
        Option("deepseek-flash", "DeepSeek Flash ｜日常问答·图文理解·速度快·免费"),
        Option("deepseek-v4-pro", "DeepSeek V4 Pro ｜深度推理·复杂问题更强·响应较慢"),
        Option("doubao-1.5-pro", "豆包 1.5 Pro ｜通用旗舰·多模态理解·免费额度"),
        Option("glm-5.3-flash", "智谱 GLM 5.3 Flash ｜超长上下文·普惠旗舰·免费"),
        Option("kimi-k2.6", "Kimi K2.6 ｜超长文档与多模态分析·免费"),
        Option("qwen3-vl-plus", "通义千问 Qwen3-VL ｜视觉旗舰·看图问答·免费"),
        Option("custom-model", "自定义模型 ｜使用凭证区配置的地址与密钥"),
    )

    /** 语音合成 TTS */
    val TTS = listOf(
        Option("edge", "微软 Edge 云端 ｜首句约1秒秒播·免费"),
        Option("cosy", "硅基流动 CosyVoice2 ｜高拟真人声·免费"),
        Option("deepseek_tts", "DeepSeek TTS ｜官方高质·收费"),
        Option("system_tts", "系统离线 TTS ｜本地免流·免费"),
    )

    /** 松手后自动发送延时档位（设计稿 3/5/7/9 秒；-1 表示关） */
    val AUTO_SEND_DELAYS = listOf(-1, 3, 5, 7, 9)

    /** 思考强度（DeepSeek 专属） */
    val REASONING_EFFORTS = listOf("low", "high", "max")

    fun labelFor(list: List<Option>, id: String): String =
        list.firstOrNull { it.id == id }?.label ?: "跟随全局/默认"
}
