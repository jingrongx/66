package com.lvyoubao.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo 免费实时天气（无需 Key）。
 *
 * 这个接口在境外：实测设备侧 RTT 200~300ms、丢包 33%，握手常常要一两秒，
 * 撞上丢包会直接卡满超时——「问天气特别慢」就是它。所以这里做了三件事：
 *  1. **超时收紧**：connect 2.5s / read 3s / 整个调用 3.5s。宁可这次没数据让模型按常识答，
 *     也不能把整轮问答拖到十几秒。
 *  2. **结果缓存**（同一坐标 10 分钟）：连着问「今天/明天天气」时第二次直接命中，0ms。
 *  3. **[prewarm]**：唤醒那一刻后台先查一次，用户还在说话，等答案要天气数据时早就在缓存里了。
 */
object WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    /** 缓存有效期：10 分钟。天气这种数据没必要每问一次都跨境查一遍 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private class Entry(val text: String, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    /** 正在后台预热的坐标，避免同一坐标并发重复请求 */
    private val warming = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    private fun key(lon: Double, lat: Double): String =
        String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon)

    /** 只读缓存（过期也给，标陈旧）：同步、0ms，问天气时先用它顶上 */
    fun cached(key: String): String? = cache[key]?.text

    fun cached(lon: Double, lat: Double): String? = cached(key(lon, lat))

    fun isFresh(key: String): Boolean {
        val e = cache[key] ?: return false
        return System.currentTimeMillis() - e.at < CACHE_TTL_MS
    }

    /**
     * 后台预热（唤醒时调用）：缓存还新鲜就什么都不做，否则异步查一次写进缓存。
     * 绝不阻塞、绝不抛异常——预热失败不影响后面的正式查询。
     */
    fun prewarm(lon: Double, lat: Double) {
        val k = key(lon, lat)
        if (isFresh(k)) return
        if (!warming.add(k)) return
        kotlin.concurrent.thread(isDaemon = true, name = "weather-prewarm") {
            try {
                runCatching { current(lon, lat) }
            } finally {
                warming.remove(k)
            }
        }
    }

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
        val cacheKey = key(lon, lat) + ":fc$days"
        cache[cacheKey]?.let { e ->
            if (System.currentTimeMillis() - e.at < CACHE_TTL_MS) return e.text
        }
        val text = forecastFromNetwork(lon, lat, days)
        if (text.isNotBlank()) cache[cacheKey] = Entry(text, System.currentTimeMillis())
        return text
    }

    private fun forecastFromNetwork(lon: Double, lat: Double, days: Int): String {
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

    /**
     * 带硬超时的实时天气：**绝不抛异常、到点就放弃**（返回 null）。
     * 问答链路上用它——超时交给 OkHttp 的 callTimeout 真正生效，
     * 而不是靠协程 withTimeout（协程取消打不断阻塞中的 socket，等于没设）。
     */
    fun currentQuick(lon: Double, lat: Double, timeoutMs: Long = 1_500): String? {
        cached(lon, lat)?.let { return it }
        val quick = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation&timezone=auto"
            quick.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val text = response.body?.string() ?: return@use null
                val root = json.parseToJsonElement(text).jsonObject
                val cur = root["current"]?.jsonObject ?: return@use null
                fun num(k: String) = cur[k]?.jsonPrimitive?.content?.toDoubleOrNull()
                val code = num("weather_code")?.toInt() ?: -1
                val desc = weatherCodes[code] ?: "未知"
                val temp = num("temperature_2m")?.let { "%.0f".format(it) } ?: "?"
                val feels = num("apparent_temperature")?.let { "%.0f".format(it) } ?: "?"
                val wind = num("wind_speed_10m")?.let { "%.0f".format(it) } ?: "?"
                val rain = num("precipitation")?.let { "%.1f".format(it) } ?: "0.0"
                val result = "$desc，气温 ${temp}°C（体感 ${feels}°C），风速 ${wind}km/h，近1小时降水 ${rain}mm"
                cache[key(lon, lat)] = Entry(result, System.currentTimeMillis())
                result
            }
        }.getOrNull()
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
            val result = "$desc，气温 ${temp}°C（体感 ${feels}°C），风速 ${wind}km/h，近1小时降水 ${rain}mm"
            // 成功才写缓存：失败的网络结果不该被当成「新鲜天气」缓存十分钟
            cache[key(lon, lat)] = Entry(result, System.currentTimeMillis())
            return result
        }
    }
}
