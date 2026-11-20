package com.lvyoubao.app.ui.spark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * 灵动模式图片统一解码：降采样至 ≤1280 + 按 EXIF 方向转正。
 * 系统相机返回的 JPEG 常带旋转标记（Camera2 裸抓帧不处理时照片直接是横的），相册照片同理。
 */
internal object SparkImageCodec {

    fun decodeUpright(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver

        // 注意：inJustDecodeBounds=true 的解码恒返回 null（只填 bounds.outWidth/outHeight），
        // 不能拿它的返回值判空——否则永远判「读取失败」（v0.8.77 曾踩坑，普通模式 v0.7.5 也踩过同一个）
        val boundsStream = resolver.openInputStream(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientationStream = resolver.openInputStream(uri) ?: return decoded
        val orientation = orientationStream.use { exifStream ->
            ExifInterface(exifStream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotation == 0f) decoded else {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
    }.getOrNull()
}
