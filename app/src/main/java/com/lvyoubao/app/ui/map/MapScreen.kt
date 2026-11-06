package com.lvyoubao.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.NavigationTracker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ME_ARGB = android.graphics.Color.argb(255, 21, 101, 192)

/** 字节数可读化：进度卡显示 已下载/总量 用 */
private fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1L shl 10 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(onClose: () -> Unit, app: TravelApp) {
    val vm: MapViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { MapViewModel(app.applicationContext, app.settingsRepository, app.trackStore) } } },
    )
    val state by vm.ui.collectAsState()
    val me by NavigationTracker.fix.collectAsState()
    val context = LocalContext.current

    // 地图页可见期间订阅定位：给「我的位置」和实时位置点供数
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { NavigationTracker.stop(context) }
    }
    val scope = rememberCoroutineScope()
    var selectedMap by remember { mutableStateOf<File?>(null) }
    var destination by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("view") }

    BackHandler { onClose() }

    // 选中最新的地图文件
    androidx.compose.runtime.LaunchedEffect(state.maps) {
        if (selectedMap == null) selectedMap = state.maps.lastOrNull()
        if (selectedMap != null && state.maps.none { it.path == selectedMap?.path }) {
            selectedMap = state.maps.lastOrNull()
        }
        if (state.maps.isEmpty()) tab = "download"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text("离线地图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))

        // 页内切换：查看已下载 / 下载更多省份
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == "view", onClick = { tab = "view" }, label = { Text("查看地图") })
            FilterChip(selected = tab == "download", onClick = { tab = "download" }, label = { Text("下载更多省份") })
        }
        Spacer(Modifier.height(8.dp))

        // 已下载地图（两个页签都显示，便于对照）
        if (state.maps.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.maps.forEach { map ->
                    val hasUpdate = state.updatesAvailable.contains(map.name.removeSuffix(".map"))
                    FilterChip(
                        selected = selectedMap?.path == map.path,
                        onClick = { selectedMap = map; tab = "view" },
                        label = { Text(MapRegions.displayName(map.name) + if (hasUpdate) " ·有新版" else "") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.trackPoints.size >= 2) "橙色细线是感知足迹（${state.trackPoints.size} 个记录点）" else "开启感知记录后，足迹会以橙色细线叠加在这里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (tab == "view") {
                    TextButton(onClick = { selectedMap?.let { vm.deleteMap(it) } }) { Text("删除当前地图") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // 下载进度
        state.downloading?.let { region ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在下载 ${MapRegions.displayName(region)}（省流量建议 Wi-Fi）", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelDownload() }) { Text("取消") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    if (state.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                append("${fmtSize(state.progressBytes)} / ${fmtSize(state.totalBytes)}")
                                append(" · ${state.speedKbs} KB/s")
                                if (state.speedKbs > 0) {
                                    val remainSec = (state.totalBytes - state.progressBytes) / (state.speedKbs * 1024)
                                    append(" · 剩余约 ")
                                    append(if (remainSec >= 90) "${(remainSec + 59) / 60} 分钟" else "${remainSec.coerceAtLeast(1)} 秒")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (tab == "download" || selectedMap?.exists() != true) {
            // 下载管理
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "下载所在省份的离线地图（约 50–200MB，一次下载永久离线）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 地图数据有时效性（OSM 定期更新）：手动触发一次全量检查
                TextButton(onClick = { vm.refreshRemoteList(force = true) }, enabled = !state.loadingRemote) {
                    Text(if (state.loadingRemote) "检查中…" else "检查更新")
                }
            }
            // 镜像源加速（可选）：官方源在德国，把 .map 自托管到 OSS/NAS 等国内可达处后，下载快一个数量级
            var showMirrorEdit by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.mirrorUrl.isBlank()) "镜像源加速（可选）：自托管 .map 文件，下载快一个数量级"
                    else "镜像源：$state.mirrorUrl",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { showMirrorEdit = !showMirrorEdit }) {
                    Text(if (showMirrorEdit) "收起" else if (state.mirrorUrl.isBlank()) "设置" else "修改")
                }
            }
            if (showMirrorEdit) {
                var mirrorDraft by remember(showMirrorEdit) { mutableStateOf(state.mirrorUrl) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = mirrorDraft,
                        onValueChange = { mirrorDraft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("镜像前缀，如 https://xxx/…/china（末尾省区名.map）") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {
                            vm.setMirror(mirrorDraft)
                            showMirrorEdit = false
                        },
                        label = { Text("保存") },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (state.updatesAvailable.isNotEmpty()) {
                Text(
                    "有 ${state.updatesAvailable.size} 个已下载省区的底图出了新版，下面点「更新」即可（多段并行下载，中断会续传）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("或粘贴自定义 .map 链接") },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { if (customUrl.isNotBlank()) vm.download(customUrl.trim()) },
                    label = { Text("下载") },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.loadingRemote) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.remoteList) { region ->
                        val hasUpdate = state.updatesAvailable.contains(region)
                        val hasLocal = state.maps.any { it.name == "$region.map" }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    MapRegions.displayName(region),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                state.remoteDates[region]?.let { date ->
                                    Text(
                                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                                            .format(java.util.Date(date)) + " 数据" + if (hasUpdate) " · 有新版" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            AssistChip(
                                onClick = { vm.download(region) },
                                label = {
                                    Text(
                                        when {
                                            hasUpdate -> "更新"
                                            hasLocal -> "重下"
                                            else -> "下载"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val map = selectedMap!!
            // 先在 IO 线程校验地图文件：损坏文件（下载中断遗留）不进渲染层，给出明确指引而不是崩溃
            var fileBroken by remember(map.absolutePath) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(map.absolutePath) {
                fileBroken = withContext(Dispatchers.IO) {
                    runCatching { org.mapsforge.map.reader.MapFile(map).close() }.isFailure
                }
            }
            when (fileBroken) {
                null -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
                true -> {
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "这个地图文件打不开（多半是下载中断损坏了）。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row {
                                TextButton(onClick = { vm.deleteMap(map) }) { Text("删除后重新下载") }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
                else -> {
                    var routeOverlay by remember(map.absolutePath) { mutableStateOf<org.mapsforge.map.layer.overlay.Polyline?>(null) }
                    var drawnPoints by remember(map.absolutePath) { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
                    var trackOverlay by remember(map.absolutePath) { mutableStateOf<org.mapsforge.map.layer.overlay.Polyline?>(null) }
                    var drawnTrack by remember(map.absolutePath) { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
                    var mapViewRef by remember(map.absolutePath) { mutableStateOf<org.mapsforge.map.android.view.MapView?>(null) }
                    var meMarker by remember(map.absolutePath) { mutableStateOf<org.mapsforge.map.layer.overlay.Marker?>(null) }
                    var lastMeDrawn by remember(map.absolutePath) { mutableStateOf<NavigationTracker.Fix?>(null) }
                    key(map.absolutePath) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView<org.mapsforge.map.android.view.MapView>(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp)),
                                factory = { ctx ->
                                    val center = LocationContext.current(ctx)
                                    // 没有定位时默认落在地图文件的中心，别瞎飘
                                    val bb = runCatching {
                                        val mf = org.mapsforge.map.reader.MapFile(map)
                                        try {
                                            mf.mapFileInfo.boundingBox
                                        } finally {
                                            runCatching { mf.close() }
                                        }
                                    }.getOrNull()
                                    val centerLat = center?.lat
                                        ?: bb?.let { b -> (b.minLatitude + b.maxLatitude) / 2 }
                                        ?: 28.0
                                    val centerLon = center?.lon
                                        ?: bb?.let { b -> (b.minLongitude + b.maxLongitude) / 2 }
                                        ?: 116.0
                                    buildOfflineMapView(ctx, map, centerLat, centerLon, 16.toByte())
                                        .also { mapViewRef = it }
                                },
                                onReset = { _ -> },
                                onRelease = { mapView: org.mapsforge.map.android.view.MapView ->
                                    if (mapViewRef === mapView) mapViewRef = null
                                    runCatching { mapView.destroyAll() }
                                },
                                update = { mapView: org.mapsforge.map.android.view.MapView ->
                                    // 切换省份：Compose 会复用旧 MapView（factory 不会重跑），
                                    // 这里必须原地换底图，否则显示的还是上一个省
                                    if (mapView.tag != map.absolutePath) {
                                        mapView.tag = map.absolutePath
                                        val bb = runCatching {
                                            val mf = org.mapsforge.map.reader.MapFile(map)
                                            try {
                                                mf.mapFileInfo.boundingBox
                                            } finally {
                                                runCatching { mf.close() }
                                            }
                                        }.getOrNull()
                                        val meNow = me
                                        val cLat = meNow?.lat ?: bb?.let { b -> (b.minLatitude + b.maxLatitude) / 2 } ?: 28.0
                                        val cLon = meNow?.lon ?: bb?.let { b -> (b.minLongitude + b.maxLongitude) / 2 } ?: 116.0
                                        swapBaseMap(mapView, map, cLat, cLon, if (meNow != null) 16.toByte() else 11.toByte())
                                    }
                                    // 实时位置点（有定位就画，切图后自动补上）
                                    val f = me
                                    if (f != null && f != lastMeDrawn) {
                                        lastMeDrawn = f
                                        removeLayer(mapView.layerManager.layers, meMarker)
                                        meMarker = dotMarker(context, f.lat, f.lon, ME_ARGB, 15)
                                            ?.also { mapView.layerManager.layers.add(it) }
                                    }
                                    if (state.trackPoints != drawnTrack) {
                                        trackOverlay?.let { overlay ->
                                            runCatching { mapView.layerManager.layers.remove(overlay) }
                                        }
                                        trackOverlay = addPolyline(
                                            mapView.layerManager.layers, state.trackPoints,
                                            android.graphics.Color.argb(200, 255, 138, 101), 8f,
                                        )
                                        drawnTrack = state.trackPoints
                                    }
                                    if (state.routePoints != drawnPoints) {
                                        routeOverlay?.let { overlay ->
                                            runCatching { mapView.layerManager.layers.remove(overlay) }
                                        }
                                        // 白边压底 + 主色在上，与会话地图卡同款描边
                                        addPolyline(
                                            mapView.layerManager.layers, state.routePoints,
                                            android.graphics.Color.WHITE, 22f,
                                        )
                                        routeOverlay = addPolyline(
                                            mapView.layerManager.layers, state.routePoints,
                                            android.graphics.Color.argb(240, 13, 115, 133), 12f,
                                        )
                                        drawnPoints = state.routePoints
                                        // 画完路线自动框住全程（等布局完成再取宽高）
                                        mapView.post {
                                            val pts = state.routePoints.map {
                                                org.mapsforge.core.model.LatLong(it.second, it.first)
                                            }
                                            if (pts.isNotEmpty()) {
                                                mapView.model.mapViewPosition.setCenter(
                                                    org.mapsforge.core.model.LatLong(
                                                        pts.map { it.latitude }.average(),
                                                        pts.map { it.longitude }.average(),
                                                    ),
                                                )
                                                mapView.model.mapViewPosition.setZoomLevel(
                                                    fitZoomLevel(mapView.width, mapView.height, pts),
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                            // 右下角控制列：放大 / 缩小 / 我的位置（内置缩放按钮已关，避免遮挡）
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(10.dp),
                            ) {
                                // 缩放圆钮与 RouteMapCard 的 RoundMapButton 同规格：44dp 圆 + 20sp，触达面积达标
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                                    Text(
                                        "＋",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable {
                                                mapViewRef?.model?.mapViewPosition?.zoomIn()
                                            }
                                            .padding(10.dp),
                                    )
                                }
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                                    Text(
                                        "－",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable {
                                                mapViewRef?.model?.mapViewPosition?.zoomOut()
                                            }
                                            .padding(10.dp),
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
                                            .clickable {
                                                scope.launch {
                                                    NavigationTracker.start(context)
                                                    val f = NavigationTracker.fix.value
                                                        ?: withContext(Dispatchers.IO) {
                                                            LocationContext.current(context)?.let {
                                                                NavigationTracker.Fix(it.lat, it.lon)
                                                            }
                                                        }
                                                    mapViewRef?.let { mv ->
                                                        f?.let {
                                                            mv.model.mapViewPosition.setCenter(
                                                                org.mapsforge.core.model.LatLong(it.lat, it.lon),
                                                            )
                                                            mv.model.mapViewPosition.setZoomLevel(16.toByte())
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = "回到我的位置",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text("我的位置", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("步行去哪儿？（名称/地址）") },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.routeTo(context, destination.trim()) },
                    enabled = !state.routing && destination.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.routing) CircularProgressIndicator(Modifier.size(18.dp)) else Text("路线")
                }
            }
            state.routeSummary?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.message?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.clearMessage() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 感知足迹折线（日出橙细线）与路线折线统一走 [addPolyline]（描边样式），不再在本文件里各自画 */

