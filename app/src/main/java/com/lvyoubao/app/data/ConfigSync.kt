package com.lvyoubao.app.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 扫码同步配置的载荷与二维码内容编解码。
 *
 * 两种二维码内容：
 * 1. 局域网推送（主流程，车机接收）：lvyoubao://lan/?h=<ip>&p=<port>&t=<token>
 *    车机起一个一次性本地服务，手机扫码后把本机配置 POST 过去。
 * 2. 完整配置直带（备用，有摄像头的设备互扫）：lvyoubao://config/<base64url(json)>
 */
@Serializable
data class SyncableConfig(
    val presetId: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val amapKey: String = "",
    val asrKey: String = "",
    val homeAddress: String = "",
    val answerStyle: String = "",
    val mapMirrorUrl: String = "",
    val duplexApiKey: String = "",   // 豆包全双工实时语音 Key
    val duplexVoice: String = "",    // 全双工音色 ID

    // ===== 全量同步扩展（设计稿：同步所有配置页的所有配置）=====
    // 布尔/数值一律可空：null = 旧版载荷没这个字段，merge 时保留接收端原值，兼容老二维码
    val thinking: Boolean? = null,
    val reasoningEffort: String = "",
    val searchEnabled: Boolean? = null,
    val safetyCheckEnabled: Boolean? = null,
    val themeMode: String = "",
    val largeTextMode: Boolean? = null,
    val largeTextFontScale: Float? = null,
    val sparkMode: Boolean? = null,
    val sparkFontScale: Float? = null,
    val keepAliveEnabled: Boolean? = null,
    val bootStartEnabled: Boolean? = null,
    val autoSpeak: Boolean? = null,
    val dialectMode: Boolean? = null,
    val asrAutoSendSeconds: Int? = null,
    val asrAutoSendEnabled: Boolean? = null,
    val proactiveReminders: Boolean? = null,
    val voiceBargeIn: Int? = null,
    val wakeWordEnabled: Boolean? = null,
    val savedProvidersJson: String = "", // Map<String, ProviderCredentials> 序列化后传递
    val savedAddresses: List<SavedAddress> = emptyList(),
    val voiceAsrModel: String = "",
    val voiceLlmModel: String = "",
    val voiceTtsModel: String = "",
    val callEngine: String = "",
    val callAsrModel: String = "",
    val callLlmModel: String = "",
    val callTtsModel: String = "",
    val robotEnabled: Boolean? = null, // 机器人控制：语音指挥开关
    val robotHost: String = "",        // 机器人中枢地址（局域网 IP / 127.0.0.1）
)

object ConfigSyncCodec {

    private const val LAN_PREFIX = "lvyoubao://lan/?"
    private const val CONFIG_PREFIX = "lvyoubao://config/"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun fromSettings(s: AiSettings) = SyncableConfig(
        presetId = s.presetId,
        baseUrl = s.baseUrl,
        apiKey = s.apiKey,
        model = s.model,
        amapKey = s.amapKey,
        asrKey = s.asrKey,
        homeAddress = s.homeAddress,
        answerStyle = s.answerStyle,
        mapMirrorUrl = s.mapMirrorUrl,
        duplexApiKey = s.duplexApiKey,
        duplexVoice = s.duplexVoice,
        thinking = s.thinking,
        reasoningEffort = s.reasoningEffort,
        searchEnabled = s.searchEnabled,
        safetyCheckEnabled = s.safetyCheckEnabled,
        themeMode = s.themeMode,
        largeTextMode = s.largeTextMode,
        largeTextFontScale = s.largeTextFontScale,
        sparkMode = s.sparkMode,
        sparkFontScale = s.sparkFontScale,
        keepAliveEnabled = s.keepAliveEnabled,
        bootStartEnabled = s.bootStartEnabled,
        autoSpeak = s.autoSpeak,
        dialectMode = s.dialectMode,
        asrAutoSendSeconds = s.asrAutoSendSeconds,
        asrAutoSendEnabled = s.asrAutoSendEnabled,
        proactiveReminders = s.proactiveReminders,
        voiceBargeIn = s.voiceBargeIn,
        wakeWordEnabled = s.wakeWordEnabled,
        savedProvidersJson = Json.encodeToString(s.savedProviders),
        savedAddresses = s.savedAddresses,
        voiceAsrModel = s.voiceAsrModel,
        voiceLlmModel = s.voiceLlmModel,
        voiceTtsModel = s.voiceTtsModel,
        callEngine = s.callEngine,
        callAsrModel = s.callAsrModel,
        callLlmModel = s.callLlmModel,
        callTtsModel = s.callTtsModel,
        robotEnabled = s.robotEnabled,
        robotHost = s.robotHost,
    )

    /** 把配置合并回 AiSettings（只覆盖载荷里非空的字段） */
    fun merge(s: AiSettings, c: SyncableConfig): AiSettings = s.copy(
        presetId = c.presetId.ifEmpty { s.presetId },
        baseUrl = c.baseUrl.ifEmpty { s.baseUrl },
        apiKey = c.apiKey.ifEmpty { s.apiKey },
        model = c.model.ifEmpty { s.model },
        amapKey = c.amapKey.ifEmpty { s.amapKey },
        asrKey = c.asrKey.ifEmpty { s.asrKey },
        homeAddress = c.savedAddresses.firstOrNull { it.label == "家" }?.address
            ?: c.savedAddresses.firstOrNull()?.address
            ?: c.homeAddress.ifEmpty { s.homeAddress },
        answerStyle = c.answerStyle.ifEmpty { s.answerStyle },
        mapMirrorUrl = c.mapMirrorUrl.ifEmpty { s.mapMirrorUrl },
        duplexApiKey = c.duplexApiKey.ifEmpty { s.duplexApiKey },
        duplexVoice = c.duplexVoice.ifEmpty { s.duplexVoice },
        // 字符串：非空覆盖；布尔/数值可空：null（旧版载荷没带）保留接收端原值
        thinking = c.thinking ?: s.thinking,
        reasoningEffort = c.reasoningEffort.ifEmpty { s.reasoningEffort },
        searchEnabled = c.searchEnabled ?: s.searchEnabled,
        safetyCheckEnabled = c.safetyCheckEnabled ?: s.safetyCheckEnabled,
        themeMode = c.themeMode.ifEmpty { s.themeMode },
        largeTextMode = c.largeTextMode ?: s.largeTextMode,
        largeTextFontScale = c.largeTextFontScale ?: s.largeTextFontScale,
        sparkMode = c.sparkMode ?: s.sparkMode,
        sparkFontScale = c.sparkFontScale ?: s.sparkFontScale,
        keepAliveEnabled = c.keepAliveEnabled ?: s.keepAliveEnabled,
        bootStartEnabled = c.bootStartEnabled ?: s.bootStartEnabled,
        autoSpeak = c.autoSpeak ?: s.autoSpeak,
        dialectMode = c.dialectMode ?: s.dialectMode,
        asrAutoSendSeconds = c.asrAutoSendSeconds ?: s.asrAutoSendSeconds,
        asrAutoSendEnabled = c.asrAutoSendEnabled ?: s.asrAutoSendEnabled,
        proactiveReminders = c.proactiveReminders ?: s.proactiveReminders,
        voiceBargeIn = c.voiceBargeIn ?: s.voiceBargeIn,
        wakeWordEnabled = c.wakeWordEnabled ?: s.wakeWordEnabled,
        savedProviders = c.savedProvidersJson.takeIf { it.isNotBlank() }?.let { json ->
            runCatching { Json.decodeFromString<Map<String, ProviderCredentials>>(json) }
                .getOrDefault(s.savedProviders)
        } ?: s.savedProviders,
        savedAddresses = c.savedAddresses.ifEmpty { s.savedAddresses },
        voiceAsrModel = c.voiceAsrModel,
        voiceLlmModel = c.voiceLlmModel,
        voiceTtsModel = c.voiceTtsModel,
        callEngine = c.callEngine.ifEmpty { s.callEngine },
        callAsrModel = c.callAsrModel,
        callLlmModel = c.callLlmModel,
        callTtsModel = c.callTtsModel,
        robotEnabled = c.robotEnabled ?: s.robotEnabled,
        robotHost = c.robotHost.ifEmpty { s.robotHost },
    )

    fun encodeJson(c: SyncableConfig): String = json.encodeToString(c)

    fun decodeJson(text: String): SyncableConfig? = runCatching {
        json.decodeFromString<SyncableConfig>(text)
    }.getOrNull()

    /** 局域网推送二维码内容 */
    fun encodeLanQr(host: String, port: Int, token: String): String =
        "${LAN_PREFIX}h=$host&p=$port&t=$token"

    data class LanEndpoint(val host: String, val port: Int, val token: String)

    /** 解析局域网推送二维码；不是本应用的二维码返回 null */
    fun parseLanQr(content: String): LanEndpoint? {
        if (!content.startsWith("lvyoubao://lan/")) return null
        val query = content.substringAfter('?', "")
        var host: String? = null
        var port: Int? = null
        var token: String? = null
        query.split('&').forEach { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@forEach
            val k = pair.substring(0, i)
            val v = pair.substring(i + 1)
            when (k) {
                "h" -> host = v
                "p" -> port = v.toIntOrNull()
                "t" -> token = v
            }
        }
        val h = host ?: return null
        val p = port ?: return null
        val t = token ?: return null
        return LanEndpoint(h, p, t)
    }

    /** 完整配置直带二维码内容（备用方向：有摄像头的设备互扫） */
    fun encodeConfigQr(c: SyncableConfig): String {
        val encoded = Base64.encodeToString(
            encodeJson(c).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return CONFIG_PREFIX + encoded
    }

    /** 解析配置直带二维码；不是本应用的二维码返回 null */
    fun parseConfigQr(content: String): SyncableConfig? {
        if (!content.startsWith(CONFIG_PREFIX)) return null
        val encoded = content.removePrefix(CONFIG_PREFIX)
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            decodeJson(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }
}
