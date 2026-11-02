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
}
