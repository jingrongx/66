package com.lvyoubao.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.lvyoubao.app.data.observation.SensingBus
import java.util.Locale

/**
 * 问答的位置上下文：优先取感知服务的实时定位，否则取系统最近已知位置；
 * 用平台 Geocoder 反解出城市/区域名（脱敏：坐标只给 2 位小数，约 1km 精度）。
 */
object LocationContext {

    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    data class Place(val lat: Double, val lon: Double, val description: String)

    fun current(context: Context): Place? {
        if (!hasPermission(context)) return null
        val loc = latestFix(context) ?: return null
        return Place(
            lat = loc.latitude,
            lon = loc.longitude,
            description = describe(context, loc),
        )
    }

    private fun latestFix(context: Context): Location? {
        // 1) 感知服务的实时定位（记录中时最准）
        val lat = SensingBus.lastLatitude.value
        val lon = SensingBus.lastLongitude.value
        val at = SensingBus.lastLocationAt.value
        if (lat != null && lon != null && at != null && System.currentTimeMillis() - at < 10 * 60_000L) {
            val l = Location("lvyoubao-sensing")
            l.latitude = lat
            l.longitude = lon
            return l
        }
        // 2) 系统最近已知位置
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null }
                .getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun describe(context: Context, loc: Location): String {
        val coords = String.format(Locale.US, "%.2f, %.2f", loc.longitude, loc.latitude)
        val area = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()
                ?.let { a ->
                    listOfNotNull(a.adminArea, a.locality ?: a.subLocality, a.featureName)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(3)
                        .joinToString("")
                }
        }.getOrNull()
        return if (area.isNullOrBlank()) "坐标 $coords 附近" else "$area（坐标 $coords 附近）"
    }
}
