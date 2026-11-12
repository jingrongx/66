package com.lvyoubao.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.lvyoubao.app.data.RichBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回答正文富内容渲染（v0.8.48）：把 [RichBlocks] 解析出的块逐个渲染——
 * 普通文本保持原样式；表格→表格卡（可全屏/横屏、存 CSV/图片）；图表→图表卡（可全屏缩放、存图片）。
 */
@Composable
fun RichContent(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    largeText: Boolean = false,
) {
    val blocks = remember(text) { RichBlocks.parse(text) }
    val style = TextStyle(fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight)
    Column {
        blocks.forEach { block ->
            when (block) {
                is RichBlocks.Block.Text -> Text(block.text, style = style)
                is RichBlocks.Block.Table -> TableCard(block, largeText = largeText)
                is RichBlocks.Block.Diagram -> DiagramCard(block, largeText = largeText)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/* ================================ 表格卡 ================================ */

/** 表格卡（内嵌预览 + 点击全屏）：全屏里可横屏、双表头锁定、存 CSV、存图片 */
@Composable
fun TableCard(table: RichBlocks.Block.Table, largeText: Boolean) {
    var fullscreen by remember { mutableStateOf(false) }
    val cellFont = if (largeText) 17.sp else 15.sp
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { fullscreen = true },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "📋 表格 · ${table.rows.size}行 × ${table.header.size}列",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "点开全屏 ↗",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            val hScroll = rememberScrollState()
            val previewWidths = remember(table, cellFont) { tableColWidths(table, cellFont, maxChars = 10) }
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 表头行
                Row(Modifier.horizontalScroll(hScroll)) {
                    table.header.forEachIndexed { c, cell ->
                        Box(
                            Modifier
                                .width(colWidthAt(previewWidths, c))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                cell,
                                fontSize = cellFont,
                                lineHeight = cellFont * 1.4f,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                // 数据行（预览最多 5 行，全的进全屏）
                val previewRows = table.rows.take(5)
                previewRows.forEachIndexed { idx, row ->
                    Row(Modifier.horizontalScroll(hScroll)) {
                        row.forEachIndexed { c, cell ->
                            Box(
                                Modifier
                                    .width(colWidthAt(previewWidths, c))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    cell,
                                    fontSize = cellFont,
                                    lineHeight = cellFont * 1.4f,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (idx != previewRows.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    }
                }
            }
            if (table.rows.size > 5) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "共 ${table.rows.size} 行，点开全屏查看全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (fullscreen) TableFullscreenDialog(table = table, largeText = largeText) { fullscreen = false }
}

/**
 * 列宽组：按整列最长文本估算（同列等宽对齐），CJK 一字 ≈ 字号宽。
 * 预览窄（10 字封顶）；全屏 26 字封顶，且给 [fillWidth] 时列总宽不足就按比例放大铺满可用宽度
 * ——横屏大屏上不再右边一大片空白，长文本靠单元格内换行消化。
 */
private fun tableColWidths(
    table: RichBlocks.Block.Table,
    fontSize: TextUnit,
    maxChars: Int,
    fillWidth: androidx.compose.ui.unit.Dp? = null,
): List<androidx.compose.ui.unit.Dp> {
    val colCount = table.header.size.coerceAtLeast(1)
    val maxLens = IntArray(colCount) { 2 }
    (listOf(table.header) + table.rows).forEach { row ->
        row.forEachIndexed { c, cell -> if (c < colCount) maxLens[c] = maxLens[c].coerceAtLeast(cell.length) }
    }
    val widths = maxLens.map { len -> (len.coerceAtMost(maxChars) * fontSize.value + 20).dp }
    val fill = fillWidth ?: return widths
    val sum = widths.fold(0.dp) { acc, w -> acc + w }
    if (sum >= fill || sum.value <= 0f) return widths
    val k = fill.value / sum.value
    return widths.map { (it.value * k).dp }
}

private fun colWidthAt(widths: List<androidx.compose.ui.unit.Dp>, index: Int): androidx.compose.ui.unit.Dp =
    widths.getOrElse(index) { 120.dp }

@Composable
private fun TableFullscreenDialog(table: RichBlocks.Block.Table, largeText: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var landscape by remember { mutableStateOf(false) }
    var savedTip by remember { mutableStateOf<String?>(null) }

    // 横屏开关：锁横屏看宽表，关掉/退出恢复系统朝向（Activity 已声明 configChanges，旋转不重建、对话框不丢）
    DisposableEffect(landscape) {
        val activity = context as? Activity
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val cellFont = if (largeText) 19.sp else 16.sp
    val hScroll = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // 列宽按可用宽度铺满（横屏/平板不再右边一大片空白），列内长文本自动换行
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val fillWidth = maxWidth - 24.dp
                val colWidths = remember(table, cellFont, fillWidth) {
                    tableColWidths(table, cellFont, maxChars = 26, fillWidth = fillWidth)
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "📋 表格 · ${table.rows.size}行 × ${table.header.size}列",
                        fontSize = if (largeText) 20.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        landscape = !landscape
                    }) { Text(if (landscape) "竖屏" else "横屏", fontSize = if (largeText) 18.sp else 15.sp) }
                    TextButton(onClick = {
                        scope.launch {
                            val tip = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = File(context.filesDir, "exports").apply { mkdirs() }
                                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val f = File(dir, "表格_$stamp.csv")
                                    f.writeText(tableToCsv(table))
                                    shareExport(context, f, "text/csv", "导出表格 CSV")
                                    "已生成 CSV，去分享里保存"
                                }.getOrElse { "导出失败：${it.message ?: "未知"}" }
                            }
                            savedTip = tip
                        }
                    }) { Text("存CSV", fontSize = if (largeText) 18.sp else 15.sp) }
                    TextButton(onClick = {
                        scope.launch {
                            val tip = withContext(Dispatchers.IO) {
                                runCatching {
                                    // 方向跟随当前查看：竖屏导竖版图，横屏导横版图
                                    val isLandscape = context.resources.configuration.orientation ==
                                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                    val bmp = drawTableBitmap(table, isLandscape)
                                    shareBitmap(context, bmp, "表格", "保存表格图片")
                                    "已生成图片，去分享里保存"
                                }.getOrElse { "导出失败：${it.message ?: "未知"}" }
                            }
                            savedTip = tip
                        }
                    }) { Text("存图片", fontSize = if (largeText) 18.sp else 15.sp) }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", fontSize = if (largeText) 18.sp else 15.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                savedTip?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                // 表头锁定在顶部（不随上下滚动），左右与数据行共用同一横向滚动；单元格文字换行不截断
                Row(Modifier.horizontalScroll(hScroll)) {
                    table.header.forEachIndexed { c, cell ->
                        Box(
                            Modifier
                                .width(colWidthAt(colWidths, c))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(
                                cell,
                                fontSize = cellFont,
                                lineHeight = cellFont * 1.4f,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    table.rows.forEachIndexed { idx, row ->
                        Row(Modifier.horizontalScroll(hScroll)) {
                            row.forEachIndexed { c, cell ->
                                Box(
                                    Modifier
                                        .width(colWidthAt(colWidths, c))
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        cell,
                                        fontSize = cellFont,
                                        lineHeight = cellFont * 1.4f,
                                    )
                                }
                            }
                        }
                        if (idx != table.rows.size - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        }
                    }
                }
                }
            }
        }
    }
}

/** 表格 → CSV（带 UTF-8 BOM，Excel/干系表格软件打开不乱码） */
private fun tableToCsv(t: RichBlocks.Block.Table): String = buildString {
    append("\uFEFF")
    fun row(cells: List<String>) {
        append(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"").replace("\n", " ") + "\"" })
        append("\r\n")
    }
    row(t.header)
    t.rows.forEach { row(it) }
}

/**
 * 表格 → 位图（导出图片用）：android.graphics 手绘整表，单元格长文本用 StaticLayout 自动换行，
 * 绝不省略号截断——全貌都在。方向跟随当前查看：竖屏查看导竖版（1080 宽），横屏查看导横版（1920 宽）。
 * 表头主色底白字、斑马行、细网格；总高超 14000px 时按比例缩小字号重排（防极端大表撑爆内存）。
 */
private fun drawTableBitmap(table: RichBlocks.Block.Table, landscape: Boolean): Bitmap {
    val targetWidth = if (landscape) 1920 else 1080
    val padH = 26f
    val padV = 22f
    var fontPx = if (landscape) 58f else 44f
    val colCount = table.header.size.coerceAtLeast(1)
    val allRows = listOf(table.header) + table.rows

    // 列宽：按单行自然宽度分配，总宽趋近目标宽（铺满不留窄条；超宽表压回目标 1.6 倍内，靠换行消化）
    fun colWidthsFor(f: Float): FloatArray {
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = f }
        val w = FloatArray(colCount) { 140f }
        allRows.forEach { row ->
            row.forEachIndexed { c, cell ->
                if (c < colCount) {
                    val natural = measure.measureText(cell) + padH * 2
                    w[c] = w[c].coerceAtLeast(natural.coerceAtMost(targetWidth * 0.7f))
                }
            }
        }
        val total = w.sum()
        if (total < targetWidth) {
            val k = targetWidth / total
            for (i in 0 until colCount) w[i] *= k
        } else if (total > targetWidth * 1.6f) {
            val k = targetWidth * 1.6f / total
            for (i in 0 until colCount) w[i] = (w[i] * k).coerceAtLeast(170f)
        }
        return w
    }

    fun cellLayout(text: String, widthPx: Float, f: Float, bold: Boolean): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = f
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        @Suppress("DEPRECATION")
        return StaticLayout(
            text, tp, widthPx.toInt().coerceAtLeast(1),
            android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 4f, true,
        )
    }

    // 布局两遍：先算行高，总高超限就缩字号重排
    var colWidths = colWidthsFor(fontPx)
    var rowLayouts: List<List<StaticLayout>> = emptyList()
    var rowHeights = FloatArray(0)
    var totalHeight = 0f
    repeat(2) {
        rowLayouts = allRows.map { row ->
            List(colCount) { c ->
                val cell = row.getOrElse(c) { "" }
                cellLayout(cell, colWidths[c] - padH * 2, fontPx, bold = row === allRows[0])
            }
        }
        rowHeights = FloatArray(allRows.size) { r -> rowLayouts[r].maxOf { it.height } + padV * 2 }
        totalHeight = rowHeights.sum()
        if (totalHeight > 14_000f) {
            fontPx *= 14_000f / totalHeight
            colWidths = colWidthsFor(fontPx)
        }
    }
    val width = colWidths.sum().toInt().coerceAtLeast(1)
    val height = totalHeight.toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val headerBg = Paint().apply { color = 0xFF1A73E8.toInt() }
    val zebra = Paint().apply { color = 0xFFF3F6FA.toInt() }
    val grid = Paint().apply { color = 0xFFD8DCE2.toInt(); strokeWidth = 2f }

    // 背景（表头蓝底 + 斑马行）
    var top = 0f
    allRows.forEachIndexed { r, _ ->
        val h = rowHeights[r]
        when {
            r == 0 -> canvas.drawRect(0f, top, width.toFloat(), top + h, headerBg)
            r % 2 == 0 -> canvas.drawRect(0f, top, width.toFloat(), top + h, zebra)
        }
        top += h
    }
    // 文本（StaticLayout 自动换行）
    top = 0f
    allRows.forEachIndexed { r, _ ->
        var x = 0f
        rowLayouts[r].forEachIndexed { c, layout ->
            canvas.save()
            canvas.translate(x + padH, top + padV)
            layout.draw(canvas)
            canvas.restore()
            x += colWidths[c]
        }
        top += rowHeights[r]
    }
    // 网格
    var gx = 0f
    for (c in 0..colCount) {
        canvas.drawLine(gx, 0f, gx, height.toFloat(), grid)
        if (c < colCount) gx += colWidths[c]
    }
    var gy = 0f
    allRows.indices.forEach { r ->
        canvas.drawLine(0f, gy, width.toFloat(), gy, grid)
        gy += rowHeights[r]
    }
    canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, grid)
    return bmp
}

/* ================================ 图表卡 ================================ */

/** 图表卡（内嵌预览 + 点击全屏）：全屏里双指缩放/拖动，可存图片 */
@Composable
fun DiagramCard(diagram: RichBlocks.Block.Diagram, largeText: Boolean) {
    var fullscreen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { fullscreen = true },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "📈 图表（流程图/思维导图）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "点开全屏 ↗",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            MermaidView(
                code = diagram.code,
                interactive = false,
                modifier = Modifier.fillMaxWidth().height(if (largeText) 280.dp else 220.dp),
            )
        }
    }
    if (fullscreen) DiagramFullscreenDialog(diagram = diagram, largeText = largeText) { fullscreen = false }
}

@Composable
private fun DiagramFullscreenDialog(diagram: RichBlocks.Block.Diagram, largeText: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = rememberMermaidHolder(interactive = true)
    var savedTip by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "📈 图表 · 双指缩放，可拖动",
                        fontSize = if (largeText) 20.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val tip = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bmp = captureWebView(holder.webView)
                                    shareBitmap(context, bmp, "图表", "保存图表图片")
                                    "已生成图片，去分享里保存"
                                }.getOrElse { "导出失败：${it.message ?: "未知"}" }
                            }
                            savedTip = tip
                        }
                    }) { Text("存图片", fontSize = if (largeText) 18.sp else 15.sp) }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", fontSize = if (largeText) 18.sp else 15.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                savedTip?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                }
                MermaidView(
                    holder = holder,
                    code = diagram.code,
                    interactive = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                )
            }
        }
    }
}

/**
 * mermaid 渲染管理器：持有 WebView，加载本地 assets 页（mermaid.min.js 离线渲染），
 * 页面就绪前收到的代码先排队。WebView 固定软件层绘制——存图片走 draw(Canvas) 必须软件渲染。
 */
private class MermaidHolder(val webView: WebView) {
    var ready = false
    var pending: String? = null
    var lastCode: String? = null

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true     // 全屏双指缩放
            displayZoomControls = false    // 不显示缩放按钮
        }
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                pending?.let { render(it) }
                pending = null
            }
        }
        webView.loadUrl("file:///android_asset/mermaid.html")
    }

    fun render(code: String) {
        lastCode = code
        if (!ready) {
            pending = code
            return
        }
        // org.json 的 quote 负责转义换行/引号，拼 JS 调用安全
        webView.evaluateJavascript("renderDiagram(" + org.json.JSONObject.quote(code) + ")", null)
    }
}

@Composable
private fun rememberMermaidHolder(interactive: Boolean): MermaidHolder {
    val context = LocalContext.current
    val holder = remember { MermaidHolder(WebView(context)) }
    // 内嵌预览不响应触摸（缩放/滚动交给全屏），点击开全屏由外层卡接手
    holder.webView.isEnabled = interactive
    DisposableEffect(Unit) { onDispose { holder.webView.destroy() } }
    return holder
}

@Composable
private fun MermaidView(
    code: String,
    interactive: Boolean,
    modifier: Modifier,
    holder: MermaidHolder = rememberMermaidHolder(interactive),
) {
    AndroidView(factory = { holder.webView }, modifier = modifier)
    LaunchedEffect(code) { holder.render(code) }
}

/** WebView 全页截图：软件层 + 内容高度，比可视区高也能整张拿到（上限 12000px 防爆内存） */
private fun captureWebView(webView: WebView): Bitmap {
    val scale = webView.resources.displayMetrics.density
    val contentH = (webView.contentHeight * scale).toInt()
    val w = webView.width.coerceAtLeast(1)
    val h = contentH.coerceAtLeast(webView.height).coerceAtMost(12_000)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    webView.draw(Canvas(bmp))
    return bmp
}

/* ================================ 导出分享 ================================ */

/** 经 FileProvider 分享导出文件（filesDir/exports 与 file_paths.xml 的 exports/ 对应） */
private fun shareExport(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 位图落盘到导出目录（file_paths 的 files-path exports/，FileProvider 才能发出去），再拉起分享 */
private fun shareBitmap(context: Context, bmp: Bitmap, namePrefix: String, title: String) {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = File(context.filesDir, "exports").apply { mkdirs() }
    val f = File(dir, "${namePrefix}_$stamp.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    shareExport(context, f, "image/png", title)
}
