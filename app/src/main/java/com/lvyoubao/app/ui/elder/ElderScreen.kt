package com.lvyoubao.app.ui.elder

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
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.map.RouteMapCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 长辈模式的大字号常用问法 */
private val ElderSuggestions = listOf(
    "附近有啥好吃的？",
    "今天天气怎么样？",
    "附近哪能坐下歇会？",
    "回家的路怎么走？",
)

/**
 * 长辈模式（v0.8.0 重构）：
 * - 会话流：整段对话像微信一样从头到尾摆出来，不再只显示最后一张卡片；
 * - 底部操作区：固定在屏幕底部、永远可见，卡片/流式输出再长也遮不住按钮；
 * - 按住说话：按住录音、松手就记下来，可以连说好几段，说多少记多少，
 *   全部说完再点旁边的「发送」才发出去（充分的停顿时间由握住时长自己决定）；
 * - 拍照提问：拍照/相册的图挂在草稿上，跟语音一起发送；
 * - 语音通话：全屏通话界面，按住说话松手即问，回答自动朗读，像打电话一样聊；
 * - 对话历史：大字号列表，点开切换、可删、可新建。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElderScreen(app: TravelApp, onExit: () -> Unit) {
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
    var listening by remember { mutableStateOf(false) } // 转写/系统识别进行中
    var recording by remember { mutableStateOf<SpeechInput.Recording?>(null) }
    var recordStartTs by remember { mutableStateOf(0L) }
    var recordTick by remember { mutableIntStateOf(0) } // 录音秒数（每秒自增驱动刷新）

    BackHandler {
        when {
            showHistory -> showHistory = false
            showCall -> {
                TtsPlayer.stop()
                showCall = false
            }
            else -> {
                TtsPlayer.stop()
                vm.setElderMode(false)
                onExit()
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
                HotwordCorrector.buildIfNeeded(vm.sessionList.value, emptyList())
                SpeechInput.listen(context, "请说话")
                    .onSuccess { vm.appendQuestion(HotwordCorrector.correct(it)) }
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
                    vm.appendQuestion(HotwordCorrector.correct(spoken))
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
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { handlePicked(it) } }

    // ---------- 自动朗读：新回答一落地就读（通话中不依赖开关，天然要出声） ----------

    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
    var spokenTs by remember { mutableStateOf(0L) }
    val ttsState by TtsPlayer.state.collectAsState()
    LaunchedEffect(lastAssistant?.ts, settings.autoSpeak, showCall) {
        val msg = lastAssistant ?: return@LaunchedEffect
        // 只读 20 秒内的新回答：切历史/重进不再把旧消息复读一遍
        val fresh = System.currentTimeMillis() - msg.ts < 20_000
        if ((showCall || settings.autoSpeak) && msg.ts != spokenTs && fresh) {
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

    val canSend = (state.question.isNotBlank() || state.attachments.isNotEmpty()) && !state.sending

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ---------- 头部：标题 + 退出 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "旅游宝 · 长辈模式",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    TtsPlayer.stop()
                    vm.setElderMode(false)
                    onExit()
                },
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("退出", fontSize = 18.sp) }
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
            ) { Text("📞 通话", fontSize = 17.sp, maxLines = 1) }
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
                            ElderSuggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = {
                                        vm.updateQuestion(suggestion)
                                        vm.send(context)
                                    },
                                    label = { Text(suggestion, fontSize = 19.sp) },
                                )
                            }
                        }
                    }
                }
            }
            items(messages) { message ->
                when (message.role) {
                    "user" -> ElderUserBubble(message)
                    else -> ElderAssistantCard(
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
                item { ElderStreamingCard(streamState) }
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
                                    IconButton(onClick = { vm.removeAttachment(a) }, modifier = Modifier.size(30.dp)) {
                                        Text("✕", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 草稿输入框：语音按段累积进这里，也可以直接打字
                OutlinedTextField(
                    value = state.question,
                    onValueChange = vm::updateQuestion,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (recording != null) "正在听你说…" else "按住下方说话，松手就记到这里",
                            fontSize = 19.sp,
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
                // 主操作行：按住说话（大） | 拍照 | 相册 | 发送（大）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                holdBig -> "🎤 按住说话"
                                else -> "🎤 点一下说"
                            },
                            fontSize = 18.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            color = if (recording != null) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
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
                        Text("发送", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
                        Text("正在帮你想，稍等一下…", fontSize = 17.sp)
                    }
                }
            }
        }
    }

    // ---------- 对话历史（全屏大字号列表） ----------
    if (showHistory) {
        ElderHistoryOverlay(
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
            listening = listening,
            recording = recording,
            recordTick = recordTick,
            ttsSpeaking = ttsState is TtsPlayer.State.Speaking || ttsState is TtsPlayer.State.Loading,
            ttsUnavailable = (ttsState as? TtsPlayer.State.Unavailable)?.reason,
            holdModifier = holdToTalkModifier(autoSend = true),
            onHangUp = {
                TtsPlayer.stop()
                showCall = false
            },
        )
    }
}

/** 用户消息：右侧大气泡，超大字，长按可选中复制 */
@Composable
private fun ElderUserBubble(message: SessionMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Surface(
                shape = RoundedCornerShape(22.dp, 6.dp, 22.dp, 22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    (if (message.hadImage) "🖼 " else "") + message.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontSize = 22.sp,
                    lineHeight = 32.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 助手卡片：超大字回答 + 要点 + 地图卡 + 再读一遍/收藏 */
@Composable
private fun ElderAssistantCard(
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
            RouteMapCard(card = card, app = app, elder = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onToggleSpeak,
                modifier = Modifier.height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (ttsActive) "⏹ 别读了" else "🔊 再读一遍", fontSize = 18.sp) }
            Button(
                onClick = onSaveMemory,
                modifier = Modifier.height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("收藏", fontSize = 18.sp) }
        }
    }
}

/** 生成中的回答：实时流式正文，超大字，可选中复制 */
@Composable
private fun ElderStreamingCard(streamState: ChatViewModel.StreamState?) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.padding(18.dp)) {
                if (streamState == null || (streamState.answer.isBlank() && streamState.reasoning.isBlank())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在帮你想…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
private fun ElderHistoryOverlay(
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
                TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                    Text("关闭", fontSize = 19.sp)
                }
            }
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("➕ 新对话", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
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
 * 中间默认展示会话文字稿（含正在生成的回答），看得见 AI 在说什么，也可长按选中复制。
 * 全链路免费：录音→云端方言口音转文字→大模型回答→Edge/云端朗读。
 */
@Composable
private fun VoiceCallOverlay(
    messages: List<SessionMessage>,
    streamState: ChatViewModel.StreamState?,
    sending: Boolean,
    listening: Boolean,
    recording: SpeechInput.Recording?,
    recordTick: Int,
    ttsSpeaking: Boolean,
    ttsUnavailable: String?,
    holdModifier: Modifier,
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语音通话中", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(formatCallDuration(seconds), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            val status = when {
                recording != null -> "正在听你说…（${recordTick}s，说完松手）"
                listening -> "正在记下来…"
                sending -> "正在帮你想…"
                ttsSpeaking -> "正在回答，想插话就按住说话"
                else -> "按住下面的按钮说话"
            }
            Text(status, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ttsUnavailable?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))

            // 会话文字稿：默认可见，看得见 AI 说了什么，长按可选中复制
            androidx.compose.foundation.text.selection.SelectionContainer {
                LazyColumn(
                    state = transcriptState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages) { message ->
                        if (message.role == "user") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        message.text,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        fontSize = 17.sp,
                                        lineHeight = 24.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    message.text,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontSize = 17.sp,
                                    lineHeight = 25.sp,
                                )
                            }
                        }
                    }
                    if (sending) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    streamState?.answer?.takeIf { it.isNotBlank() }
                                        ?: streamState?.reasoning?.takeIf { it.isNotBlank() }?.let { "（思考中）" + it.takeLast(160) }
                                        ?: "正在帮你想…",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontSize = 17.sp,
                                    lineHeight = 25.sp,
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

            Spacer(Modifier.height(12.dp))
            // 按住说话大按钮（松手即发问，自动等回答、自动朗读）
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(70.dp))
                    .background(
                        if (recording != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    .then(holdModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (recording != null) "松手\n我就回答" else "按住\n说话",
                    fontSize = 25.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (recording != null) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onHangUp,
                modifier = Modifier.fillMaxWidth(0.62f).height(58.dp),
                shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("📵 挂断", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun formatCallDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(Locale.CHINA, m, s)
}

private fun newCameraImageUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
