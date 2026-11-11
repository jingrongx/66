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
 * 定位精度圈：当前位置周围一圈半透明蓝，半径 = 定位误差半径（米），专业地图的标配——
 * 用户一眼看出这个点有多可信，误差大时不会误以为手机飘了。
 */
internal fun accuracyCircle(lat: Double, lon: Double, accuracyM: Float): org.mapsforge.map.layer.overlay.Circle? {
    val radius = accuracyM.coerceIn(15f, 3000f)
    return runCatching {
        val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(android.graphics.Color.argb(40, 13, 115, 133))
            setStyle(org.mapsforge.core.graphics.Style.FILL)
        }
        val stroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(android.graphics.Color.argb(90, 13, 115, 133))
            setStrokeWidth(2f)
            setStyle(org.mapsforge.core.graphics.Style.STROKE)
        }
        org.mapsforge.map.layer.overlay.Circle(LatLong(lat, lon), radius, fill, stroke)
    }.getOrNull()
}

/**
 * 某张 .map 在点位附近是否「真有瓦片数据」：
 * bbox 包含 ≠ 有数据——省界框会盖到邻省一角（浙江的框北缘就压着浦东），
 * 只下了邻省时点位照样落进邻省的框里，表现却是该区域一片空白（用户实锤「在上海看到的是浙江的框、灰的图」）；
 * 残缺文件（同步半截）文件头能过校验，瓦片区却是空的。所以真探一次瓦片：
 * z12 一片约 10 公里见方，城市/乡镇必有道路，退一级 z10（~40 公里）兜底荒野；
 * 有 POI 或道路才算「有图」。失败一律按没数据处理。
 */
internal fun mapHasDataAt(file: File, lat: Double, lon: Double): Boolean = runCatching {
    val mf = org.mapsforge.map.reader.MapFile(file)
    try {
        if (!mf.mapFileInfo.boundingBox.contains(lat, lon)) return@runCatching false
        for (z in 12 downTo 10) {
            val tx = MercatorProjection.longitudeToTileX(lon, z.toByte()).toInt()
            val ty = MercatorProjection.latitudeToTileY(lat, z.toByte()).toInt()
            val data = mf.readMapData(org.mapsforge.core.model.Tile(tx, ty, z.toByte(), 256))
            if (data.pointOfInterests.isNotEmpty() || data.ways.isNotEmpty()) return@runCatching true
        }
        false
    } finally {
        runCatching { mf.close() }
    }
}.getOrDefault(false)

/**
 * 全部已下载、头校验通过且「文件完整」的离线地图：底图渲染永远用全量叠加，
 * 「同一时间只显示一个省」的问题（路线在 A 省却渲染了 B 省的文件）从根上消失。
 * 完整性校验是关键：.map 文件头里记着声称的 fileSize——同步半截/中断落盘的残缺文件
 * 实际长度对不上（头能过打开校验、前段还有数据），一进渲染层对应区域就是灰板，
 * 瓦片探针还会被残存的前段数据骗成「有图」——这里直接剔除，让缺口报告把它当缺失省强制重下。
 */
internal fun allValidMaps(context: Context): List<File> =
    File(context.filesDir, "maps")
        .listFiles { f -> f.name.endsWith(".map") }
        ?.sortedBy { it.name }
        .orEmpty()
        .filter { f ->
            runCatching {
                val mf = org.mapsforge.map.reader.MapFile(f)
                try {
                    f.length() == mf.mapFileInfo.fileSize
                } finally {
                    runCatching { mf.close() }
                }
            }.getOrDefault(false)
        }

/**
 * 选图结果：全部可渲染的地图文件 + 关键点里没有被任何底图盖住的（含猜出的缺失省份）。
 * files 现在就是「全部已下载」——底图不再按路线挑省份，下载了哪省就显示哪省，
 * 缺口报告（uncovered/missingProvinces）只用来提示就地补下载。
 */
internal data class MapSelection(
    val files: List<File>,
    /** 没有任何已下载地图覆盖的关键点 (经度, 纬度) */
    val uncoveredPoints: List<Pair<Double, Double>>,
    /** 缺失的省份（中文，已排除已下载的，按点位邻近度排序） */
    val missingProvinces: List<String>,
)

/**
 * 全量底图 + 缺口报告：关键点（起终点）探不到任何已下载地图的瓦片数据时，
 * 把"哪片没图、缺哪个省"显式交回给界面，自动就地补下载，而不是静默留白让用户以为是 bug。
 * 覆盖判定用「真有瓦片数据」（mapHasDataAt）而不是省框包含——bbox 会盖到邻省一角、
 * 残缺文件能过文件头校验，两者的表现都是空白但按 bbox 判"有图"。
 * points 传 (经度, 纬度) 对。
 */
internal fun pickMapSelection(context: Context, points: List<Pair<Double, Double>>): MapSelection {
    val maps = allValidMaps(context)
    val targets = points.filter { it.first != 0.0 || it.second != 0.0 }
    // 每张图对关键点探数据（有数据即短路）；探得到数据的省才是真覆盖
    val provincesWithData = maps
        .filter { f -> targets.any { mapHasDataAt(f, it.second, it.first) } }
        .map { MapRegions.displayName(it.name) }
        .toSet()
    val uncovered = targets.filter { p -> maps.none { mapHasDataAt(it, p.second, p.first) } }
    val missing = uncovered
        .flatMap { MapRegions.guessProvinces(it.second, it.first) }
        // 排除「探得到数据」的省；有文件但探不到数据的省（残缺/只盖到邻省一角）保留——
        // 缺省补下载会强制重下该省，正好把坏文件治好
        .filter { it !in provincesWithData }
        .distinct()
    android.util.Log.i(
        "lvyoubao.ai",
        "底图${maps.size}张 真有数据省=$provincesWithData 未覆盖关键点=${uncovered.size} 缺失省份=$missing",
    )
    return MapSelection(maps, uncovered, missing)
}

/** 找一个能把所有点装下的缩放级别（留 18% 边距）；单点（地点卡「附近」）直接给街级 16 */
internal fun fitZoomLevel(widthPx: Int, heightPx: Int, points: List<LatLong>): Byte {
    if (widthPx <= 0 || heightPx <= 0 || points.isEmpty()) return 14
    if (points.size == 1) return 16
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
 * 叠放顺序关键：瓦片是不透明的——上层在自己包围盒内的空白瓦片会把下层的真实内容盖掉。
 * 把「包含视场中心的省」放在最上层（用户所在/要去的省优先显示），交界空白带被挤到邻省一侧；
 * 各层独立 TileCache（id 带下标防共享冲突），装饰（路线、标记）由调用方按需叠加。
 * 打不开的文件不再静默跳过，写日志（tag=lvyoubao.ai）——设备同步来的半截文件此前无声消失，
 * 症状恰是「该省区域一片空白」。
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
    val ordered = mapFiles.sortedBy { file ->
        runCatching {
            val mf = org.mapsforge.map.reader.MapFile(file)
            try {
                mf.mapFileInfo.boundingBox.contains(centerLat, centerLon)
            } finally {
                runCatching { mf.close() }
            }
        }.getOrDefault(false)
    }
    ordered.forEachIndexed { i, file ->
        val tileCache = org.mapsforge.map.android.util.AndroidUtil.createTileCache(
            context,
            "lvyoubao-map-$i",
            mapView.model.displayModel.tileSize,
            1.0f,
            1.5,
        )
        val mapDataStore = runCatching {
            val mf = org.mapsforge.map.reader.MapFile(file)
            android.util.Log.i(
                "lvyoubao.ai",
                "底图层$i=${file.name} bbox=${mf.mapFileInfo.boundingBox}",
            )
            mf
        }.getOrNull() ?: run {
            android.util.Log.i("lvyoubao.ai", "底图层$i=${file.name} 打不开（损坏或不完整），已跳过")
            return@forEachIndexed
        }
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
    // 比例尺只留公制：mapsforge 默认画两行（km + mi），中国人看英里反而添乱
    runCatching {
        val sb = mapView.mapScaleBar
        if (sb is org.mapsforge.map.scalebar.DefaultMapScaleBar) {
            sb.setScaleBarMode(org.mapsforge.map.scalebar.DefaultMapScaleBar.ScaleBarMode.SINGLE)
            sb.setDistanceUnitAdapter(org.mapsforge.map.scalebar.MetricUnitAdapter.INSTANCE)
            sb.setSecondaryDistanceUnitAdapter(org.mapsforge.map.scalebar.MetricUnitAdapter.INSTANCE)
        }
        sb.isVisible = true
    }
    mapView.model.mapViewPosition.setCenter(LatLong(centerLat, centerLon))
    mapView.model.mapViewPosition.setZoomLevel(zoom)
    return mapView
}
