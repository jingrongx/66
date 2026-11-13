package com.lvyoubao.app.data

import com.lvyoubao.app.data.observation.FatigueEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 高德开放平台 Web API（POI 检索 / 步行路径规划 / 地理编码），需要用户自己的 Key */
class AmapClient(private val key: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class Poi(val name: String, val address: String, val lon: Double, val lat: Double, val distanceM: Int)

    @Serializable
    data class RouteResult(
        val distanceM: Int,
        val durationMin: Int,
        val points: List<Pair<Double, Double>>,
        val climbM: Int = 0,
    )

    /** 目的地解析结果：坐标 + 高德实际命中的名称（POI 名比模型输出的原句可靠，地图卡标题/跳高德都用它） */
    data class ResolvedDest(val lon: Double, val lat: Double, val name: String)

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            return response.body?.string() ?: throw ApiException("高德接口空响应")
        }
    }

    /** 把高德错误码翻译成可操作的提示（最常见的坑：Key 类型不对） */
    private fun mapAmapError(info: String?, infocode: String?): String {
        val code = infocode.orEmpty()
        val text = info.orEmpty()
        return when {
            code == "10009" || text.contains("USERKEY_PLAT_NOMATCH", true) ->
                "这个高德 Key 是「Android平台」类型，查不了路线/地点。请在 lbs.amap.com 控制台的「旅游宝」应用里再添加一个 Key，服务平台选「Web服务」，把新 Key 填到设置里"
            text.contains("INVALID_USER_KEY", true) || code == "10001" ->
                "高德 Key 无效，请到设置里检查是否复制完整"
            code == "10003" || text.contains("EXCEED", true) ->
                "高德接口调用超限，稍后再试"
            text.isNotBlank() -> "高德接口返回：$text"
            else -> "高德接口返回：未知错误（$code）"
        }
    }

    /** 关键词 POI 检索（按当前位置距离排序），失败抛 ApiException */
    fun poiSearch(keyword: String, wgsLon: Double, wgsLat: Double): List<Poi> {
        val g = Gcj02.fromWgs(wgsLat, wgsLon)
        val url = "https://restapi.amap.com/v3/place/text" +
            "?keywords=${urlEncode(keyword)}&location=${g.second},${g.first}&offset=6&page=1&extensions=base&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        val status = root["status"]?.jsonPrimitive?.content
        if (status != "1") {
            throw ApiException(mapAmapError(
                root["info"]?.jsonPrimitive?.content,
                root["infocode"]?.jsonPrimitive?.content,
            ))
        }
        return root["pois"]?.jsonArray?.mapNotNull { p ->
            val o = p.jsonObject
            val loc = o["location"]?.jsonPrimitive?.content?.split(",") ?: return@mapNotNull null
            val plon = loc.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val plat = loc.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            // POI 坐标 GCJ-02 → WGS-84，与调用方传入的位置同一坐标系后算距离
            val w = Gcj02.toWgs(plat, plon)
            Poi(
                name = o["name"]?.jsonPrimitive?.content ?: "",
                address = (o["address"]?.jsonPrimitive?.content ?: "").take(40),
                lon = w.second,
                lat = w.first,
                distanceM = FatigueEngine.haversine(wgsLat, wgsLon, w.first, w.second).toInt(),
            )
        }.orEmpty()
    }

    /** 步行路径规划：输入 WGS-84 坐标，内部转 GCJ-02 调高德，返回转回 WGS-84 途经点坐标（画 OSM 底图同系） */
    fun walkingRoute(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): RouteResult {
        val gFrom = Gcj02.fromWgs(fromLat, fromLon)
        val gTo = Gcj02.fromWgs(toLat, toLon)
        val url = "https://restapi.amap.com/v3/direction/walking" +
            "?origin=${gFrom.second},${gFrom.first}&destination=${gTo.second},${gTo.first}&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            throw ApiException(mapAmapError(
                root["info"]?.jsonPrimitive?.content,
                root["infocode"]?.jsonPrimitive?.content,
            ))
        }
        val path = root["route"]!!.jsonObject["paths"]!!.jsonArray.first().jsonObject
        val points = mutableListOf<Pair<Double, Double>>()
        path["steps"]!!.jsonArray.forEach { s ->
            val polyline = s.jsonObject["polyline"]?.jsonPrimitive?.content ?: return@forEach
            polyline.split(";").forEach { seg ->
                val c = seg.split(",")
                val lon = c.getOrNull(0)?.toDoubleOrNull()
                val lat = c.getOrNull(1)?.toDoubleOrNull()
                if (lon != null && lat != null) {
                    val w = Gcj02.toWgs(lat, lon)
                    points.add(w.second to w.first)
                }
            }
        }
        return RouteResult(
            distanceM = path["distance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            durationMin = ((path["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 60).toInt(),
            points = points,
        )
    }

    /** 地理编码：地址 → 坐标 */
    fun geocode(address: String, city: String? = null): Pair<Double, Double>? {        val url = "https://restapi.amap.com/v3/geocode/geo" +
            "?address=${urlEncode(address)}${city?.let { "&city=${urlEncode(it)}" } ?: ""}&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            throw ApiException(mapAmapError(
                root["info"]?.jsonPrimitive?.content,
                root["infocode"]?.jsonPrimitive?.content,
            ))
        }
        val loc = root["geocodes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("location")?.jsonPrimitive?.content
            ?.split(",") ?: throw ApiException("没找到「$address」的坐标，换个说法或输入「纬度,经度」试试")
        val lon = loc.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lat = loc.getOrNull(1)?.toDoubleOrNull() ?: return null
        // 高德返回 GCJ-02，统一转成 WGS-84 再出这个类（画 OSM 底图 / 与 GPS 同系）
        val w = Gcj02.toWgs(lat, lon)
        return w.second to w.first
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------- 多出行方式路线规划（会话地图卡用） ----------

    /**
     * 按出行方式查路线，返回全部方案（步行/骑行/驾车可能多条，公交取前 3 条）。
     * 任一方式失败抛 ApiException，由调用方提示。
     */
    fun routePlans(mode: RouteMode, fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): List<RoutePlan> {
        // 坐标系边界：调用方传 WGS-84（GPS/离线底图），高德接口要 GCJ-02；
        // 返回的折线再转回 WGS-84，这样路线才能贴着 OSM 底图的道路走
        val gFrom = Gcj02.fromWgs(fromLat, fromLon)
        val gTo = Gcj02.fromWgs(toLat, toLon)
        val list = when (mode) {
            RouteMode.WALK -> v3Paths(RouteMode.WALK, "walking", gFrom.second, gFrom.first, gTo.second, gTo.first)
            RouteMode.DRIVE -> v3Paths(RouteMode.DRIVE, "driving", gFrom.second, gFrom.first, gTo.second, gTo.first)
            RouteMode.RIDE -> v4Bicycle(gFrom.second, gFrom.first, gTo.second, gTo.first)
            RouteMode.BUS -> v3Transit(gFrom.second, gFrom.first, gTo.second, gTo.first)
        }
        return list.map { plan ->
            plan.copy(
                points = plan.points.map { (lon, lat) ->
                    val w = Gcj02.toWgs(lat, lon)
                    w.second to w.first
                },
            )
        }
    }

    /** v3 步行/驾车：同一套结构，steps 带分步指引与折线 */
    private fun v3Paths(
        mode: RouteMode,
        api: String,
        fromLon: Double,
        fromLat: Double,
        toLon: Double,
        toLat: Double,
    ): List<RoutePlan> {
        val url = "https://restapi.amap.com/v3/direction/$api" +
            "?origin=$fromLon,$fromLat&destination=$toLon,$toLat&key=$key&extensions=base"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            throw ApiException(mapAmapError(
                root["info"]?.jsonPrimitive?.content,
                root["infocode"]?.jsonPrimitive?.content,
            ))
        }
        val paths = root["route"]?.jsonObject?.get("paths")?.jsonArray ?: return emptyList()
        return paths.mapNotNull { p ->
            val o = p.jsonObject
            val dist = o["distance"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val dur = ((o["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 60).toInt()
            val pts = mutableListOf<Pair<Double, Double>>()
            val steps = mutableListOf<String>()
            val starts = mutableListOf<Int>()
            o["steps"]?.jsonArray?.forEach { s ->
                val so = s.jsonObject
                val polyline = so["polyline"]?.jsonPrimitive?.content ?: return@forEach
                starts.add(pts.size)
                steps.add(stripHtml(so["instruction"]?.jsonPrimitive?.content ?: ""))
                polyline.split(";").forEach { seg ->
                    val c = seg.split(",")
                    val lon = c.getOrNull(0)?.toDoubleOrNull()
                    val lat = c.getOrNull(1)?.toDoubleOrNull()
                    if (lon != null && lat != null) pts.add(lon to lat)
                }
            }
            RoutePlan(mode = mode, distanceM = dist, durationMin = dur, points = pts, steps = steps, stepStarts = starts)
        }
    }

    /** v4 骑行（注意 v4 的结构与 v3 不同，duration 单位秒） */
    private fun v4Bicycle(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): List<RoutePlan> {
        val url = "https://restapi.amap.com/v4/direction/bicycling" +
            "?origin=$fromLon,$fromLat&destination=$toLon,$toLat&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        val errcode = root["errcode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (errcode != 0) {
            throw ApiException("骑行路线查询失败：" + (root["errmsg"]?.jsonPrimitive?.content ?: "未知错误"))
        }
        val paths = root["data"]?.jsonObject?.get("paths")?.jsonArray ?: return emptyList()
        return paths.mapNotNull { p ->
            val o = p.jsonObject
            val dist = o["distance"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            val dur = ((o["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 60).toInt()
            val pts = mutableListOf<Pair<Double, Double>>()
            val steps = mutableListOf<String>()
            val starts = mutableListOf<Int>()
            o["steps"]?.jsonArray?.forEach { s ->
                val so = s.jsonObject
                val polyline = so["polyline"]?.jsonPrimitive?.content ?: return@forEach
                starts.add(pts.size)
                steps.add(stripHtml(so["instruction"]?.jsonPrimitive?.content ?: ""))
                polyline.split(";").forEach { seg ->
                    val c = seg.split(",")
                    val lon = c.getOrNull(0)?.toDoubleOrNull()
                    val lat = c.getOrNull(1)?.toDoubleOrNull()
                    if (lon != null && lat != null) pts.add(lon to lat)
                }
            }
            RoutePlan(mode = RouteMode.RIDE, distanceM = dist, durationMin = dur, points = pts, steps = steps, stepStarts = starts)
        }
    }

    /** v3 公交（transit/integrated）：取前 3 个换乘方案，折线 = 步行段 + 公交段拼接 */
    private fun v3Transit(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): List<RoutePlan> {
        val city = regeocodeCity(fromLon, fromLat, isGcj02 = true) ?: throw ApiException("公交路线需要先定位所在城市")
        val url = "https://restapi.amap.com/v3/direction/transit/integrated" +
            "?origin=$fromLon,$fromLat&destination=$toLon,$toLat&city=${urlEncode(city)}&strategy=0&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            throw ApiException(mapAmapError(
                root["info"]?.jsonPrimitive?.content,
                root["infocode"]?.jsonPrimitive?.content,
            ))
        }
        val transits = root["route"]?.jsonObject?.get("transits")?.jsonArray ?: return emptyList()
        return transits.take(3).mapIndexedNotNull { idx, t ->
            val o = t.jsonObject
            val dist = o["distance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val dur = ((o["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 60).toInt()
            val pts = mutableListOf<Pair<Double, Double>>()
            val steps = mutableListOf<String>()
            val starts = mutableListOf<Int>()
            o["segments"]?.jsonArray?.forEach { segEl ->
                val seg = segEl.jsonObject
                val walking = seg["walking"]?.jsonObject
                val bus = seg["bus"]?.jsonObject
                walking?.get("steps")?.jsonArray?.forEach { s ->
                    val polyline = s.jsonObject["polyline"]?.jsonPrimitive?.content ?: return@forEach
                    starts.add(pts.size)
                    steps.add(stripHtml(s.jsonObject["instruction"]?.jsonPrimitive?.content ?: "步行"))
                    polyline.split(";").forEach { p ->
                        val c = p.split(",")
                        val lon = c.getOrNull(0)?.toDoubleOrNull()
                        val lat = c.getOrNull(1)?.toDoubleOrNull()
                        if (lon != null && lat != null) pts.add(lon to lat)
                    }
                }
                bus?.get("buslines")?.jsonArray?.firstOrNull()?.let { lineEl ->
                    val line = lineEl.jsonObject
                    starts.add(pts.size)
                    steps.add("乘坐 ${stripHtml(line["name"]?.jsonPrimitive?.content ?: "公交")}")
                    line["polyline"]?.jsonPrimitive?.content?.split(";")?.forEach { p ->
                        val c = p.split(",")
                        val lon = c.getOrNull(0)?.toDoubleOrNull()
                        val lat = c.getOrNull(1)?.toDoubleOrNull()
                        if (lon != null && lat != null) pts.add(lon to lat)
                    }
                }
            }
            if (pts.isEmpty()) return@mapIndexedNotNull null
            RoutePlan(
                mode = RouteMode.BUS, distanceM = dist, durationMin = dur,
                points = pts, steps = steps, stepStarts = starts,
                tag = if (transits.size > 1) "方案${"一二三".getOrElse(idx) { '一' }}" else "",
            )
        }
    }

    /** 逆地理编码：坐标 → 城市（公交路线必填 city 参数，支持直接传 GCJ-02 或 WGS-84） */
    fun regeocodeCity(lon: Double, lat: Double, isGcj02: Boolean = false): String? = runCatching {
        val (gcjLat, gcjLon) = if (isGcj02) lat to lon else Gcj02.fromWgs(lat, lon)
        val url = "https://restapi.amap.com/v3/geocode/regeo?location=${gcjLon},${gcjLat}&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") return@runCatching null
        // 高德单点逆地理编码返回 regeocode 对象（batch=true 才有 regeocodes 数组）
        val regeo = root["regeocode"]?.jsonObject
            ?: root["regeocodes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@runCatching null
        val ac = regeo["addressComponent"]?.jsonObject
        val cityVal = runCatching { ac?.get("city")?.jsonPrimitive?.content }.getOrNull()
        val provVal = runCatching { ac?.get("province")?.jsonPrimitive?.content }.getOrNull()
        val adcodeVal = runCatching { ac?.get("adcode")?.jsonPrimitive?.content }.getOrNull()
        val city = cityVal?.takeIf { it.isNotBlank() && it != "[]" }
        val province = provVal?.takeIf { it.isNotBlank() && it != "[]" }
        val adcode = adcodeVal?.takeIf { it.isNotBlank() && it != "[]" }
        city ?: province ?: adcode
    }.getOrNull()

    companion object {
        /** 「回家」类目的地词：命中且设置里有家庭地址时，直接用地址解析，不再附近 POI 检索 */
        val HOME_WORDS = setOf("家", "我家", "老家", "家里", "家地址", "家庭地址", "住所", "住处", "屋里")

        /**
         * 判断目的地文本是不是「回家」类：剥掉引导词后看是否等于 HOME_WORDS 里的词。
         * 枚举法不误伤（"家装市场""家具城"这类含"家"的真地名不命中）。
         */
        fun isHomeDestination(destination: String): Boolean {
            val d = destination.trim()
                .replace(Regex("^(回|去|到|返回|回我|回个|导航回|导航去|带我去|带我回|怎么回|怎么去|怎么到)"), "")
                .replace(Regex("(那边|那里|那儿|这边)$"), "")
                .trim()
            return d in HOME_WORDS
        }
        /**
         * 目的地解析：POI 检索优先（坐标贴近实际入口/门址），名称对得上才采信；
         * 兜底地理编码。解决"高德导航传过去的终点有点不准、要手动纠正"的问题。
         * 返回值带上高德实际命中的名称：模型有时把整句问题（"大会岭有什么特色"）当地点传来，
         * 地图卡标题和跳高德的目的地名必须用干净的地名，不能照抄原句。
         */
    fun resolveDestination(amap: AmapClient, name: String, fromLon: Double, fromLat: Double): ResolvedDest? {
            val clean = cleanPlaceName(name)
            if (clean.isEmpty()) throw ApiException("目的地为空")
            val strip = Regex("[\\s（）]")
            val qn = strip.replace(clean, "")
            val poi = runCatching { amap.poiSearch(clean, fromLon, fromLat) }.getOrNull()
                ?.firstOrNull { p ->
                    val pn = strip.replace(p.name, "")
                    pn.isNotEmpty() && (pn.contains(qn) || qn.contains(pn))
                }
                ?.let { ResolvedDest(it.lon, it.lat, it.name) }
            return poi ?: amap.geocode(clean)?.let { ResolvedDest(it.first, it.second, clean) }
        }

        /**
         * 从口语文本里抠出地名：模型偶尔把整句问题当目的地/关键词传来
         * （如"大会岭有什么特色"），POI 检索和跳高德前先把问题词剥掉。
         * 剥完不足 2 个字就放弃（说明原文可能本来就是地名），返回原文。
         */
        fun cleanPlaceName(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            val noise = Regex(
                "请问|麻烦|帮我|我想知道|我想|我要|现在|一下|查询|查下|看看|搜索|告诉我|介绍一下|介绍下|" +
                    "有什么特色|特色是什么|有什么好玩|有什么好看的|怎么样|好玩吗|值得去吗|值得一去吗|美不美|" +
                    "怎么走|怎么去|怎么到|怎么前往|如何去|如何到达|在哪儿|在哪里|在哪|多远|远不远|" +
                    "导航到|导航去|带我去|路线规划|路线|附近的|附近|门票多少钱|门票",
            )
            repeat(3) { s = noise.replace(s, "") }
            s = s.replace(Regex("[?？!！。,，、:：;；\"'（）()\\s]+"), "")
            s = s.removePrefix("去").removePrefix("到").removePrefix("前往").removePrefix("回")
            return if (s.length >= 2) s else raw.trim()
        }

        /** 解析 GPX 轨迹（BRouter 返回），同时算累计爬升 */
        fun stripHtml(s: String): String =
            s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

        /** 解析 GPX 轨迹（BRouter 返回），同时算累计爬升 */
        fun parseGpx(gpx: String): RouteResult {
            val points = Regex("<trkpt[^>]*lat=\"(-?[\\d.]+)\"[^>]*lon=\"(-?[\\d.]+)\"[^>]*>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
                .findAll(gpx)
                .mapNotNull { m ->
                    val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                    val ele = Regex("<ele>(-?[\\d.]+)</ele>").find(m.groupValues[3])?.groupValues?.get(1)?.toDoubleOrNull()
                    Triple(lat, lon, ele)
                }
                .toList()
            var climb = 0.0
            var lastEle: Double? = null
            points.forEach { (_, _, ele) ->
                val last = lastEle
                if (ele != null && last != null && ele - last > 2.0) climb += ele - last
                if (ele != null) lastEle = ele
            }
            var dist = 0.0
            points.zipWithNext().forEach { (a, b) -> dist += FatigueEngine.haversine(a.first, a.second, b.first, b.second) }
            return RouteResult(
                distanceM = dist.toInt(),
                durationMin = (dist / 70.0 / 60).toInt().coerceAtLeast(1), // 步行 ~70m/min
                points = points.map { it.second to it.first },
                climbM = climb.toInt(),
            )
        }
    }
}
