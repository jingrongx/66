package com.lvyoubao.app.ui.spark

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SparkModeScreen(
    app: TravelApp,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SparkViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { SparkViewModel(app) } } },
    )

    val eyeState by vm.eyeState.collectAsState()
    val statusHint by vm.statusHint.collectAsState()
    val scope = rememberCoroutineScope()

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.onEyeClicked(context) {}
        } else {
            vm.setTransientHint("需要麦克风权限才能语音对话")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.captureAndAnalyze(context)
        } else {
            vm.setTransientHint("需要相机权限才能拍照观察")
        }
    }

    // 按返回键退出灵动屏模式回到主界面
    BackHandler {
        vm.exitSparkMode(onExit)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14)) // 深邃沉稳的暗夜底色，适配小屏 OLED/LCD
            .pointerInput(eyeState) {
                detectTapGestures(
                    onTap = {
                        vm.onEyeClicked(context) {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDoubleTap = {
                        vm.onDoubleClicked(context) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onLongPress = {
                        vm.toggleMute()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 双眼动画核心画板
        CuteEyesCanvas(
            eyeState = eyeState,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部极简状态与提示浮层
        if (!statusHint.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color(0x77000000), shape = CircleShape)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    text = statusHint.orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 右上角退出小按钮
        IconButton(
            onClick = { vm.exitSparkMode(onExit) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(32.dp),
        ) {
            Text(
                text = "✕",
                color = Color.White.copy(alpha = 0.28f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 极简、可爱、充满灵气的动态机器人双眼绘制
 */
@Composable
private fun CuteEyesCanvas(
    eyeState: SparkEyeState,
    modifier: Modifier = Modifier,
) {
    // 基础呼吸循环动画（0.96f ~ 1.04f）
    val infiniteTransition = rememberInfiniteTransition(label = "breath")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathScale",
    )

    // 说话时的欢快律动
    val speakingBounce by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingBounce",
    )

    // 眨眼动画（高度缩放 1f -> 0.08f -> 1f）
    val blinkAnim = remember { Animatable(1f) }
    // 眼球朝向偏移（用于思考时的左右上方转动）
    val lookOffsetX = remember { Animatable(0f) }
    val lookOffsetY = remember { Animatable(0f) }

    // 随机眨眼协程
    LaunchedEffect(eyeState) {
        while (true) {
            if (eyeState != SparkEyeState.MUTED) {
                delay((2500..5500).random().toLong())
                // 快速眨眼
                blinkAnim.animateTo(0.08f, tween(110))
                blinkAnim.animateTo(1f, tween(130))
            } else {
                delay(1000)
            }
        }
    }

    // 状态切换时的眼神朝向变化
    LaunchedEffect(eyeState) {
        when (eyeState) {
            SparkEyeState.THINKING -> {
                // 思考时眼神望向右上
                lookOffsetX.animateTo(12f, tween(300))
                lookOffsetY.animateTo(-10f, tween(300))
            }
            SparkEyeState.LISTENING -> {
                // 倾听时直视前方且微动
                lookOffsetX.animateTo(0f, tween(200))
                lookOffsetY.animateTo(2f, tween(200))
            }
            else -> {
                lookOffsetX.animateTo(0f, tween(250))
                lookOffsetY.animateTo(0f, tween(250))
            }
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // 眼睛主色调：灵动冰青蓝，聆听时更亮微偏青白，静默时为温和紫蓝
        val eyeColor = when (eyeState) {
            SparkEyeState.LISTENING -> Color(0xFF64FFDA) // 青亮色
            SparkEyeState.THINKING -> Color(0xFF80D8FF)  // 浅海蓝
            SparkEyeState.SPEAKING -> Color(0xFF00E5FF)  // 活泼电光蓝
            SparkEyeState.MUTED -> Color(0xFF78909C)     // 沉静灰蓝
            SparkEyeState.CAPTURING -> Color(0xFFFFD54F) // 拍照快门金黄色
            SparkEyeState.IDLE -> Color(0xFF4EE2EC)      // 标志性灵动蓝
        }

        // 根据屏幕宽高自适应眼睛大小（针对 308x240 设计）
        val baseWidth = (size.width * 0.22f).coerceIn(48f, 85f)
        val baseHeight = (size.height * 0.38f).coerceIn(60f, 105f)
        val eyeSpacing = (size.width * 0.11f).coerceIn(24f, 44f)

        val dynamicScale = when (eyeState) {
            SparkEyeState.LISTENING -> 1.12f
            SparkEyeState.SPEAKING -> breathScale * 1.05f
            SparkEyeState.MUTED -> 0.92f
            else -> breathScale
        }

        val curWidth = baseWidth * dynamicScale
        val curHeight = baseHeight * dynamicScale * blinkAnim.value
        val offsetY = if (eyeState == SparkEyeState.SPEAKING) speakingBounce else 0f

        val leftEyeCenter = Offset(cx - eyeSpacing - curWidth / 2f + lookOffsetX.value, cy + offsetY + lookOffsetY.value)
        val rightEyeCenter = Offset(cx + eyeSpacing + curWidth / 2f + lookOffsetX.value, cy + offsetY + lookOffsetY.value)

        if (eyeState == SparkEyeState.MUTED) {
            // 静默模式：绘制两弯舒缓的小弧线（安睡状）
            drawSleepyEye(leftEyeCenter, curWidth)
            drawSleepyEye(rightEyeCenter, curWidth)
        } else {
            // 正常、聆听、思考、表达：绘制圆润的椭圆胶囊大眼 + 高光
            drawSingleEye(leftEyeCenter, curWidth, curHeight, eyeColor, eyeState)
            drawSingleEye(rightEyeCenter, curWidth, curHeight, eyeColor, eyeState)
        }
    }
}

/** 单只眼睛的胶囊外形与灵动高光绘制 */
private fun DrawScope.drawSingleEye(
    center: Offset,
    width: Float,
    height: Float,
    color: Color,
    state: SparkEyeState,
) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f

    // 眼睛主体（大圆角矩形）
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                color,
                color.copy(alpha = 0.88f),
            ),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )

    // 倾听状态时的呼吸光晕轮廓
    if (state == SparkEyeState.LISTENING) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset(left - 3f, top - 3f),
            size = Size(width + 6f, height + 6f),
            cornerRadius = CornerRadius((width + 6f) / 2f, (width + 6f) / 2f),
            style = Stroke(width = 2.5f),
        )
    }

    // 灵动高光：左上方主光点 + 右下方小微光，打造通透感
    if (height > width * 0.4f) {
        val highlightRadius = width * 0.18f
        val hx = center.x - width * 0.20f
        val hy = center.y - height * 0.22f

        drawCircle(
            color = Color.White.copy(alpha = 0.92f),
            radius = highlightRadius,
            center = Offset(hx, hy),
        )

        // 副高光
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = highlightRadius * 0.45f,
            center = Offset(center.x + width * 0.22f, center.y + height * 0.22f),
        )
    }
}

/** 静默安睡闭眼状态：两道柔和的月牙弯弧 */
private fun DrawScope.drawSleepyEye(center: Offset, width: Float) {
    val path = Path().apply {
        moveTo(center.x - width / 2f, center.y)
        quadraticTo(
            center.x,
            center.y + width * 0.35f,
            center.x + width / 2f,
            center.y,
        )
    }
    drawPath(
        path = path,
        color = Color(0xFF78909C),
        style = Stroke(width = 5.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}
