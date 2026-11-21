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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.lvyoubao.app.R
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.ui.map.RouteMapCard
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.DeviceTools
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.duplex.DoubaoDuplexClient
import com.lvyoubao.app.data.duplex.DuplexCallSetup
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.largetext.DoubaoCallOverlay
import com.lvyoubao.app.ui.largetext.DuplexTurnUi
import com.lvyoubao.app.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val timerLeft by vm.timerRemainingSec.collectAsState()
    val alarmActive by vm.alarmActive.collectAsState()
    val alarmText by vm.alarmText.collectAsState()

    // 右上角「设置」进设置页（模式互斥，退出灵动模式只能在这里关开关）
    var showSettings by remember { mutableStateOf(false) }
    // 触摸节流：手指划过时每个 move 事件都会重排息屏倒计时，没必要
    var lastTouchAt by remember { mutableStateOf(0L) }

    // ---------- 豆包全双工实时通话（端到端 S2S，灵动模式顶栏 📞 直达） ----------
    // 灵动模式没有大字模式那排「通话」按钮，这里给一条独立入口：
    // 通话期间麦克风整条让给豆包（停朗读/待机唤醒），挂断后恢复。
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
    // 通话起算时刻：界面收起再展开时通话计时接着走
    var callStartedAt by remember { mutableStateOf(0L) }
    // 本轮已被本机能力接走的识别文本：模型那条迟到的回答不再落第二张卡片
    var localHandledAsr by remember { mutableStateOf("") }
    var localHandledAt by remember { mutableStateOf(0L) }
    // 通话中 FC 工具带回的地图卡缓冲：随本轮回答一起挂到会话
    val duplexMapCards = remember { mutableStateListOf<MapCardData>() }
    // 通话会话流（识别=右、回答=左），覆盖层直接渲染
    val duplexTurns = remember { mutableStateListOf<DuplexTurnUi>() }
    val callScope = rememberCoroutineScope()

    fun duplexNewUserTurn() {
        duplexTurns.add(DuplexTurnUi("user", "", streaming = true))
        if (duplexTurns.size > 30) duplexTurns.removeAt(0)
    }
    fun duplexUpdateUser(text: String) {
        val i = duplexTurns.lastIndex
        if (i >= 0 && duplexTurns[i].role == "user") duplexTurns[i] = duplexTurns[i].copy(text = text)
        else { duplexNewUserTurn(); duplexUpdateUser(text) }
    }
    fun duplexUpdateAssistant(text: String, done: Boolean) {
        val i = duplexTurns.lastIndex
        if (i >= 0 && duplexTurns[i].role == "assistant") {
            duplexTurns[i] = duplexTurns[i].copy(text = text, streaming = !done)
        } else {
            duplexTurns.add(DuplexTurnUi("assistant", text, streaming = !done))
            if (duplexTurns.size > 30) duplexTurns.removeAt(0)
        }
    }

    LaunchedEffect(Unit) {
        duplexClient.onEvent.collect { ev ->
            when (ev) {
                // 服务端切新 item = 新一轮说话：上一轮的识别文字先清掉，避免跨轮越滚越长
                is DoubaoDuplexClient.Event.AsrStarted -> {
                    duplexAsr = ""
                    duplexNewUserTurn()
                }
                // 注意：豆包的 transcription.delta 是「累计快照」而不是增量，直接覆盖；
                // 早前用 += 拼会出现「你你好你好啊」这种叠字。
                is DoubaoDuplexClient.Event.Asr -> {
                    duplexAsr = ev.text
                    duplexUpdateUser(ev.text)
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
                                duplexUpdateAssistant(confirm, done = true)
                                localHandledAsr = said
                                localHandledAt = System.currentTimeMillis()
                                SparkChatStore.appendDuplexTurn(said, confirm)
                                vm.summarizeCallTurns()
                            }
                        }
                    }
                }
                is DoubaoDuplexClient.Event.Answer -> {
                    lastVoiceAt = System.currentTimeMillis()
                    if (ev.isFinal) {
                        duplexAnswer = ev.text
                    } else {
                        // 兼容两种下发：累计快照直接替换，真增量才追加
                        duplexAnswer = if (duplexAnswer.isNotEmpty() && ev.text.startsWith(duplexAnswer)) ev.text
                        else duplexAnswer + ev.text
                    }
                    duplexUpdateAssistant(duplexAnswer, done = ev.isFinal)
                }
                // 模型决定调工具（天气/附近/路线）：本地执行，结果回传，模型接着说。
                // 机器人动作/电量/计时这类本机能力不走 FC——Asr final 时已经抢先本地执行了。
                is DoubaoDuplexClient.Event.ToolCall -> {
                    lastVoiceAt = System.currentTimeMillis()
                    val ackJob = callScope.launch {
                        delay(1_200)
                        duplexClient.speak("我查一下哈") // 执行超过 1.2 秒先出声，别让通话死寂
                    }
                    callScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                AppTools.execute(context, settings, ev.name, ev.arguments) { duplexMapCards.add(it) }
                            }.getOrElse { "工具执行失败：${it.message ?: "未知原因"}。请如实告知用户。" }
                        }
                        ackJob.cancel()
                        duplexClient.sendToolResult(ev.callId, result)
                    }
                }
                // 识别流里蹦出急停词：客户端已停播+取消回答，这里把机器人也停住（本地链的急停要等句尾）
                is DoubaoDuplexClient.Event.StopRequested -> {
                    callScope.launch {
                        DeviceTools.stopRobot(context, settings)
                        duplexClient.speak("好的，已停下")
                    }
                }
                // 一轮收口（response.done / 被打断的残句都走这）：落会话历史的唯一入口。
                is DoubaoDuplexClient.Event.TurnDone -> {
                    lastVoiceAt = System.currentTimeMillis()
                    duplexAnswer = ev.answer
                    duplexUpdateAssistant(ev.answer, done = true)
                    if (ev.answer.isNotBlank()) {
                        // 本轮若已被本机能力接走（并已 cancelResponse），模型这条是残留的下发：
                        // 只在通话界面显示，不再往会话里落第二张卡片。
                        val handledLocally = ev.asr.isNotBlank() &&
                            ev.asr == localHandledAsr &&
                            System.currentTimeMillis() - localHandledAt < 15_000L
                        if (!handledLocally) {
                            // 一轮结束落灵动会话流：挂断后到「聊天」能回看，下一轮对话也带得上
                            SparkChatStore.appendDuplexTurn(ev.asr, ev.answer)
                            vm.summarizeCallTurns() // 通话也是会话：攒够了就总结进长期记忆
                        }
                    }
                    duplexMapCards.lastOrNull()?.let { SparkChatStore.attachMapCard(it) }
                    duplexMapCards.clear()
                }
                is DoubaoDuplexClient.Event.SessionReady -> {
                    duplexAsr = ""
                    duplexAnswer = ""
                    duplexHint = null
                    duplexMapCards.clear()
                    duplexTurns.clear()
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
            // 通话期间不许熄屏，并且压住 WiFi 省电：
            // 这台设备息屏后 WiFi 会睡，豆包实时流一断就「黑屏之后没声音」。
            // 钉住是按「通话会话」算的，界面收起（callUiVisible=false）不解钉，挂断才解。
            com.lvyoubao.app.ui.common.ScreenAwake.pin("全双工通话")
            com.lvyoubao.app.ui.common.NetKeep.acquire(context, "duplex-call")
            callStartedAt = System.currentTimeMillis()
            duplexAsr = ""
            duplexAnswer = ""
            duplexHint = null
            if (settings.duplexApiKey.isBlank()) {
                duplexHint = "未配置豆包实时语音 Key，请到设置 → 凭证中心 → 豆包 填写"
            } else if (!SpeechInput.hasRecordPermission(context)) {
                duplexHint = "需要麦克风权限才能全双工通话"
            } else {
                // 记忆 + 能力一次带上：小源人设/长期记忆/时间位置进 instructions，
                // 共享受话的最近成对 QA 进 dialog_context，天气/附近/路线进 tools
                val setup = DuplexCallSetup.build(
                    context,
                    persona = DuplexCallSetup.PERSONA_SPARK,
                    history = SparkChatStore.historyBudget(8_000),
                    settings = settings,
                    // 灵动模式不挂本机能力工具：本地命令链更快，双挂会重复执行
                    deviceTools = false,
                )
                duplexClient.start(
                    settings.duplexApiKey, settings.duplexVoice,
                    systemPrompt = setup.instructions,
                    callTools = setup.tools,
                    historyContext = setup.dialogContext,
                    dialect = setup.dialect,
                    music = setup.music,
                )
            }
        }
        onDispose {
            duplexClient.close()
            vm.setDuplexAudioActive(false)
            com.lvyoubao.app.ui.common.NetKeep.release("duplex-call")
            com.lvyoubao.app.ui.common.ScreenAwake.unpin("全双工通话")
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
    // 通话中（界面已收起）电话键单独一个状态：底色深一档 + 右上角呼吸点，
    // 不用在右边「按住说话」上写字，一眼就知道通话还没断
    val callPulse by rememberInfiniteTransition(label = "callPulse").animateFloat(
        initialValue = 0.22f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "callPulseAlpha",
    )
    // 挂断：胶囊上的红键和全屏通话里的按钮共用一套收尾
    val hangUpCall: () -> Unit = {
        duplexClient.close()
        showDuplexCall = false
        callUiVisible = false
        vm.setShowEyes(true)
    }
    // 通话时长：显示在胶囊通话条上，收起/展开都不归零
    var callElapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(showDuplexCall) {
        if (!showDuplexCall) {
            callElapsedSec = 0
            return@LaunchedEffect
        }
        while (true) {
            callElapsedSec = ((System.currentTimeMillis() - callStartedAt) / 1000L).toInt().coerceAtLeast(0)
            delay(1_000)
        }
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
            .background(Brush.verticalGradient(listOf(SparkColors.bgTop, SparkColors.bgBottom))) // 墨玉：顶部微亮、底部近黑
            // 触摸即「有人在场」：屏幕保持亮着，空闲息屏的倒计时从这一刻重新算。
            // 用 Initial 阶段旁观，不消费事件，不影响下面任何点击/长按手势。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            val now = System.currentTimeMillis()
                            if (now - lastTouchAt > 800) {
                                lastTouchAt = now
                                com.lvyoubao.app.ui.common.ScreenAwake.idle()
                            }
                        }
                    }
                }
            }
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
        // ---------- 顶栏：状态点 + 标题，右侧三颗图标钮（聊天/设置/退出） ----------
        // 旧版是「💬 聊天 / 设置 / 退出」三个文字按钮，小屏上横着占掉近三分之一宽度，改图标后宽度还给标题。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((30 * scale).dp)
                .padding(horizontal = (8 * scale).dp, vertical = (2 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .background(if (isRecording) SparkColors.danger else SparkColors.accent, shape = CircleShape),
            )
            Spacer(Modifier.width((5 * scale).dp))
            Text(
                text = when {
                    isRecording -> "录音中 ${recordTick}s"
                    !statusHint.isNullOrBlank() -> statusHint.orEmpty()
                    showEyes -> "灵动"
                    else -> "对话"
                },
                color = SparkColors.text,
                fontSize = (11 * scale).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (0.4 * scale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
                SparkIconButton(
                    iconRes = if (showEyes) R.drawable.ic_spark_chat else R.drawable.ic_spark_eye,
                    contentDescription = if (showEyes) "聊天" else "萌眼",
                    scale = scale,
                ) { vm.toggleShowEyes() }
                SparkIconButton(R.drawable.ic_spark_tune, "设置", scale) { showSettings = true }
                SparkIconButton(
                    iconRes = R.drawable.ic_spark_power,
                    contentDescription = "退出",
                    scale = scale,
                    tint = SparkColors.dangerSoft,
                ) { exitApp() }
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
                        // 眼睛动画 + 底部一句提示：提示贴在下沿（离眼睛留出 30px 呼吸区），
                        // 之前紧贴眼睛下方，看着像贴在脸上。
                        Box(Modifier.fillMaxSize()) {
                            CuteEyesCanvas(
                                eyeState = eyeState,
                                scale = scale,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = (34 * scale).dp),
                            )
                            Text(
                                text = when {
                                    showDuplexCall -> "通话中 · 直接说话就行"
                                    isRecording -> "松手发送"
                                    else -> "说「小源小源」/「嗨小源」，或按住下面说话"
                                },
                                color = SparkColors.sub,
                                fontSize = (9 * scale).sp,
                                letterSpacing = (0.2 * scale).sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = (6 * scale).dp),
                            )
                        }
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

        // ---------- 底部操作区：一行搞定（相机 · 胶囊 · 相册） ----------
        // 旧版萌眼态是「相机/相册一行 + 胶囊一行」两行，240px 高里白吃 80px；现在两态共用一行。
        // 通话中：胶囊整条变成通话条——左红键挂断、中间状态+时长（点一下回全屏）、右箭头，
        // 「按住说话」这一整块位置被通话占满；相机/相册置灰（麦克风归豆包，拍照发问会打架）。
        val onCamera: () -> Unit = {
            if (CameraSnapshotHelper.hasCameraPermission(context)) {
                cameraLauncher.launch(photoUri)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        val onGallery: () -> Unit = { galleryLauncher.launch("image/*") }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = (7 * scale).dp)
                .padding(bottom = (7 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((6 * scale).dp),
        ) {
            SparkRoundButton(
                iconRes = R.drawable.ic_spark_camera,
                contentDescription = "拍照",
                scale = scale,
                enabled = !showDuplexCall,
                onClick = onCamera,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((36 * scale).dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (showDuplexCall) SparkColors.accent.copy(alpha = 0.10f) else SparkColors.card,
                    )
                    .border(
                        width = 1.dp,
                        color = if (showDuplexCall) SparkColors.accent.copy(alpha = 0.38f) else SparkColors.line,
                        shape = RoundedCornerShape(percent = 50),
                    ),
            ) {
                if (showDuplexCall) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding((3 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 挂断：不用先进全屏
                        Box(
                            modifier = Modifier
                                .width((34 * scale).dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(percent = 50))
                                .background(SparkColors.danger)
                                .clickable { hangUpCall() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_hangup),
                                contentDescription = "挂断",
                                tint = Color.White,
                                modifier = Modifier.size((15 * scale).dp),
                            )
                        }
                        // 状态 + 时长：点一下回到全屏通话
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { enterCall() }
                                .padding(start = (7 * scale).dp, end = (6 * scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (duplexState == DoubaoDuplexClient.State.CONNECTING) {
                                // 接通中：细环占位
                                Box(
                                    modifier = Modifier
                                        .size((9 * scale).dp)
                                        .border(1.5.dp, SparkColors.accent.copy(alpha = 0.4f), CircleShape),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size((5 * scale).dp)
                                        .background(
                                            color = if (duplexState == DoubaoDuplexClient.State.ERROR) {
                                                SparkColors.danger
                                            } else {
                                                SparkColors.accent.copy(alpha = callPulse)
                                            },
                                            shape = CircleShape,
                                        ),
                                )
                            }
                            Spacer(Modifier.width((6 * scale).dp))
                            Text(
                                text = callStatusText(duplexState),
                                color = SparkColors.text,
                                fontSize = (10.5 * scale).sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.weight(1f))
                            if (duplexState != DoubaoDuplexClient.State.ERROR) {
                                Text(
                                    text = callTimerText(callElapsedSec),
                                    color = SparkColors.sub,
                                    fontSize = (10 * scale).sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_chevron),
                                contentDescription = null,
                                tint = SparkColors.sub,
                                modifier = Modifier
                                    .padding(start = (4 * scale).dp)
                                    .size((12 * scale).dp),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding((3 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 电话键：没通话就起一通；通话中（界面收起）就重新展开，不重连
                        Box(
                            modifier = Modifier
                                .width((34 * scale).dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(percent = 50))
                                .background(SparkColors.accent)
                                .clickable(onClick = enterCall),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_phone),
                                contentDescription = "实时通话",
                                tint = SparkColors.accentInk,
                                modifier = Modifier.size((15 * scale).dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(talkGesture),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_mic),
                                contentDescription = null,
                                tint = SparkColors.text.copy(alpha = 0.75f),
                                modifier = Modifier.size((13 * scale).dp),
                            )
                            Spacer(Modifier.width((5 * scale).dp))
                            Text(
                                text = when {
                                    isRecording -> "松手发送 (${recordTick}s)"
                                    else -> "按住说话"
                                },
                                fontSize = (11 * scale).sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SparkColors.text,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            SparkRoundButton(
                iconRes = R.drawable.ic_spark_gallery,
                contentDescription = "相册",
                scale = scale,
                enabled = !showDuplexCall,
                onClick = onGallery,
            )
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
                    turns = duplexTurns,
                    hint = duplexHint,
                    photos = emptyList(),
                    onPickCamera = {},
                    onPickGallery = {},
                    onRemovePhoto = {},
                    onBargeIn = { duplexClient.bargeIn() },
                    startedAtMs = callStartedAt,
                    onHangUp = { hangUpCall() },
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
    val app = LocalContext.current.applicationContext as TravelApp

    // 新内容到达自动滚到底部
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    // 墨玉：不再给整段对话套一张深色大卡（原来像一块板砖压在屏幕上），直接铺在渐变底色上；
    // 小源的话左对齐正文 + 一个小圆点，只有自己说的才用气泡——同高度能多放一倍字。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = (1 * scale).dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy((9 * scale).dp),
    ) {
        if (messages.isEmpty()) {
            Text(
                text = "还没有对话\n按住下面那个按钮说句话\n或者拍张照让我看看",
                color = SparkColors.sub,
                fontSize = (10.5 * scale).sp,
                lineHeight = (16 * scale).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = (26 * scale).dp),
            )
        }

        messages.forEach { msg ->
            if (msg.role == "user") {
                // 自己说的：右侧青色淡底气泡，带图轮展示全部照片缩略图
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
                                        .border(1.dp, SparkColors.line, RoundedCornerShape((8 * scale).dp)),
                                )
                            }
                        }
                        Spacer(Modifier.height((4 * scale).dp))
                    }
                    Text(
                        text = msg.text,
                        color = SparkColors.text,
                        fontSize = (10.5 * scale).sp,
                        lineHeight = (15 * scale).sp,
                        modifier = Modifier
                            .widthIn(max = (220 * scale).dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = (9 * scale).dp,
                                    topEnd = (9 * scale).dp,
                                    bottomEnd = (3 * scale).dp,
                                    bottomStart = (9 * scale).dp,
                                ),
                            )
                            .background(SparkColors.userBubble)
                            .border(
                                1.dp,
                                SparkColors.line,
                                RoundedCornerShape(
                                    topStart = (9 * scale).dp,
                                    topEnd = (9 * scale).dp,
                                    bottomEnd = (3 * scale).dp,
                                    bottomStart = (9 * scale).dp,
                                ),
                            )
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
                // 小源：小圆点 + 左对齐正文（不套气泡），流式中先给省略号占位
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = (5 * scale).dp)
                            .size((5 * scale).dp)
                            .background(SparkColors.accent.copy(alpha = 0.85f), CircleShape),
                    )
                    Spacer(Modifier.width((7 * scale).dp))
                    // 正文下面可以挂地图卡（路线 / 附近检索的工具结果）——和小源说的话在同一个气泡区
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = msg.text.ifBlank { "…" },
                            color = SparkColors.text,
                            fontSize = (11 * scale).sp,
                            lineHeight = (16 * scale).sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(msg) {
                                    detectTapGestures(
                                        onTap = { onMsgTap(msg) },
                                        onLongPress = { onMsgLongPress(msg) },
                                    )
                                },
                        )
                        msg.mapCard?.let { card ->
                            Spacer(Modifier.height((6 * scale).dp))
                            RouteMapCard(card = card, app = app, largeText = false)
                        }
                    }
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

        // 墨玉：全部收敛到同一支青色（不再一会儿薄荷绿一会儿亮青，看着像两套主题）
        val eyeColor = when (eyeState) {
            SparkEyeState.LISTENING -> Color(0xFF46E9F9)
            SparkEyeState.THINKING -> Color(0xFF7FD4FF)
            SparkEyeState.SPEAKING -> Color(0xFF2FD8EE)
            SparkEyeState.MUTED -> Color(0xFF6E8792)
            SparkEyeState.CAPTURING -> Color(0xFFFFC46B)
            SparkEyeState.IDLE -> Color(0xFF3DD9EA)
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

        // 柔光：眼睛背后一圈 18% 的同色径向渐变（旧版没有这层，眼睛像贴纸贴在纯黑上）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(eyeColor.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(cx, cy),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(cx, cy),
        )

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
            colors = listOf(color, color.copy(alpha = 0.82f)),
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

/** 通话条上的状态话术（与胶囊里的小圆点同一个信息源） */
private fun callStatusText(state: DoubaoDuplexClient.State): String = when (state) {
    DoubaoDuplexClient.State.CONNECTING -> "接通中…"
    DoubaoDuplexClient.State.CONNECTED -> "已连接"
    DoubaoDuplexClient.State.LISTENING -> "正在听你说"
    DoubaoDuplexClient.State.SPEAKING -> "正在回答"
    DoubaoDuplexClient.State.CLOSING -> "正在挂断…"
    DoubaoDuplexClient.State.ERROR -> "连接出错，点一下重试"
    else -> "通话中"
}

/** 通话时长 MM:SS */
private fun callTimerText(sec: Int): String =
    String.format("%02d:%02d", (sec / 60).coerceAtLeast(0), (sec % 60).coerceAtLeast(0))
