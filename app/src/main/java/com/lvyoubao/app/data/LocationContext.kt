package com.lvyoubao.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lvyoubao.app.data.observation.SensingBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 问答的位置上下文。
 *
 * 定位分两级：
 * - [current]：被动快照，只读缓存不等定位（AndroidView factory、点击回调等不能挂起的场景）。
 *   缓存来源按新旧取最优：感知服务实时定位 > 本进程主动定位缓存 > 系统最近已知位置。
 * - [fresh]：挂起等一个新鲜位置——缓存 2 分钟内直接返回；过期就向网络/GPS 主动要一个
 *   实时定点（精度达标提前返回，否则等到超时取窗口内最准的），要不到再退回缓存。
 *   发消息、天气、地图卡等协程场景一律走它。此前只被动读「系统最近已知位置」，
 *   那是别的 App 上次定位留下的，可能是几小时前甚至上一个城市的——「定位更新不及时」的根子在这。
 * - [prewarm]：进会话页/拿到定位权限时后台先定位一次，用户真提问时缓存已是新的，
 *   发送不用额外等。
 */
object LocationContext {

    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    /** 缓存判定「够新」的门槛：2 分钟内不重新定位（感知服务运行中本身 20~30 秒一个点） */
    private const val FRESH_MS = 2 * 60_000L

    /** 主动定位的最长等待：网络定点通常 1~3 秒；GPS 冷启动慢但更准，值得多等几秒 */
    private const val FIX_TIMEOUT_MS = 8_000L

    /** 精度达标线（米）：定位误差不超过它就提前返回，不为更准的白等 */
    private const val GOOD_ACCURACY_M = 60f

    /** 感知服务定位的采纳上限：停录超过 10 分钟的点不再当实时位置用 */
    private const val SENSING_TTL_MS = 10 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    data class Place(
        val lat: Double,
        val lon: Double,
        val description: String,
        /** 定位精度（误差半径，米）；被动快照拿不到时为 null */
        val accuracyM: Float? = null,
    )

    /** 本进程主动定位留下的最新定点（部分 ROM 上系统 lastKnown 更新滞后，自己记一份更可靠） */
    @Volatile
    private var ownFix: Location? = null

    /** 并发的 fresh 请求共用一次主动定位：预热和发送撞车时不会重复定位 */
    private val refreshMutex = Mutex()

    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(context: Context): Place? {
        if (!hasPermission(context)) return null
        val loc = latestFix(context) ?: return null
        return toPlace(context, loc)
    }

    suspend fun fresh(
        context: Context,
        maxAgeMs: Long = FRESH_MS,
        timeoutMs: Long = FIX_TIMEOUT_MS,
    ): Place? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        refreshMutex.withLock {
            val cached = latestFix(context)
            if (cached != null && System.currentTimeMillis() - cached.time <= maxAgeMs) {
                return@withLock toPlace(context, cached)
            }
            val got = requestFreshFix(context, timeoutMs)
            val best = got ?: cached ?: return@withLock null
            toPlace(context, best)
        }
    }

    /** 阻塞版 [fresh]：仅在「IO 线程、允许阻塞」的工具执行路径用（AppTools.execute） */
    fun freshBlocking(context: Context): Place? =
        kotlinx.coroutines.runBlocking { fresh(context) }

    /** 后台预热定位：结果落进 ownFix 缓存，失败静默 */
    fun prewarm(context: Context) {
        if (!hasPermission(context)) return
        prewarmScope.launch { runCatching { fresh(context.applicationContext) } }
    }

    private fun toPlace(context: Context, loc: Location): Place {
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        return Place(
            lat = loc.latitude,
            lon = loc.longitude,
            description = describe(context, loc) + coarseNote(accuracy),
            accuracyM = accuracy,
        )
    }

    /** 粗精度附注：误差大时写进描述，AI 知道位置只是大概，不会拿去精确指路 */
    private fun coarseNote(accuracyM: Float?): String = when {
        accuracyM == null -> ""
        accuracyM > 1000f -> "（定位精度约 ${(accuracyM / 1000).toInt() + 1} 公里，仅供参考）"
        accuracyM > 200f -> "（定位精度约 ${accuracyM.toInt() / 100 * 100 + 100} 米，仅供参考）"
        else -> ""
    }

    private fun latestFix(context: Context): Location? {
        val candidates = mutableListOf<Location>()
        // 1) 感知服务的实时定位（记录中时最准）
        val lat = SensingBus.lastLatitude.value
        val lon = SensingBus.lastLongitude.value
        val at = SensingBus.lastLocationAt.value
        if (lat != null && lon != null && at != null && System.currentTimeMillis() - at < SENSING_TTL_MS) {
            val l = Location("lvyoubao-sensing")
            l.latitude = lat
            l.longitude = lon
            // 带上真实取点时间，新旧比较和「够新」判断才不会被当成本条刚生成的时间
            l.time = at
            candidates.add(l)
        }
        // 2) 本进程主动定位的缓存
        ownFix?.let { candidates.add(it) }
        // 3) 系统最近已知位置
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).mapNotNull { provider ->
                runCatching { if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null }
                    .getOrNull()
            }.let { candidates.addAll(it) }
        }
        return candidates.maxByOrNull { it.time }
    }

    /**
     * 主动要一个实时定点：网络/GPS 同时挂请求（1 秒间隔），窗口内取精度最好的一个。
     * 精度达标（≤60 米）提前返回不白等——网络定点快但可能只有基站级精度（几百米误差），
     * 此前「先到先用」拿到的就是这种，搜「附近」的圆心整体偏掉；GPS 更准但冷启动慢，
     * 给它几秒钟机会，等不到更准的再交出手里的。
     */
    private suspend fun requestFreshFix(context: Context, timeoutMs: Long): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        // best 放协程外面：超时取消后还能取到窗口内最准的定点
        val best = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        ownFix = loc
                        val cur = best.get()
                        if (cur == null || loc.accuracy < cur.accuracy) best.set(loc)
                        val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
                        if (acc <= GOOD_ACCURACY_M && cont.isActive) {
                            providers.forEach { p -> runCatching { lm.removeUpdates(this) } }
                            cont.resume(loc)
                        }
                    }
                }
                providers.forEach { p ->
                    runCatching { lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper()) }
                }
                cont.invokeOnCancellation {
                    providers.forEach { p -> runCatching { lm.removeUpdates(listener) } }
                }
            }
        }
        return fix ?: best.get()
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
