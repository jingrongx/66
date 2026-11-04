package com.lvyoubao.app.ui.sync

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 生成二维码位图（白底黑码，保证暗色主题下也能扫） */
private fun generateQrBitmap(content: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        px,
        px,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(px * px)
    for (y in 0 until px) {
        for (x in 0 until px) {
            pixels[y * px + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, px, 0, 0, px, px)
    }
}

/** 二维码展示组件：白底圆角卡片内嵌码图 */
@Composable
fun QrCodeImage(content: String, size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content, 620) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "同步二维码",
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .size(size + 12.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(6.dp)
            .size(size),
    )
}
