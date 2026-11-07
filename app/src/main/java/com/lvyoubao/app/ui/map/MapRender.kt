package com.lvyoubao.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.io.File
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline

/**
 * 旅游宝里所有 mapsforge 离线地图共用的渲染件（离线地图页 + 会话地图卡）。
 *
 * 重要：mapsforge 画笔默认是 FILL 样式，画折线前必须显式设成 STROKE，
 * 否则整条路线会被当成多边形填充，表现为地图上出现「蓝色大三角」——此前路线显示异常的根因。
 */
private var graphicsFactoryReady = false

internal fun ensureGraphicFactory(context: Context) {
    if (!graphicsFactoryReady) {
        AndroidGraphicFactory.createInstance(context.applicationContext)
        graphicsFactoryReady = true
    }
}

/** 折线画笔：描边样式 + 颜色 + 线宽 */
internal fun strokePaint(argb: Int, widthF: Float): org.mapsforge.core.graphics.Paint =
    AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(argb)
        setStrokeWidth(widthF)
        setStyle(Style.STROKE)
        // 圆头圆角：转弯处自然衔接，线端不缺一块
        runCatching { setStrokeCap(org.mapsforge.core.graphics.Cap.ROUND) }
        runCatching { setStrokeJoin(org.mapsforge.core.graphics.Join.ROUND) }
    }

/** 往图层里加一条折线（描边样式），返回引用便于后续替换 */
internal fun addPolyline(
    layers: Layers,
    points: List<Pair<Double, Double>>,  // (经度, 纬度)
    argb: Int,
    widthF: Float,
): Polyline? {
    if (points.size < 2) return null
    return runCatching {
        val line = Polyline(strokePaint(argb, widthF), AndroidGraphicFactory.INSTANCE)
        points.forEach { (lon, lat) -> line.addPoint(LatLong(lat, lon)) }
        layers.add(line)
        line
    }.getOrNull()
}

internal fun removeLayer(layers: Layers, layer: Layer?) {
    if (layer != null) runCatching { layers.remove(layer) }
}

/**
 * 圆点标记：当前位置/起终点统一用它，居中锚点。
 * 之前的实现拿 App 启动图标当位置标记，图大又看不出确切点位。
 */
internal fun dotMarker(context: Context, lat: Double, lon: Double, argb: Int, sizeDp: Int): Marker? = runCatching {
    val density = context.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt().coerceAtLeast(12)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val ring = android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, ring)
    val core = android.graphics.Paint().apply { isAntiAlias = true; color = argb }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - sizePx * 0.12f, core)
    val mfBitmap = AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp) as android.graphics.drawable.Drawable)
    Marker(LatLong(lat, lon), mfBitmap, mfBitmap.width / 2, mfBitmap.height / 2)
}.getOrNull()

/**
 * 朝向箭头标记：导航时替代蓝点，箭头方向 = 运动方向（0°=北）。
 * 白描边 + 品牌色箭头，旋转画在位图里，改朝向只需重建小位图。
 */
internal fun arrowMarker(context: Context, lat: Double, lon: Double, argb: Int, bearingDeg: Float, sizeDp: Int): Marker? = runCatching {
    val density = context.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt().coerceAtLeast(24)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.save()
    canvas.rotate(bearingDeg, sizePx / 2f, sizePx / 2f)
    val stroke = android.graphics.Paint().apply {
        isAntiAlias = true; color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE
        strokeWidth = sizePx * 0.14f; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val fill = android.graphics.Paint().apply { isAntiAlias = true; color = argb; style = android.graphics.Paint.Style.FILL }
    val w = sizePx.toFloat(); val h = sizePx.toFloat()
    val path = android.graphics.Path().apply {
        moveTo(w * 0.50f, h * 0.08f)   // 尖端朝上（0°=北）
        lineTo(w * 0.86f, h * 0.86f)
        lineTo(w * 0.50f, h * 0.66f)
        lineTo(w * 0.14f, h * 0.86f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    canvas.restore()
    val mfBitmap = AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp) as android.graphics.drawable.Drawable)
    Marker(LatLong(lat, lon), mfBitmap, mfBitmap.width / 2, mfBitmap.height / 2)
}.getOrNull()

/**
 * 原地换底图：移除旧渲染层（先 onDestroy 关闭旧 MapFile 防句柄泄漏），换上新地图。
 * 用于同一 MapView 切换省份——Compose AndroidView 会复用旧视图，factory 不再执行，
 * 必须在 update 阶段自己换底图，否则切省后显示的还是上一次的地图。
 */
internal fun swapBaseMap(
    mv: org.mapsforge.map.android.view.MapView,
    mapFile: File,
    centerLat: Double,
    centerLon: Double,
    zoom: Byte,
) {
    ensureGraphicFactory(mv.context)
    val layers = mv.layerManager.layers
    layers.filterIsInstance<org.mapsforge.map.layer.renderer.TileRendererLayer>().firstOrNull()?.let { old ->
        runCatching { old.onDestroy() }
        runCatching { layers.remove(old) }
    }
    val tileCache = org.mapsforge.map.android.util.AndroidUtil.createTileCache(
        mv.context, "lvyoubao-map", mv.model.displayModel.tileSize, 1.0f, 1.5,
    )
    val mapDataStore = org.mapsforge.map.reader.MapFile(mapFile)
    val layer = org.mapsforge.map.layer.renderer.TileRendererLayer(
        tileCache, mapDataStore, mv.model.mapViewPosition, false, true, false,
        AndroidGraphicFactory.INSTANCE,
    )
    runCatching { layer.setXmlRenderTheme(org.mapsforge.map.rendertheme.InternalRenderTheme.DEFAULT) }
    layers.add(0, layer)
    mv.model.mapViewPosition.setCenter(LatLong(centerLat, centerLon))
    mv.model.mapViewPosition.setZoomLevel(zoom)
}

/**
 * 挑一张覆盖所有关键点的离线地图；都不覆盖就退回最新一张（和离线地图页的默认选择一致）。
 * points 传 (经度, 纬度) 对。
 */
internal fun pickMapFile(context: Context, points: List<Pair<Double, Double>>): File? =
    pickMapFiles(context, points).lastOrNull()

/**
 * 选图结果：相交的地图文件 + 关键点里没有被任何底图盖住的（含猜出的缺失省份）。
 * 用户站在省界上（如三清山服务区，江西侧）而只下载了邻省时，底图就是一半空白——
 * 把"哪片没图、缺哪个省"显式交回给界面，提示一键补下载，而不是静默留白让用户以为是 bug。
 */
internal data class MapSelection(
    val files: List<File>,
    /** 没有任何已下载地图覆盖的关键点 (经度, 纬度) */
    val uncoveredPoints: List<Pair<Double, Double>>,
    /** 缺失的省份（中文，已排除已下载的，按点位邻近度排序） */
    val missingProvinces: List<String>,
    /** 一张都不相交退回了「最新一张」兜底（显示的省份与点位无关） */
    val fellBack: Boolean,
)

/**
 * 挑出所有与关键点范围相交的离线地图，并报告底图缺口。
 * 跨省路线（如江西→上海）没有一张图能覆盖全程，此前只挑一张就退回
 * 「文件名排序的最后一张」，显示的根本不是路线所在区域——用户"已下载该省地图却不显示"的根因。
 * 改为返回全部相交省份，渲染层逐张叠 TileRendererLayer，路线全程都有底图。
 * points 传 (经度, 纬度) 对；一张都不相交时退回最新一张（与离线地图页默认一致）并标记 fellBack。
 */
internal fun pickMapSelection(context: Context, points: List<Pair<Double, Double>>): MapSelection {
    val maps = File(context.filesDir, "maps")
        .listFiles { f -> f.name.endsWith(".map") }
        ?.sortedBy { it.name }
        .orEmpty()
    val targets = points.filter { it.first != 0.0 || it.second != 0.0 }
    if (maps.isEmpty() || targets.isEmpty()) {
        return MapSelection(
            files = if (maps.isEmpty()) emptyList() else listOf(maps.last()),
            uncoveredPoints = targets,
            missingProvinces = targets.flatMap { MapRegions.guessProvinces(it.second, it.first) }.distinct(),
            fellBack = maps.isNotEmpty(),
        )
    }
    // 用路线包络框和每张图的省界框做相交判断：几千个折线点逐点 contains 太慢，也漏掉途经省
    val envelope = org.mapsforge.core.model.BoundingBox(
        targets.minOf { it.second }, targets.minOf { it.first },
        targets.maxOf { it.second }, targets.maxOf { it.first },
    )
    val covering = maps.filter { m ->
        runCatching {
            val mf = org.mapsforge.map.reader.MapFile(m)
            try {
                mf.mapFileInfo.boundingBox.intersects(envelope)
            } finally {
                runCatching { mf.close() }
            }
        }.getOrDefault(false)
    }.ifEmpty { listOf(maps.last()) }
    // 逐点查覆盖：只对少数关键点（起终点）做精确 bbox 判断，途经点不查（路线穿省已由相交覆盖）
    val boxes = covering.map { f ->
        runCatching {
            val mf = org.mapsforge.map.reader.MapFile(f)
            try {
                f to mf.mapFileInfo.boundingBox
            } finally {
                runCatching { mf.close() }
            }
        }.getOrNull()
    }.filterNotNull()
    val uncovered = targets.filter { p -> boxes.none { (_, b) -> b.contains(p.second, p.first) } }
    val downloaded = boxes.map { (f, _) -> MapRegions.displayName(f.name) }.toSet()
    val missing = uncovered
        .flatMap { MapRegions.guessProvinces(it.second, it.first) }
        .filter { it !in downloaded }
        .distinct()
    return MapSelection(covering, uncovered, missing, fellBack = covering.size == 1 && covering[0] == maps.last() && maps.size > 1)
}

internal fun pickMapFiles(context: Context, points: List<Pair<Double, Double>>): List<File> =
    pickMapSelection(context, points).files

/** 找一个能把所有点装下的缩放级别（留 18% 边距） */
internal fun fitZoomLevel(widthPx: Int, heightPx: Int, points: List<LatLong>): Byte {
    if (widthPx <= 0 || heightPx <= 0 || points.isEmpty()) return 14
    var zoom = 12
    while (zoom < 20) {
        val xs = points.map { MercatorProjection.longitudeToPixelX(it.longitude, zoom.toByte(), 256) }
        val ys = points.map { MercatorProjection.latitudeToPixelY(it.latitude, zoom.toByte(), 256) }
        if ((xs.max() - xs.min()) > widthPx * 0.82 || (ys.max() - ys.min()) > heightPx * 0.82) break
        zoom++
    }
    return (zoom - 1).coerceAtLeast(12).toByte()
}

/**
 * 构建离线地图视图：底图 + 初始中心/缩放。装饰（路线、标记）由调用方按需叠加。
 * 损坏的地图文件不进这里（调用前需校验）。
 */
internal fun buildOfflineMapView(
    context: Context,
    mapFile: File,
    centerLat: Double,
    centerLon: Double,
    zoom: Byte,
): org.mapsforge.map.android.view.MapView = buildOfflineMapViewMulti(
    context, listOf(mapFile), centerLat, centerLon, zoom,
)

/**
 * 多张底图叠加的地图视图：跨省路线（江西→上海）全程都有底图。
 * 每张 .map 一个 TileRendererLayer，互不重叠（省界框相交才入选），叠几层就渲染几省。
 * 各层独立 TileCache（id 带下标防共享冲突），装饰（路线、标记）由调用方按需叠加。
 */
internal fun buildOfflineMapViewMulti(
    context: Context,
    mapFiles: List<File>,
    centerLat: Double,
    centerLon: Double,
    zoom: Byte,
): org.mapsforge.map.android.view.MapView {
    ensureGraphicFactory(context)
    val mapView = org.mapsforge.map.android.view.MapView(context)
    mapView.isClickable = true
    mapFiles.forEachIndexed { i, file ->
        val tileCache = org.mapsforge.map.android.util.AndroidUtil.createTileCache(
            context,
            "lvyoubao-map-$i",
            mapView.model.displayModel.tileSize,
            1.0f,
            1.5,
        )
        val mapDataStore = runCatching { org.mapsforge.map.reader.MapFile(file) }.getOrNull() ?: return@forEachIndexed
        val rendererLayer = org.mapsforge.map.layer.renderer.TileRendererLayer(
            tileCache,
            mapDataStore,
            mapView.model.mapViewPosition,
            false,
            true,
            false,
            AndroidGraphicFactory.INSTANCE,
        )
        runCatching { rendererLayer.setXmlRenderTheme(org.mapsforge.map.rendertheme.InternalRenderTheme.DEFAULT) }
        mapView.layerManager.layers.add(rendererLayer)
    }
    // 内置缩放按钮位置固定在右下，会挡住自绘的「我的位置/全屏」等操作，统一关掉、由界面层自己画
    runCatching { mapView.setBuiltInZoomControls(false) }
    mapView.model.mapViewPosition.setCenter(LatLong(centerLat, centerLon))
    mapView.model.mapViewPosition.setZoomLevel(zoom)
    return mapView
}
