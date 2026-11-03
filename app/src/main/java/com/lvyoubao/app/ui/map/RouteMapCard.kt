package com.lvyoubao.app.ui.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.ApiException
import com.lvyoubao.app.data.AmapClient
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.Gcj02
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.MapPoi
import com.lvyoubao.app.data.NavigationTracker
import com.lvyoubao.app.data.RouteMode
import com.lvyoubao.app.data.RoutePlan
import com.lvyoubao.app.data.observation.FatigueEngine
import com.lvyoubao.app.ui.common.TtsPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROUTE_ARGB = android.graphics.Color.argb(240, 13, 115, 133)
private val ME_ARGB = android.graphics.Color.argb(255, 21, 101, 192)
private val DEST_ARGB = android.graphics.Color.argb(255, 230, 74, 46)
private val ORIGIN_ARGB = android.graphics.Color.argb(255, 117, 117, 117)
private val POI_ARGB = android.graphics.Color.argb(255, 255, 152, 0)

private val MODE_EMOJI = mapOf(RouteMode.WALK to "🚶", RouteMode.RIDE to "🚴", RouteMode.DRIVE to "🚗", RouteMode.BUS to "🚌")

/** 导航进度：离最近顶点下标 / 当前段 / 下一转弯 / 剩余距离 */
private data class NavState(
    val nearestIdx: Int,
    val currentStep: Int,
    val nextStep: Int,
    val nextStepDistM: Int,
    val remainM: Int,
)

/**
 * 会话内的地图卡：问「怎么走 / 在哪」时直接嵌在回答下方。
 * - 内嵌小图：双指缩放、拖动（已处理与会话列表的滑动冲突），一键全屏
 * - 全屏地图（点「全屏」/ 开始导航自动进入）：铺满整屏，右侧 缩放+/−、罗盘、我的位置，
 *   底部悬浮面板放方式切换/方案/指引/导航操作，像高德地图那样整屏操作
 * - 路线卡 + 地点卡 + 导航（跟图/语音播报可静音/偏航重规划/到达判定）+ 跳高德传目的地
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteMapCard(
    card: MapCardData,
    app: TravelApp,
    elder: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settingsRepository.settings.collectAsState(initial = AiSettings())

    val mapFile = remember(card) {
        pickMapFile(context, listOf(card.fromLon to card.fromLat, card.toLon to card.toLat))
    }

    // ---- 目的地（地点卡可换选）与路线方案 ----
    var dest by remember(card) { mutableStateOf(card.toLon to card.toLat) }   // (经度, 纬度)
    var selectedPoi by remember(card) { mutableStateOf<MapPoi?>(card.pois.firstOrNull()) }
    var plans by remember(card) { mutableStateOf<Map<RouteMode, List<RoutePlan>>>(emptyMap()) }
    var mode by remember(card) { mutableStateOf(RouteMode.WALK) }
    var planIndex by remember(card) { mutableStateOf(0) }
    var loading by remember(card) { mutableStateOf(false) }
    var error by remember(card) { mutableStateOf<String?>(null) }

    var expanded by rememberSaveable { mutableStateOf(false) }
    var navigating by rememberSaveable { mutableStateOf(false) }
    var voiceOn by rememberSaveable { mutableStateOf(true) }
    var showSteps by remember { mutableStateOf(false) }
    var lastAnnouncedStep by remember { mutableStateOf(-1) }
    var lastReplanAt by remember { mutableStateOf(0L) }
    var replanning by remember { mutableStateOf(false) }
    var panelHeightPx by remember { mutableStateOf(0) }
    var userDragging by remember { mutableStateOf(false) }

    val cardPlan = remember(card) {
        if (card.kind != "route" || card.points.size < 2) null
        else RoutePlan(
            mode = RouteMode.WALK,
            distanceM = card.distanceM,
            durationMin = card.durationMin,
            points = card.points.map { p -> (p.getOrNull(0) ?: 0.0) to (p.getOrNull(1) ?: 0.0) },
            steps = card.steps,
        )
    }
    val currentPlan: RoutePlan? = plans[mode]?.getOrNull(planIndex)
        ?: cardPlan.takeIf { mode == RouteMode.WALK && dest == (card.toLon to card.toLat) }

    val me by NavigationTracker.fix.collectAsState()

    // 地图视图与叠加层（内嵌/全屏共用一套状态，切换时地图重建会自动重画）
    var mapViewRef by remember { mutableStateOf<org.mapsforge.map.android.view.MapView?>(null) }
    var routeLayer by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Polyline?>(null) }
    var originMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var destMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var meMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var lastDrawKey by remember { mutableStateOf("") }
    var lastMeDrawn by remember { mutableStateOf<NavigationTracker.Fix?>(null) }
    var followZoomed by remember { mutableStateOf(false) }

    val big = elder

    // ---- 路线查询 ----
    suspend fun resolveFrom(): Pair<Double, Double> {
        NavigationTracker.fix.value?.let { return it.lon to it.lat }
        val place = withContext(Dispatchers.IO) { LocationContext.current(context) }
            ?: throw ApiException("没有位置信息，请授权定位")
        return place.lon to place.lat
    }

    fun fetchPlans(m: RouteMode, announce: Boolean = false) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val from = runCatching { resolveFrom() }.getOrElse { e ->
                    error = e.message ?: "没有位置信息"
                    return@launch
                }
                val key = settings.amapKey
                if (key.isBlank()) throw ApiException("查路线需要高德 Key：请到「设置」里填写（lbs.amap.com 免费申请）")
                val list = withContext(Dispatchers.IO) {
                    AmapClient(key).routePlans(m, from.first, from.second, dest.first, dest.second)
                }
                if (list.isEmpty()) {
                    error = "没查到${m.label}路线，换一种出行方式试试"
                } else {
                    plans = plans + (m to list)
                    planIndex = 0
                    if (announce) {
                        val r = list[0]
                        TtsPlayer.speak(
                            context,
                            "${m.label}前往${card.title}，全程${MapCardData.distanceText(r.distanceM)}，${MapCardData.durationText(r.durationMin)}",
                        )
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "路线查询失败"
            } finally {
                loading = false
            }
        }
    }

    fun switchMode(m: RouteMode) {
        if (m == mode && plans[m] != null) return
        mode = m
        planIndex = 0
        if (plans[m] == null) fetchPlans(m)
    }

    /** 地点卡：选中一个候选点作为目的地，直接查步行路线 */
    fun selectPoi(poi: MapPoi) {
        if (selectedPoi == poi && dest == (poi.lon to poi.lat)) return
        selectedPoi = poi
        dest = poi.lon to poi.lat
        plans = emptyMap()
        planIndex = 0
        error = null
        fetchPlans(RouteMode.WALK)
    }

    fun replan(announce: Boolean) {
        if (replanning) return
        replanning = true
        scope.launch {
            try {
                val from = runCatching { resolveFrom() }.getOrNull() ?: return@launch
                val key = settings.amapKey
                if (key.isBlank()) return@launch
                val list = withContext(Dispatchers.IO) {
                    AmapClient(key).routePlans(mode, from.first, from.second, dest.first, dest.second)
                }
                if (list.isNotEmpty()) {
                    plans = plans + (mode to list)
                    planIndex = 0
                    if (announce) TtsPlayer.speak(context, "已按当前位置重新规划路线")
                }
            } catch (_: Exception) {
            } finally {
                replanning = false
            }
        }
    }

    // ---- 导航 ----
    fun startNavigation() {
        navigating = true
        followZoomed = false
        userDragging = false
        lastAnnouncedStep = -1
        NavigationTracker.start(context)
        expanded = true   // 导航直接进全屏，像高德一样整屏跟图
        fetchPlans(mode)
        val plan = currentPlan
        if (voiceOn && plan != null) {
            TtsPlayer.speak(
                context,
                "开始导航，全程${MapCardData.distanceText(plan.distanceM)}，${MapCardData.durationText(plan.durationMin)}。请留意语音提示",
            )
        }
    }

    fun stopNavigation(announceArrival: Boolean = false) {
        if (!navigating) return
        navigating = false
        followZoomed = false
        NavigationTracker.stop(context)
        if (announceArrival) {
            if (voiceOn) TtsPlayer.speak(context, "已到达目的地附近，导航结束")
        } else {
            TtsPlayer.stop()
        }
    }

    DisposableEffect(card) {
        onDispose { NavigationTracker.stop(context) }
    }

    // 导航进度计算
    val nav: NavState? = remember(me, currentPlan) {
        val m = me
        val plan = currentPlan
        if (m == null || plan == null || plan.points.isEmpty()) null
        else {
            val idx = NavigationTracker.nearestIndex(m.lat, m.lon, plan.points)
            var remain = FatigueEngine.haversine(
                m.lat, m.lon, plan.points[idx].second, plan.points[idx].first,
            ).toDouble()
            for (i in idx until plan.points.size - 1) {
                remain += FatigueEngine.haversine(
                    plan.points[i].second, plan.points[i].first,
                    plan.points[i + 1].second, plan.points[i + 1].first,
                ).toDouble()
            }
            var cur = -1
            plan.stepStarts.forEachIndexed { i, s -> if (s <= idx) cur = i }
            val next = plan.stepStarts.indexOfFirst { it > idx }
            val nextDist = if (next >= 0) {
                val p = plan.points[plan.stepStarts[next]]
                FatigueEngine.haversine(m.lat, m.lon, p.second, p.first).toInt()
            } else -1
            NavState(idx, cur, next, nextDist, remain.toInt())
        }
    }

    // 接近下一转弯 → 播报那条指引
    LaunchedEffect(nav?.nextStepDistM, navigating, voiceOn) {
        val plan = currentPlan ?: return@LaunchedEffect
        val n = nav ?: return@LaunchedEffect
        if (!navigating || !voiceOn) return@LaunchedEffect
        if (n.nextStep >= 0 && n.nextStepDistM in 1..45 && lastAnnouncedStep != n.nextStep) {
            lastAnnouncedStep = n.nextStep
            TtsPlayer.speak(context, "前方，" + (plan.steps.getOrNull(n.nextStep) ?: "继续前进"))
        }
    }

    // 偏航（离路线 80 米开）→ 自动重新规划（30 秒最多一次）
    LaunchedEffect(me) {
        val m = me ?: return@LaunchedEffect
        val plan = currentPlan ?: return@LaunchedEffect
        if (!navigating || plan.points.isEmpty()) return@LaunchedEffect
        val off = NavigationTracker.distanceToPolylineM(m.lat, m.lon, plan.points)
        if (off > 80 && System.currentTimeMillis() - lastReplanAt > 30_000) {
            lastReplanAt = System.currentTimeMillis()
            if (voiceOn) TtsPlayer.speak(context, "偏航了，正在重新规划路线")
            replan(announce = false)
        }
    }

    // 到达判定
    LaunchedEffect(nav?.remainM) {
        val n = nav ?: return@LaunchedEffect
        if (navigating && n.remainM in 0..40) stopNavigation(announceArrival = true)
    }

    // ---- 摘要文本（地点卡未查路线时显示选中点的距离，不显示"0 米"） ----
    val summaryText: String = run {
        val distM = currentPlan?.distanceM ?: selectedPoi?.distanceM ?: card.distanceM
        val durMin = currentPlan?.durationMin ?: card.durationMin
        buildString {
            append(MapCardData.distanceText(distM))
            if (durMin > 0) append(" · ").append(MapCardData.durationText(durMin))
        }
    }

    // ---- 地图视图（内嵌与全屏共用；重建时自动重画所有图层） ----
    @Composable
    fun CardMap(modifier: Modifier) {
        if (mapFile == null) {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Text("这一片还没下载离线地图", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "到侧边栏「离线地图与路线」下载所在省份即可看图（摘要与跳高德不受影响）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        AndroidView(
            modifier = modifier.clip(RoundedCornerShape(if (expanded) 0.dp else 14.dp)),
            factory = { ctx ->
                val centerLat = if (card.hasOrigin) (card.fromLat + dest.second) / 2 else dest.second
                val centerLon = if (card.hasOrigin) (card.fromLon + dest.first) / 2 else dest.first
                buildOfflineMapView(ctx, mapFile, centerLat, centerLon, 14.toByte())
                    .also { mv ->
                        mapViewRef = mv
                        lastDrawKey = ""      // 重建后强制重画图层
                        lastMeDrawn = null
                        // 拖动时告诉父容器别抢手势（会话列表会吃掉纵向滑动，导致内嵌地图拖不动）
                        mv.setOnTouchListener { v, ev ->
                            when (ev.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                    if (navigating) userDragging = true   // 手动看图时暂停自动跟图
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false   // 事件继续交给地图自己处理
                        }
                    }
            },
            onRelease = { mv ->
                runCatching { mv.destroyAll() }
                if (mapViewRef === mv) mapViewRef = null
                routeLayer = null; originMarker = null; destMarker = null; meMarker = null
                lastDrawKey = ""
            },
            update = { mv ->
                val plan = currentPlan
                val key = listOf(
                    mode.name, planIndex, plan?.distanceM ?: -1, plan?.points?.size ?: 0,
                    dest.first, dest.second, selectedPoi?.name.orEmpty(), navigating, expanded,
                ).joinToString("|")
                if (key != lastDrawKey) {
                    lastDrawKey = key
                    val layers = mv.layerManager.layers
                    removeLayer(layers, routeLayer)
                    routeLayer = addPolyline(layers, plan?.points.orEmpty(), ROUTE_ARGB, 12f)
                    removeLayer(layers, originMarker)
                    removeLayer(layers, destMarker)
                    val originPt = plan?.points?.firstOrNull() ?: (card.fromLon to card.fromLat)
                    originMarker = if (card.hasOrigin) {
                        dotMarker(context, originPt.second, originPt.first, ORIGIN_ARGB, 12)
                            ?.also { layers.add(it) }
                    } else null
                    val destPt = plan?.points?.lastOrNull() ?: dest
                    destMarker = dotMarker(context, destPt.second, destPt.first, DEST_ARGB, 15)
                        ?.also { layers.add(it) }
                    if (card.kind == "place") {
                        card.pois.forEach { poi ->
                            val sel = poi == selectedPoi
                            dotMarker(context, poi.lat, poi.lon, if (sel) DEST_ARGB else POI_ARGB, if (sel) 15 else 11)
                                ?.also { layers.add(it) }
                        }
                    }
                    // 等布局完成后自动框住全程
                    mv.post {
                        val pts = plan?.points?.map { org.mapsforge.core.model.LatLong(it.second, it.first) }
                            ?: listOf(org.mapsforge.core.model.LatLong(dest.second, dest.first))
                        val all = if (card.hasOrigin) {
                            pts + org.mapsforge.core.model.LatLong(card.fromLat, card.fromLon)
                        } else pts
                        if (all.isNotEmpty()) {
                            mv.model.mapViewPosition.setCenter(
                                org.mapsforge.core.model.LatLong(
                                    all.map { it.latitude }.average(),
                                    all.map { it.longitude }.average(),
                                ),
                            )
                            mv.model.mapViewPosition.setZoomLevel(fitZoomLevel(mv.width, mv.height, all))
                        }
                    }
                }
                // 实时位置点 + 导航跟图
                val f = me
                if (f != null && f != lastMeDrawn) {
                    lastMeDrawn = f
                    val layers = mv.layerManager.layers
                    removeLayer(layers, meMarker)
                    meMarker = dotMarker(context, f.lat, f.lon, ME_ARGB, 15)?.also { layers.add(it) }
                    if (navigating && !userDragging) {
                        mv.model.mapViewPosition.setCenter(org.mapsforge.core.model.LatLong(f.lat, f.lon))
                        if (!followZoomed) {
                            mv.model.mapViewPosition.setZoomLevel(17.toByte())
                            followZoomed = true
                        }
                    }
                }
            },
        )
    }

    // ---- 圆形小按钮 ----
    @Composable
    fun RoundMapButton(label: String, sizeDp: Int = 44, onClick: () -> Unit) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.size(sizeDp.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { onClick() }) {
                Text(label, fontSize = if (label.length == 1) 20.sp else 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    fun locateMe() {
        scope.launch {
            val f = NavigationTracker.fix.value ?: withContext(Dispatchers.IO) {
                LocationContext.current(context)?.let { NavigationTracker.Fix(it.lat, it.lon) }
            }
            if (f != null) {
                userDragging = false   // 点"我的位置"恢复自动跟图
                NavigationTracker.start(context)
                mapViewRef?.let { mv ->
                    mv.model.mapViewPosition.setCenter(org.mapsforge.core.model.LatLong(f.lat, f.lon))
                    mv.model.mapViewPosition.setZoomLevel(16.toByte())
                }
            }
        }
    }

    // ---- 信息面板（标题/方式/方案/指引/操作），内嵌与全屏共用 ----
    @Composable
    fun InfoPanel(modifier: Modifier) {
        Column(modifier) {
            // 导航横幅（全屏时顶部另有精简横幅，这里导航中只显示操作行）
            if (!(expanded && navigating && nav != null)) {
                if (navigating && nav != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            val headline = when {
                                nav.remainM in 0..40 -> "马上就到目的地"
                                nav.currentStep >= 0 && currentPlan != null ->
                                    currentPlan.steps.getOrNull(nav.currentStep) ?: "沿当前道路前进"
                                else -> "沿路线前进"
                            }
                            Text(
                                headline,
                                fontSize = if (big) 24.sp else 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "剩余 ${MapCardData.distanceText(nav.remainM)}" + (if (nav.nextStepDistM > 0) " · ${nav.nextStepDistM} 米后转弯" else ""),
                                fontSize = if (big) 19.sp else 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // 标题 + 摘要
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (card.kind == "place") "「${card.title}」附近" else card.title,
                    style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${MODE_EMOJI[mode] ?: ""} $summaryText",
                    style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 地点卡候选点列表
            if (card.kind == "place" && card.pois.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                card.pois.forEach { poi ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectPoi(poi) }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                poi.name,
                                fontSize = if (big) 20.sp else 15.sp,
                                fontWeight = if (poi == selectedPoi) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (poi.address.isNotBlank()) {
                                Text(
                                    poi.address,
                                    fontSize = if (big) 16.sp else 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "约 ${MapCardData.distanceText(poi.distanceM)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { selectPoi(poi) }) {
                            Text("去这里", fontSize = if (big) 18.sp else 13.sp)
                        }
                    }
                }
            }

            // 出行方式（FlowRow 挤不下自动换行，不再竖排）
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RouteMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { switchMode(m) },
                        label = { Text("${MODE_EMOJI[m]}${m.label}", fontSize = if (big) 18.sp else 13.sp) },
                    )
                }
                if (loading) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            // 多方案切换
            val planList = plans[mode]
            if (planList != null && planList.size > 1) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    planList.forEachIndexed { i, p ->
                        AssistChip(
                            onClick = { planIndex = i },
                            label = {
                                Text(
                                    p.tag.ifBlank { "方案${i + 1}" },
                                    fontSize = if (big) 17.sp else 12.sp,
                                    fontWeight = if (i == planIndex) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }

            // 分步指引
            val steps = currentPlan?.steps.orEmpty()
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(if (showSteps) "收起分步指引" else "查看分步指引（${steps.size} 步）", fontSize = if (big) 18.sp else 14.sp)
                }
                if (showSteps) {
                    steps.forEachIndexed { index, step ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(
                                "${index + 1}. ",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 20.sp else 15.sp,
                            )
                            Text(step, fontSize = if (big) 20.sp else 15.sp, lineHeight = if (big) 30.sp else 22.sp)
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }

            // 操作区
            Spacer(Modifier.height(8.dp))
            if (!navigating) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startNavigation() },
                        enabled = currentPlan != null || card.kind == "place",
                        modifier = if (big) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("开始导航", fontSize = if (big) 20.sp else 15.sp) }
                    OutlinedButton(
                        onClick = { openInAmapApp(context, card, mode, dest) },
                        modifier = if (big) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("高德地图", fontSize = if (big) 20.sp else 15.sp) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (voiceOn) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { voiceOn = !voiceOn },
                    ) {
                        Text(
                            if (voiceOn) "🔊 播报中" else "🔇 已静音",
                            fontSize = if (big) 19.sp else 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (big) 14.dp else 9.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { replan(announce = voiceOn) },
                        modifier = if (big) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("重新规划", fontSize = if (big) 19.sp else 14.sp) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { stopNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = if (big) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("退出导航", fontSize = if (big) 19.sp else 14.sp) }
                }
            }
        }
    }

    // ---- 内嵌卡片（全屏时隐藏，避免同时创建两个地图实例） ----
    if (!expanded) Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box {
                CardMap(
                    Modifier
                        .fillMaxWidth()
                        .height(if (big) 250.dp else 210.dp),
                )
                if (mapFile != null) {
                    // 右下角：全屏 + 我的位置，纵向排开互不遮挡
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 2.dp,
                        ) {
                            Text(
                                "⛶ 全屏",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { expanded = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { locateMe() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = "回到我的位置",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text("我的位置", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            InfoPanel(Modifier.fillMaxWidth())
        }
    }

    // ---- 全屏地图（像高德一样整屏操作） ----
    if (expanded) {
        val azimuth by CompassSensor.azimuth
        DisposableEffect(Unit) {
            CompassSensor.start(context)
            onDispose { CompassSensor.stop(context) }
        }
        androidx.activity.compose.BackHandler { expanded = false }
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    CardMap(Modifier.fillMaxSize())

                    // 顶部：返回 + 导航横幅
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                            Box(Modifier.clickable { expanded = false }.padding(10.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "退出全屏",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (navigating && nav != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    val headline = when {
                                        nav.remainM in 0..40 -> "马上就到目的地"
                                        nav.currentStep >= 0 && currentPlan != null ->
                                            currentPlan.steps.getOrNull(nav.currentStep) ?: "沿当前道路前进"
                                        else -> "沿路线前进"
                                    }
                                    Text(
                                        headline,
                                        fontSize = if (big) 22.sp else 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "剩余 ${MapCardData.distanceText(nav.remainM)}" + (if (nav.nextStepDistM > 0) " · ${nav.nextStepDistM} 米后转弯" else ""),
                                        fontSize = if (big) 17.sp else 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    // 右侧竖排控制列：放大 / 缩小 / 罗盘 / 我的位置
                    // 底部悬浮面板会盖住屏幕下半段，控制列在整屏垂直居中的基础上
                    // 上移面板高的一半，正好落在可见地图区域的中间（列表展开也不挡）
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .graphicsLayer { translationY = -panelHeightPx / 2f },
                    ) {
                        RoundMapButton("＋") {
                            val mv = mapViewRef
                            if (mv != null) mv.model.mapViewPosition.zoomIn()
                        }
                        RoundMapButton("－") {
                            val mv = mapViewRef
                            if (mv != null) mv.model.mapViewPosition.zoomOut()
                        }
                        // 罗盘：指示设备朝向（本地地图库暂不支持底图旋转，用指北箭头代替）
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "▲",
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer { rotationZ = azimuth },
                                )
                            }
                        }
                        RoundMapButton("◎") {
                            locateMe()
                        }
                    }

                    // 底部悬浮面板（高度变化实时回传给控制列做避让）
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { panelHeightPx = it.height },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (big) 430.dp else 340.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            InfoPanel(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 设备朝向（0°=北，顺时针），罗盘徽章用；地图库本身不支持底图旋转，这里只做指示 */
private object CompassSensor {
    private val _azimuth = mutableFloatStateOf(0f)
    val azimuth: State<Float> get() = _azimuth

    private var listener: SensorEventListener? = null

    fun start(context: Context) {
        if (listener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ORIENTATION)
            ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val deg = when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val r = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(r, event.values)
                        val o = FloatArray(3)
                        SensorManager.getOrientation(r, o)
                        Math.toDegrees(o[0].toDouble()).toFloat()
                    }
                    else -> event.values[0]
                }
                _azimuth.value = ((deg % 360f) + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        runCatching { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop(context: Context) {
        listener?.let { l ->
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(l)
        }
        listener = null
    }
}

/**
 * 把目的地传给高德：三级尝试，任一成功即返回。
 * 1) amapuri://route/plan（新版深链，参数直达规划页，目的地已填好）
 * 2) androidamap://route（官方 URI API，老版高德）
 * 3) uri.amap.com 网页导航链接（没装高德时开网页；callnative=1 装了高德会自动拉起）
 */
private fun openInAmapApp(context: android.content.Context, card: MapCardData, mode: RouteMode, dest: Pair<Double, Double>) {
    val t = when (mode) {
        RouteMode.DRIVE -> "0"
        RouteMode.WALK -> "1"
        RouteMode.BUS -> "2"
        RouteMode.RIDE -> "3"
    }
    val enc = { s: String -> runCatching { java.net.URLEncoder.encode(s, "UTF-8") }.getOrDefault(s) }
    val dname = enc(card.title.ifBlank { "目的地" })
    // App 内坐标统一是 WGS-84（离线底图/GPS），高德 App 要 GCJ-02（dev=0）
    val g = Gcj02.fromWgs(dest.second, dest.first)
    val dlat = g.first
    val dlon = g.second
    val deepLinks = listOf(
        "amapuri://route/plan?sourceApplication=lvyoubao&dlat=$dlat&dlon=$dlon&dname=$dname&dev=0&m=0&t=$t",
        "androidamap://route?sourceApplication=lvyoubao&lat=$dlat&lon=$dlon&dname=$dname&dev=0&m=0&t=$t&sname=${enc("我的位置")}",
    )
    for (link in deepLinks) {
        if (runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        ) {
            return
        }
    }
    val webMode = when (mode) {
        RouteMode.DRIVE -> "car"
        RouteMode.RIDE -> "ride"
        RouteMode.BUS -> "bus"
        RouteMode.WALK -> "walk"
    }
    val web = "https://uri.amap.com/navigation?to=$dlon,$dlat,$dname&mode=$webMode&policy=1&src=lvyoubao&coordinate=gaode&callnative=1"
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(web))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "没有找到高德地图，网页版也打不开", android.widget.Toast.LENGTH_SHORT).show()
    }
}
