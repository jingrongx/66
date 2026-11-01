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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.observation.FatigueBand
import com.lvyoubao.app.data.observation.SensingBus
import com.lvyoubao.app.ui.ChatViewModel

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
    onOpenSessions: () -> Unit,
    app: TravelApp,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {},
    onOpenObservation: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ChatViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    var locationDismissed by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(LocationContext.hasPermission(context)) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted || LocationContext.hasPermission(context)
    }

    // 相机拍照
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            ChatViewModel.readAttachment(context, uri)?.let { vm.addAttachment(it) }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { ChatViewModel.readAttachment(context, it)?.let { a -> vm.addAttachment(a) } }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ChatViewModel.readAttachment(context, it)?.let { a -> vm.addAttachment(a) }
        }
    }

    // 新消息到达时滚到底部
    LaunchedEffect(messages.size, state.sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size + if (state.sending) 1 else 0).coerceAtLeast(0))
        }
    }

    val isDeepSeek = settings.presetId == "deepseek"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点：未记录=空心灰；记录中绿/黄=描边；橙/红=实心
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
            Spacer(Modifier.width(10.dp))
            Text("旅游宝", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "零决策旅行助手",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.newSession() }) {
                Icon(Icons.Default.Add, contentDescription = "新建会话")
            }
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Default.List, contentDescription = "会话记录")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        if (updateAvailable) {
            Spacer(Modifier.height(12.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
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
            Spacer(Modifier.height(12.dp))
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

        // 消息列表
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
                            .padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "想去哪，问一句",
                            style = MaterialTheme.typography.headlineMedium,
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
                                    onClick = { vm.updateQuestion(suggestion); vm.send(context) },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
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

        // 附件与输入区
        if (state.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {
                    val uri = newCameraImageUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                label = { Text("拍照") },
            )
            AssistChip(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                label = { Text("相册") },
            )
            AssistChip(
                onClick = { fileLauncher.launch(arrayOf("*/*")) },
                label = { Text("文件") },
            )
            Spacer(Modifier.weight(1f))
            if (isDeepSeek) {
                FilterChip(
                    selected = settings.thinking,
                    onClick = { vm.setThinking(!settings.thinking) },
                    label = { Text("深度思考") },
                )
                FilterChip(
                    selected = settings.searchEnabled,
                    onClick = { vm.setSearchEnabled(!settings.searchEnabled) },
                    label = { Text("联网搜索") },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.question,
                onValueChange = vm::updateQuestion,
                modifier = Modifier.weight(1f),
                placeholder = { Text("问一句…") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        keyboard?.hide()
                        vm.send(context)
                    },
                ),
            )
            Spacer(Modifier.width(10.dp))
            FilledIconButton(
                onClick = {
                    keyboard?.hide()
                    vm.send(context)
                },
                enabled = (state.question.isNotBlank() || state.attachments.isNotEmpty()) && !state.sending,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
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
                "规则校验通过",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "已降级：${message.violations.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSaveMemory) { Text("收藏") }
    }
}

private fun newCameraImageUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
