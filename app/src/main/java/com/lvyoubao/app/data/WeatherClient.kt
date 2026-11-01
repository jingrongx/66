package com.lvyoubao.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Open-Meteo 免费实时天气（无需 Key） */
object WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val weatherCodes = mapOf(
        0 to "晴", 1 to "基本晴", 2 to "多云", 3 to "阴",
        45 to "雾", 48 to "雾凇",
        51 to "毛毛雨", 53 to "毛毛雨", 55 to "毛毛雨",
        61 to "小雨", 63 to "中雨", 65 to "大雨",
        66 to "冻雨", 67 to "冻雨",
        71 to "小雪", 73 to "中雪", 75 to "大雪", 77 to "雪粒",
        80 to "阵雨", 81 to "阵雨", 82 to "强阵雨",
        85 to "阵雪", 86 to "阵雪",
        95 to "雷阵雨", 96 to "雷阵雨伴冰雹", 99 to "雷阵雨伴冰雹",
    )

    fun current(lon: Double, lat: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation&timezone=auto"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw ApiException("天气接口空响应")
            val root = json.parseToJsonElement(text).jsonObject
            val cur = root["current"]?.jsonObject ?: throw ApiException("天气接口返回异常")
            fun num(key: String) = cur[key]?.jsonPrimitive?.content?.toDoubleOrNull()
            val code = num("weather_code")?.toInt() ?: -1
            val desc = weatherCodes[code] ?: "未知"
            val temp = num("temperature_2m")?.let { "%.0f".format(it) } ?: "?"
            val feels = num("apparent_temperature")?.let { "%.0f".format(it) } ?: "?"
            val wind = num("wind_speed_10m")?.let { "%.0f".format(it) } ?: "?"
            val rain = num("precipitation")?.let { "%.1f".format(it) } ?: "0.0"
            return "$desc，气温 ${temp}°C（体感 ${feels}°C），风速 ${wind}km/h，近1小时降水 ${rain}mm"
        }
    }
}
