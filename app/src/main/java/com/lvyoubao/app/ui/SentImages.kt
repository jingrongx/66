package com.lvyoubao.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话里「已发送图片」的展示：消息里持久化的是压缩图文件路径（filesDir/sent_images），
 * 气泡里显示小缩略图，点开全屏看大图，点任意位置关闭。
 * 旧版本消息没有图片文件（只有 hadImage 标记），保持「🖼+文字」的老样子。
 */

/** 用户气泡里的缩略图行：每张可点开大图 */
@Composable
fun SentImageThumbnails(paths: List<String>, modifier: Modifier = Modifier, thumbSize: Dp = 120.dp) {
    if (paths.isEmpty()) return
    var viewing by remember { mutableStateOf<String?>(null) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.take(4).forEach { path ->
            SentImageThumb(path = path, size = thumbSize) { viewing = path }
        }
    }
    viewing?.let { path ->
        FullScreenImageViewer(path = path, onDismiss = { viewing = null })
    }
}

@Composable
private fun SentImageThumb(path: String, size: Dp, onClick: () -> Unit) {
    val bmp = rememberDecodedBitmap(path = path, target = 320)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = bmp
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "发送的图片，点击查看大图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 解码中或文件已不在（旧消息/被清理）：回退成和以前一样的图标占位
            Text("🖼", fontSize = 26.sp)
        }
    }
}

/** 后台解码本地图片文件，按 target 降采样，失败返回 null（不卡主线程） */
@Composable
private fun rememberDecodedBitmap(path: String, target: Int): Bitmap? {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.exists() || file.length() <= 0) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    return bmp
}

/** 全屏看大图：点任意位置关闭（长辈用户一个动作就退出，不需要手势教学） */
@Composable
private fun FullScreenImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val bmp = rememberDecodedBitmap(path = path, target = 2048)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = bmp
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "图片大图",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            Text(
                "点任意位置关闭",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}
