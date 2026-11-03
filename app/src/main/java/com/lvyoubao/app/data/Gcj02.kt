package com.lvyoubao.app.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02（火星坐标系，高德接口）与 WGS-84（GPS / OSM 离线底图）互转。
 * 算法与开源社区 eviltransform / coordtransform 一致。
 *
 * 为什么必须有这一步：旅游宝的离线底图是 OSM 数据（WGS-84），而高德
 * 路线折线 / POI / 地理编码返回的都是 GCJ-02。不转换直接画到离线地图上，
 * 整条路线会系统性偏移 300~600 米——表现为「规划的路线不贴着道路走」，
 * 导航的偏航判定也会永远误报。约定：AmapClient 对外一律说 WGS-84，
 * 内部调高德接口前转 GCJ-02，返回结果转回 WGS-84。
 */
object Gcj02 {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 中国大陆坐标范围外（含海外）没有 GCJ 偏移，原样返回 */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02（跳转高德 App 等需要火星坐标的场景） */
    fun fromWgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (dLat, dLon) = delta(lat, lon)
        return (lat + dLat) to (lon + dLon)
    }

    /** GCJ-02 → WGS-84（画到 OSM 离线底图前必须转换） */
    fun toWgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (dLat, dLon) = delta(lat, lon)
        return (lat - dLat) to (lon - dLon)
    }

    /** 在给定点位处的 GCJ 偏移量（单步近似，误差 < 2 米，画线与导航足够） */
    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0)) * 2.0 / 3.0
        return ret
    }
}
