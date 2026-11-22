package com.lvyoubao.app.ui.largetext

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.ChatSession
import com.lvyoubao.app.data.DeviceTools
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.duplex.DoubaoDuplexClient
import com.lvyoubao.app.data.duplex.DuplexCallSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.SentImageThumbnails
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.common.WakeHub
import com.lvyoubao.app.ui.common.rememberDraftValue
import com.lvyoubao.app.ui.common.CardShareDialog
import com.lvyoubao.app.ui.common.CardShareTipDialog
import com.lvyoubao.app.ui.common.rememberCardShareState
import com.lvyoubao.app.ui.map.RouteMapCard
import com.lvyoubao.app.ui.settings.SettingsScreen
import com.lvyoubao.app.ui.update.UpdateReadyDialog
import com.lvyoubao.app.ui.update.UpdateScreen
import com.lvyoubao.app.ui.update.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 大字模式的大字号常用问法 */
private val LargeTextSuggestions = listOf(
    "附近有啥好吃的？",
    "今天天气怎么样？",
    "附近哪能坐下歇会？",
    "回家的路怎么走？",
)

/**
 * 大字模式（v0.8.0 会话流重构，v0.8.6 改名并接管启动默认模式）：
 * - 会话流：整段对话像微信一样从头到尾摆出来，不再只显示最后一张卡片；
 * - 底部操作区：固定在屏幕底部、永远可见，卡片/流式输出再长也遮不住按钮；
 * - 按住说话：按住录音、松手就记下来，可以连说好几段，说多少记多少，
 *   全部说完再点旁边的「发送」才发出去（充分的停顿时间由握住时长自己决定）；
 * - 拍照提问：拍照/相册的图挂在草稿上，跟语音一起发送；
 * - 语音通话：全屏通话界面，按住说话松手即问，回答自动朗读，像打电话一样聊；
 *   通话里也能拍照/选相册挂图，说完松手图文一起发，文字稿显示与朗读同一份全文（v0.8.8）；
 * - 对话历史：大字号列表，点开切换、可删、可新建；
 * - 右上角「设置」进设置页（家人帮着配 AI/大字模式开关都在这），「退出」直接退出应用；
 * - 是否默认进入大字模式由设置里的开关决定（设置里关了，下次启动就是普通模式）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LargeTextScreen(
    app: TravelApp,
    onExitApp: () -> Unit,
    newVersion: String? = null, // 非空 = 有新版本，顶部操作带出更新横幅（MainActivity 统一检查）
    onUpdateClick: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ChatViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val streamState by vm.stream.collectAsState()
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val autoSendIn by vm.autoSendCountdown.collectAsState()
    val sessionList by vm.sessionList.collectAsState()
    val currentSessionId by vm.currentSessionId.collectAsState()
    val savedEvidenceTitles by vm.savedEvidenceTitles.collectAsState()
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showHistory by remember { mutableStateOf(false) }
    var showCall by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) } // 转写/系统识别进行中
    var recording by remember { mutableStateOf<SpeechInput.Recording?>(null) }
    var recordStartTs by remember { mutableStateOf(0L) }
    var recordTick by remember { mutableIntStateOf(0) } // 录音秒数（每秒自增驱动刷新）

    val updateVm: UpdateViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { UpdateViewModel(app) } } },
    )

    BackHandler {
        when {
            showUpdate -> showUpdate = false
            showSettings -> showSettings = false
            showHistory -> showHistory = false
            showCall -> {
                TtsPlayer.stop()
                showCall = false
            }
            else -> {
                // 大字模式是本应用的默认模式：返回键/退出键都是退出应用（回到桌面），
                // 不再切回普通模式——模式切换只认设置里的「大字模式」开关
                TtsPlayer.stop()
                onExitApp()
            }
        }
    }

    // ---------- 语音：按住说话、松手记录 ----------

    fun beginCloudRecord() {
        // 开录前先闭嘴：别让朗读声混进麦克风
        TtsPlayer.stop()
        SpeechInput.startRecording(context)
            .onSuccess {
                recording = it
                recordStartTs = System.currentTimeMillis()
                recordTick = 0
            }
            .onFailure { vm.setTransientError("录音启动失败，请再试一次") }
    }

    val recordPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginCloudRecord() else vm.setTransientError("没有录音权限，无法把语音转成文字")
    }

    /** 按下：开始录音（配了转写 Key 走录音+云端转字；没有就用系统识别点一下说） */
    fun startHoldTalk() {
        if (recording != null || listening) return
        // 再按住说话=上一段的自动发送倒计时作废，说完这段重新算
        vm.cancelAutoSend()
        if (settings.asrKey.isNotBlank()) {
            if (SpeechInput.hasRecordPermission(context)) beginCloudRecord()
            else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (SpeechInput.isAvailable(context)) {
            // 没配 Key：系统识别兜底（它自己管理断句，已把停顿阈值放宽到静音 8 秒）
            listening = true
            scope.launch {
                TtsPlayer.stop()
                HotwordCorrector.buildIfNeeded(vm.sessionList.value, vm.quickNotes, vm.memoryFacts)
                SpeechInput.listen(context, "请说话")
                    .onSuccess { vm.appendVoiceTranscript(it) }
                    .onFailure { vm.setTransientError(it.message ?: "没听清楚，请再试一次") }
                listening = false
            }
        } else {
            vm.setTransientError("这台手机没有语音识别。可让家人在设置里填「语音识别 Key」（免费），或点输入框打字提问")
        }
    }

    /** 松手：停止录音 → 转文字 → 记进草稿。通话界面里 autoSend=true，松手即问 */
    fun endHoldTalk(autoSend: Boolean) {
        val rec = recording ?: return
        recording = null
        listening = true
        scope.launch {
            SpeechInput.stopAndTranscribe(rec, settings.asrKey, settings.dialectMode)
                .onSuccess { spoken ->
                    HotwordCorrector.buildIfNeeded(vm.sessionList.value, vm.quickNotes, vm.memoryFacts)
                    // 通话模式松手即发：不等 AI 纠错（模型看得到上下文，理解不受影响），只做本地热词
                    vm.appendVoiceTranscript(spoken, polish = !autoSend)
                    if (autoSend) vm.send(context)
                }
                .onFailure { vm.setTransientError(it.message ?: "没听清楚，请再试一次") }
            listening = false
        }
    }

    /** 按住说话手势：按下开始录，松开记录。放在公共函数里供主界面与通话界面共用 */
    fun holdToTalkModifier(autoSend: Boolean): Modifier = Modifier.pointerInput("hold_$autoSend") {
        detectTapGestures(
            onPress = {
                startHoldTalk()
                try {
                    awaitRelease()
                } catch (_: Exception) {
                    // 手势被系统打断（如来电），也按松手处理，别把录音吊死
                }
                endHoldTalk(autoSend)
            },
        )
    }

    // 录音秒数计时
    LaunchedEffect(recording != null) {
        while (recording != null) {
            delay(1_000)
            recordTick++
        }
    }

    DisposableEffect(Unit) {
        onDispose { recording?.let { SpeechInput.cancelRecording(it) } }
    }

    // ---------- 附件：拍照 / 相册 ----------

    fun handlePicked(uri: android.net.Uri) {
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ChatViewModel.readAttachment(context, uri) }
            }
            result.onSuccess { (attachment, warning) ->
                vm.addAttachment(attachment)
                warning?.let { vm.setTransientError(it) }
            }.onFailure {
                vm.setTransientError("附件读取失败，请重试或换个文件")
            }
        }
    }

    // 豆包全双工：client 常驻 remember，进入 doubao 通话时 start，退出 close；classic 链路完全不动。
    val duplexClient = remember { DoubaoDuplexClient(context) }
    val duplexState by duplexClient.state.collectAsState()
    var duplexAsr by remember { mutableStateOf("") }
    var duplexAnswer by remember { mutableStateOf("") }
    var duplexHint by remember { mutableStateOf<String?>(null) }
    // 豆包通话中的拍照缓冲：图片随该轮对话落会话历史（纯语音 S2S 不解析图内容，只作记录）
    var duplexPhotos by remember { mutableStateOf<List<ChatViewModel.PendingAttachment>>(emptyList()) }
    // 通话中 FC 工具带回的地图卡缓冲：随本轮回答一起挂到会话
    val duplexMapCards = remember { mutableStateListOf<MapCardData>() }
    // 上一轮已落库的（识别|回答）指纹：同一轮被重复收口只落一次
    var lastDuplexTurnKey by remember { mutableStateOf("") }
    // 通话会话流（识别=右、回答=左），覆盖层直接渲染
    val duplexTurns = remember { mutableStateListOf<DuplexTurnUi>() }

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

    /** 豆包通话专用：拍照/选图只读附件加入 duplexPhotos，不走 vm 的经典发送链 */
    fun handleDuplexPicked(uri: android.net.Uri) {
        scope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ChatViewModel.readAttachment(context, uri) }
            }
            result.onSuccess { (attachment, warning) ->
                if (attachment.isImage) duplexPhotos = duplexPhotos + attachment
                warning?.let { duplexHint = it }
            }.onFailure { duplexHint = "照片读取失败，请重试" }
        }
    }

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            if (showCall && settings.callEngine == "doubao") handleDuplexPicked(uri) else handlePicked(uri)
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_PICK_IMAGES),
    ) { uris ->
        val duplex = showCall && settings.callEngine == "doubao"
        uris.forEach { if (duplex) handleDuplexPicked(it) else handlePicked(it) }
    }

    // ---------- 自动朗读：新回答一落地就读（通话中不依赖开关，天然要出声） ----------

    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
    var spokenTs by remember { mutableStateOf(0L) }
    val ttsState by TtsPlayer.state.collectAsState()
    // 先把「这次要不要出声」告诉 VM：它会在 AI 一开始吐字就按句开播，
    // 而不是等整段回答落地再整段合成——那正是普通/大字模式比灵动模式慢几秒的根因。
    LaunchedEffect(settings.autoSpeak, showCall, settings.callEngine) {
        val duplexCall = showCall && settings.callEngine == "doubao"
        vm.setLiveSpeakEnabled(!duplexCall && (showCall || settings.autoSpeak))
    }
    LaunchedEffect(lastAssistant?.ts, settings.autoSpeak, showCall, showSettings, showUpdate, settings.callEngine) {
        val msg = lastAssistant ?: return@LaunchedEffect
        // 只读 20 秒内的新回答：切历史/重进不再把旧消息复读一遍；停在设置/更新页时也先别出声
        val fresh = System.currentTimeMillis() - msg.ts < 20_000
        val overlayOpen = showSettings || showUpdate
        // 豆包全双工自己走 WebSocket 出声（音频在 DuplexAudioEngine 里播放），
        // 绝不能再让 TtsPlayer 读同一段回答，否则两套音频抢 AudioTrack 双重发声。
        val duplexCall = showCall && settings.callEngine == "doubao"
        if (!duplexCall && (showCall || settings.autoSpeak) && !overlayOpen && msg.ts != spokenTs && fresh) {
            spokenTs = msg.ts
            // 等这一轮的播报裁决：PENDING = 流式还在接第一句（4 字首句实测要 1.7 秒才出声），
            // 最多等 4.5 秒；已经出声就什么都不做，只有彻底没出声才整段读
            val deadline = System.currentTimeMillis() + 4_500
            while (System.currentTimeMillis() < deadline && vm.speechState.value == ChatViewModel.Speech.PENDING) {
                delay(120)
            }
            if (vm.speechState.value == ChatViewModel.Speech.STREAMING) return@LaunchedEffect
            com.lvyoubao.app.data.LogStore.log("autospeak", "整段兜底播报：流式没出声，回答 ${msg.text.length} 字")
            TtsPlayer.speak(context, ChatViewModel.speakableText(msg))
        }
    }

    // 新消息/流式输出增长时滚到最底
    LaunchedEffect(messages.size, state.sending, streamState?.answer?.length) {
        if (messages.isNotEmpty()) {
            val target = (messages.size - 1 + if (state.sending) 1 else 0).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    val canSend = (state.question.isNotBlank() || state.attachments.isNotEmpty()) && !state.sending && !state.polishing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---------- 顶部操作带：浅青灰底 + 圆角下缘 + 投影，把「操作」和「会话」隔成两个房间 ----------
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        // 头部：标题 + 五个功能入口（新对话 / 历史 / 通话 / 设置 / 退出）收成一行。
        // 以前是「第一行 设置+退出、第二行 新对话+历史+通话」两排大按钮，
        // 240px 高的小屏光控件就吃掉三分之一，手机上更显得散。退出单独留红字，不藏进菜单。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "大字模式",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            HeaderIcon(Icons.Default.Add, "新对话") { keyboard?.hide(); vm.newSession() }
            HeaderIcon(Icons.Default.List, "历史对话") { keyboard?.hide(); showHistory = true }
            HeaderIcon(Icons.Default.Phone, "通话") { keyboard?.hide(); showCall = true }
            HeaderIcon(Icons.Default.Settings, "设置") { keyboard?.hide(); showSettings = true }
            // 退出：直接退出应用回桌面（模式切换只认设置里的「大字模式」开关）
            TextButton(
                onClick = {
                    TtsPlayer.stop()
                    onExitApp()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    "退出",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
        // 更新横幅：发现新版本时占满一行（大字模式下也要能看到、点得到）
        if (newVersion != null) {
            Button(
                onClick = onUpdateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    "🚀 发现新版本 $newVersion，点按更新",
                    fontSize = 17.sp,
                    maxLines = 1,
                )
            }
        }
        }
        }

        // ---------- 会话流：整个对话从头到尾 ----------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (messages.isEmpty() && !state.sending) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("有事您说话", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "按住下面的麦克风说话，\n说完松手就记下来，可以连说几段，\n最后点「发送」。",
                            fontSize = 20.sp,
                            lineHeight = 30.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(28.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LargeTextSuggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = {
                                        vm.updateQuestion(suggestion)
                                        vm.send(context)
                                    },
                                    // 大字模式 chip 加高到 44dp，19sp 字号不再挤在默认 32dp 的框里
                                    modifier = Modifier.height(44.dp),
                                    label = { Text(suggestion, fontSize = 19.sp) },
                                )
                            }
                        }
                    }
                }
            }
            items(messages) { message ->
                when (message.role) {
                    "user" -> LargeTextUserBubble(
                        message,
                        onEdit = { vm.editMessage(message) },
                        onResend = { vm.resendMessage(context, message) },
                    )
                    else -> LargeTextAssistantCard(
                        message = message,
                        app = app,
                        ttsActive = ttsState is TtsPlayer.State.Speaking || ttsState is TtsPlayer.State.Loading,
                        onToggleSpeak = {
                            TtsPlayer.toggle(context, ChatViewModel.speakableText(message))
                        },
                        savedMemory = vm.evidenceTitle(message) in savedEvidenceTitles,
                        onToggleMemory = { vm.toggleSaveMemory(message) },
                    )
                }
            }
            if (state.sending) {
                item { LargeTextStreamingCard(streamState) }
            }
        }

        state.error?.let { error ->
            Text(
                error,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---------- 底部操作区：固定在下方，独立滚动区域，永远不会被卡片盖住。
        // 浅青灰底与顶部操作带同色呼应——上下两条「操作带」夹着中间的会话区 ----------
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .shadow(elevation = 14.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding() // 键盘弹出时整块上移，输入框不被键盘盖住
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 附件缩略条
                if (state.attachments.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.attachments.forEach { a ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        (if (a.isImage) "🖼 " else "📄 ") + a.name.take(14),
                                        fontSize = 16.sp,
                                    )
                                    IconButton(onClick = { vm.removeAttachment(a) }, modifier = Modifier.size(28.dp)) {
                                        Text("✕", fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // AI 智能纠错进行中：发送被禁用，改完自动回填输入框（8 秒超时放行）
                if (state.polishing) {
                    Text(
                        "⏳ 正在智能纠错（结合上下文修识别错字），改完才能发送…",
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                // 语音自动发送倒计时（设置里开启后才出现）：点输入框/再按住说话/手动编辑都会取消
                autoSendIn?.let { sec ->
                    Text(
                        "⏳ $sec 秒后自动发送，点一下输入框可取消",
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                // 草稿输入框：语音按段累积进这里，也可以直接打字。
                // 光标交给 TextFieldValue 托管：语音从外面追加文字时把光标放到末尾，
                // 不然识别结果停在原光标处，看着像「跑到最前面」
                val draftValue = rememberDraftValue(state.question)
                OutlinedTextField(
                    value = draftValue.value,
                    onValueChange = {
                        draftValue.value = it
                        vm.updateQuestion(it.text)
                    },
                    // 点输入框（获得焦点）= 用户要自己接管，取消语音自动发送
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) vm.cancelAutoSend() },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    placeholder = {
                        // 一行放得下的写法：21sp 大字下原句太长会折行，不好看
                        Text(
                            if (recording != null) "正在听你说…" else "按住下方说话，松手记到这里",
                            fontSize = 21.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 21.sp, lineHeight = 30.sp),
                    shape = RoundedCornerShape(18.dp),
                    trailingIcon = {
                        if (state.question.isNotBlank()) {
                            TextButton(onClick = { vm.clearQuestion() }) { Text("清空", fontSize = 16.sp) }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                // 主操作行：拍照 | 相册 | 按住说话（大） | 发送（大）——说话按钮挨着发送，说完顺手就发
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 拍照
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                keyboard?.hide()
                                val uri = newCameraImageUri(context)
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("📷", fontSize = 26.sp) }
                    // 相册
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                keyboard?.hide()
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("🖼", fontSize = 26.sp) }
                    // 按住说话大按钮（标签保持一行，不换行不截断）
                    val holdBig = settings.asrKey.isNotBlank()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (recording != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                            .then(holdToTalkModifier(autoSend = false)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when {
                                recording != null -> "松手（${recordTick}s）"
                                listening -> "正在记录…"
                                // 去掉麦克风 emoji：按钮宽度本就紧张，emoji 挤得文字贴边
                                holdBig -> "按住说话"
                                else -> "点一下说"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp),
                            fontSize = 20.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            fontWeight = FontWeight.Bold,
                            color = if (recording != null) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
                    // 发送
                    Button(
                        onClick = {
                            keyboard?.hide()
                            vm.send(context)
                        },
                        enabled = canSend,
                        modifier = Modifier.height(80.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Text("发送", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (state.sending) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在帮你想，稍等一下…", fontSize = 20.sp)
                    }
                }
            }
        }
    }

    // ---------- 对话历史（全屏大字号列表） ----------
    if (showHistory) {
        LargeTextHistoryOverlay(
            sessions = sessionList,
            currentId = currentSessionId,
            onPick = { id -> vm.switchSession(id) { showHistory = false } },
            onNew = { vm.newSession { showHistory = false } },
            onDelete = { vm.deleteSession(it) },
            onClose = { showHistory = false },
        )
    }

    // ---------- 语音通话（全屏） ----------
    // 收集豆包事件：ASR/回答流累加到局部状态，错误转成提示；播报状态看 State 不看 ASR。
    LaunchedEffect(Unit) {
        duplexClient.onEvent.collect { ev ->
            when (ev) {
                is DoubaoDuplexClient.Event.AsrStarted -> {
                    duplexAsr = ""
                    duplexNewUserTurn()
                }
                // transcription.delta 是累计快照，直接覆盖（用 += 会叠成「你你好你好啊」）
                is DoubaoDuplexClient.Event.Asr -> {
                    duplexAsr = ev.text
                    duplexUpdateUser(ev.text)
                }
                is DoubaoDuplexClient.Event.Answer -> {
                    if (ev.isFinal) {
                        duplexAnswer = ev.text
                    } else {
                        // 兼容两种下发：累计快照（新文本以旧文本开头）直接替换，真增量才追加
                        duplexAnswer = if (duplexAnswer.isNotEmpty() && ev.text.startsWith(duplexAnswer)) ev.text
                        else duplexAnswer + ev.text
                    }
                    duplexUpdateAssistant(duplexAnswer, done = ev.isFinal)
                }
                // 模型决定调工具：地图三件套走 AppTools，本机能力（机器人/计时/记事/记忆/音量/电量）
                // 走 DeviceTools——大字/普通模式没有本地命令链，全靠这条 FC 路补齐。
                // 失败也要回传——不回传模型就一直干等。
                is DoubaoDuplexClient.Event.ToolCall -> {
                    val ackJob = scope.launch {
                        delay(1_200)
                        duplexClient.speak("我查一下哈") // 执行超过 1.2 秒先出声，别让通话死寂
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                when (ev.name) {
                                    AppTools.POI, AppTools.WEATHER, AppTools.ROUTE ->
                                        AppTools.execute(context, settings, ev.name, ev.arguments) { duplexMapCards.add(it) }
                                    else -> DeviceTools.execute(context, settings, ev.name, ev.arguments)
                                }
                            }.getOrElse { "工具执行失败：${it.message ?: "未知原因"}。请如实告知用户。" }
                        }
                        ackJob.cancel()
                        duplexClient.sendToolResult(ev.callId, result)
                    }
                }
                // 识别流里蹦出急停词：客户端已停播+取消回答，这里把机器人也停住
                is DoubaoDuplexClient.Event.StopRequested -> {
                    scope.launch { DeviceTools.stopRobot(context, settings) }
                }
                // 一轮收口（response.done / 被打断的残句都走这）：落会话历史的唯一入口。
                // FC 一轮可能多段收口，ChatViewModel 里会就地并进同一张卡。
                is DoubaoDuplexClient.Event.TurnDone -> {
                    duplexAnswer = ev.answer
                    duplexUpdateAssistant(ev.answer, done = true)
                    if (ev.answer.isNotBlank()) {
                        val key = ev.asr.filterNot { it.isWhitespace() } + "|" + ev.answer.filterNot { it.isWhitespace() }
                        if (key != lastDuplexTurnKey) {
                            lastDuplexTurnKey = key
                            val paths = duplexPhotos.mapNotNull { it.localPath }
                            vm.appendDuplexTurn(ev.asr, ev.answer, paths)
                            duplexPhotos = emptyList()
                            vm.summarizeCallTurns() // 通话也是会话：攒够了就总结进长期记忆
                        }
                    }
                    duplexMapCards.lastOrNull()?.let { vm.attachDuplexMapCard(it) }
                    duplexMapCards.clear()
                }
                is DoubaoDuplexClient.Event.SessionReady -> {
                    duplexAsr = ""
                    duplexAnswer = ""
                    duplexHint = null
                    duplexPhotos = emptyList()
                    duplexMapCards.clear()
                    lastDuplexTurnKey = ""
                    duplexTurns.clear()
                }
                is DoubaoDuplexClient.Event.Error -> duplexHint = when (ev.code) {
                    "CONFIG" -> "未配置豆包实时语音 Key，请到设置里填写后再通话"
                    else -> "通话出错：${ev.message}"
                }
                else -> Unit
            }
        }
    }

    // 进入/退出豆包通话：start / close。key 为空时不强启，给出提示（避免老用户默认 doubao 却没 key）。
    DisposableEffect(showCall, settings.callEngine) {
        if (showCall && settings.callEngine == "doubao") {
            TtsPlayer.stop() // 先让 classic 朗读闭嘴，避免抢音频输出
            // 通话期间不许熄屏 + 压住 WiFi 省电：息屏后 WiFi 入睡会让实时音频流断开，
            // 现象就是「黑屏之后听不到声音」（大字/普通模式同样需要，不只在灵动模式）
            com.lvyoubao.app.ui.common.ScreenAwake.pin("豆包通话")
            com.lvyoubao.app.ui.common.NetKeep.acquire(context, "doubao-call")
            duplexAsr = ""
            duplexAnswer = ""
            duplexHint = null
            if (settings.duplexApiKey.isBlank()) {
                duplexHint = "未配置豆包实时语音 Key，请到设置里填写后再通话"
            } else if (!SpeechInput.hasRecordPermission(context)) {
                duplexHint = "需要麦克风权限才能全双工通话"
            } else {
                // 记忆 + 能力一次带上：人设/长期记忆/时间位置进 instructions，
                // 共享受话的最近成对 QA 进 dialog_context，天气/附近/路线进 tools
                val setup = DuplexCallSetup.build(
                    context,
                    persona = DuplexCallSetup.PERSONA_TRAVEL,
                    history = vm.duplexHistory(currentSessionId),
                    settings = settings,
                    deviceTools = true, // 大字/普通没有本地命令链，机器人/计时等靠 FC 补齐
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
            if (settings.callEngine == "doubao") {
                duplexClient.close()
                com.lvyoubao.app.ui.common.NetKeep.release("doubao-call")
                com.lvyoubao.app.ui.common.ScreenAwake.unpin("豆包通话")
            }
        }
    }
    // 彻底退出应用时释放 OkHttp 线程池（不可复用，仅 exit 时调用）
    DisposableEffect(Unit) {
        onDispose { duplexClient.destroy() }
    }

    // ---------- 唤醒词（端侧 KWS 零配额；唤醒后按设置进通话或单次语音） ----------
    var wakeListening by remember { mutableStateOf(false) }
    fun dispatchWake() {
        if (settings.wakeAction == "call" && settings.callEngine == "doubao" &&
            settings.duplexApiKey.isNotBlank() && SpeechInput.hasRecordPermission(context)
        ) {
            showCall = true // 上面的 DisposableEffect 会自动起豆包通话
        } else {
            wakeListening = true
            WakeHub.startOneShot(context, settings.asrKey, settings.dialectMode) { text ->
                wakeListening = false
                if (!text.isNullOrBlank()) vm.appendVoiceTranscript(text) // 自带「自动发送」调度
            }
        }
    }
    LaunchedEffect(
        settings.wakeWordEnabled, settings.wakeAction, settings.callEngine, settings.duplexApiKey,
        recording, listening, wakeListening, showCall,
    ) {
        val inCall = showCall && settings.callEngine == "doubao"
        val can = settings.wakeWordEnabled && !inCall && recording == null && !listening && !wakeListening &&
            SpeechInput.hasRecordPermission(context)
        if (can) WakeHub.startStandby(context) { dispatchWake() } else WakeHub.stopStandby()
    }
    DisposableEffect(Unit) {
        onDispose {
            WakeHub.stopStandby()
            WakeHub.cancelOneShot()
        }
    }

    if (showCall) {
        if (settings.callEngine == "doubao") {
            DoubaoCallOverlay(
                state = duplexState,
                turns = duplexTurns,
                hint = duplexHint,
                photos = duplexPhotos,
                onPickCamera = {
                    val uri = newCameraImageUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onPickGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemovePhoto = { idx -> duplexPhotos = duplexPhotos.filterIndexed { i, _ -> i != idx } },
                onBargeIn = { duplexClient.bargeIn() },
                onHangUp = {
                    duplexClient.close()
                    showCall = false
                },
            )
        } else {
            VoiceCallOverlay(
                messages = messages,
                streamState = streamState,
                sending = state.sending,
                attachments = state.attachments,
                listening = listening,
                recording = recording,
                recordTick = recordTick,
                ttsSpeaking = ttsState is TtsPlayer.State.Speaking || ttsState is TtsPlayer.State.Loading,
                ttsUnavailable = (ttsState as? TtsPlayer.State.Unavailable)?.reason,
                holdModifier = holdToTalkModifier(autoSend = true),
                onPickCamera = {
                    keyboard?.hide()
                    val uri = newCameraImageUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onPickGallery = {
                    keyboard?.hide()
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemoveAttachment = { vm.removeAttachment(it) },
                onHangUp = {
                    TtsPlayer.stop()
                    showCall = false
                },
            )
        }
    }

    // 自动更新就绪弹窗：新版包后台下载完成就弹「安装」；更新页打开时不重复弹
    val autoUpdateState by com.lvyoubao.app.data.AutoUpdater.state.collectAsState()
    if (!showUpdate && autoUpdateState.downloadedApk != null && autoUpdateState.release != null &&
        autoUpdateState.dismissedTag != autoUpdateState.release?.tag_name
    ) {
        UpdateReadyDialog(autoUpdateState)
    }

    // ---------- 设置 / 更新（家人帮忙配置时全屏盖在大字模式上） ----------
    // 这两页自身不带背景色（普通模式里靠 MainActivity 根布局的 Surface 补底），
    // 叠在大字模式上必须自己垫一整屏不透明 Surface，不然底下会话内容会透出来
    if (showUpdate) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            UpdateScreen(onClose = { showUpdate = false }, vm = updateVm)
        }
    } else if (showSettings) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SettingsScreen(
                onClose = { showSettings = false },
                onOpenUpdate = { showUpdate = true },
                app = app,
            )
        }
    }
}

/** 用户消息：右侧大气泡，超大字，带图时显示缩略图（点击看大图），长按可选中复制，下方可编辑/重发 */
@Composable
private fun LargeTextUserBubble(message: SessionMessage, onEdit: () -> Unit, onResend: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Surface(
                    shape = RoundedCornerShape(22.dp, 6.dp, 22.dp, 22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.imagePaths.isNotEmpty()) {
                            SentImageThumbnails(paths = message.imagePaths)
                        }
                        if (message.text.isNotBlank()) {
                            Text(
                                (if (message.imagePaths.isEmpty() && message.hadImage) "🖼 " else "") + message.text,
                                fontSize = 22.sp,
                                lineHeight = 32.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
        // 编辑/重发：撤回这条及其后的消息——文本图片回输入框改完再发，或原样再问一遍
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            // 浅青灰底：对已发消息的修补动作，不再与主操作抢深青色
            Button(
                onClick = onEdit,
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) { Text("✏️ 编辑", fontSize = 17.sp) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onResend,
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) { Text("🔁 重发", fontSize = 17.sp) }
        }
    }
}

/** 助手卡片：超大字回答 + 要点 + 地图卡 + 再读一遍/收藏 */
@Composable
private fun LargeTextAssistantCard(
    message: SessionMessage,
    app: TravelApp,
    ttsActive: Boolean,
    onToggleSpeak: () -> Unit,
    savedMemory: Boolean,
    onToggleMemory: () -> Unit,
) {
    // 分享：卡片图生成/微信/相册/权限，统一在 CardShareUi 收口
    val shareState = rememberCardShareState(message)
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        // 文字整体可长按选中、自由复制（按钮不受影响）
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.padding(18.dp)) {
                // 富内容：表格/图表自动渲染成可全屏的卡片（字号随大字模式放大）
                com.lvyoubao.app.ui.common.RichContent(
                    text = message.text,
                    fontSize = 25.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.Bold,
                    largeText = true,
                )
                message.steps.forEachIndexed { index, step ->
                    Spacer(Modifier.height(10.dp))
                    Text("${index + 1}. $step", fontSize = 22.sp, lineHeight = 32.sp)
                }
                message.action?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("👉 $it", fontSize = 21.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        message.mapCard?.let { card ->
            Spacer(Modifier.height(8.dp))
            RouteMapCard(card = card, app = app, largeText = true)
        }
        Spacer(Modifier.height(10.dp))
        // 按钮行与卡片边缘留出与正文一致的内边距；三颗按钮等宽分摊（不换行、分享不再被挤出屏幕）。
        // 卡片上的动作一律浅青灰底（surfaceVariant），卡片回归内容主体，深青不再往回答区里扎堆
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onToggleSpeak,
                modifier = Modifier.weight(1f).height(52.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(if (ttsActive) "⏹ 别读了" else "🔊 朗读", fontSize = 17.sp, maxLines = 1) }
            // 收藏切换：已收藏换浅橙底深棕字（大字模式青底绿字看不清），再点恢复浅青灰「收藏」
            Button(
                onClick = onToggleMemory,
                modifier = Modifier.weight(1f).height(52.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (savedMemory) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(if (savedMemory) "✔ 已收藏" else "收藏", fontSize = 17.sp, maxLines = 1)
            }
            Button(
                onClick = { shareState.open() },
                modifier = Modifier.weight(1f).height(52.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("分享", fontSize = 17.sp, maxLines = 1) }
        }
    }
    CardShareDialog(shareState)
    CardShareTipDialog(shareState)
}


/** 顶部功能图标：40dp 触控区 + 22dp 图标（小屏上比 17sp 文字按钮省一半地方） */
@Composable
private fun HeaderIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 生成中的回答：实时流式正文，超大字，可选中复制 */
@Composable
private fun LargeTextStreamingCard(streamState: ChatViewModel.StreamState?) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.padding(18.dp)) {
                if (streamState == null || (streamState.answer.isBlank() && streamState.reasoning.isBlank())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在帮你想…", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    if (streamState.answer.isBlank() && streamState.reasoning.isNotBlank()) {
                        Text("正在想…", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (streamState.answer.isNotBlank()) {
                        com.lvyoubao.app.ui.common.RichContent(
                            text = streamState.answer,
                            fontSize = 25.sp,
                            lineHeight = 37.sp,
                            fontWeight = FontWeight.Bold,
                            largeText = true,
                        )
                    }
                }
            }
        }
    }
}

/** 对话历史：全屏列表，大字号，点了就切过去 */
@Composable
private fun LargeTextHistoryOverlay(
    sessions: List<ChatSession>,
    currentId: String?,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("对话历史", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.height(52.dp)) {
                    Text("关闭", fontSize = 17.sp)
                }
            }
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("新对话", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            Spacer(Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text("还没有对话", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == currentId
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(session.id) },
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    fontSize = 21.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    session.messages.lastOrNull()?.text?.take(36)?.replace("\n", " ") ?: "（空会话）",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.updatedAt)) +
                                        if (selected) " · 当前对话" else "",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(session.id) }, modifier = Modifier.size(44.dp)) {
                                Text("🗑", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 通话会话流的一条消息：user=识别稿（右侧），assistant=回答（左侧）；streaming=还在收字 */
data class DuplexTurnUi(val role: String, val text: String, val streaming: Boolean = false)

/**
 * 豆包全双工通话界面（端到端 S2S）：不走 ASR→LLM→TTS 串行链路，
 * 音频由 DuplexAudioEngine 实时收发；界面按会话流排版（识别右/回答左），
 * 插话按一下（bargeIn）即可打断播报。 fab 按钮常驻，无需按住说话。
 */
@Composable
internal fun DoubaoCallOverlay(
    state: DoubaoDuplexClient.State,
    turns: List<DuplexTurnUi>,
    hint: String?,
    photos: List<ChatViewModel.PendingAttachment>,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onBargeIn: () -> Unit,
    onHangUp: () -> Unit,
    showPhotoActions: Boolean = true,
    // 通话起算时刻（wall clock ms）：界面收起再展开时计时接着走，不会被重置成 00:00。
    // 传 0 就按本层首次组合的时刻兜底（大字模式那条老链路不需改）。
    startedAtMs: Long = 0L,
) {
    val baseMs = remember(startedAtMs) {
        if (startedAtMs > 0L) startedAtMs else System.currentTimeMillis()
    }
    val seconds = remember { mutableStateOf(0) }
    LaunchedEffect(state, baseMs) {
        while (state == DoubaoDuplexClient.State.LISTENING || state == DoubaoDuplexClient.State.SPEAKING || state == DoubaoDuplexClient.State.CONNECTED) {
            kotlinx.coroutines.delay(1000)
            seconds.value = ((System.currentTimeMillis() - baseMs) / 1000L).toInt().coerceAtLeast(0)
        }
    }
    val statusText = when (state) {
        DoubaoDuplexClient.State.CONNECTING -> "正在连接…"
        DoubaoDuplexClient.State.CONNECTED -> "已连接，正在建立会话…"
        DoubaoDuplexClient.State.LISTENING -> "正在听你说"
        DoubaoDuplexClient.State.SPEAKING -> "正在回答，想插话就点一下"
        DoubaoDuplexClient.State.CLOSING -> "正在挂断…"
        DoubaoDuplexClient.State.ERROR -> "连接出错"
        else -> "语音通话中"
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 顶栏：状态 + 计时 + 挂断
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("语音通话中", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(statusText, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "%02d:%02d".format(seconds.value / 60, seconds.value % 60),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onHangUp,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) { Text("挂断") }
            }

            hint?.let {
                Text(
                    it,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            // 文字稿：会话流排版（识别=右、回答=左，像聊天一样往下滚，新内容自动滚到底）
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length, turns.lastOrNull()?.streaming) {
                if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(turns.size) { idx ->
                    val t = turns[idx]
                    val body = if (t.streaming && t.text.isBlank()) "…" else t.text
                    if (t.role == "user") {
                        Surface(
                            color = Color(0xFFD8EEF8),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End),
                        ) {
                            Text(body, fontSize = 22.sp, color = Color(0xFF0B3A55), modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
                        ) {
                            Text(body, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }

            // 待发照片条：随本轮对话一起落历史（纯语音 S2S 不解析图内容，只作记录）
            if (photos.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(photos.size) { idx ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                                Text("📷", fontSize = 20.sp)
                                IconButton(onClick = { onRemovePhoto(idx) }) {
                                    Text("✕", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 底部操作区：拍照 / 相册 / 插话打断（全双工常开采集，无需按住说话）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPhotoActions) {
                    Button(
                        onClick = onPickCamera,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) { Text("拍照", fontSize = 16.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onPickGallery,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) { Text("相册", fontSize = 16.sp) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onBargeIn,
                    enabled = state == DoubaoDuplexClient.State.SPEAKING || state == DoubaoDuplexClient.State.LISTENING,
                    modifier = Modifier.height(56.dp).weight(1f),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("插话打断", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * 语音通话：像打电话一样的全屏界面。
 * 按住说话、松手就问，回答自动朗读；朗读时随时按住即可插话（会先停掉朗读再录）。
 * 中间展示会话文字稿（含正在生成的回答）：内容与朗读同一份全文（回答+要点+建议，
 * v0.8.8 起不再只有正文——之前"语音播报是完整的、文字稿缺内容"就是这来的），
 * 字号 22sp 与主界面一致，可长按选中复制；
 * 底部常驻操作区：拍照/相册可把图挂到草稿（说完松手图文一起发）+ 按住说话/挂断。
 * 全链路免费：录音→云端方言口音转文字→大模型回答→Edge/云端朗读。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCallOverlay(
    messages: List<SessionMessage>,
    streamState: ChatViewModel.StreamState?,
    sending: Boolean,
    attachments: List<ChatViewModel.PendingAttachment>,
    listening: Boolean,
    recording: SpeechInput.Recording?,
    recordTick: Int,
    ttsSpeaking: Boolean,
    ttsUnavailable: String?,
    holdModifier: Modifier,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onRemoveAttachment: (ChatViewModel.PendingAttachment) -> Unit,
    onHangUp: () -> Unit,
) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    val transcriptState = rememberLazyListState()
    // 新消息/流式输出增长时文字稿滚到底
    LaunchedEffect(messages.size, sending, streamState?.answer?.length) {
        if (messages.isNotEmpty()) {
            val target = (messages.size - 1 + if (sending) 1 else 0).coerceAtLeast(0)
            runCatching { transcriptState.animateScrollToItem(target) }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("语音通话中", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(formatCallDuration(seconds), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            val status = when {
                recording != null -> "正在听你说…（${recordTick}s，说完松手）"
                listening -> "正在记下来…"
                sending -> "正在帮你想…"
                ttsSpeaking -> "正在回答，想插话就按住说话"
                else -> "按住下面的按钮说话"
            }
            Text(
                status,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            ttsUnavailable?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // 会话文字稿：固定占满中间一整块，看得见 AI 说了什么；每条消息自己包选择容器（长按可复制）。
            // 注意 weight 必须挂在 Column 的直接子项上：之前整个 LazyColumn 包在 SelectionContainer 里，
            // weight 失效——空的时候两个按钮全顶到上半屏，消息一多按钮反被顶出屏幕
            LazyColumn(
                state = transcriptState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { message ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        if (message.role == "user") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (message.imagePaths.isNotEmpty()) {
                                            SentImageThumbnails(paths = message.imagePaths, thumbSize = 96.dp)
                                        }
                                        if (message.text.isNotBlank()) {
                                            Text(
                                                (if (message.imagePaths.isEmpty() && message.hadImage) "🖼 " else "") + message.text,
                                                fontSize = 22.sp,
                                                lineHeight = 32.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    // 文字稿 = 朗读同一份全文（回答+要点+建议），只是把"第N条"换成编号换行，
                                    // 之前只显示正文，播报完整而文字缺内容
                                    callTranscriptText(message),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontSize = 22.sp,
                                    lineHeight = 32.sp,
                                )
                            }
                        }
                    }
                }
                if (sending) {
                    item {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    streamState?.answer?.takeIf { it.isNotBlank() }
                                        ?: streamState?.reasoning?.takeIf { it.isNotBlank() }?.let { "（思考中）" + it.takeLast(160) }
                                        ?: "正在帮你想…",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontSize = 22.sp,
                                    lineHeight = 32.sp,
                                    color = if (streamState?.answer?.isNotBlank() == true) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 底部操作区：跟主界面同一个套路——常驻底栏、永远在最上层，
            // 大按钮放屏幕最底下，文字稿再多也只在自己的滚动区里滚，压不住它们
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 14.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    // 通话里挂的图（拍照/相册），跟主页同一份草稿；说完话松手就图文一起发
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attachments.forEach { a ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            (if (a.isImage) "🖼 " else "📄 ") + a.name.take(14),
                                            fontSize = 16.sp,
                                        )
                                        IconButton(
                                            onClick = { onRemoveAttachment(a) },
                                            modifier = Modifier.size(28.dp),
                                        ) { Text("✕", fontSize = 16.sp) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 拍照：图挂到草稿上，按住说话问，松手图文一起发出
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable(onClick = onPickCamera),
                            contentAlignment = Alignment.Center,
                        ) { Text("📷", fontSize = 26.sp) }
                        // 相册
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable(onClick = onPickGallery),
                            contentAlignment = Alignment.Center,
                        ) { Text("🖼", fontSize = 26.sp) }
                        // 按住说话大按钮（松手即发问，自动等回答、自动朗读）——规格与主界面操作行一致
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(80.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (recording != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                                .then(holdModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    recording != null -> "松手（${recordTick}s）"
                                    listening -> "正在记录…"
                                    else -> "按住说话"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp),
                                fontSize = 20.sp,
                                maxLines = 1,
                                fontWeight = FontWeight.Bold,
                                color = if (recording != null) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                            )
                        }
                        Button(
                            onClick = onHangUp,
                            modifier = Modifier.height(80.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) { Text("挂断", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }
            }
        }
    }
}

private fun formatCallDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(Locale.CHINA, m, s)
}

/** 通话文字稿的全文：与朗读同一份内容（回答 + 要点 + 建议），只是换成编号换行排版 */
private fun callTranscriptText(message: SessionMessage): String = buildString {
    append(message.text)
    message.steps.forEachIndexed { i, s -> append("\n${i + 1}. $s") }
    message.action?.let { append("\n👉 $it") }
}

private fun newCameraImageUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
