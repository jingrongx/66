package com.lvyoubao.app.data

import android.content.Context
import com.lvyoubao.app.data.observation.FatigueEngine
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
            parameters = """{"type":"object","properties":{"destination":{"type":"string","description":"目的地名称，只填地名本身（如'大会岭'），不要把用户整句话当目的地"}},"required":["destination"]}""",
        ),
    )

    data class ToolDef(val name: String, val description: String, val parameters: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 工具执行：返回给模型的文本之外，还会把「查路线 / 搜地点」拿到的结构化数据
     * 通过 [onMapCard] 回传，会话层据此在回答下方渲染地图卡。
     */
    fun execute(
        context: Context,
        settings: AiSettings,
        name: String,
        argumentsJson: String,
        onMapCard: (MapCardData) -> Unit = {},
    ): String {
        val args: JsonObject? = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrNull()
        val arg = { key: String -> args?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
        // 工具回调在 IO 线程（允许阻塞）：走 freshBlocking 主动定位，搜「附近」不用旧位置
        val place = LocationContext.freshBlocking(context)
        return try {
            when (name) {
                POI -> {
                    val keyword = AmapClient.cleanPlaceName(arg("keyword") ?: throw ApiException("缺少 keyword"))
                    if (settings.amapKey.isBlank()) return "未配置高德 Key，无法查 POI。建议回答时提示用户在设置里填写高德 Key。"
                    val p = place ?: throw ApiException("没有位置信息")
                    val pois = AmapClient(settings.amapKey).poiSearch(keyword, p.lon, p.lat)
                    if (pois.isEmpty()) "附近没有找到「$keyword」"
                    else {
                        // 地点卡：候选点带回会话，图上标点、可点「去这里」
                        onMapCard(
                            MapCardData(
                                kind = "place",
                                title = keyword,
                                fromLat = p.lat,
                                fromLon = p.lon,
                                toLat = pois.first().lat,
                                toLon = pois.first().lon,
                                pois = pois.map { MapPoi(it.name, it.address, it.lat, it.lon, it.distanceM) },
                            ),
                        )
                        pois.joinToString("\n") {
                            "- ${it.name}｜${it.address}｜约 ${it.distanceM} 米"
                        }
                    }
                }

                WEATHER -> {
                    val p = place ?: throw ApiException("没有位置信息")
                    WeatherClient.current(p.lon, p.lat)
                }

                ROUTE -> {
                    val destination = arg("destination") ?: throw ApiException("缺少 destination")
                    route(context, settings, destination, onMapCard)
                }

                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.message ?: "未知原因"}。请如实告知用户该信息暂时查不到。"
        }
    }

    /** 路线：优先高德（有 Key 时），否则 BRouter（已安装时），都不可用给明确提示 */
    fun route(
        context: Context,
        settings: AiSettings,
        destination: String,
        onMapCard: (MapCardData) -> Unit = {},
    ): String {
        val place = LocationContext.freshBlocking(context) ?: throw ApiException("没有位置信息")
        if (settings.amapKey.isNotBlank()) {
            val amap = AmapClient(settings.amapKey)
            // 目的地是「回家」类词时直接用设置的家庭地址：AI 偶尔只传一个"家"字，
            // 附近 POI 检索会命中别家含"家"的店，跳高德就带错地址
            val home = settings.homeAddress.trim()
            val destinationText = if (AmapClient.isHomeDestination(destination) && home.isNotBlank()) home else destination
            val to = if (destinationText == home) {
                amap.geocode(destinationText)?.let {
                    AmapClient.ResolvedDest(it.first, it.second, AmapClient.cleanPlaceName(destinationText))
                }
            } else {
                AmapClient.resolveDestination(amap, destinationText, place.lon, place.lat)
            } ?: throw ApiException("无法解析目的地「$destinationText」的坐标")
            // 出行方式按直线距离自动选：超过 2 公里用驾车（跨城回家步行规划不合理也容易失败）
            val straightKm = FatigueEngine.haversine(place.lat, place.lon, to.lat, to.lon) / 1000.0
            val routeMode = if (straightKm > 2.0) RouteMode.DRIVE else RouteMode.WALK
            // 新接口带分步指引；异常时退回旧步行接口（无指引但能画线）
            val card = runCatching {
                val plan = amap.routePlans(routeMode, place.lon, place.lat, to.lon, to.lat).firstOrNull()
                if (plan != null) {
                    MapCardData(
                        kind = "route",
                        title = to.name,
                        mode = routeMode.name,
                        fromLat = place.lat,
                        fromLon = place.lon,
                        toLat = to.lat,
                        toLon = to.lon,
                        distanceM = plan.distanceM,
                        durationMin = plan.durationMin,
                        points = plan.points.map { listOf(it.first, it.second) },
                        steps = plan.steps,
                    )
                } else {
                    val r = amap.walkingRoute(place.lon, place.lat, to.lon, to.lat)
                    MapCardData(
                        kind = "route",
                        title = to.name,
                        fromLat = place.lat,
                        fromLon = place.lon,
                        toLat = to.lat,
                        toLon = to.lon,
                        distanceM = r.distanceM,
                        durationMin = r.durationMin,
                        points = r.points.map { listOf(it.first, it.second) },
                    )
                }
            }.getOrElse { throw it }
            onMapCard(card)
            return "${routeMode.label}至「${to.name}」：${MapCardData.distanceText(card.distanceM)}，${MapCardData.durationText(card.durationMin)}（高德${routeMode.label}路线）"
        }
        if (BRouterClient.isInstalled(context)) {
            // BRouter 需要坐标起点：先粗定位（用当前坐标），目的地用文本让 BRouter 不行——
            // BRouter 只接受坐标，因此这里同样尝试高德 geocode？没有 Key 就无法解析目的地。
            throw ApiException("BRouter 需要目的地坐标；请在设置里填写高德 Key 以解析目的地，或直接给出坐标。")
        }
        throw ApiException("路线查询需要高德 Key（设置里填写）或安装 BRouter 应用")
    }

    fun formatRoute(r: AmapClient.RouteResult, destination: String, source: String): String =
        "步行至「$destination」：${MapCardData.distanceText(r.distanceM)}，${MapCardData.durationText(r.durationMin)}" +
            (if (r.climbM > 20) "，累计爬升约 ${r.climbM} 米" else "") +
            "（$source）"
}
