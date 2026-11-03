package com.lvyoubao.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.ApiException
import com.lvyoubao.app.data.AmapClient
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.MapPoi
import com.lvyoubao.app.data.NavigationTracker
import com.lvyoubao.app.data.RouteMode
import com.lvyoubao.app.data.RoutePlan
import com.lvyoubao.app.data.observation.FatigueEngine
import com.lvyoubao.app.ui.common.TtsPlayer
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
 * - 路线卡：真实折线路线 + 起终点标记，步行/骑行/驾车/公交切换，多条方案切换
 * - 导航模式：实时跟图 + 位置点、接近转弯语音播报（可静音）、偏航自动重新规划
 * - 「高德地图」按钮：把目的地传给高德 App 导航（没装就开网页版）
 * - 地点卡：候选点标在图上，点「去这里」直接查路线
 */
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
    var fromLonLat by remember(card) { mutableStateOf(card.fromLon to card.fromLat) }

    var navigating by rememberSaveable { mutableStateOf(false) }
    var voiceOn by rememberSaveable { mutableStateOf(true) }
    var showSteps by remember { mutableStateOf(false) }
    var lastAnnouncedStep by remember { mutableStateOf(-1) }
    var lastReplanAt by remember { mutableStateOf(0L) }
    var replanning by remember { mutableStateOf(false) }

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

    // 地图叠加层状态
    var mapViewRef by remember { mutableStateOf<org.mapsforge.map.android.view.MapView?>(null) }
    var routeLayer by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Polyline?>(null) }
    var originMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var destMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var meMarker by remember { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
    var lastDrawKey by remember { mutableStateOf("") }
    var lastMeDrawn by remember { mutableStateOf<NavigationTracker.Fix?>(null) }
    var followZoomed by remember { mutableStateOf(false) }

    val titleStyle = if (elder) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall

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
                fromLonLat = from
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
        if (selectedPoi == poi && dest == poi.lon to poi.lat) return
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
                fromLonLat = from
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
        lastAnnouncedStep = -1
        NavigationTracker.start(context)
        // 重新取一次路线：保证从当前位置出发、带分步指引（导航语音靠它）
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

    // ---- 界面 ----
    val mapHeight = when {
        navigating && elder -> 420.dp
        navigating -> 360.dp
        elder -> 250.dp
        else -> 210.dp
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (mapFile != null) {
                Box {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(mapHeight)
                            .clip(RoundedCornerShape(14.dp)),
                        factory = { ctx ->
                            val centerLat = if (card.hasOrigin) (card.fromLat + dest.second) / 2 else dest.second
                            val centerLon = if (card.hasOrigin) (card.fromLon + dest.first) / 2 else dest.first
                            buildOfflineMapView(ctx, mapFile, centerLat, centerLon, 14.toByte()).also { mapViewRef = it }
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
                                dest.first, dest.second, selectedPoi?.name.orEmpty(), navigating,
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
                                // 地点卡：把所有候选点都标出来
                                if (card.kind == "place") {
                                    card.pois.forEach { poi ->
                                        val selected = poi == selectedPoi
                                        dotMarker(context, poi.lat, poi.lon, if (selected) DEST_ARGB else POI_ARGB, if (selected) 15 else 11)
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
                                if (navigating) {
                                    mv.model.mapViewPosition.setCenter(org.mapsforge.core.model.LatLong(f.lat, f.lon))
                                    if (!followZoomed) {
                                        mv.model.mapViewPosition.setZoomLevel(17.toByte())
                                        followZoomed = true
                                    }
                                }
                            }
                        },
                    )
                    // 回到我的位置
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        val f = NavigationTracker.fix.value ?: withContext(Dispatchers.IO) {
                                            LocationContext.current(context)?.let {
                                                NavigationTracker.Fix(it.lat, it.lon)
                                            }
                                        }
                                        if (f != null) {
                                            mapViewRef?.let { mv ->
                                                mv.model.mapViewPosition.setCenter(
                                                    org.mapsforge.core.model.LatLong(f.lat, f.lon),
                                                )
                                                mv.model.mapViewPosition.setZoomLevel(16.toByte())
                                            }
                                        }
                                    }
                                }
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
            } else {
                // 没有这片离线地图：占位说明，不挡摘要和跳高德
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("这一片还没下载离线地图", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "到侧边栏「离线地图与路线」下载所在省份即可看图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 导航横幅：当前路段 + 剩余距离
            if (navigating && nav != null) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val plan = currentPlan
                        val headline = when {
                            nav.remainM in 0..40 -> "马上就到目的地"
                            nav.currentStep >= 0 && plan != null -> plan.steps.getOrNull(nav.currentStep) ?: "沿当前道路前进"
                            else -> "沿路线前进"
                        }
                        Text(headline, fontSize = if (elder) 22.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "剩余 ${MapCardData.distanceText(nav.remainM)}" + (if (nav.nextStepDistM > 0) " · ${nav.nextStepDistM} 米后转弯" else ""),
                            fontSize = if (elder) 18.sp else 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 标题 + 摘要
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (card.kind == "place") "「${card.title}」附近" else card.title,
                    style = titleStyle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${MODE_EMOJI[mode] ?: ""} ${MapCardData.distanceText(currentPlan?.distanceM ?: card.distanceM)} · ${MapCardData.durationText(currentPlan?.durationMin ?: card.durationMin)}",
                    style = if (elder) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 地点卡：候选点列表，点一下当作目的地
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
                                fontSize = if (elder) 20.sp else 15.sp,
                                fontWeight = if (poi == selectedPoi) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (poi.address.isNotBlank()) {
                                Text(
                                    poi.address,
                                    fontSize = if (elder) 16.sp else 12.sp,
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
                            Text("去这里", fontSize = if (elder) 18.sp else 13.sp)
                        }
                    }
                }
            }

            // 出行方式切换
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RouteMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { switchMode(m) },
                        label = { Text("${MODE_EMOJI[m]}${m.label}", fontSize = if (elder) 18.sp else 13.sp) },
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    planList.forEachIndexed { i, p ->
                        AssistChip(
                            onClick = { planIndex = i },
                            label = {
                                Text(
                                    p.tag.ifBlank { "方案${i + 1}" },
                                    fontSize = if (elder) 17.sp else 12.sp,
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
                    Text(if (showSteps) "收起分步指引" else "查看分步指引（${steps.size} 步）")
                }
                if (showSteps) {
                    steps.forEachIndexed { index, step ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(
                                "${index + 1}. ",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (elder) 20.sp else 15.sp,
                            )
                            Text(step, fontSize = if (elder) 20.sp else 15.sp, lineHeight = if (elder) 30.sp else 22.sp)
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
                        modifier = if (elder) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("开始导航", fontSize = if (elder) 20.sp else 15.sp)
                    }
                    OutlinedButton(
                        onClick = { openInAmapApp(context, card, mode, dest) },
                        modifier = if (elder) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("高德地图", fontSize = if (elder) 20.sp else 15.sp)
                    }
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
                            fontSize = if (elder) 19.sp else 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (elder) 14.dp else 9.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { replan(announce = voiceOn) },
                        modifier = if (elder) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("重新规划", fontSize = if (elder) 19.sp else 14.sp) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { stopNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = if (elder) Modifier.height(56.dp) else Modifier,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("退出导航", fontSize = if (elder) 19.sp else 14.sp) }
                }
            }
        }
    }
}

/** 把目的地传给高德：优先拉起高德 App 路线页，没装就开网页版 */
private fun openInAmapApp(context: android.content.Context, card: MapCardData, mode: RouteMode, dest: Pair<Double, Double>) {
    val t = when (mode) {
        RouteMode.DRIVE -> "0"
        RouteMode.WALK -> "1"
        RouteMode.BUS -> "2"
        RouteMode.RIDE -> "3"
    }
    val dname = runCatching { java.net.URLEncoder.encode(card.title.ifBlank { "目的地" }, "UTF-8") }.getOrDefault("目的地")
    val appUri = "androidamap://route?sourceApplication=lvyoubao&lat=${dest.second}&lon=${dest.first}" +
        "&dev=0&m=0&t=$t&dname=$dname&sname=${java.net.URLEncoder.encode("我的位置", "UTF-8")}"
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(appUri))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        val webMode = when (mode) {
            RouteMode.DRIVE -> "car"
            RouteMode.RIDE -> "ride"
            RouteMode.BUS -> "bus"
            RouteMode.WALK -> "walk"
        }
        val web = "https://uri.amap.com/navigation?to=${dest.first},${dest.second},$dname&mode=$webMode&policy=1&src=lvyoubao&coordinate=gaode&callnative=1"
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(web))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            android.widget.Toast.makeText(context, "没有找到高德地图，网页版也打不开", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
