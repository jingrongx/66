package com.lvyoubao.app.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Function Calling 工具执行器：模型决定“要查什么”，这里负责拿真实数据。
 * 事实一律来自工具返回（推演方案第 4 节：实时查询替代模型记忆）。
 */
object AppTools {

    const val POI = "search_poi"
    const val WEATHER = "get_weather"
    const val ROUTE = "get_route"

    val definitions = listOf(
        ToolDef(
            name = POI,
            description = "搜索附近的地点/店铺/景点（需要定位权限），返回名称、地址和距离",
            parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"要搜什么，例如：厕所、面馆、药店"}},"required":["keyword"]}""",
        ),
        ToolDef(
            name = WEATHER,
            description = "查询当前位置的实时天气（温度/体感/风/降水），需要定位权限",
            parameters = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolDef(
            name = ROUTE,
            description = "查询从当前位置步行到某地的路线：距离、耗时、累计爬升",
            parameters = """{"type":"object","properties":{"destination":{"type":"string","description":"目的地名称或地址"}},"required":["destination"]}""",
        ),
    )

    data class ToolDef(val name: String, val description: String, val parameters: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun execute(context: Context, settings: AiSettings, name: String, argumentsJson: String): String {
        val args: JsonObject? = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrNull()
        val arg = { key: String -> args?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
        val place = LocationContext.current(context)
        return try {
            when (name) {
                POI -> {
                    val keyword = arg("keyword") ?: throw ApiException("缺少 keyword")
                    if (settings.amapKey.isBlank()) return "未配置高德 Key，无法查 POI。建议回答时提示用户在设置里填写高德 Key。"
                    val p = place ?: throw ApiException("没有位置信息")
                    val pois = AmapClient(settings.amapKey).poiSearch(keyword, p.lon, p.lat)
                    if (pois.isEmpty()) "附近没有找到「$keyword」"
                    else pois.joinToString("\n") {
                        "- ${it.name}｜${it.address}｜约 ${it.distanceM} 米"
                    }
                }

                WEATHER -> {
                    val p = place ?: throw ApiException("没有位置信息")
                    WeatherClient.current(p.lon, p.lat)
                }

                ROUTE -> {
                    val destination = arg("destination") ?: throw ApiException("缺少 destination")
                    route(context, settings, destination)
                }

                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.message ?: "未知原因"}。请如实告知用户该信息暂时查不到。"
        }
    }

    /** 路线：优先高德（有 Key 时），否则 BRouter（已安装时），都不可用给明确提示 */
    fun route(context: Context, settings: AiSettings, destination: String): String {
        val place = LocationContext.current(context) ?: throw ApiException("没有位置信息")
        if (settings.amapKey.isNotBlank()) {
            val amap = AmapClient(settings.amapKey)
            val to = amap.geocode(destination)
                ?: throw ApiException("无法解析目的地「$destination」的坐标")
            val r = amap.walkingRoute(place.lon, place.lat, to.first, to.second)
            return formatRoute(r, destination, "高德步行路线")
        }
        if (BRouterClient.isInstalled(context)) {
            // BRouter 需要坐标起点：先粗定位（用当前坐标），目的地用文本让 BRouter 不行——
            // BRouter 只接受坐标，因此这里同样尝试高德 geocode？没有 Key 就无法解析目的地。
            throw ApiException("BRouter 需要目的地坐标；请在设置里填写高德 Key 以解析目的地，或直接给出坐标。")
        }
        throw ApiException("路线查询需要高德 Key（设置里填写）或安装 BRouter 应用")
    }

    fun formatRoute(r: AmapClient.RouteResult, destination: String, source: String): String =
        "步行至「$destination」：${"%.1f".format(r.distanceM / 1000f)} 公里，约 ${r.durationMin} 分钟" +
            (if (r.climbM > 20) "，累计爬升约 ${r.climbM} 米" else "") +
            "（$source）"
}
