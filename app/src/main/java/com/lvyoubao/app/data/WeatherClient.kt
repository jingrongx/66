package com.lvyoubao.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    /** 7 日逐日预报（含降水概率），供"啥时候天晴"这类问题直接作答 */
    fun forecast(lon: Double, lat: Double, days: Int = 7): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum" +
            "&forecast_days=$days&timezone=auto"
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw ApiException("天气接口空响应")
            val root = json.parseToJsonElement(text).jsonObject
            val daily = root["daily"]?.jsonObject ?: throw ApiException("天气接口返回异常")
            fun arr(key: String): List<String>? = daily[key]?.jsonArray?.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            val dates = arr("time") ?: return@use ""
            val codes = arr("weather_code")
            val tMax = arr("temperature_2m_max")
            val tMin = arr("temperature_2m_min")
            val probs = arr("precipitation_probability_max")
            val rains = arr("precipitation_sum")
            val dayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            return@use dates.mapIndexed { i, d ->
                val code = codes?.getOrNull(i)?.toDoubleOrNull()?.toInt() ?: -1
                val desc = weatherCodes[code] ?: "未知"
                val lo = tMin?.getOrNull(i)?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "?"
                val hi = tMax?.getOrNull(i)?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "?"
                val prob = probs?.getOrNull(i) ?: "?"
                val rainMm = rains?.getOrNull(i)?.toDoubleOrNull() ?: 0.0
                val weekday = try {
                    dayNames[java.time.LocalDate.parse(d).dayOfWeek.value % 7]
                } catch (_: Exception) { "" }
                val tag = when (i) { 0 -> "今天"; 1 -> "明天"; 2 -> "后天"; else -> weekday }
                val day = d.substringAfterLast("-")
                val rainText = if (rainMm > 0.1) "，降水量 " + String.format(java.util.Locale.CHINA, "%.1f", rainMm) + "mm" else ""
                tag + "(" + day + ")：" + desc + " " + lo + "~" + hi + "°C，降水概率 " + prob + "%" + rainText
            }.joinToString("\n")
        } ?: throw ApiException("天气接口返回异常")
    }

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
