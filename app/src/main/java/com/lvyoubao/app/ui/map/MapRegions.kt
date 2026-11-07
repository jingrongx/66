package com.lvyoubao.app.ui.map

/**
 * mapsforge 官方 China 数据源的省区 slug（拼音/英文）→ 中文地名。
 * 只用于展示；下载仍用原始 slug 拼 URL。
 */
object MapRegions {

    private val ZH = mapOf(
        "anhui" to "安徽",
        "beijing" to "北京",
        "chongqing" to "重庆",
        "fujian" to "福建",
        "gansu" to "甘肃",
        "guangdong" to "广东",
        "guangxi" to "广西",
        "guizhou" to "贵州",
        "hainan" to "海南",
        "hebei" to "河北",
        "heilongjiang" to "黑龙江",
        "henan" to "河南",
        "hong-kong" to "香港",
        "hubei" to "湖北",
        "hunan" to "湖南",
        "inner-mongolia" to "内蒙古",
        "jiangsu" to "江苏",
        "jiangxi" to "江西",
        "jilin" to "吉林",
        "liaoning" to "辽宁",
        "macau" to "澳门",
        "ningxia" to "宁夏",
        "qinghai" to "青海",
        "shaanxi" to "陕西",
        "shandong" to "山东",
        "shanghai" to "上海",
        "shanxi" to "山西",
        "sichuan" to "四川",
        "tianjin" to "天津",
        "tibet" to "西藏",
        "xinjiang" to "新疆",
        "yunnan" to "云南",
        "zhejiang" to "浙江",
    )

    /** "guangdong" / "guangdong.map" → "广东"；自定义链接/未知名原样展示（去 .map 后缀） */
    fun displayName(slugOrFileName: String): String {
        val key = slugOrFileName.lowercase().removeSuffix(".map")
        return ZH[key] ?: key
    }

    /** 中文省名 → slug（拼下载 URL 用）；未知返回 null */
    fun slugOf(provinceZh: String): String? =
        ZH.entries.firstOrNull { it.value == provinceZh }?.key

    /** 省界粗框：西经度 / 东经度 / 南纬度 / 北纬度，给「这个点位在哪个省」做近似判断 */
    private data class Box(val w: Double, val e: Double, val s: Double, val n: Double)

    /**
     * 精度到 0.5° 级别就够——只用来提示"哪个省的地图没下载"；边界点可能同时命中邻省框，
     * 调用方把已下载的省份排除掉再提示。
     */
    private val BBOX = mapOf(
        "anhui" to Box(114.9, 119.6, 29.4, 34.6),
        "beijing" to Box(115.4, 117.5, 39.4, 41.1),
        "chongqing" to Box(105.3, 110.2, 28.1, 32.2),
        "fujian" to Box(115.8, 120.7, 23.5, 28.3),
        "gansu" to Box(92.2, 108.7, 32.6, 42.8),
        "guangdong" to Box(109.7, 117.5, 20.2, 25.5),
        "guangxi" to Box(104.5, 112.1, 20.9, 26.4),
        "guizhou" to Box(103.6, 109.5, 24.6, 29.2),
        "hainan" to Box(108.6, 111.1, 18.1, 20.2),
        "hebei" to Box(113.4, 119.9, 36.0, 42.6),
        "heilongjiang" to Box(121.2, 135.1, 43.4, 53.6),
        "henan" to Box(110.3, 116.7, 31.4, 36.4),
        "hong-kong" to Box(113.8, 114.5, 22.1, 22.6),
        "hubei" to Box(108.3, 116.2, 29.0, 33.3),
        "hunan" to Box(108.8, 114.3, 24.6, 30.1),
        "inner-mongolia" to Box(97.2, 126.1, 37.4, 53.3),
        "jiangsu" to Box(116.3, 121.9, 30.8, 35.1),
        "jiangxi" to Box(113.4, 118.5, 24.5, 30.1),
        "jilin" to Box(121.6, 131.3, 40.9, 46.3),
        "liaoning" to Box(118.8, 125.8, 38.7, 43.5),
        "macau" to Box(113.5, 113.6, 22.1, 22.2),
        "ningxia" to Box(104.3, 107.7, 35.2, 39.6),
        "qinghai" to Box(89.4, 103.1, 31.6, 39.2),
        "shaanxi" to Box(105.5, 111.3, 31.7, 39.6),
        "shandong" to Box(114.8, 122.7, 34.4, 38.4),
        "shanghai" to Box(120.8, 122.2, 30.7, 31.9),
        "shanxi" to Box(110.2, 114.6, 34.6, 40.7),
        "sichuan" to Box(97.3, 108.6, 26.0, 34.3),
        "tianjin" to Box(116.7, 118.0, 38.5, 40.3),
        "tibet" to Box(78.4, 99.1, 26.8, 36.5),
        "xinjiang" to Box(73.4, 96.4, 34.3, 49.2),
        "yunnan" to Box(97.5, 106.2, 21.1, 29.2),
        "zhejiang" to Box(118.0, 122.3, 27.0, 31.2),
    )

    /** 点位可能落在的省（中文，按省框中心离点的距离近→远）；框外返回空 */
    fun guessProvinces(lat: Double, lon: Double): List<String> = BBOX.entries
        .mapNotNull { (slug, b) ->
            if (lon in b.w..b.e && lat in b.s..b.n) {
                // 框中心距离做排序键：跨省框重叠时先提示更贴近的那省
                slug to Math.hypot(lon - (b.w + b.e) / 2, lat - (b.s + b.n) / 2)
            } else null
        }
        .sortedBy { it.second }
        .map { ZH[it.first] ?: it.first }
}
