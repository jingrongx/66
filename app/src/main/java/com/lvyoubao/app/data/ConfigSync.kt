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
    )

    /** 把配置合并回 AiSettings（只覆盖载荷里非空的字段） */
    fun merge(s: AiSettings, c: SyncableConfig): AiSettings = s.copy(
        presetId = c.presetId.ifEmpty { s.presetId },
        baseUrl = c.baseUrl.ifEmpty { s.baseUrl },
        apiKey = c.apiKey.ifEmpty { s.apiKey },
        model = c.model.ifEmpty { s.model },
        amapKey = c.amapKey.ifEmpty { s.amapKey },
        asrKey = c.asrKey.ifEmpty { s.asrKey },
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
