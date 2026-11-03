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
 * 挑一张覆盖所有关键点的离线地图；都不覆盖就退回最新一张（和离线地图页的默认选择一致）。
 * points 传 (经度, 纬度) 对。
 */
internal fun pickMapFile(context: Context, points: List<Pair<Double, Double>>): File? {
    val maps = File(context.filesDir, "maps")
        .listFiles { f -> f.name.endsWith(".map") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (maps.isEmpty()) return null
    val targets = points.filter { it.first != 0.0 || it.second != 0.0 }
    return maps.lastOrNull { m ->
        runCatching {
            val mf = org.mapsforge.map.reader.MapFile(m)
            try {
                val bb = mf.mapFileInfo.boundingBox
                targets.all { bb.contains(LatLong(it.second, it.first)) }
            } finally {
                runCatching { mf.close() }
            }
        }.getOrDefault(false)
    } ?: maps.last()
}

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
): org.mapsforge.map.android.view.MapView {
    ensureGraphicFactory(context)
    val mapView = org.mapsforge.map.android.view.MapView(context)
    mapView.isClickable = true
    val tileCache = org.mapsforge.map.android.util.AndroidUtil.createTileCache(
        context,
        "lvyoubao-map",
        mapView.model.displayModel.tileSize,
        1.0f,
        1.5,
    )
    val mapDataStore = org.mapsforge.map.reader.MapFile(mapFile)
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
    mapView.model.mapViewPosition.setCenter(LatLong(centerLat, centerLon))
    mapView.model.mapViewPosition.setZoomLevel(zoom)
    return mapView
}
