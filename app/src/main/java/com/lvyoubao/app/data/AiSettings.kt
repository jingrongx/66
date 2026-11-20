package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

data class AiSettings(
    val presetId: String = ProviderPresets.first().id,
    val baseUrl: String = ProviderPresets.first().baseUrl,
    val apiKey: String = "",
    val model: String = ProviderPresets.first().defaultModel,
    val thinking: Boolean = false,
    val searchEnabled: Boolean = true,
    val safetyCheckEnabled: Boolean = true, // 内容安全风控：开启 DeepSeek 官方安全审核，默认开启
    val answerStyle: String = "concise", // 回答风格：concise 简洁（默认）/ medium 中等 / detailed 详尽；提问里明确要详细时仍会给详细
    val themeMode: String = "system",
    val amapKey: String = "",
    val asrKey: String = "",
    val mapMirrorUrl: String = "",    // 离线地图镜像前缀（如自托管 OSS/NAS），下载优先走镜像、失败回退官方源
    val deviceName: String = "",      // 设备名（地图同步用）：中文随机名+型号，首次生成后持久化；不入扫码同步
    val dialectMode: Boolean = false, // 方言口音识别：赣语抚州话等本地说法（TeleSpeech-ASR 免费模型）
    val asrAutoSendSeconds: Int = -1, // 语音识别后自动发送：-1 关（默认）/0 立即/1/3/5 秒；点输入框、再次按住说话或手动编辑即取消
    val largeTextMode: Boolean = false, // 大字模式：打开则启动直接进大字模式，关闭则进普通模式
    val largeTextFontScale: Float = 1.0f, // 大字模式字号系数：设置页可调 0.85~1.3，通过 fontScale 整体缩放该模式 sp 文字
    val sparkMode: Boolean = false, // 灵动模式：专为手表/小屏打造，极简可爱双眼，唤醒即对话，按需视觉识别
    val sparkFontScale: Float = 1.0f, // 灵动模式字号系数：同上，小屏用户可再放大
    val homeAddress: String = "",     // 常用地址（家）：问「回家的路」直接导航回家，不用每次问地址
    val autoSpeak: Boolean = false,   // 顶部开关：新回答自动朗读
    val keepAliveEnabled: Boolean = false, // 后台常驻：前台服务保活，不被系统回收（灵动模式/随身设备场景）
    val bootStartEnabled: Boolean = false, // 开机自启：开机完成后自动拉起应用/常驻服务（设为桌面时系统本来就会拉起）
    val robotEnabled: Boolean = false, // 机器人控制：语音指挥机器人动作（需机器人已装 xrobotctl 控制中枢）
    val robotHost: String = "",        // 机器人中枢地址：机器人局域网 IP（机器人本机可用 127.0.0.1），端口缺省 8899
    val proactiveReminders: Boolean = true, // 主动提醒：电量低/充满时小源自主说一句（只在空闲时说，不打断对话）
    val voiceBargeIn: Int = 1,        // 语音打断灵敏度（连续对话）：0 关(半双工)/1 标准/2 灵敏；回声压不住的设备调关
    val wakeWordEnabled: Boolean = true, // 唤醒词监听：灵动模式待机时喊「小源小源」直接进连续对话（云识别关键词，限流防杂音）
    val savedProviders: Map<String, ProviderCredentials> = emptyMap(), // 每个供应商各自记住的接口配置，切换时恢复自己的，互不带Key
    val duplexApiKey: String = "",     // 豆包端到端实时语音（Seeduplex 全双工）专用 Key：火山「实时语音」产品，走 X-Api-Key，非文本大模型 Key
    val duplexVoice: String = "zh_female_vv_jupiter_bigtts", // 全双工通话音色 ID（默认 Vivi 2.0）

    // ===== 以下为设置页设计稿新增字段（批次 A）=====
    val reasoningEffort: String = "high", // 思考强度（DeepSeek 专属）：low / high / max
    val savedAddresses: List<SavedAddress> = emptyList(), // 常用地址（任意个）：问「回XX」按 label 匹配
    // 语音消息（主界面按住说话）：三段各自独立选模型，空字符串=跟随全局/默认
    val voiceAsrModel: String = "",
    val voiceLlmModel: String = "",
    val voiceTtsModel: String = "",
    val asrAutoSendEnabled: Boolean = false, // 松手自动发送独立开关（关时 asrAutoSendSeconds 无效）
    // 实时通话（全屏）：引擎 doubao 全双工 / classic 经典串行（预留 realtime 音视频暂不开放）
    val callEngine: String = "doubao",
    // 经典串行链路三段独立选模型，空字符串=跟随全局/默认
    val callAsrModel: String = "",
    val callLlmModel: String = "",
    val callTtsModel: String = "",
)
