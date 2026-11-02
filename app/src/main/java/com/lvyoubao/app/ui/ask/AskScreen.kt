package com.lvyoubao.app.ui.ask

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.ChatSession
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.RuleValidator
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.observation.FatigueBand
import com.lvyoubao.app.data.observation.SensingBus
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Suggestions = listOf(
    "附近有啥好吃的？",
    "还要走多久？",
    "附近哪能坐下歇会？",
    "该往回走了吗？",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    app: TravelApp,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {},
    onOpenObservation: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ChatViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessionList by vm.sessionList.collectAsState()
    val currentSessionId by vm.currentSessionId.collectAsState()
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var locationDismissed by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(LocationContext.hasPermission(context)) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted = granted || LocationContext.hasPermission(context) }

    fun handlePicked(uri: android.net.Uri) {
        scope.launch {
            // 解码/压缩放在 IO 线程，失败一定给用户看原因（不再"选完没反应"）
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
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

    // 相机拍照
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
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            handlePicked(it)
        }
    }

    // 语音提问：应用内语音识别（兼容国产 ROM），失败一定给出直白原因
    var listening by remember { mutableStateOf(false) }
    fun startVoice() {
        if (listening) return
        listening = true
        scope.launch {
            SpeechInput.listen(context, "说出来，我帮你问")
                .onSuccess { vm.updateQuestion(it) }
                .onFailure { vm.setTransientError(it.message ?: "语音识别失败") }
            listening = false
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredSessions = remember(searchQuery, sessionList) {
        if (searchQuery.isBlank()) sessionList else sessionList.filter { s ->
            s.title.contains(searchQuery, true) || s.messages.any { it.text.contains(searchQuery, true) }
        }
    }

    // 新消息到达时滚到底部
    LaunchedEffect(messages.size, state.sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size + if (state.sending) 1 else 0).coerceAtLeast(0))
        }
    }

    val isDeepSeek = settings.presetId == "deepseek"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            vm.newSession { scope.launch { drawerState.close() } }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建会话")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索对话内容…") },
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    var lastGroup = ""
                    filteredSessions.forEach { session ->
                        val group = groupLabel(session.updatedAt)
                        if (group != lastGroup) {
                            lastGroup = group
                            Text(
                                group,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
                            )
                        }
                        SessionDrawerRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onClick = {
                                vm.switchSession(session.id) { scope.launch { drawerState.close() } }
                            },
                            onDelete = { vm.deleteSession(session.id) },
                        )
                    }
                    if (filteredSessions.isEmpty()) {
                        Text(
                            if (searchQuery.isBlank()) "还没有会话" else "没有匹配的会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    DrawerEntry("离线地图与路线") { onOpenMap() }
                    DrawerEntry("随手记与游记") { onOpenNotes() }
                    DrawerEntry("我的记忆（收藏的证据）") { onOpenMemory() }
                    DrawerEntry("感知日志（疲劳观测）") { onOpenObservation() }
                    DrawerEntry("设置") { onOpenSettings() }
                    Spacer(Modifier.height(24.dp))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            // 顶栏：抽屉 | 状态点 | 新建 | 设置
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "会话列表")
                }
                Spacer(Modifier.weight(1f))
                val sensingRunning by SensingBus.running.collectAsState()
                val sensingBand by SensingBus.band.collectAsState()
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .then(
                            when {
                                !sensingRunning -> Modifier.border(
                                    2.dp, MaterialTheme.colorScheme.outline, CircleShape,
                                )
                                sensingBand == FatigueBand.AMBER || sensingBand == FatigueBand.RED ->
                                    Modifier.background(Color(sensingBand.argb))
                                else -> Modifier.border(2.dp, Color(sensingBand.argb), CircleShape)
                            }
                        )
                        .clickable { onOpenObservation() },
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.newSession() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }

            if (updateAvailable) {
                Spacer(Modifier.height(8.dp))
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("发现新版本", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onUpdateClick) { Text("去更新") }
                    }
                }
            }

            if (!locationGranted && !locationDismissed) {
                Spacer(Modifier.height(8.dp))
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "开启定位后，回答自动带上你的位置",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                            Text("开启")
                        }
                        IconButton(onClick = { locationDismissed = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭提示", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // 消息区 / 欢迎区
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 72.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                greeting(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "答得短，带数字，能照做。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(28.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Suggestions.forEach { suggestion ->
                                    AssistChip(
                                        onClick = {
                                            vm.updateQuestion(suggestion)
                                            vm.send(context)
                                        },
                                        label = { Text(suggestion) },
                                    )
                                }
                            }
                        }
                    }
                }
                items(messages) { message ->
                    when (message.role) {
                        "user" -> UserBubble(message)
                        else -> AssistantCard(message, onSaveMemory = { vm.saveToMemory(message) })
                    }
                }
                if (state.sending) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .padding(horizontal = 64.dp),
                        )
                    }
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 底部合并输入卡（参考官方样式）：输入 + 开关 + 附件 + 语音
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    // 已选附件直接显示在输入框上方（选中后立刻可见，避免"感觉没加上"）
                    if (state.attachments.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.attachments.forEach { a ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            (if (a.isImage) "🖼 " else "📄 ") + a.name.take(16),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        IconButton(onClick = { vm.removeAttachment(a) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.question,
                        onValueChange = vm::updateQuestion,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("发消息，或说说你想去哪…") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            keyboard?.hide()
                            vm.send(context)
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = settings.thinking,
                            onClick = { vm.setThinking(!settings.thinking) },
                            label = { Text("深度思考") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = settings.searchEnabled,
                            onClick = { vm.setSearchEnabled(!settings.searchEnabled) },
                            label = { Text("联网搜索") },
                        )
                        Spacer(Modifier.weight(1f))
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加附件")
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("拍照") },
                                    onClick = {
                                        menuOpen = false
                                        val uri = newCameraImageUri(context)
                                        pendingCameraUri = uri
                                        cameraLauncher.launch(uri)
                                    },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("相册") },
                                    onClick = {
                                        menuOpen = false
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("文件") },
                                    onClick = {
                                        menuOpen = false
                                        fileLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { startVoice() }, enabled = !listening) {
                            Text(if (listening) "…" else "🎤", fontSize = 18.sp)
                        }
                        if (state.question.isNotBlank() || state.attachments.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    keyboard?.hide()
                                    vm.send(context)
                                },
                                enabled = !state.sending,
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "发送",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 5..10 -> "早上好，今天去哪玩？"
        in 11..13 -> "中午好，想吃点啥？"
        in 14..17 -> "下午好，慢慢逛。"
        in 18..22 -> "晚上好，聊聊明天的行程？"
        else -> "夜深了，早点休息。"
    }
}

private fun groupLabel(ts: Long): String {
    val date = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.isAfter(today.minusDays(7)) -> "近 7 天"
        else -> "更早"
    }
}

@Composable
private fun DrawerEntry(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SessionDrawerRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                else Color.Transparent,
            )
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                session.messages.lastOrNull()?.text?.take(30) ?: "（空会话）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UserBubble(message: SessionMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                (if (message.hadImage) "🖼 " else "") + message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantCard(message: SessionMessage, onSaveMemory: () -> Unit) {
    val context = LocalContext.current
    val ttsState by TtsPlayer.state.collectAsState()
    val ttsText = remember(message.ts) {
        buildString {
            append(message.text)
            message.steps.forEachIndexed { i, s -> append("。第${i + 1}条，$s") }
            message.action?.let { append("。建议：$it") }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(
                message.text,
                fontSize = 20.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            if (message.steps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                message.steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            "${index + 1}. ",
                            fontSize = 18.sp,
                            lineHeight = 27.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            step,
                            fontSize = 18.sp,
                            lineHeight = 27.sp,
                        )
                    }
                }
            }
            message.action?.let { action ->
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(action, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        if (message.violations.isEmpty()) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                RuleValidator.PASS_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "小提醒：" + message.violations.joinToString("；"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { TtsPlayer.toggle(context, ttsText) }) {
            Text(if (ttsState is TtsPlayer.State.Speaking) "停止" else "朗读")
        }
        TextButton(onClick = onSaveMemory) { Text("收藏") }
    }
    (ttsState as? TtsPlayer.State.Unavailable)?.let { unavailable ->
        Text(
            unavailable.reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

private fun newCameraImageUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
