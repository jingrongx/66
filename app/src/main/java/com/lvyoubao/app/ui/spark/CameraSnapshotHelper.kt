package com.lvyoubao.app.ui.spark

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 专为小屏/低内存设备（如 MT8167S / 1GB RAM）打造的即拍即走快照工具：
 * - 无需持久化 Camera 预览，零待机功耗与零后台占用；
 * - 仅在触发时打开 Camera2，向 ImageReader 单帧抓取 JPEG，完成后立刻释放所有相机资源；
 * - 自动压缩至 ~150KB，转换成 DeepSeek Vision 所需的 base64 data URL。
 */
object CameraSnapshotHelper {

    private const val TAG = "SparkCamera"

    data class SnapshotResult(
        val dataUrl: String,
        val bitmap: Bitmap,
    )

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun takeSnapshot(context: Context): SnapshotResult? = withTimeoutOrNull(7_000) {
        val rawBytes = captureSingleFrame(context) ?: return@withTimeoutOrNull null
        withContext(Dispatchers.IO) {
            processSnapshot(rawBytes)
        }
    }

    suspend fun takeSnapshotDataUrl(context: Context): String? = takeSnapshot(context)?.dataUrl

    @SuppressLint("MissingPermission")
    private suspend fun captureSingleFrame(context: Context): ByteArray? = suspendCancellableCoroutine { cont ->
        if (!hasCameraPermission(context)) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val thread = HandlerThread("SparkCameraThread").apply { start() }
        val handler = Handler(thread.looper)

        var cameraId: String? = null
        var chosenSize = Size(1024, 768)

        try {
            val ids = cameraManager.cameraIdList
            // 优先后置摄像头
            cameraId = ids.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.firstOrNull()

            if (cameraId != null) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                // 选择宽在 800~1600 间最接近 1280 的分辨率，兼顾识别清晰度与低内存
                chosenSize = sizes.filter { it.width in 800..1600 }
                    .minByOrNull { kotlin.math.abs(it.width - 1280) }
                    ?: sizes.firstOrNull()
                    ?: Size(1024, 768)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera characteristics", e)
        }

        if (cameraId == null) {
            thread.quitSafely()
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val imageReader = ImageReader.newInstance(chosenSize.width, chosenSize.height, ImageFormat.JPEG, 1)
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var isResumed = false

        fun cleanup() {
            try {
                captureSession?.close()
                cameraDevice?.close()
                imageReader.close()
                thread.quitSafely()
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup exception", e)
            }
        }

        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        if (cont.isActive) cont.resume(bytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read image buffer failed", e)
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        if (cont.isActive) cont.resume(null)
                    }
                } finally {
                    image.close()
                }
            }
        }, handler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        device.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        }
                                        session.capture(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Session capture failed", e)
                                        if (!isResumed) {
                                            isResumed = true
                                            cleanup()
                                            if (cont.isActive) cont.resume(null)
                                        }
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Configure capture session failed")
                                    if (!isResumed) {
                                        isResumed = true
                                        cleanup()
                                        if (cont.isActive) cont.resume(null)
                                    }
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createCaptureSession failed", e)
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "openCamera error: $error")
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera exception", e)
            if (!isResumed) {
                isResumed = true
                cleanup()
                if (cont.isActive) cont.resume(null)
            }
        }

        cont.invokeOnCancellation {
            cleanup()
        }
    }

    /** 降采样并压缩为高质量紧凑的 JPEG Base64 Data URL 以及用于界面预览的 Bitmap */
    private fun processSnapshot(rawBytes: ByteArray): SnapshotResult? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)

        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
            sample *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)

        val compressedBytes = out.toByteArray()
        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64"

        SnapshotResult(dataUrl = dataUrl, bitmap = bitmap)
    }.getOrNull()
}
