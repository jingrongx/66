package com.lvyoubao.app.data

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

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            return response.body?.string() ?: throw ApiException("高德接口空响应")
        }
    }

    /** 关键词 POI 检索（按当前位置距离排序），失败抛 ApiException */
    fun poiSearch(keyword: String, lon: Double, lat: Double): List<Poi> {
        val url = "https://restapi.amap.com/v3/place/text" +
            "?keywords=${urlEncode(keyword)}&location=$lon,$lat&offset=6&page=1&extensions=base&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        val status = root["status"]?.jsonPrimitive?.content
        if (status != "1") throw ApiException("高德 POI 查询失败：${root["info"]?.jsonPrimitive?.content ?: "未知错误"}")
        return root["pois"]?.jsonArray?.mapNotNull { p ->
            val o = p.jsonObject
            val loc = o["location"]?.jsonPrimitive?.content?.split(",") ?: return@mapNotNull null
            val plon = loc.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val plat = loc.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Poi(
                name = o["name"]?.jsonPrimitive?.content ?: "",
                address = (o["address"]?.jsonPrimitive?.content ?: "").take(40),
                lon = plon,
                lat = plat,
                distanceM = FatigueEngine.haversine(lat, lon, plat, plon).toInt(),
            )
        }.orEmpty()
    }

    /** 步行路径规划：返回距离/耗时/途经点坐标 */
    fun walkingRoute(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): RouteResult {
        val url = "https://restapi.amap.com/v3/direction/walking" +
            "?origin=$fromLon,$fromLat&destination=$toLon,$toLat&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            throw ApiException("高德路径规划失败：${root["info"]?.jsonPrimitive?.content ?: "未知错误"}")
        }
        val path = root["route"]!!.jsonObject["paths"]!!.jsonArray.first().jsonObject
        val points = mutableListOf<Pair<Double, Double>>()
        path["steps"]!!.jsonArray.forEach { s ->
            val polyline = s.jsonObject["polyline"]?.jsonPrimitive?.content ?: return@forEach
            polyline.split(";").forEach { seg ->
                val c = seg.split(",")
                val lon = c.getOrNull(0)?.toDoubleOrNull()
                val lat = c.getOrNull(1)?.toDoubleOrNull()
                if (lon != null && lat != null) points.add(lon to lat)
            }
        }
        return RouteResult(
            distanceM = path["distance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            durationMin = ((path["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 60).toInt(),
            points = points,
        )
    }

    /** 地理编码：地址 → 坐标 */
    fun geocode(address: String, city: String? = null): Pair<Double, Double>? {
        val url = "https://restapi.amap.com/v3/geocode/geo" +
            "?address=${urlEncode(address)}${city?.let { "&city=${urlEncode(it)}" } ?: ""}&key=$key"
        val root = json.parseToJsonElement(get(url)).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") return null
        val loc = root["geocodes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("location")?.jsonPrimitive?.content
            ?.split(",") ?: return null
        val lon = loc.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lat = loc.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lon to lat
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        /** 解析 GPX 轨迹（BRouter 返回），同时算累计爬升 */
        fun parseGpx(gpx: String): RouteResult {
            val points = Regex("<trkpt[^>]*lat=\"([-\d.]+)\"[^>]*lon=\"([-\d.]+)\"[^>]*>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
                .findAll(gpx)
                .mapNotNull { m ->
                    val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                    val ele = Regex("<ele>([-\d.]+)</ele>").find(m.groupValues[3])?.groupValues?.get(1)?.toDoubleOrNull()
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
