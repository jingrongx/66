package com.lvyoubao.app.data

import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.lvyoubao.app.data.observation.FatigueEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 导航用的实时定位：向系统定位服务订阅位置更新（约 2 秒一次），
 * 与感知服务互不影响，只在导航进行中开启、退出即停。
 * fix 是 StateFlow，地图卡收值后实时挪动位置点并跟图。
 */
object NavigationTracker {

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float = 0f, val ts: Long = 0)

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private var listener: android.location.LocationListener? = null

    fun start(context: Context) {
        if (listener != null) return
        if (!LocationContext.hasPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // 先用最近已知位置顶上，地图不用空等第一次 GPS
        LocationContext.current(context)?.let {
            _fix.value = Fix(it.lat, it.lon, ts = System.currentTimeMillis())
        }
        val l = android.location.LocationListener { loc ->
            _fix.value = Fix(loc.latitude, loc.longitude, loc.accuracy, System.currentTimeMillis())
        }
        listener = l
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 2000L, 1f, l, Looper.getMainLooper())
                }
            }
        }
    }

    fun stop(context: Context) {
        val l = listener ?: return
        listener = null
        runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l)
        }
    }

    // ---------- 路线几何小工具（导航进度 / 偏航判断） ----------

    /** 点到折线（(经度,纬度) 对）的最近距离，米 */
    fun distanceToPolylineM(meLat: Double, meLon: Double, points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().minOfOrNull { (a, b) ->
            segmentDistanceM(meLat, meLon, a.second, a.first, b.second, b.first)
        } ?: 0.0

    /** 折线上离我最近的顶点下标 */
    fun nearestIndex(meLat: Double, meLon: Double, points: List<Pair<Double, Double>>): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = FatigueEngine.haversine(meLat, meLon, p.second, p.first).toDouble()
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 点到线段的最短距离（米）：等距圆柱近似，几百米尺度足够判断偏航 */
    fun segmentDistanceM(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val cosLat = Math.cos(Math.toRadians(pLat))
        val x = (pLon - aLon) * cosLat
        val y = pLat - aLat
        val dx = (bLon - aLon) * cosLat
        val dy = bLat - aLat
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((x * dx + y * dy) / len2).coerceIn(0.0, 1.0)
        val px = x - t * dx
        val py = y - t * dy
        return Math.sqrt(px * px + py * py) * 111_320.0
    }
}
