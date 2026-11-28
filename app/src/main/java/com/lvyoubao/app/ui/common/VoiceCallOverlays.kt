package com.lvyoubao.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvyoubao.app.R
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.duplex.DoubaoDuplexClient
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.SentImageThumbnails
import kotlinx.coroutines.delay
import java.util.Locale

/** 通话会话流的一条消息：user=识别稿（右侧），assistant=回答（左侧）；streaming=还在收字 */
data class DuplexTurnUi(val role: String, val text: String, val streaming: Boolean = false)

/**
 * 全应用共用的豆包全双工通话界面（端到端 S2S）：
 * 普通 / 大字 / 灵动模式统一渲染，按需通过 [showPhotoActions] 控制是否露出拍照挂图栏。
 */
@Composable
fun DoubaoCallOverlay(
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
    startedAtMs: Long = 0L,
    speakerphone: Boolean = true,
    onToggleSpeakerphone: () -> Unit = {},
) {
    val baseMs = remember(startedAtMs) {
        if (startedAtMs > 0L) startedAtMs else System.currentTimeMillis()
    }
    val seconds = remember { mutableStateOf(0) }
    LaunchedEffect(state, baseMs) {
        while (
            state == DoubaoDuplexClient.State.LISTENING ||
            state == DoubaoDuplexClient.State.SPEAKING ||
            state == DoubaoDuplexClient.State.CONNECTED
        ) {
            delay(1_000)
            seconds.value = ((System.currentTimeMillis() - baseMs) / 1000L).toInt().coerceAtLeast(0)
        }
    }
    val statusText = when (state) {
        DoubaoDuplexClient.State.CONNECTING -> "正在连接…"
        DoubaoDuplexClient.State.CONNECTED -> "已连接，正在建立会话…"
        DoubaoDuplexClient.State.LISTENING -> "正在听你说"
        DoubaoDuplexClient.State.SPEAKING -> "正在回答 · 可插话"
        DoubaoDuplexClient.State.CLOSING -> "正在挂断…"
        DoubaoDuplexClient.State.ERROR -> "连接出错"
        else -> "语音通话中"
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotColor = when (state) {
                    DoubaoDuplexClient.State.LISTENING -> Color(0xFF16A34A)
                    DoubaoDuplexClient.State.SPEAKING -> MaterialTheme.colorScheme.primary
                    DoubaoDuplexClient.State.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.secondary
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "语音通话中",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.padding(start = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%02d:%02d".format(seconds.value / 60, seconds.value % 60),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            " · ",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            statusText,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallSpeakerToggleChip(
                        speakerphone = speakerphone,
                        onToggle = onToggleSpeakerphone,
                    )
                    Button(
                        onClick = onHangUp,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spark_hangup),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("挂断", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            hint?.let {
                Text(
                    it,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            val listState = rememberLazyListState()
            LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length, turns.lastOrNull()?.streaming) {
                if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
            }
            LazyColumn(
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

            if (photos.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(photos.size) { idx ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_spark_camera),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("照片 ${idx + 1}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                IconButton(onClick = { onRemovePhoto(idx) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除照片",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPhotoActions) {
                    Surface(
                        modifier = Modifier
                            .size(width = 62.dp, height = 64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(onClick = onPickCamera),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_camera),
                                contentDescription = "拍照",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("拍照", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .size(width = 62.dp, height = 64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(onClick = onPickGallery),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_gallery),
                                contentDescription = "相册",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("相册", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Button(
                    onClick = onBargeIn,
                    enabled = state == DoubaoDuplexClient.State.SPEAKING || state == DoubaoDuplexClient.State.LISTENING,
                    modifier = Modifier.height(64.dp).weight(1f),
                    shape = RoundedCornerShape(22.dp),
                ) { Text("插话打断", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * 全应用共用的经典串行语音通话界面（ASR → LLM → TTS 三段各自独立选模型）：
 * 按住说话、松手就问，回答自动朗读；期间自动切换到设置里配置的通话 TTS 引擎（`callTtsModel`）
 * 并暂时停止待机唤醒避免与按住采集抢麦，挂断后恢复。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceCallOverlay(
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
    showPhotoActions: Boolean = true,
    holdState: HoldTalkController.State? = null,
    defaultSpeakerphone: Boolean = true,
) {
    val context = LocalContext.current
    var speakerphone by remember(defaultSpeakerphone) { mutableStateOf(defaultSpeakerphone) }
    DisposableEffect(Unit) {
        TtsPlayer.setCallMode(true, context, speakerphone)
        WakeHub.stopStandby()
        onDispose {
            TtsPlayer.setCallMode(false)
            WakeHub.resumeStandby()
        }
    }

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    val transcriptState = rememberLazyListState()
    LaunchedEffect(messages.size, sending, streamState?.answer?.length) {
        if (messages.isNotEmpty()) {
            val target = (messages.size - 1 + if (sending) 1 else 0).coerceAtLeast(0)
            runCatching { transcriptState.animateScrollToItem(target) }
        }
    }
    val isHoldPressing = holdState?.pressing == true || recording != null
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                    ) {
                        Text("语音通话中", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            formatCallDuration(seconds),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CallSpeakerToggleChip(
                        speakerphone = speakerphone,
                        onToggle = {
                            val next = !speakerphone
                            speakerphone = next
                            TtsPlayer.setCallSpeakerphone(context, next)
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                val status = when {
                    holdState?.cancelling == true -> "松手即可取消本次发言"
                    isHoldPressing -> "正在听你说…（${recordTick}s，说完松手）"
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

            LazyColumn(
                state = transcriptState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { message ->
                    SelectionContainer {
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
                        SelectionContainer {
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
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attachments.forEach { a ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if (a.isImage) R.drawable.ic_spark_gallery else R.drawable.ic_spark_chat,
                                            ),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(a.name.take(14), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        IconButton(
                                            onClick = { onRemoveAttachment(a) },
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "移除附件",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showPhotoActions) {
                            Surface(
                                modifier = Modifier
                                    .size(width = 60.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable(onClick = onPickCamera),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_spark_camera),
                                        contentDescription = "拍照",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "拍照",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .size(width = 60.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable(onClick = onPickGallery),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_spark_gallery),
                                        contentDescription = "相册",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "相册",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isHoldPressing) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                )
                                .then(holdModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_spark_mic),
                                    contentDescription = null,
                                    tint = if (isHoldPressing) MaterialTheme.colorScheme.onError
                                    else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        holdState?.cancelling == true -> "松手取消"
                                        isHoldPressing -> "松手（${recordTick}s）"
                                        listening -> "正在记录…"
                                        else -> "按住说话"
                                    },
                                    fontSize = 19.sp,
                                    maxLines = 1,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isHoldPressing) {
                                        MaterialTheme.colorScheme.onError
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    },
                                )
                            }
                        }
                        Button(
                            onClick = onHangUp,
                            modifier = Modifier.height(72.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spark_hangup),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("挂断", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
        }
        if (holdState != null) {
            HoldTalkFullScreenOverlay(state = holdState, releaseLabel = "发出去")
        }
    }
}

/** 通话顶栏操作组中的「免提 / 听筒」等高同圆角切换按钮（豆包全双工与经典串行通话共用） */
@Composable
private fun CallSpeakerToggleChip(
    speakerphone: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (speakerphone) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val fg = if (speakerphone) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (speakerphone) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    }
    Surface(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(15.dp),
        color = bg,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (speakerphone) R.drawable.ic_lt_speaker else R.drawable.ic_spark_phone,
                ),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (speakerphone) "免提" else "听筒",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
            )
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
