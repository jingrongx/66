package com.lvyoubao.app.ui.largetext

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.lvyoubao.app.data.ChatSession
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.SentImageThumbnails
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.common.rememberDraftValue
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
fun LargeTextScreen(app: TravelApp, onExitApp: () -> Unit) {
    val vm: ChatViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ChatViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val streamState by vm.stream.collectAsState()
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessionList by vm.sessionList.collectAsState()
    val currentSessionId by vm.currentSessionId.collectAsState()
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
                    .onFailure { vm.setTransientError(it.message ?: "语音识别失败，请再试一次") }
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
                .onFailure { vm.setTransientError(it.message ?: "语音识别失败，请再试一次") }
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

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) handlePicked(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_PICK_IMAGES),
    ) { uris -> uris.forEach { handlePicked(it) } }

    // ---------- 自动朗读：新回答一落地就读（通话中不依赖开关，天然要出声） ----------

    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
    var spokenTs by remember { mutableStateOf(0L) }
    val ttsState by TtsPlayer.state.collectAsState()
    LaunchedEffect(lastAssistant?.ts, settings.autoSpeak, showCall, showSettings, showUpdate) {
        val msg = lastAssistant ?: return@LaunchedEffect
        // 只读 20 秒内的新回答：切历史/重进不再把旧消息复读一遍；停在设置/更新页时也先别出声
        val fresh = System.currentTimeMillis() - msg.ts < 20_000
        val overlayOpen = showSettings || showUpdate
        if ((showCall || settings.autoSpeak) && !overlayOpen && msg.ts != spokenTs && fresh) {
            spokenTs = msg.ts
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
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ---------- 头部：标题 + 设置 + 退出 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "旅游宝 · 大字模式",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 设置：家人帮着配的地方（AI、大字模式开关都在设置里），全屏盖在大字模式上
            Button(
                onClick = {
                    keyboard?.hide()
                    showSettings = true
                },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("设置", fontSize = 17.sp, maxLines = 1) }
            Spacer(Modifier.width(8.dp))
            // 退出：直接退出应用回桌面，不再切模式（模式切换只认设置里的「大字模式」开关）
            Button(
                onClick = {
                    TtsPlayer.stop()
                    onExitApp()
                },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("退出", fontSize = 17.sp, maxLines = 1) }
        }
        // 第二行：三个大导航按钮（新对话 / 历史 / 语音通话）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { keyboard?.hide(); vm.newSession() },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("新对话", fontSize = 17.sp, maxLines = 1) }
            Button(
                onClick = { keyboard?.hide(); showHistory = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("历史对话", fontSize = 17.sp, maxLines = 1) }
            Button(
                onClick = { keyboard?.hide(); showCall = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("通话", fontSize = 17.sp, maxLines = 1) }
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
                        onSaveMemory = { vm.saveToMemory(message) },
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

        // ---------- 底部操作区：固定在下方，独立滚动区域，永远不会被卡片盖住 ----------
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
                    modifier = Modifier.fillMaxWidth(),
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
    if (showCall) {
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
            Button(
                onClick = onEdit,
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) { Text("✏️ 编辑", fontSize = 17.sp) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onResend,
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
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
    onSaveMemory: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        // 文字整体可长按选中、自由复制（按钮不受影响）
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.padding(18.dp)) {
                Text(message.text, fontSize = 25.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
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
        // 按钮行与卡片边缘留出与正文一致的内边距，不再贴着圆角边
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onToggleSpeak,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (ttsActive) "⏹ 别读了" else "🔊 再读一遍", fontSize = 17.sp, maxLines = 1) }
            Button(
                onClick = onSaveMemory,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("收藏", fontSize = 17.sp, maxLines = 1) }
        }
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
                        Text(
                            streamState.answer,
                            fontSize = 25.sp,
                            lineHeight = 37.sp,
                            fontWeight = FontWeight.Bold,
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
