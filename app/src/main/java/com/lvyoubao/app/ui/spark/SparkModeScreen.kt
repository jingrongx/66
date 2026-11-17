package com.lvyoubao.app.ui.spark

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.ui.settings.SettingsScreen
import kotlinx.coroutines.delay

@Composable
fun SparkModeScreen(
    app: TravelApp,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SparkViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { SparkViewModel(app) } } },
    )

    val eyeState by vm.eyeState.collectAsState()
    val showEyes by vm.showEyes.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val recordTick by vm.recordTick.collectAsState()
    val messages by vm.messages.collectAsState()
    val statusHint by vm.statusHint.collectAsState()

    // 右上角「设置」进设置页（模式互斥，退出灵动屏只能在这里关开关）
    var showSettings by remember { mutableStateOf(false) }

    // 长按会话消息弹出的操作面板（收藏/分享）
    var actionMsg by remember { mutableStateOf<SparkMessage?>(null) }

    // 退出 = 退出应用（对齐大字模式：停朗读停录音后 finishAffinity）
    val exitApp = {
        vm.exitApp()
        onExitApp()
    }

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

    // 返回键 = 退出应用（模式切换只认设置页开关）
    BackHandler {
        exitApp()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)), // 深邃科技黑底色（铺满含系统栏区域）
    ) {
        // 布局按 308x240 手表小屏设计；在手机等大屏上以可用短边为基准等比放大，
        // 保持与设计稿一致的构图与可点面积（小屏恒为 1，观感不变）
        val scale = (minOf(maxWidth, maxHeight) / 240.dp).coerceIn(1f, 1.9f)

        // 接管系统栏/刘海 insets：与普通/大字模式同口径，灵动屏自身内容不与系统栏重叠。
        // 注意设置页不能放这层里面——它自带 safeDrawing 接管，嵌进来会双重留白/透明漏底
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 顶部状态与退出栏（随屏幕等比放大，不挤不裁） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((30 * scale).dp)
                .padding(horizontal = (8 * scale).dp, vertical = (2 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧状态指示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size((6 * scale).dp)
                        .background(
                            if (isRecording) Color(0xFFFF5252) else Color(0xFF00E5FF),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width((4 * scale).dp))
                Text(
                    text = when {
                        isRecording -> "录音中 ${recordTick}s"
                        !statusHint.isNullOrBlank() -> statusHint.orEmpty()
                        showEyes -> "灵动屏"
                        else -> "聊天"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = (13 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右侧操作：切换视图 + 设置（模式开关只认设置页）+ 红色退出应用
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 切换眼睛 / 会话文字
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape((8 * scale).dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { vm.toggleShowEyes() }
                        .padding(horizontal = (8 * scale).dp, vertical = (4 * scale).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (showEyes) "💬 聊天" else "👀 萌眼",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = (12 * scale).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.width((6 * scale).dp))

                // 设置：进设置页（大字/灵动屏开关在这里，互斥）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape((8 * scale).dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { showSettings = true }
                        .padding(horizontal = (8 * scale).dp, vertical = (4 * scale).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "设置",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = (12 * scale).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.width((6 * scale).dp))

                // 退出：红色，直接退出应用回桌面（参考大字模式，模式切换只认设置页开关）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape((8 * scale).dp))
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { exitApp() }
                        .padding(horizontal = (10 * scale).dp, vertical = (4 * scale).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "退出",
                        color = Color.White,
                        fontSize = (13 * scale).sp,
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
                .padding(horizontal = (8 * scale).dp),
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
                        scale = scale,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 会话流展示（完整历史回看：用户问题/拍照轮在右，小源回答在左）
                    // 点消息重播朗读；长按弹出收藏/分享
                    ChatStream(
                        messages = messages,
                        scale = scale,
                        onMsgTap = { msg -> vm.replayMessage(context, msg.text) },
                        onMsgLongPress = { msg -> actionMsg = msg },
                    )
                }
            }
        }

        // ---------- 底部操作区：相机 + 相册 + 按住说话 ----------
        // 三颗按钮同高同圆角、随屏幕等比放大（手机上约 57dp 高，远超 48dp 可点下限），
        // 侧按钮改半透明白与顶部胶囊同语言，主按钮保持品牌青色，视觉左轻右重层次分明
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((46 * scale).dp)
                .padding(horizontal = (8 * scale).dp, vertical = (3 * scale).dp),
            horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 📷 拍照提问按钮
            Box(
                modifier = Modifier
                    .size((40 * scale).dp)
                    .clip(RoundedCornerShape((12 * scale).dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape((12 * scale).dp))
                    .clickable {
                        if (CameraSnapshotHelper.hasCameraPermission(context)) {
                            vm.captureAndAnalyze(context)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("📷", fontSize = (20 * scale).sp)
            }

            // 🖼️ 相册选图提问按钮
            Box(
                modifier = Modifier
                    .size((40 * scale).dp)
                    .clip(RoundedCornerShape((12 * scale).dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape((12 * scale).dp))
                    .clickable {
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🖼️", fontSize = (20 * scale).sp)
            }

            // 🎙️ 按住说话按钮（操作逻辑同常规/大字模式：按住即录，松手即发，按住时显示萌眼）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((40 * scale).dp)
                    .clip(RoundedCornerShape((12 * scale).dp))
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
                    text = if (isRecording) "松手发送 (${recordTick}s)" else "🎙 按住说话",
                    fontSize = (16 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        }
        }

        // ---------- 消息操作浮层：长按会话消息弹出（收藏/分享） ----------
        actionMsg?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { actionMsg = null } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape((14 * scale).dp),
                    color = Color(0xFF1B2330),
                ) {
                    Column(
                        modifier = Modifier
                            .padding((14 * scale).dp)
                            .widthIn(min = (160 * scale).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = msg.text.take(24) + if (msg.text.length > 24) "…" else "",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = (12 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height((10 * scale).dp))
                        Row(horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
                            // 收藏/取消收藏（与普通模式同一套个人飞轮证据，重复长按同一条切换）
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape((10 * scale).dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable {
                                        vm.toggleFavorite(msg) { hint ->
                                            vm.setTransientHint(hint)
                                            actionMsg = null
                                        }
                                    }
                                    .padding(horizontal = (12 * scale).dp, vertical = (6 * scale).dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "⭐ 收藏",
                                    color = Color.White,
                                    fontSize = (13 * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            // 分享：系统分享面板（纯文本，微信/短信/剪贴板都能接）
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape((10 * scale).dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg.text)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(send, "分享会话内容"))
                                        }
                                        actionMsg = null
                                    }
                                    .padding(horizontal = (12 * scale).dp, vertical = (6 * scale).dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "↗ 分享",
                                    color = Color.White,
                                    fontSize = (13 * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 设置页覆盖层：右上角「设置」进入 ----------
        // 独立全屏宿主：Surface 铺不透明背景抢占整屏，insets 由设置页自管
        // （与大字/灵动屏开关互斥，退出灵动屏只认这里的开关）
        if (showSettings) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SettingsScreen(
                    onClose = { showSettings = false },
                    onOpenUpdate = {},
                    app = app,
                )
            }
        }
    }
}

/**
 * 会话流展示（参考普通/大字模式的消息流）：
 * - 完整历史可回看：用户问题在右（浅蓝气泡），小源回答在左（深色气泡）；
 * - 照片只挂在拍照/选图那一轮的消息上，随消息流滚走，不再常驻顶部；
 * - AI 回答流式追加，新内容到达自动滚到底部。
 */
@Composable
private fun ChatStream(
    messages: List<SparkMessage>,
    scale: Float,
    onMsgTap: (SparkMessage) -> Unit = {},
    onMsgLongPress: (SparkMessage) -> Unit = {},
) {
    val scrollState = rememberScrollState()

    // 新内容到达自动滚到底部
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape((12 * scale).dp),
        color = Color(0xFF161B22),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding((8 * scale).dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy((6 * scale).dp),
        ) {
            if (messages.isEmpty()) {
                Text(
                    text = "还没有对话～\n按住下方按钮说说话\n或拍张照让小源看看 👀",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = (13 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = (24 * scale).dp),
                )
            }

            messages.forEach { msg ->
                if (msg.role == "user") {
                    // 用户消息：靠右浅蓝气泡，拍照轮带缩略图
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        msg.photo?.let { photo ->
                            Image(
                                bitmap = photo.asImageBitmap(),
                                contentDescription = "本轮照片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = (72 * scale).dp, height = (54 * scale).dp)
                                    .clip(RoundedCornerShape((8 * scale).dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape((8 * scale).dp)),
                            )
                            Spacer(Modifier.height((4 * scale).dp))
                        }
                        Text(
                            text = msg.text,
                            color = Color(0xFFB3E5FC),
                            fontSize = (14 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .widthIn(max = (250 * scale).dp)
                                .clip(RoundedCornerShape((10 * scale).dp))
                                .background(Color(0xFF80D8FF).copy(alpha = 0.16f))
                                .pointerInput(msg) {
                                    // 点＝重播朗读，长按＝收藏/分享
                                    detectTapGestures(
                                        onTap = { onMsgTap(msg) },
                                        onLongPress = { onMsgLongPress(msg) },
                                    )
                                }
                                .padding(horizontal = (8 * scale).dp, vertical = (5 * scale).dp),
                        )
                    }
                } else {
                    // 小源回答：靠左深色气泡，流式更新中先显示占位
                    Text(
                        text = msg.text.ifBlank { "小源思考中…" },
                        color = Color.White,
                        fontSize = (15 * scale).sp,
                        lineHeight = (21 * scale).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .widthIn(max = (250 * scale).dp)
                            .clip(RoundedCornerShape((10 * scale).dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .pointerInput(msg) {
                                // 点＝重播朗读，长按＝收藏/分享
                                detectTapGestures(
                                    onTap = { onMsgTap(msg) },
                                    onLongPress = { onMsgLongPress(msg) },
                                )
                            }
                            .padding(horizontal = (8 * scale).dp, vertical = (5 * scale).dp),
                    )
                }
            }
        }
    }
}

/**
 * 极简可爱的动态机器人双眼绘制
 */
@Composable
private fun CuteEyesCanvas(
    eyeState: SparkEyeState,
    scale: Float,
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
        initialValue = -4f * scale,
        targetValue = 4f * scale,
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

        val baseWidth = (size.width * 0.20f).coerceIn(44f * scale, 75f * scale)
        val baseHeight = (size.height * 0.44f).coerceIn(55f * scale, 90f * scale)
        val eyeSpacing = (size.width * 0.10f).coerceIn(20f * scale, 38f * scale)

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
            drawSleepyEye(leftEyeCenter, curWidth, scale)
            drawSleepyEye(rightEyeCenter, curWidth, scale)
        } else {
            drawSingleEye(leftEyeCenter, curWidth, curHeight, eyeColor, eyeState, scale)
            drawSingleEye(rightEyeCenter, curWidth, curHeight, eyeColor, eyeState, scale)
        }
    }
}

private fun DrawScope.drawSingleEye(
    center: Offset,
    width: Float,
    height: Float,
    color: Color,
    state: SparkEyeState,
    scale: Float,
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
            topLeft = Offset(left - 3f * scale, top - 3f * scale),
            size = Size(width + 6f * scale, height + 6f * scale),
            cornerRadius = CornerRadius((width + 6f * scale) / 2f, (width + 6f * scale) / 2f),
            style = Stroke(width = 2.5f * scale),
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

private fun DrawScope.drawSleepyEye(center: Offset, width: Float, scale: Float) {
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
        style = Stroke(width = 5.5f * scale, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}
