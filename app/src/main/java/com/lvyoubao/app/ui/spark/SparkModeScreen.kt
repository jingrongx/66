package com.lvyoubao.app.ui.spark

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import kotlinx.coroutines.delay

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
    val showEyes by vm.showEyes.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val recordTick by vm.recordTick.collectAsState()
    val lastCapturedBitmap by vm.lastCapturedBitmap.collectAsState()
    val userQuestion by vm.userQuestion.collectAsState()
    val aiAnswer by vm.aiAnswer.collectAsState()
    val statusHint by vm.statusHint.collectAsState()

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startHoldTalk(context) {}
        } else {
            vm.setTransientHint("需要麦克风权限才能录音")
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

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            vm.handlePickedImage(context, uri)
        }
    }

    // 返回键退出灵动屏模式回到普通界面
    BackHandler {
        vm.exitSparkMode(onExit)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)), // 深邃科技黑底色
    ) {
        // ---------- 顶部状态与退出栏（高度 ~28dp，专为 308x240 小屏适配） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧状态指示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isRecording) Color(0xFFFF5252) else Color(0xFF00E5FF),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        isRecording -> "录音中 ${recordTick}s"
                        !statusHint.isNullOrBlank() -> statusHint.orEmpty()
                        showEyes -> "灵动屏"
                        else -> "会话内容"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(160.dp),
                )
            }

            // 右侧操作：切换视图 + 显眼退出按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 切换眼睛 / 会话文字
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { vm.toggleShowEyes() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (showEyes) "看文字" else "看眼睛",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 显眼的退出按钮
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.exitSparkMode(onExit) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹ 退出",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ---------- 中间主呈现区（权重 1f）：按住说话显示眼睛动画，回答时显示会话框 ----------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = showEyes,
                transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(200))) },
                label = "MainContent",
            ) { isEyesMode ->
                if (isEyesMode) {
                    // 眼睛动画展示
                    CuteEyesCanvas(
                        eyeState = eyeState,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 会话框展示（含拍照缩略图与文字）
                    ConversationCard(
                        photo = lastCapturedBitmap,
                        userQuestion = userQuestion,
                        aiAnswer = aiAnswer,
                    )
                }
            }
        }

        // ---------- 底部操作区（高度 ~42dp）：相机按钮 + 相册按钮 + 按住说话大按钮 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 📷 拍照提问按钮
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable {
                        if (CameraSnapshotHelper.hasCameraPermission(context)) {
                            vm.captureAndAnalyze(context)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("📷", fontSize = 18.sp)
            }

            // 🖼️ 相册选图提问按钮
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable {
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🖼", fontSize = 18.sp)
            }

            // 🎙️ 按住说话按钮（操作逻辑同常规/大字模式：按住即录，松手即发，按住时显示萌眼）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                vm.startHoldTalk(context) {
                                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    vm.endHoldTalk(context)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isRecording) "松手发送 (${recordTick}s)" else "按住说话",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 会话框展示：
 * - 顶部若有拍照，显示拍照缩略图（满足"界面能看出拍了什么"的需求）；
 * - 用户提问简述；
 * - AI 回答滚屏与朗读同步展示。
 */
@Composable
private fun ConversationCard(
    photo: Bitmap?,
    userQuestion: String?,
    aiAnswer: String?,
) {
    val scrollState = rememberScrollState()

    // 内容增加时自动滚到底部
    LaunchedEffect(aiAnswer) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF161B22),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(scrollState),
        ) {
            // 拍摄的照片展示（直观看到摄像头抓拍的画面）
            if (photo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 54.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .background(Color.Black),
                    ) {
                        Image(
                            bitmap = photo.asImageBitmap(),
                            contentDescription = "拍摄的照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "已拍摄画面",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            // 用户的问题
            if (!userQuestion.isNullOrBlank()) {
                Text(
                    text = "问：$userQuestion",
                    color = Color(0xFF80D8FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
            }

            // AI 的回答
            Text(
                text = aiAnswer.takeUnless { it.isNullOrBlank() } ?: "小源思考中…",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * 极简可爱的动态机器人双眼绘制
 */
@Composable
private fun CuteEyesCanvas(
    eyeState: SparkEyeState,
    modifier: Modifier = Modifier,
) {
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

    val speakingBounce by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingBounce",
    )

    val blinkAnim = remember { Animatable(1f) }
    val lookOffsetX = remember { Animatable(0f) }
    val lookOffsetY = remember { Animatable(0f) }

    LaunchedEffect(eyeState) {
        while (true) {
            if (eyeState != SparkEyeState.MUTED) {
                delay((2500..5500).random().toLong())
                blinkAnim.animateTo(0.08f, tween(110))
                blinkAnim.animateTo(1f, tween(130))
            } else {
                delay(1000)
            }
        }
    }

    LaunchedEffect(eyeState) {
        when (eyeState) {
            SparkEyeState.THINKING -> {
                lookOffsetX.animateTo(10f, tween(300))
                lookOffsetY.animateTo(-8f, tween(300))
            }
            SparkEyeState.LISTENING -> {
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

        val eyeColor = when (eyeState) {
            SparkEyeState.LISTENING -> Color(0xFF64FFDA)
            SparkEyeState.THINKING -> Color(0xFF80D8FF)
            SparkEyeState.SPEAKING -> Color(0xFF00E5FF)
            SparkEyeState.MUTED -> Color(0xFF78909C)
            SparkEyeState.CAPTURING -> Color(0xFFFFD54F)
            SparkEyeState.IDLE -> Color(0xFF4EE2EC)
        }

        val baseWidth = (size.width * 0.20f).coerceIn(44f, 75f)
        val baseHeight = (size.height * 0.44f).coerceIn(55f, 90f)
        val eyeSpacing = (size.width * 0.10f).coerceIn(20f, 38f)

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
            drawSleepyEye(leftEyeCenter, curWidth)
            drawSleepyEye(rightEyeCenter, curWidth)
        } else {
            drawSingleEye(leftEyeCenter, curWidth, curHeight, eyeColor, eyeState)
            drawSingleEye(rightEyeCenter, curWidth, curHeight, eyeColor, eyeState)
        }
    }
}

private fun DrawScope.drawSingleEye(
    center: Offset,
    width: Float,
    height: Float,
    color: Color,
    state: SparkEyeState,
) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(color, color.copy(alpha = 0.88f)),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )

    if (state == SparkEyeState.LISTENING) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset(left - 3f, top - 3f),
            size = Size(width + 6f, height + 6f),
            cornerRadius = CornerRadius((width + 6f) / 2f, (width + 6f) / 2f),
            style = Stroke(width = 2.5f),
        )
    }

    if (height > width * 0.4f) {
        val highlightRadius = width * 0.18f
        val hx = center.x - width * 0.20f
        val hy = center.y - height * 0.22f

        drawCircle(
            color = Color.White.copy(alpha = 0.92f),
            radius = highlightRadius,
            center = Offset(hx, hy),
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = highlightRadius * 0.45f,
            center = Offset(center.x + width * 0.22f, center.y + height * 0.22f),
        )
    }
}

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
