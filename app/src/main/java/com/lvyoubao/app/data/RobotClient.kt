package com.lvyoubao.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * xrobotctl 机器人控制中枢客户端（https://github.com/jingrongx/xrobotctl）：
 * 机器人本机常驻 HTTP 服务（默认端口 8899），零依赖 GET 接口；动作进中枢串行队列逐条下发，
 * /stop 急停可抢占清空队列。返回 JSON：成功 ok=true，失败 ok=false + msg。
 * 现内置机型：优必选「小方头」（visbot 驱动，能力 move/turn/head/vision/status）；
 * 新机型由中枢新增驱动即可，这里按 /ping 的 driver/name 区分展示。
 */
class RobotClient(private val host: String) {

    companion object {
        const val DEFAULT_PORT = 8899

        /** 小方头头部角度硬限制（中枢文档：-25~23，正=抬头 负=低头） */
        const val HEAD_MIN = -25
        const val HEAD_MAX = 23

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 地址规范化：容忍粘贴带协议/路径/空格（http://192.168.3.102:8899/）；
         * 缺端口补 8899。解析不出主机返回 null。
         */
        fun normalizeHost(raw: String): String? {
            var t = raw.trim().trimEnd('/')
            if (t.isBlank()) return null
            t = t.removePrefix("http://").removePrefix("https://")
            t = t.substringBefore('/') // 去掉路径
            if (t.isBlank() || !t.contains('.') && !t.contains(':')) return null
            // IPv4 带端口或纯 IP；纯 IP 补默认端口（localhost/127.0.0.1 也补）
            val hasPort = t.lastIndexOf(':') > t.lastIndexOf('.')
            return if (hasPort) t else "$t:$DEFAULT_PORT"
        }

        /** 从 /ping 的返回里取机器人显示名（如「优必选 小方头」） */
        private fun nameOf(obj: JsonObject): String =
            obj["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "机器人" }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 统一 GET + ok 解包：失败带直白原因（连接不上/超时/中枢报错原文） */
    private suspend fun get(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("http://$host$path").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("机器人服务异常（HTTP ${response.code}）")
                val obj = json.parseToJsonElement(body).jsonObject
                val ok = obj["ok"]?.jsonPrimitive?.content.orEmpty() == "true"
                if (!ok) error(obj["msg"]?.jsonPrimitive?.content.orEmpty().ifBlank { "机器人没接受这条指令" })
                obj
            }
        }.recoverCatching { e ->
            throw IllegalStateException(
                when {
                    e.message?.contains("timeout", true) == true -> "机器人连接超时，请检查网络"
                    e.message?.contains("connect", true) == true ||
                        e.message?.contains("ECONNREFUSED", true) == true ||
                        e.message?.contains("Unable to resolve", true) == true -> "连不上机器人，请确认地址和中枢已打开"
                    else -> e.message ?: "机器人连接失败"
                },
            )
        }
    }

    /** 状态检查：成功返回机器人名称（也用于设置页「测试连接」） */
    suspend fun ping(): Result<String> = get("/ping").map { nameOf(it) }

    /** 当前机型支持的能力列表（move/turn/head/vision/status 等，用于展示与降级判断） */
    suspend fun capabilities(): Result<List<String>> = get("/capabilities").map { obj ->
        (obj["capabilities"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
    }

    /** 前进/后退（dist 厘米，正=前进 负=后退；中枢用 speed 符号表达方向），speed 厘米/秒默认 8 */
    suspend fun move(distCm: Int, speedCps: Int = 8): Result<Unit> {
        val dist = distCm.coerceIn(-500, 500)
        val speed = if (dist < 0) -abs(speedCps) else abs(speedCps)
        return get("/move?dist=${abs(dist)}&speed=$speed").map { }
    }

    /** 转向（angle 度，正=左转 负=右转），speed 度/秒默认 60 */
    suspend fun turn(angleDeg: Int, speedDps: Int = 60): Result<Unit> =
        get("/turn?angle=${angleDeg.coerceIn(-360, 360)}&speed=${abs(speedDps)}").map { }

    /** 抬头/低头（angle 度，正=抬头 负=低头，硬件限制 -25~23） */
    suspend fun head(angleDeg: Int): Result<Unit> =
        get("/head?angle=${angleDeg.coerceIn(HEAD_MIN, HEAD_MAX)}").map { }

    /** 急停：清空排队动作 + 立即停底盘与视觉（中枢抢占，不受队列阻塞） */
    suspend fun stop(): Result<Unit> = get("/stop").map { }
}
