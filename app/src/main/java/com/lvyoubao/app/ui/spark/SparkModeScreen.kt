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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.duplex.DoubaoDuplexClient
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.largetext.DoubaoCallOverlay
import com.lvyoubao.app.ui.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SparkModeScreen(
    app: TravelApp,
    onExitApp: () -> Unit,
    newVersion: String? = null, // 非空 = 有新版本，顶栏下出更新横幅（MainActivity 统一检查）
    onUpdateClick: () -> Unit = {},
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
    val pendingPhotos by vm.pendingPhotos.collectAsState()
    val isContinuous by vm.isContinuous.collectAsState()
    val timerLeft by vm.timerRemainingSec.collectAsState()
    val alarmActive by vm.alarmActive.collectAsState()
    val alarmText by vm.alarmText.collectAsState()

    // 右上角「设置」进设置页（模式互斥，退出灵动模式只能在这里关开关）
    var showSettings by remember { mutableStateOf(false) }

    // ---------- 豆包全双工实时通话（端到端 S2S，灵动模式顶栏 📞 直达） ----------
    // 灵动模式没有大字模式那排「通话」按钮，这里给一条独立入口：
    // 通话期间麦克风整条让给豆包（停朗读/连聊/待机唤醒），挂断后恢复。
    val settings by vm.settings.collectAsState()
    var showDuplexCall by remember { mutableStateOf(false) }
    // 通话进行中但界面已收起（回萌眼待机）：**通话不挂断**，人再开口或点电话键就重新展开
    var callUiVisible by remember { mutableStateOf(false) }
    val duplexClient = remember { DoubaoDuplexClient(context) }
    val duplexState by duplexClient.state.collectAsState()
    var duplexAsr by remember { mutableStateOf("") }
    var duplexAnswer by remember { mutableStateOf("") }
    var duplexHint by remember { mutableStateOf<String?>(null) }
    // 最后一次「有人在说话」的时间：朗读完 + 几秒没人说话就把界面收回萌眼（不挂断）
    var lastVoiceAt by remember { mutableStateOf(System.currentTimeMillis()) }
    // 本轮已被本机能力接走的识别文本：模型那条迟到的回答不再落第二张卡片
    var localHandledAsr by remember { mutableStateOf("") }
    var localHandledAt by remember { mutableStateOf(0L) }
    val callScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        duplexClient.onEvent.collect { ev ->
            when (ev) {
                // 服务端切新 item = 新一轮说话：上一轮的识别文字先清掉，避免跨轮越滚越长
                is DoubaoDuplexClient.Event.AsrStarted -> {
                    duplexAsr = ""
                }
                // 注意：豆包的 transcription.delta 是「累计快照」而不是增量，直接覆盖；
                // 早前用 += 拼会出现「你你好你好啊」这种叠字。
                is DoubaoDuplexClient.Event.Asr -> {
                    duplexAsr = ev.text
                    lastVoiceAt = System.currentTimeMillis()
                    // 界面收起时只要有人开口，就把通话界面拉回来（通话一直没断）
                    if (!callUiVisible && ev.text.isNotBlank()) {
                        callUiVisible = true
                        vm.setShowEyes(false)
                    }
                    if (ev.isFinal && ev.text.isNotBlank()) {
                        val said = ev.text
                        // 先说给本机能力听：机器人动作 / 电量 / 音量 / 计时 / 记事 / 记忆查找命中就本地执行，
                        // 再用豆包音色把确认话术说出来（模型那条回答同时打断，避免各说一套）。
                        callScope.launch {
                            val confirm = runCatching { vm.handleCallCommand(context, said) }.getOrNull()
                            if (confirm != null) {
                                duplexClient.cancelResponse()
                                duplexClient.speak(confirm)
                                duplexAnswer = confirm
                                localHandledAsr = said
                                localHandledAt = System.currentTimeMillis()
                                SparkChatStore.appendDuplexTurn(said, confirm)
                            }
                        }
                    }
                }
                is DoubaoDuplexClient.Event.Answer -> {
                    lastVoiceAt = System.currentTimeMillis()
                    if (ev.isFinal) {
                        duplexAnswer = ev.text
                        // 本轮若已被本机能力接走（并已 cancelResponse），模型这条是残留的下发：
                        // 只在通话界面显示，不再往会话里落第二张卡片。
                        val handledLocally = duplexAsr.isNotBlank() &&
                            duplexAsr == localHandledAsr &&
                            System.currentTimeMillis() - localHandledAt < 15_000L
                        if (!handledLocally) {
                            // 一轮结束落灵动会话流：挂断后到「聊天」能回看，下一轮对话也带得上
                            SparkChatStore.appendDuplexTurn(duplexAsr, ev.text)
                        }
                    } else {
                        // 兼容两种下发：累计快照直接替换，真增量才追加
                        duplexAnswer = if (duplexAnswer.isNotEmpty() && ev.text.startsWith(duplexAnswer)) ev.text
                        else duplexAnswer + ev.text
                    }
                }
                is DoubaoDuplexClient.Event.SessionReady -> {
                    duplexAsr = ""
                    duplexAnswer = ""
                    duplexHint = null
                }
                is DoubaoDuplexClient.Event.Error -> duplexHint = when (ev.code) {
                    "CONFIG" -> "未配置豆包实时语音 Key，请到设置 → 凭证中心 → 豆包 填写"
                    else -> "通话出错：${ev.message}"
                }
                else -> Unit
            }
        }
    }

    DisposableEffect(showDuplexCall) {
        if (showDuplexCall) {
            vm.setDuplexAudioActive(true)
            duplexAsr = ""
            duplexAnswer = ""
            duplexHint = null
            if (settings.duplexApiKey.isBlank()) {
                duplexHint = "未配置豆包实时语音 Key，请到设置 → 凭证中心 → 豆包 填写"
            } else if (!SpeechInput.hasRecordPermission(context)) {
                duplexHint = "需要麦克风权限才能全双工通话"
            } else {
                duplexClient.start(settings.duplexApiKey, settings.duplexVoice)
            }
        }
        onDispose {
            duplexClient.close()
            vm.setDuplexAudioActive(false)
        }
    }
    // 彻底退出应用时释放 OkHttp 线程池（与大字模式同口径）
    DisposableEffect(Unit) {
        onDispose { duplexClient.destroy() }
    }
    // 通话中返回键 = 挂断（不能直接退出应用）
    BackHandler(enabled = showDuplexCall) {
        duplexClient.close()
        showDuplexCall = false
        callUiVisible = false
        vm.setShowEyes(true)
    }

    // 通话中「回答播完 + 几秒没人说话」→ 界面收回萌眼动画，但**不挂断**：
    // 麦克风仍归豆包，人再开口（ASR 到达）或点电话键就重新展开，聊天不断线。
    LaunchedEffect(showDuplexCall) {
        if (!showDuplexCall) return@LaunchedEffect
        lastVoiceAt = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            val silentMs = System.currentTimeMillis() - lastVoiceAt
            val listening = duplexState == DoubaoDuplexClient.State.LISTENING ||
                duplexState == DoubaoDuplexClient.State.CONNECTED
            // 刚接通还没说过话给 10 秒（有充足时间开口），一轮回答之后按 6 秒收
            val waitMs = if (duplexAsr.isBlank() && duplexAnswer.isBlank()) 10_000L else 6_000L
            if (callUiVisible && listening && silentMs > waitMs) {
                callUiVisible = false
                vm.setShowEyes(true)
            }
        }
    }
    // 每回到「聆听」态（一轮回答刚播完）重新起算静默时长：只在朗读完之后才收界面，
    // 说话/思考过程中不会突然被收走。
    LaunchedEffect(duplexState) {
        if (duplexState == DoubaoDuplexClient.State.LISTENING) {
            lastVoiceAt = System.currentTimeMillis()
        }
    }

    // 电话键：没通话就起一通；通话中（界面已收起）就重新展开，不重连
    val enterCall: () -> Unit = {
        if (!showDuplexCall) showDuplexCall = true
        callUiVisible = true
        lastVoiceAt = System.currentTimeMillis() // 手动展开后重新起算静默，别刚展开就被收走
        vm.setShowEyes(false)
    }

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

    // 拍照走系统相机（TakePicture 落到 FileProvider 文件）：方向/曝光都由相机应用处理——
    // 之前 Camera2 裸抓帧没处理传感器方向（横照）也没预览暖场（首帧全黑）
    val cameraPhotoFile = remember {
        File(context.cacheDir, "images").apply { mkdirs() }
        File(context.cacheDir, "images/spark_capture.jpg")
    }
    val photoUri = remember {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cameraPhotoFile,
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) {
            // 拍完先进待发送缓冲（可继续连拍多张），点「识别」或按住说话才发出去
            vm.addPendingPhotos(context, listOf(photoUri))
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(photoUri)
        } else {
            vm.setTransientHint("需要相机权限才能拍照观察")
        }
    }

    // 相册走系统多选（GetMultipleContents = ACTION_GET_CONTENT + 多选）：
    // 没有 Photo Picker 的老设备/魔改 ROM 也能调出图库，且支持一次勾多张
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.addPendingPhotos(context, uris)
        }
    }

    // 返回键 = 退出应用（模式切换只认设置页开关）
    BackHandler {
        exitApp()
    }

    // 灵动模式进前台：待机唤醒词监听随页面开关（设置变化由 VM 的 settings 收集器重算）
    LaunchedEffect(Unit) { vm.onSparkActive() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)) // 深邃科技黑底色（铺满含系统栏区域）
            // 倒计时铃响时点屏幕任意空白处停止（按钮类控件自己消费点击，不受影响）
            .pointerInput(Unit) { detectTapGestures { if (alarmActive) vm.stopAlarm() } },
    ) {
        // 布局按 308x240 手表小屏设计；在手机等大屏上以可用短边为基准等比放大，
        // 保持与设计稿一致的构图与可点面积（小屏恒为 1，观感不变）
        val scale = (minOf(maxWidth, maxHeight) / 240.dp).coerceIn(1f, 1.9f)

        // 接管系统栏/刘海 insets：与普通/大字模式同口径，灵动模式自身内容不与系统栏重叠。
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
                        showEyes -> "旅游宝 · 灵动"
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

                // 设置：进设置页（大字/灵动模式开关在这里，互斥）
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

        // ---------- 更新横幅：有新版本时插在顶栏下，占一行可点 ----------
        if (newVersion != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (8 * scale).dp, vertical = (3 * scale).dp)
                    .clip(RoundedCornerShape((8 * scale).dp))
                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                    .clickable(onClick = onUpdateClick)
                    .padding(horizontal = (10 * scale).dp, vertical = (6 * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🚀 有新版本 $newVersion，点按更新",
                    color = Color(0xFF80DEEA),
                    fontSize = (13 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    // 告警响着：眼睛区显示「时间到」+ 提醒事项 + 停止引导（点屏幕任意处/说别响了）
                    if (alarmActive) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "⏰ 时间到",
                                fontSize = (26 * scale).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            if (alarmText.length > "时间到啦".length) {
                                Text(
                                    text = alarmText.removePrefix("时间到啦，").removePrefix("时间到啦"),
                                    fontSize = (15 * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF80DEEA),
                                )
                            }
                            Text(
                                text = "点按屏幕停止",
                                fontSize = (11 * scale).sp,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    } else if (timerLeft != null) {
                        // 倒计时进行中：眼睛区让位给大字时分秒（语音「计时10分钟」）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = formatCountdown(timerLeft!!),
                                fontSize = (48 * scale).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "倒计时",
                                fontSize = (11 * scale).sp,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    } else {
                        // 眼睛动画展示
                        CuteEyesCanvas(
                            eyeState = eyeState,
                            scale = scale,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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

        // ---------- 待发送照片条：拍照/相册可连续加多张，「识别」一次问完；按住说话也带图 ----------
        if (pendingPhotos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (8 * scale).dp, vertical = (2 * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((6 * scale).dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
                ) {
                    pendingPhotos.forEach { photo ->
                        Image(
                            bitmap = photo.bitmap.asImageBitmap(),
                            contentDescription = "待发送照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = (44 * scale).dp, height = (33 * scale).dp)
                                .clip(RoundedCornerShape((6 * scale).dp))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape((6 * scale).dp)),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape((10 * scale).dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.analyzePending(context) }
                        .padding(horizontal = (10 * scale).dp, vertical = (6 * scale).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "🔍 识别 ${pendingPhotos.size} 张",
                        color = Color.White,
                        fontSize = (12 * scale).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ---------- 底部操作区（随状态换摆法） ----------
        // 萌眼态：方案 A 完整脸——脸颊圆钮分列两侧，全宽「嘴形」胶囊居中正对眼睛（对称成脸）；
        // 会话态：紧凑对称单行（📷｜按住说话｜🖼️），嘴依然居中不歪，聊天区更高（组合方案）
        val onCamera: () -> Unit = {
            if (CameraSnapshotHelper.hasCameraPermission(context)) {
                cameraLauncher.launch(photoUri)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        val onGallery: () -> Unit = { galleryLauncher.launch("image/*") }
        // 连聊中的整颗呼吸青光（边框描边 1.1s 往复）
        val glowTransition = rememberInfiniteTransition(label = "convGlow")
        val glowAlpha by glowTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "glowAlpha",
        )
        val convCapsuleBg = when {
            isRecording -> Color(0xFFD32F2F)
            isContinuous -> Color(0xFF00838F)
            else -> MaterialTheme.colorScheme.primary
        }
        val convGlowBorder = if (isContinuous) {
            Modifier.border(2.dp, Color(0xFF00E5FF).copy(alpha = glowAlpha), RoundedCornerShape(percent = 50))
        } else {
            Modifier
        }
        val talkGesture = Modifier.pointerInput(showDuplexCall) {
            detectTapGestures(
                onPress = {
                    if (showDuplexCall) {
                        // 通话没挂断、只是界面收起：按住说话不能抢麦克风，点一下就展开通话界面
                        enterCall()
                    } else {
                        vm.startHoldTalk(context) {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            vm.endHoldTalk(context)
                        }
                    }
                },
            )
        }

        if (showEyes) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (10 * scale).dp, vertical = (4 * scale).dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(
                        modifier = Modifier
                            .size((30 * scale).dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable(onClick = onCamera),
                        contentAlignment = Alignment.Center,
                    ) { Text("📷", fontSize = (15 * scale).sp) }
                    Box(
                        modifier = Modifier
                            .size((30 * scale).dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable(onClick = onGallery),
                        contentAlignment = Alignment.Center,
                    ) { Text("🖼️", fontSize = (15 * scale).sp) }
                }
                Spacer(Modifier.height((5 * scale).dp))
                // 一体胶囊：左端是实时通话（豆包全双工，亮青底 + 电话图标），右侧按住说话
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((40 * scale).dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(convCapsuleBg)
                        .then(convGlowBorder),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        // 实时通话：豆包全双工（端到端 S2S，免按键、随时插话）——占原来「连聊」的位置
                        Box(
                            modifier = Modifier
                                .padding((4 * scale).dp)
                                .width((78 * scale).dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color(0xFF00E5FF))
                                .clickable(onClick = enterCall)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "实时通话",
                                tint = Color(0xFF06222B),
                                modifier = Modifier.size((21 * scale).dp),
                            )
                        }
                        // 嘴：按住临时说一句
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(talkGesture),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when {
                                    showDuplexCall -> "📞 通话中，点一下继续"
                                    isRecording -> "松手发送 (${recordTick}s)"
                                    isContinuous -> "说吧，我在听"
                                    else -> "🎙 按住说话"
                                },
                                fontSize = (16 * scale).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (10 * scale).dp, vertical = (4 * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            ) {
                Box(
                    modifier = Modifier
                        .size((40 * scale).dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(onClick = onCamera),
                    contentAlignment = Alignment.Center,
                ) { Text("📷", fontSize = (19 * scale).sp) }
                // 会话态：实时通话钮 + 按住说话拼成一颗胶囊（与萌眼态同构，紧凑窄版）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((40 * scale).dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(convCapsuleBg)
                        .then(convGlowBorder),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        // 实时通话：豆包全双工（会话态同样可达，占原「连聊」位置）
                        Box(
                            modifier = Modifier
                                .padding((3 * scale).dp)
                                .width((52 * scale).dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color(0xFF00E5FF))
                                .clickable(onClick = enterCall)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "实时通话",
                                tint = Color(0xFF06222B),
                                modifier = Modifier.size((18 * scale).dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(talkGesture),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when {
                                    showDuplexCall -> "📞 通话中，点一下继续"
                                    isRecording -> "松手发送 (${recordTick}s)"
                                    isContinuous -> "说吧，我在听"
                                    else -> "🎙 按住说话"
                                },
                                fontSize = (15 * scale).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size((40 * scale).dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(onClick = onGallery),
                    contentAlignment = Alignment.Center,
                ) { Text("🖼️", fontSize = (19 * scale).sp) }
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

        // ---------- 豆包全双工通话覆盖层：点电话键进入，挂断回萌眼 ----------
        // 注意只在 callUiVisible 时铺满屏：通话没挂断、界面收起时露出下面的萌眼动画
        if (showDuplexCall && callUiVisible) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DoubaoCallOverlay(
                    state = duplexState,
                    asrText = duplexAsr.takeLast(240),
                    answerText = duplexAnswer,
                    hint = duplexHint,
                    photos = emptyList(),
                    onPickCamera = {},
                    onPickGallery = {},
                    onRemovePhoto = {},
                    onBargeIn = { duplexClient.bargeIn() },
                    onHangUp = {
                        duplexClient.close()
                        showDuplexCall = false
                        callUiVisible = false
                        vm.setShowEyes(true)
                    },
                    showPhotoActions = false,
                )
            }
        }

        // ---------- 设置页覆盖层：右上角「设置」进入 ----------
        // 独立全屏宿主：Surface 铺不透明背景抢占整屏，insets 由设置页自管
        // （大字/灵动模式开关互斥，退出灵动模式只认这里的开关；检查更新接统一更新页）
        if (showSettings) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SettingsScreen(
                    onClose = { showSettings = false },
                    onOpenUpdate = onUpdateClick,
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
                    // 用户消息：靠右浅蓝气泡，带图轮展示全部照片缩略图
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (msg.photos.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
                            ) {
                                msg.photos.forEach { photo ->
                                    Image(
                                        bitmap = photo.asImageBitmap(),
                                        contentDescription = "本轮照片",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = (72 * scale).dp, height = (54 * scale).dp)
                                            .clip(RoundedCornerShape((8 * scale).dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape((8 * scale).dp)),
                                    )
                                }
                            }
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

/** 倒计时显示：≥1 小时用 H:MM:SS，否则 MM:SS */
private fun formatCountdown(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
