package com.lvyoubao.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 天气数据源：**优先国内（高德），Open-Meteo 只做兜底**。
 *
 * 为什么换：Open-Meteo 在境外，设备侧实测 RTT 200~300ms、丢包 33%，PC 直连 20 秒都连不上；
 * 「问天气特别慢」根本不是模型慢，是这条跨境链路慢。缓存和超时只是把伤口包起来，
 * 真正的解法是用国内接口——高德开放平台本来就要填 Key（路线/POI 在用），
 * 顺手就能查天气，同一个 Key、同一条国内链路，实测 100ms 级。
 *
 * 链路：坐标 →（高德逆地理）adcode →（高德天气）实况 / 4 日预报。
 * 没有高德 Key、或者高德报错时，自动退回 Open-Meteo，不会因为缺 Key 就没有天气。
 *
 * 另外两层兜底仍然保留：
 *  - 结果缓存 10 分钟（同一坐标连着问「今天/明天」第二次 0ms）；
 *  - [prewarm] 在唤醒那一刻后台先查一次，用户还在说话，缓存已经就位。
 */
object WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** 国内链路（高德）：50~150ms 的接口，2 秒足够 */
    private val cnClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    /** 境外兜底（Open-Meteo）：超时收紧，宁可没数据也别拖住整轮问答 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    /** 缓存有效期：10 分钟。天气这种数据没必要每问一次都重新查 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 坐标 → adcode 的缓存：行政区划不会变，半小时一次足够 */
    private const val ADCODE_TTL_MS = 30 * 60 * 1000L

    private class Entry(val text: String, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val adcodeCache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    /** 正在后台预热的坐标，避免同一坐标并发重复请求 */
    private val warming = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * 高德 Key 不可用（平台类型不对 / Key 无效）时的封禁截止时间。
     * 用户填的常是「Android 平台」Key，调 Web 服务会稳定回 10009；不记住的话
     * 每问一次天气都先白等一次失败请求。10 分钟内不再尝试。
     */
    @Volatile
    private var amapBlockedUntil = 0L

    private fun amapUsable(amapKey: String): Boolean =
        amapKey.isNotBlank() && System.currentTimeMillis() > amapBlockedUntil

    private fun noteAmapFailure(info: String?) {
        val text = info.orEmpty()
        if (text.contains("USERKEY_PLAT_NOMATCH", true) || text.contains("INVALID_USER_KEY", true) ||
            text.contains("10009") || text.contains("10001")
        ) {
            amapBlockedUntil = System.currentTimeMillis() + 10 * 60 * 1000L
            LogStore.log(
                "weather",
                "高德 Key 不是「Web服务」类型（$text），10 分钟内改用国内免 Key 源；" +
                    "去 lbs.amap.com 加一个「Web服务」Key 填到设置里即可恢复",
            )
        }
    }

    private fun key(lon: Double, lat: Double): String =
        String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon)

    // ---------------- 国内主链路：高德 ----------------

    /** 坐标 → 高德 adcode（行政区编码，天气接口要的就是它）；有缓存先吃缓存 */
    private fun amapAdcode(lon: Double, lat: Double, amapKey: String): String? {
        val k = key(lon, lat)
        adcodeCache[k]?.let { e ->
            if (System.currentTimeMillis() - e.at < ADCODE_TTL_MS) return e.text
        }
        return runCatching {
            val (gcjLat, gcjLon) = Gcj02.fromWgs(lat, lon)
            val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?location=$gcjLon,$gcjLat&key=$amapKey"
            val root = json.parseToJsonElement(
                cnClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    r.body?.string() ?: ""
                },
            ).jsonObject
            if (root["status"]?.jsonPrimitive?.content != "1") {
                LogStore.log("weather", "高德逆地理失败：${root["info"]?.jsonPrimitive?.content}")
                noteAmapFailure(root["info"]?.jsonPrimitive?.content)
                return null
            }
            val ac = root["regeocode"]?.jsonObject?.get("addressComponent")?.jsonObject
            val adcode = ac?.get("adcode")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() && it != "[]" }
            if (adcode != null) adcodeCache[k] = Entry(adcode, System.currentTimeMillis())
            adcode
        }.getOrNull()
    }

    /** 高德实况：晴，气温 12°C，东风 ≤3 级，湿度 45% */
    private fun amapNow(lon: Double, lat: Double, amapKey: String): String? = runCatching {
        val adcode = amapAdcode(lon, lat, amapKey) ?: return null
        val url = "https://restapi.amap.com/v3/weather/weatherInfo" +
            "?key=$amapKey&city=$adcode&extensions=base"
        val root = json.parseToJsonElement(
            cnClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                r.body?.string() ?: ""
            },
        ).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            LogStore.log("weather", "高德天气失败：${root["info"]?.jsonPrimitive?.content}")
            return null
        }
        val live = root["lives"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        fun s(k: String) = live[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "[]" }
        val weather = s("weather") ?: return null
        val temp = s("temperature") ?: "?"
        val windDir = s("winddirection")
        val windPower = s("windpower")
        val humidity = s("humidity")
        val report = s("reporttime")?.substringAfter(' ') ?: ""
        buildString {
            append("$weather，气温 ${temp}°C")
            if (windDir != null) append("，${windDir}风 ${windPower ?: ""}级")
            if (humidity != null) append("，湿度 ${humidity}%")
            append("（高德实况")
            if (report.isNotBlank()) append("，$report 更新")
            append("）")
        }
    }.getOrNull()

    /** 高德预报：今天(21)：晴转多云 5~15°C，东风≤3级（高德给 4 天，没有降水概率） */
    private fun amapForecast(lon: Double, lat: Double, amapKey: String): String? = runCatching {
        val adcode = amapAdcode(lon, lat, amapKey) ?: return null
        val url = "https://restapi.amap.com/v3/weather/weatherInfo" +
            "?key=$amapKey&city=$adcode&extensions=all"
        val root = json.parseToJsonElement(
            cnClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                r.body?.string() ?: ""
            },
        ).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "1") {
            LogStore.log("weather", "高德预报失败：${root["info"]?.jsonPrimitive?.content}")
            return null
        }
        val casts = root["forecasts"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("casts")?.jsonArray ?: return null
        if (casts.isEmpty()) return null
        val dayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        casts.mapIndexedNotNull { i, el ->
            val c = el.jsonObject
            fun s(k: String) = c[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "[]" }
            val date = s("date") ?: return@mapIndexedNotNull null
            val dayW = s("dayweather") ?: return@mapIndexedNotNull null
            val nightW = s("nightweather")
            val hi = s("daytemp")
            val lo = s("nighttemp")
            val wind = s("daywind")
            val power = s("daypower")
            val weekday = runCatching {
                dayNames[java.time.LocalDate.parse(date).dayOfWeek.value % 7]
            }.getOrDefault("")
            val tag = when (i) { 0 -> "今天"; 1 -> "明天"; 2 -> "后天"; else -> weekday }
            val short = date.substringAfterLast("-")
            val desc = if (nightW != null && nightW != dayW) "${dayW}转$nightW" else dayW
            buildString {
                append("$tag($short)：$desc")
                if (hi != null && lo != null) append(" $lo~$hi°C")
                if (wind != null) append("，${wind}风${power ?: ""}级")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.getOrNull()

    // ---------------- 对外接口 ----------------

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
    fun prewarm(lon: Double, lat: Double, amapKey: String = "", cityHint: String = "") {
        val k = key(lon, lat)
        if (isFresh(k)) return
        if (!warming.add(k)) return
        kotlin.concurrent.thread(isDaemon = true, name = "weather-prewarm") {
            try {
                runCatching { current(lon, lat, amapKey, cityHint) }
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

    /**
     * 逐日预报，供"啥时候天晴"这类问题直接作答。
     * 高德优先（国内、100ms 级、4 天）；没 Key 或失败才走 Open-Meteo（7 天 + 降水概率）。
     */
    fun forecast(
        lon: Double,
        lat: Double,
        days: Int = 7,
        amapKey: String = "",
        cityHint: String = "",
    ): String {
        val cacheKey = key(lon, lat) + ":fc$days"
        cache[cacheKey]?.let { e ->
            if (System.currentTimeMillis() - e.at < CACHE_TTL_MS) return e.text
        }
        // 国内优先①：高德（4 天）
        if (amapUsable(amapKey)) {
            val cn = amapForecast(lon, lat, amapKey)
            if (!cn.isNullOrBlank()) {
                LogStore.log("weather", "天气源：高德预报（${cn.lineSequence().count()} 天）")
                cache[cacheKey] = Entry(cn, System.currentTimeMillis())
                return cn
            }
        }
        // 国内优先②：中国天气网 7 日（免 Key）
        WeatherCn.forecast(cityHint)?.let {
            LogStore.log("weather", "天气源：中国天气网预报（${it.lineSequence().count()} 天）")
            cache[cacheKey] = Entry(it, System.currentTimeMillis())
            return it
        }
        // 兜底：Open-Meteo
        val text = forecastFromNetwork(lon, lat, days)
        if (text.isNotBlank()) {
            LogStore.log("weather", "天气源：Open-Meteo 预报（国内源都不可用）")
            cache[cacheKey] = Entry(text, System.currentTimeMillis())
        }
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
    fun currentQuick(
        lon: Double,
        lat: Double,
        timeoutMs: Long = 1_500,
        amapKey: String = "",
        cityHint: String = "",
    ): String? {
        cached(lon, lat)?.let { return it }
        // 国内优先①：高德（Key 是「Web服务」类型时 100ms 级，数据最全）
        if (amapUsable(amapKey)) {
            amapNow(lon, lat, amapKey)?.let {
                LogStore.log("weather", "天气源：高德实况")
                cache[key(lon, lat)] = Entry(it, System.currentTimeMillis())
                return it
            }
        }
        // 国内优先②：中国天气网（免 Key，50~100ms）
        WeatherCn.now(cityHint)?.let {
            LogStore.log("weather", "天气源：中国天气网")
            cache[key(lon, lat)] = Entry(it, System.currentTimeMillis())
            return it
        }
        // 兜底：境外 Open-Meteo（慢，且受丢包影响）
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
                LogStore.log("weather", "天气源：Open-Meteo 实况（高德不可用）")
                cache[key(lon, lat)] = Entry(result, System.currentTimeMillis())
                result
            }
        }.getOrNull()
    }

    fun current(lon: Double, lat: Double, amapKey: String = "", cityHint: String = ""): String {
        if (amapUsable(amapKey)) {
            amapNow(lon, lat, amapKey)?.let {
                LogStore.log("weather", "天气源：高德实况")
                cache[key(lon, lat)] = Entry(it, System.currentTimeMillis())
                return it
            }
        }
        WeatherCn.now(cityHint)?.let {
            LogStore.log("weather", "天气源：中国天气网")
            cache[key(lon, lat)] = Entry(it, System.currentTimeMillis())
            return it
        }
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
            LogStore.log("weather", "天气源：Open-Meteo 实况（高德不可用）")
            cache[key(lon, lat)] = Entry(result, System.currentTimeMillis())
            return result
        }
    }
}
