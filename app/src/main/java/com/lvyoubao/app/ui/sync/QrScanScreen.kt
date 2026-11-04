package com.lvyoubao.app.ui.sync

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.hardware.Camera

/**
 * 扫码界面：全屏相机预览 + ZXing 连续解码。
 * 用 Camera1 老接口而非 CameraX：车机/旧手机上零依赖、兼容 API 21+。
 */
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !granted -> PermissionHint("扫码需要相机权限。可以在系统设置里手动开启后重试。", onClose)
            cameraError != null -> PermissionHint(cameraError ?: "相机不可用", onClose)
            else -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        QrCameraSurface(
                            ctx = ctx,
                            onQrText = { text ->
                                onResult(text)
                            },
                            onCameraError = { message -> cameraError = message },
                        )
                    },
                )
            }
        }

        // 顶部提示与关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "对准车机上的同步二维码",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PermissionHint(message: String, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Text(message, color = Color.White)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onClose) { Text("返回", color = Color.White) }
    }
}

/** SurfaceView + Camera1 + ZXing 解码循环 */
private class QrCameraSurface(
    ctx: android.content.Context,
    private val onQrText: (String) -> Unit,
    private val onCameraError: (String) -> Unit,
) : SurfaceView(ctx), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private val appContext = ctx.applicationContext
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        openCameraAndStart(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    @Suppress("DEPRECATION")
    private fun openCameraAndStart(holder: SurfaceHolder) {
        try {
            val cam = Camera.open()
            val params = cam.parameters

            // 选一个分辨率适中的预览尺寸（解码用原始帧，与显示拉伸无关）
            val sizes = params.supportedPreviewSizes ?: emptyList()
            val previewSize = sizes
                .filter { it.width * it.height <= 1280 * 960 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.firstOrNull()
            previewSize?.let { params.setPreviewSize(it.width, it.height) }

            // 连续对焦：车机屏幕距离 10~30cm 也能对上
            val focusModes = params.supportedFocusModes ?: emptyList()
            when {
                focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                focusModes.contains(Camera.Parameters.FOCUS_MODE_MACRO) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_MACRO
            }
            cam.parameters = params

            // 按当前屏幕方向修正预览方向
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(0, info)
            val rotation = (appContext.getSystemService(android.app.Activity::class.java) as? Activity)?.windowManager?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
            val degrees = when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                else -> 270
            }
            val displayAngle = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (info.orientation + degrees) % 360
            } else {
                (info.orientation - degrees + 360) % 360
            }
            cam.setDisplayOrientation(displayAngle)

            cam.setPreviewDisplay(holder)
            cam.startPreview()
            camera = cam

            fun armDecode() {
                if (delivered.get()) return
                try {
                    cam.setOneShotPreviewCallback { data, callbackCamera ->
                        decodeExecutor.execute {
                            if (!delivered.get()) {
                                val size = callbackCamera.parameters.previewSize
                                if (size != null) {
                                    val text = decodeFrame(data, size.width, size.height)
                                    if (text != null && delivered.compareAndSet(false, true)) {
                                        onQrText(text)
                                        return@execute
                                    }
                                }
                                armDecode()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            armDecode()
        } catch (_: Exception) {
            onCameraError("本机没有可用的摄像头。扫码请在手机上使用。")
        }
    }

    private fun decodeFrame(data: ByteArray, width: Int, height: Int): String? {
        return try {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
            reader.reset()
            result.text
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseCamera() {
        try {
            camera?.stopPreview()
        } catch (_: Exception) {
        }
        try {
            camera?.setOneShotPreviewCallback(null)
        } catch (_: Exception) {
        }
        try {
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
        decodeExecutor.shutdown()
    }
}
