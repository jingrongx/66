package com.lvyoubao.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 中国天气网（weather.com.cn）免 Key 接口——**国内主链路**。
 *
 * 为什么是它：Open-Meteo 在境外（设备侧 RTT 250ms、丢包 33%），问天气慢的根子就在跨境；
 * 高德虽然也是国内，但 Web 服务 Key 和「Android 平台 Key」不是一回事（拿 Android Key 调
 * 会回 10009 USERKEY_PLAT_NOMATCH），用户手里的 Key 一旦是那种就用不了。
 * 中国天气网这两条接口不用 Key、国内 50~100ms，正好当兜底主力：
 *
 *  - 实况：`d1.weather.com.cn/sk_2d/{cityId}.html`      → 温度/天气/风/湿度
 *  - 今日：`d1.weather.com.cn/weather_index/{cityId}.html` → cityDZ（今天高低温）
 *  - 7 日：`www.weather.com.cn/weather/{cityId}.shtml`   → 7 个 `<li class="sky">` 块
 *
 * 两个坑必须记住：
 *  1. **没有 UA / Referer 一律 403**（实测 openresty 直接拒），所以要带浏览器 UA + weather.com.cn Referer；
 *  2. 接口只认 cityId（101020100 这种），得先用 `toy1.weather.com.cn/search?cityname=` 查一次，
 *     所以这里把「城市名 → cityId」缓存 30 分钟。
 */
object WeatherCn {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; zh-CN) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        // 这个站对压缩很敏感，关掉透明压缩省得踩 gzip 解不开的坑
        .build()

    private class Entry(val text: String, val at: Long)

    private const val CITY_ID_TTL_MS = 30 * 60 * 1000L
    private const val DATA_TTL_MS = 8 * 60 * 1000L

    private val cityIdCache = ConcurrentHashMap<String, Entry>()
    private val nowCache = ConcurrentHashMap<String, Entry>()
    private val fcCache = ConcurrentHashMap<String, Entry>()

    private fun get(url: String, referer: String = "http://www.weather.com.cn/"): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                LogStore.log("weather", "中国天气网 ${r.code}：$url")
                return null
            }
            r.body?.string()
        }
    }.getOrNull()

    /**
     * 从定位描述里猜城市名，用来查 cityId。
     * 描述形如「浙江省杭州市西湖区（坐标 …）」「上海市（坐标 …）」「网络定位（IP）：上海市…」。
     * 先剥掉省级前缀再取「XX市/自治州/地区/盟」，直辖市直接命中。
     */
    fun cityCandidates(hint: String): List<String> {
        if (hint.isBlank()) return emptyList()
        val text = hint.substringBefore("（").substringBefore("(")
        val stripped = text.replace(
            Regex("^[\\u4e00-\\u9fa5]{2,7}(?:省|自治区|特别行政区)"),
            "",
        )
        val out = LinkedHashSet<String>()
        Regex("[\\u4e00-\\u9fa5]{2,8}?(?:市|自治州|地区|盟)")
            .findAll(stripped)
            .mapNotNull { it.value }
            .forEach { out.add(it) }
        // 只给了省（如「浙江省」）：退回用省名查省会，聊胜于无
        if (out.isEmpty()) {
            Regex("^[\\u4e00-\\u9fa5]{2,7}(?:省|自治区)").find(text)?.value?.let { out.add(it) }
        }
        return out.take(3).toList()
    }

    /** 城市名 → cityId（101020100）。查不到返回 null */
    fun cityId(cityHint: String): String? {
        val candidates = cityCandidates(cityHint).ifEmpty {
            // 定位描述是「坐标 121.47, 31.23 附近」（设备没有 Geocoder 时常见）：
            // 直接用国内 IP 反查拿城市名，城市级精度对天气完全够用。结果缓存半小时。
            listOfNotNull(ipCity())
        }
        for (city in candidates) {
            cityIdCache[city]?.let { e ->
                if (System.currentTimeMillis() - e.at < CITY_ID_TTL_MS) return e.text
            }
            val body = get(
                "http://toy1.weather.com.cn/search?cityname=" +
                    java.net.URLEncoder.encode(city, "UTF-8"),
            ) ?: continue
            val id = Regex("\"(\\d{9})~").find(body)?.groupValues?.get(1)
            if (id != null) {
                cityIdCache[city] = Entry(id, System.currentTimeMillis())
                LogStore.log("weather", "中国天气网城市：$city → $id")
                return id
            }
        }
        return null
    }

    /** 实况：晴，气温 12.4°C，今天 7~15°C，西南风 1级，湿度 38%（中国天气网，10:50 更新） */
    fun now(cityHint: String): String? = runCatching {
        val id = cityId(cityHint) ?: return null
        nowCache[id]?.let { e ->
            if (System.currentTimeMillis() - e.at < DATA_TTL_MS) return e.text
        }
        val body = get("http://d1.weather.com.cn/sk_2d/$id.html") ?: return null
        val obj = parseVar(body, "dataSK") ?: return null
        fun s(k: String) = obj[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val weather = s("weather") ?: return null
        val temp = s("temp") ?: "?"
        val windDir = s("WD")
        val windScale = s("WS")
        val humidity = s("SD")?.removeSuffix("%")
        val time = s("time")

        val today = runCatching {
            val idx = get("http://d1.weather.com.cn/weather_index/$id.html") ?: return@runCatching null
            val dz = parseVar(idx, "cityDZ")?.get("weatherinfo")?.jsonObject ?: return@runCatching null
            fun d(k: String) = dz[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val hi = d("temp") ?: return@runCatching null
            val lo = d("tempn") ?: return@runCatching null
            "$lo~$hi°C"
        }.getOrNull()

        val text = buildString {
            append("$weather，气温 ${temp}°C")
            if (today != null) append("（今天 $today）")
            if (windDir != null) append("，$windDir ${windScale ?: ""}")
            if (humidity != null) append("，湿度 $humidity%")
            append("（中国天气网")
            if (time != null) append("，$time 更新")
            append("）")
        }
        nowCache[id] = Entry(text, System.currentTimeMillis())
        text
    }.getOrNull()

    /** 7 日预报：每行「今天(21)：晴 7~15°C，西北风转东南风<3级」 */
    fun forecast(cityHint: String): String? = runCatching {
        val id = cityId(cityHint) ?: return null
        fcCache[id]?.let { e ->
            if (System.currentTimeMillis() - e.at < DATA_TTL_MS) return e.text
        }
        val html = get(
            "http://www.weather.com.cn/weather/$id.shtml",
            referer = "http://www.weather.com.cn/weather/$id.shtml",
        ) ?: return null
        val blocks = html.split("<li class=\"sky")
        if (blocks.size < 2) return null
        val days = blocks.drop(1).take(7).mapNotNull { block ->
            val head = Regex("<h1>([^<]+)</h1>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val wea = Regex("class=\"wea\">([^<]*)<").find(block)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            val tem = Regex("<span>(-?\\d+)</span>/\\s*<i>(-?\\d+)℃</i>").find(block)
            val hi = tem?.groupValues?.get(1)
            val lo = tem?.groupValues?.get(2)
            val winds = Regex("title=\"([\\u4e00-\\u9fa5]{1,4}风)\"").findAll(block)
                .map { it.groupValues[1] }.toList()
            val power = Regex("<i>(<?\\d+级)</i>").find(block)?.groupValues?.get(1)
            // 「21日（今天）」→「今天(21)」
            val label = head
                .replace("日（", "日(")
                .replace("）", ")")
                .replace("(", "(")
            buildString {
                append(label.ifBlank { head })
                append("：$wea")
                if (hi != null && lo != null) append(" $lo~$hi°C")
                if (winds.isNotEmpty()) {
                    val w = if (winds.size >= 2 && winds[0] != winds[1]) "${winds[0]}转${winds[1]}" else winds[0]
                    append("，$w${power ?: ""}")
                }
            }
        }
        if (days.isEmpty()) return null
        val text = days.joinToString("\n")
        fcCache[id] = Entry(text, System.currentTimeMillis())
        text
    }.getOrNull()

    /**
     * 国内 IP 反查城市（pconline，免 Key）。
     * 只在「定位描述里没有城市名」时用——设备没有 Geocoder、只有坐标的那种情况。
     * 返回体是 GBK，必须自己按 GBK 解，直接 body.string() 会得到一串乱码。
     */
    private fun ipCity(): String? {
        ipCityCache?.let { e ->
            if (System.currentTimeMillis() - e.at < CITY_ID_TTL_MS) return e.text
        }
        return runCatching {
            val req = Request.Builder()
                .url("https://whois.pconline.com.cn/ipJson.jsp?json=true")
                .header("User-Agent", UA)
                .get()
                .build()
            val bytes = client.newCall(req).execute().use { it.body?.bytes() } ?: return null
            val text = String(bytes, charset("GBK"))
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            val city = obj["city"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val pro = obj["pro"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val name = city ?: pro
            if (name != null) ipCityCache = Entry(name, System.currentTimeMillis())
            name
        }.getOrNull()
    }

    @Volatile
    private var ipCityCache: Entry? = null

    /** 中国天气网的返回是 `var dataSK = {...};` 这种 JS，抠出后面那个 JSON 对象 */    private fun parseVar(body: String, name: String): kotlinx.serialization.json.JsonObject? = runCatching {
        val start = body.indexOf("var $name")
        if (start < 0) return@runCatching null
        val brace = body.indexOf('{', start)
        if (brace < 0) return@runCatching null
        // 花括号配平，避免把后面的 ;var xxx 也吃进来
        var depth = 0
        for (i in brace until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return@runCatching json.parseToJsonElement(body.substring(brace, i + 1)).jsonObject
                    }
                }
            }
        }
        null
    }.getOrNull()
}
