package com.lvyoubao.app.ui.ask

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import com.lvyoubao.app.data.SessionMessage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回答卡「分享」：把一条回答排版成竖版卡片图（PNG），可直接分享到微信或存入系统相册。
 * 不依赖第三方 SDK：微信走系统 ACTION_SEND 指定包名拉起，没装微信自动退回系统分享面板；
 * 相册保存 Android 10+ 走 MediaStore，旧版本写公共 Pictures 目录后扫库入相册。
 */
object CardSharer {

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    // 配色与 app 主题呼应：近黑正文、浅灰辅助、白色卡面
    private const val CARD_BG = 0xFFFFFFFF.toInt()
    private const val TEXT_DARK = 0xFF1F2D33.toInt()
    private const val TEXT_GRAY = 0xFF8A9BA3.toInt()
    private const val DIVIDER = 0xFFDDE9EF.toInt()

    /** 把回答内容画成卡片图；accent 传入主题色（品牌行与行动建议用） */
    fun buildCardBitmap(context: Context, message: SessionMessage, accent: Int): Bitmap {
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
        val bodyLayout = makeLayout(markdownLite(message.text), textPaint(TEXT_DARK, 42f), contentWidth)
        val stepLayouts = message.steps.mapIndexed { i, s ->
            makeLayout("${i + 1}. $s", textPaint(TEXT_DARK, 38f), contentWidth)
        }
        val actionLayout = message.action?.takeIf { it.isNotBlank() }
            ?.let { makeLayout(it, textPaint(accent, 38f, bold = true), contentWidth) }
        val footerLayout = makeLayout("由 $appName 生成", textPaint(TEXT_GRAY, 30f), contentWidth)

        // 两遍绘制：先排版量高，再画图（文字长度不定，竖图高度自适应）
        val headerH = maxOf(brandLayout.height, dateLayout.height)
        var contentH = 0
        if (message.text.isNotBlank()) contentH += bodyLayout.height + 32
        stepLayouts.forEachIndexed { i, l -> contentH += l.height + (if (i == stepLayouts.lastIndex) 0 else 14) }
        if (stepLayouts.isNotEmpty()) contentH += 24
        actionLayout?.let { contentH += it.height + 24 }
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
        if (message.text.isNotBlank()) {
            draw(bodyLayout, pad, y)
            y += bodyLayout.height + 32
        }
        stepLayouts.forEachIndexed { i, l ->
            draw(l, pad, y)
            y += l.height + (if (i == stepLayouts.lastIndex) 24 else 14)
        }
        actionLayout?.let {
            draw(it, pad, y)
            y += it.height + 24
        }
        draw(footerLayout, pad, height - outer - pad - footerLayout.height)
        return bitmap
    }

    /**
     * 分享图片到微信：写入缓存后经 FileProvider 拉起微信（选好友/朋友圈发送）。
     * 返回 true=已拉起微信；false=没装微信，已自动退回系统分享面板。
     */
    fun shareToWeChat(context: Context, bitmap: Bitmap): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, writeCacheImage(context, bitmap))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(send.apply { setPackage(WECHAT_PACKAGE) })
            true
        } catch (_: ActivityNotFoundException) {
            // 没装微信（或被系统限制拉起）→ 退回系统分享面板，用户自行选应用
            runCatching {
                context.startActivity(
                    Intent.createChooser(send.apply { setPackage(null) }, "分享卡片图"),
                )
            }
            false
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

    /** 轻量去 Markdown：标题井号、加粗星号、行内代码反引号、列表短横线，让卡片图文案更像排版过的海报 */
    private fun markdownLite(raw: String): String = raw
        .replace(Regex("```[a-zA-Z0-9]*\\n?"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
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
