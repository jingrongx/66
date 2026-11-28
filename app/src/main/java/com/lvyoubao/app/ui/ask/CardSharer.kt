package com.lvyoubao.app.ui.ask

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.lvyoubao.app.R
import com.lvyoubao.app.data.DeviceProfile
import com.lvyoubao.app.data.RichBlocks
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.common.drawTableBitmap
import com.lvyoubao.app.ui.common.renderDiagramBitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回答卡「分享」：把一条回答排版成竖版卡片图（PNG），可直接分享到微信或存入系统相册。
 * 正文用 RichBlocks 解析逐块渲染——表格、图表原样成图嵌入（复用表格卡/图表卡的导出绘制），
 * 宽表（≥4 列）自动转横版表格图再整体缩放放进正文，保证全貌可读。
 * 不依赖第三方 SDK：微信走系统 ACTION_SEND 指定包名拉起（拉不起来自动退回系统分享面板）；
 * 相册保存 Android 10+ 走 MediaStore，旧版本写公共 Pictures 目录后扫库入相册。
 */
object CardSharer {

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    // 配色与 app 主题呼应：近黑正文、浅灰辅助、白色卡面
    private const val CARD_BG = 0xFFFFFFFF.toInt()
    private const val TEXT_DARK = 0xFF1F2D33.toInt()
    private const val TEXT_GRAY = 0xFF8A9BA3.toInt()
    private const val DIVIDER = 0xFFDDE9EF.toInt()

    /** 卡片内容块：文字排版或子图（表格/图表），图块统一等比缩放到内容宽嵌入 */
    private class CardBlock(
        val layout: StaticLayout? = null,
        val bmp: Bitmap? = null,
        var drawW: Int = 0,
        var drawH: Int = 0,
        val gapAfter: Int,
    )

    /** 把回答内容画成卡片图；accent 传入主题色（品牌行与行动建议用）。表格/图表渲染较重，挂起执行 */
    suspend fun buildCardBitmap(context: Context, message: SessionMessage, accent: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val width = 1080
            val outer = 28      // 四周透明边距，白色圆角卡在聊天里更像一张卡片
            val pad = 64        // 卡内边距
            val contentWidth = width - pad * 2
            val appName = context.getString(R.string.app_name)

            val brandLayout = makeLayout("$appName · AI 助手", textPaint(accent, 46f, bold = true), contentWidth)
            val dateLayout = makeLayout(
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(message.ts)),
                textPaint(TEXT_GRAY, 34f),
                contentWidth,
                Layout.Alignment.ALIGN_OPPOSITE,
            )
            val footerLayout = makeLayout("由 $appName 生成", textPaint(TEXT_GRAY, 30f), contentWidth)
            val headerH = maxOf(brandLayout.height, dateLayout.height)

            // 正文按块排：文本块直接排版；表格块复用表格卡手绘（≥4 列用横版 1920 宽再缩放）；
            // 图表块经离屏 WebView 渲染成图（失败给占位文字，不挡分享）
            suspend fun blocksOf(fontPx: Float, stepFontPx: Float): List<CardBlock> {
                val blocks = mutableListOf<CardBlock>()
                RichBlocks.parse(message.text).forEach { b ->
                    when (b) {
                        is RichBlocks.Block.Text -> {
                            val t = markdownLite(b.text)
                            if (t.isNotBlank()) {
                                blocks.add(CardBlock(layout = makeLayout(t, textPaint(TEXT_DARK, fontPx), contentWidth), gapAfter = 26))
                            }
                        }
                        is RichBlocks.Block.Table -> {
                            val landscape = b.header.size >= 4 || b.rows.any { it.size >= 4 }
                            val sub = drawTableBitmap(b, landscape)
                            val scale = contentWidth.toFloat() / sub.width
                            blocks.add(CardBlock(bmp = sub, drawW = contentWidth, drawH = (sub.height * scale).toInt(), gapAfter = 30))
                        }
                        is RichBlocks.Block.Diagram -> {
                            val sub = renderDiagramBitmap(context, b.code, contentWidth)
                            if (sub != null) {
                                val scale = contentWidth.toFloat() / sub.width
                                blocks.add(CardBlock(bmp = sub, drawW = contentWidth, drawH = (sub.height * scale).toInt(), gapAfter = 30))
                            } else {
                                blocks.add(
                                    CardBlock(
                                        layout = makeLayout("〔图表内容建议到应用内查看〕", textPaint(TEXT_GRAY, fontPx * 0.85f), contentWidth),
                                        gapAfter = 26,
                                    ),
                                )
                            }
                        }
                    }
                }
                // 要点步骤 + 行动建议
                message.steps.forEachIndexed { i, s ->
                    blocks.add(
                        CardBlock(
                            layout = makeLayout("${i + 1}. $s", textPaint(TEXT_DARK, stepFontPx), contentWidth),
                            gapAfter = if (i == message.steps.lastIndex) 24 else 14,
                        ),
                    )
                }
                message.action?.takeIf { it.isNotBlank() }?.let {
                    blocks.add(CardBlock(layout = makeLayout(it, textPaint(accent, stepFontPx, bold = true), contentWidth), gapAfter = 0))
                }
                return blocks
            }

            val blocks = blocksOf(42f, 38f)

            // 两遍绘制：先排版量高，再画图（文字长度不定，竖图高度自适应）
            val contentH = blocks.sumOf { ((it.layout?.height ?: it.drawH)) + it.gapAfter }
            val height = outer + pad + headerH + 28 + 2 + 36 + contentH + 40 + footerLayout.height + pad + outer
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawRoundRect(
                RectF(outer.toFloat(), outer.toFloat(), (width - outer).toFloat(), (height - outer).toFloat()),
                44f, 44f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BG },
            )

            fun draw(layout: StaticLayout, x: Int, y: Int) {
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }

            var y = outer + pad
            draw(brandLayout, pad, y)
            draw(dateLayout, pad, y + (headerH - dateLayout.height) / 2)
            y += headerH + 28
            canvas.drawRect(pad.toFloat(), y.toFloat(), (width - pad).toFloat(), (y + 2).toFloat(), Paint().apply { color = DIVIDER })
            y += 2 + 36
            blocks.forEach { block ->
                val h = block.layout?.height ?: block.drawH
                if (block.layout != null) {
                    draw(block.layout, pad, y)
                } else if (block.bmp != null) {
                    // 表格/图表子图等比缩放嵌入，用完立即回收（大表子图占内存，不能等 GC）
                    canvas.drawBitmap(block.bmp, null, RectF(pad.toFloat(), y.toFloat(), (pad + block.drawW).toFloat(), (y + block.drawH).toFloat()), null)
                    block.bmp.recycle()
                }
                y += h + block.gapAfter
            }
            draw(footerLayout, pad, height - outer - pad - footerLayout.height)
            bitmap
        }

    /** 是否运行在鸿蒙 NEXT 的卓易通安卓容器里（统一委托给 [DeviceProfile.isZhuoyiContainer]）。 */
    fun isZhuoyiContainer(context: Context): Boolean = DeviceProfile.isZhuoyiContainer(context)

    /**
     * 分享图片到微信：先探测微信能否被安卓层拉起——
     * 鸿蒙卓易通里微信是原生应用，安卓层够不着，硬闯 setPackage 会被容器拦下并弹「卓易通内暂不支持此功能」，
     * 检测到容器环境不试探，直接走系统分享面板（卓易通会把面板桥接到鸿蒙分享，里面有微信）。
     * 返回 true=已拉起微信；false=走了系统分享面板。
     */
    fun shareToWeChat(context: Context, bitmap: Bitmap): Boolean {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, writeCacheImage(context, bitmap))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (isZhuoyiContainer(context)) {
            openChooser(context, base)
            return false
        }
        val wechatReady = runCatching {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage(WECHAT_PACKAGE)
            }.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        return if (wechatReady) {
            try {
                context.startActivity(base.apply { setPackage(WECHAT_PACKAGE) })
                true
            } catch (_: Exception) {
                openChooser(context, base)
                false
            }
        } else {
            openChooser(context, base)
            false
        }
    }

    private fun openChooser(context: Context, base: Intent) {
        runCatching {
            context.startActivity(Intent.createChooser(base, "分享卡片图"))
        }
    }

    /**
     * 保存到系统相册「Pictures/旅游宝」，返回保存后的文件名，失败返回 null。
     * Android 9 及以下需先授予 WRITE_EXTERNAL_STORAGE（调用方负责申请）。
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = "旅游宝卡片_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/旅游宝")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ?: return@runCatching null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                name
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "旅游宝")
                if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                val file = File(dir, name)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                name
            }
        }.getOrNull()
    }

    /** 卡片图落盘到 FileProvider 已声明的 cache/images 目录，返回可分享的 content uri */
    private fun writeCacheImage(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "card_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** 文本块的轻量去 Markdown：标题井号、加粗星号、行内代码反引号、列表短横线（表格/图表已按块单独处理） */
    private fun markdownLite(raw: String): String = raw
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "· ")

    /** StaticLayout 构建：API 23+ 用 Builder，旧车机（minSdk 21）回退旧构造 */
    private fun makeLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1.45f)
            .setIncludePad(true)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, width, align, 1.45f, 0f, true)
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}
