package com.lvyoubao.app.ui.largetext

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.R
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.ChatSession
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.SentImageThumbnails
import com.lvyoubao.app.ui.common.CardShareDialog
import com.lvyoubao.app.ui.common.AutoSpeakEffect
import com.lvyoubao.app.ui.common.CardShareTipDialog
import com.lvyoubao.app.ui.common.DoubaoCallOverlay
import com.lvyoubao.app.ui.common.HoldTalkFullScreenOverlay
import com.lvyoubao.app.ui.common.RichContent
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.common.VoiceCallOverlay
import com.lvyoubao.app.ui.common.WakeHub
import com.lvyoubao.app.ui.common.rememberCardShareState
import com.lvyoubao.app.ui.common.rememberDraftValue
import com.lvyoubao.app.ui.common.rememberHoldTalkGestureModifier
import com.lvyoubao.app.ui.map.RouteMapCard
import com.lvyoubao.app.ui.settings.SettingsScreen
import com.lvyoubao.app.ui.update.UpdateReadyDialog
import com.lvyoubao.app.ui.update.UpdateScreen
import com.lvyoubao.app.ui.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class QuickPromptCard(
    val title: String,
    val subtitle: String,
    val question: String,
    val accentBgLight: Color,
    val accentBgDark: Color,
    val accentFgLight: Color,
    val accentFgDark: Color,
)

/** 大字模式首页 2×2 快捷情境问法（直白好用，零学习成本） */
private val LargeTextQuickCards = listOf(
    QuickPromptCard(
        title = "附近美食",
        subtitle = "口碑好店与特色菜",
        question = "附近有啥好吃的？",
        accentBgLight = Color(0xFFFFEFE0),
        accentBgDark = Color(0xFF3E2610),
        accentFgLight = Color(0xFFB54708),
        accentFgDark = Color(0xFFFFB878),
    ),
    QuickPromptCard(
        title = "今日天气",
        subtitle = "冷暖变化与穿衣提醒",
        question = "今天天气怎么样？",
        accentBgLight = Color(0xFFE0F2FE),
        accentBgDark = Color(0xFF0E2F44),
        accentFgLight = Color(0xFF0369A1),
        accentFgDark = Color(0xFF7DD3FC),
    ),
    QuickPromptCard(
        title = "找地歇脚",
        subtitle = "公园长椅或安静茶座",
        question = "附近哪能坐下歇会？",
        accentBgLight = Color(0xFFDCFCE7),
        accentBgDark = Color(0xFF0F3522),
        accentFgLight = Color(0xFF15803D),
        accentFgDark = Color(0xFF6EE7B7),
    ),
    QuickPromptCard(
        title = "回家路线",
        subtitle = "一步步看怎么坐车走",
        question = "回家的路怎么走？",
        accentBgLight = Color(0xFFEDE9FE),
        accentBgDark = Color(0xFF281E46),
        accentFgLight = Color(0xFF6D28D9),
        accentFgDark = Color(0xFFC4B5FD),
    ),
)

@Immutable
private data class LargeTextPalette(
    val isDark: Boolean,
    val pageBg: Color,
    val chromeBg: Color,
    val cardBg: Color,
    val softSlotBg: Color,
    val cardBorder: Color,
    val primary: Color,
    val primarySoft: Color,
    val primaryOnSoft: Color,
    val warmSoft: Color,
    val warmText: Color,
    val dangerSoft: Color,
    val danger: Color,
    val wakeReady: Color,
    val mutedText: Color,
)

@Composable
private fun rememberLargeTextPalette(): LargeTextPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme, dark) {
        if (dark) {
            LargeTextPalette(
                isDark = true,
                pageBg = Color(0xFF0B161C),
                chromeBg = Color(0xFF122129),
                cardBg = Color(0xFF162730),
                softSlotBg = Color(0xFF1D323D),
                cardBorder = Color(0xFF2A4350),
                primary = scheme.primary,
                primarySoft = Color(0xFF0E3D4B),
                primaryOnSoft = Color(0xFF8BE4F6),
                warmSoft = Color(0xFF3A240E),
                warmText = Color(0xFFFFB777),
                dangerSoft = Color(0xFF3E1618),
                danger = scheme.error,
                wakeReady = Color(0xFF5CE0AD),
                mutedText = scheme.onSurfaceVariant,
            )
        } else {
            LargeTextPalette(
                isDark = false,
                pageBg = Color(0xFFF1F5F8),
                chromeBg = Color(0xFFFFFFFF),
                cardBg = Color(0xFFFFFFFF),
                softSlotBg = Color(0xFFE9F0F4),
                cardBorder = Color(0xFFD2E0E8),
                primary = scheme.primary,
                primarySoft = Color(0xFFE0F3F8),
                primaryOnSoft = Color(0xFF065260),
                warmSoft = Color(0xFFFFF2E5),
                warmText = Color(0xFFB34400),
                dangerSoft = Color(0xFFFDECEB),
                danger = scheme.error,
                wakeReady = Color(0xFF118453),
                mutedText = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 大字模式（适老化现代视觉升级）：
 * - 图标 + 清晰汉字双编码：杜绝纯图标盲猜和生硬系统 emoji 按钮，按钮最小触控高度 >= 44~68dp；
 * - 全功能无损打通：待机唤醒状态感知、按住说话/点按发送、智能纠错、自动发送取消按钮、
 *   倒计时与闹钟提醒横幅、全双工/经典实时通话、富内容表格/路线图、朗读/收藏/分享、防误删历史；
 * - 与普通模式/灵动模式底层 100% 共用 `WakeHub`、`HoldTalkController`、`DuplexCallController`、
 *   `VoiceCallOverlays`、`CallTimer` 与 `TtsPlayer`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LargeTextScreen(
    app: TravelApp,
    onExitApp: () -> Unit,
    newVersion: String? = null,
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
    val holdState by vm.holdTalk.state.collectAsState()
    val duplexUi by vm.callController.ui.collectAsState()
    val standbyAllowed by WakeHub.standbyAllowed.collectAsState()
    val wakeOneShot by WakeHub.listeningOneShot.collectAsState()
    val timerState by CallTimer.state.collectAsState()
    val alarmActive by CallTimer.alarmActive.collectAsState()
    val alarmText by CallTimer.alarmText.collectAsState()
    val ttsState by TtsPlayer.state.collectAsState()

    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val palette = rememberLargeTextPalette()

    var showHistory by remember { mutableStateOf(false) }
    var showClassicCall by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

    val updateVm: UpdateViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { UpdateViewModel(app) } } },
    )

    BackHandler {
        when {
            showUpdate -> showUpdate = false
            showSettings -> showSettings = false
            showHistory -> showHistory = false
            showClassicCall -> {
                TtsPlayer.stop()
                showClassicCall = false
            }
            duplexUi.active -> vm.callController.hangUp()
            else -> {
                TtsPlayer.stop()
                onExitApp()
            }
        }
    }

    // ---------- 语音：按住说话（与普通/灵动共用 HoldTalkController） ----------
    var holdTalkAutoSend by remember { mutableStateOf(false) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.beginHoldTalk(context, settings, autoSend = holdTalkAutoSend, prompt = "请说话") {}
        } else {
            vm.setTransientError("没有录音权限，无法把语音转成文字")
            vm.holdTalk.onPermissionDenied()
        }
    }

    @Composable
    fun holdToTalkModifier(autoSend: Boolean): Modifier = rememberHoldTalkGestureModifier(
        key = "hold_$autoSend",
        onBegin = {
            holdTalkAutoSend = autoSend
            vm.beginHoldTalk(
                context = context,
                settings = settings,
                autoSend = autoSend,
                prompt = "请说话",
            ) {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onCancelStateChange = { vm.setHoldTalkCancelling(it) },
        onRelease = { vm.endHoldTalk(context, settings, autoSend = autoSend) },
        onCancel = { vm.cancelHoldTalk() },
    )

    // ---------- 附件：拍照 / 相册 ----------
    fun handlePicked(uri: android.net.Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
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

    fun handleDuplexPicked(uri: android.net.Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ChatViewModel.readAttachment(context, uri) }
            }
            result.onSuccess { (attachment, warning) ->
                if (attachment.isImage) vm.callController.addPickedPhotos(listOf(attachment))
                warning?.let { vm.callController.setHint(it) }
            }.onFailure { vm.callController.setHint("照片读取失败，请重试") }
        }
    }

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            if (duplexUi.active) handleDuplexPicked(uri) else handlePicked(uri)
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_PICK_IMAGES),
    ) { uris ->
        val duplex = duplexUi.active
        uris.forEach { if (duplex) handleDuplexPicked(it) else handlePicked(it) }
    }

    // ---------- 自动朗读：流式吐字即开播 + 兜底整段读 ----------
    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
    AutoSpeakEffect(
        vm = vm,
        lastAssistant = lastAssistant,
        autoSpeak = settings.autoSpeak,
        inClassicCall = showClassicCall,
        inDuplexCall = duplexUi.active,
        suppressed = showSettings || showUpdate,
    )

    LaunchedEffect(messages.size, state.sending, streamState?.answer?.length) {
        if (messages.isNotEmpty()) {
            val target = (messages.size - 1 + if (state.sending) 1 else 0).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    // ---------- 统一待机唤醒 ----------
    LaunchedEffect(
        standbyAllowed,
        settings.wakeWordEnabled,
        settings.wakeAction,
        settings.callEngine,
        settings.duplexApiKey,
        settings.asrKey,
        settings.dialectMode,
        settings.voiceBargeIn,
        holdState.recording,
        holdState.listening,
        duplexUi.active,
        showClassicCall,
    ) {
        vm.syncStandbyWake(
            context = context,
            settings = settings,
            shouldSuppressHit = { duplexUi.active || showClassicCall },
            onWakeCall = {
                if (settings.callEngine == "doubao") {
                    vm.startDuplexCall(settings)
                } else {
                    showClassicCall = true
                }
            },
            onWakeText = { text, _ ->
                vm.appendVoiceTranscript(text, polish = false)
                vm.send(context)
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            WakeHub.stopStandby()
            WakeHub.cancelOneShot()
        }
    }

    val wakeReady = standbyAllowed && settings.wakeWordEnabled && SpeechInput.hasRecordPermission(context) &&
        !holdState.isBusy && !duplexUi.active && !showClassicCall
    val canSend = (state.question.isNotBlank() || state.attachments.isNotEmpty()) &&
        !state.sending && !state.polishing
    val isTtsBusy = ttsState is TtsPlayer.State.Speaking || ttsState is TtsPlayer.State.Loading

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.pageBg),
    ) {
        val compactScreen = maxHeight < 500.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶部工作台 ----------
            LargeTextTopBar(
                palette = palette,
                compact = compactScreen,
                wakeReady = wakeReady,
                wakeWordEnabled = settings.wakeWordEnabled,
                sessionCount = sessionList.size,
                callLabel = if (settings.callEngine == "doubao") "实时通话" else "语音通话",
                newVersion = newVersion,
                alarmActive = alarmActive,
                alarmText = alarmText,
                timerRemainSec = timerState.remainSec,
                timerLabel = timerState.label,
                onNewChat = { keyboard?.hide(); vm.newSession() },
                onOpenHistory = { keyboard?.hide(); showHistory = true },
                onStartCall = {
                    keyboard?.hide()
                    if (settings.callEngine == "doubao") vm.startDuplexCall(settings)
                    else showClassicCall = true
                },
                onOpenSettings = { keyboard?.hide(); showSettings = true },
                onExitApp = {
                    TtsPlayer.stop()
                    onExitApp()
                },
                onUpdateClick = onUpdateClick,
                onStopAlarm = { CallTimer.stopAlarm() },
                onCancelTimer = { CallTimer.cancel() },
            )

            // ---------- 中间核心阅读区：欢迎引导首页 或 完整对话卡片流 ----------
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (messages.isEmpty() && !state.sending) {
                    item(key = "welcome") {
                        LargeTextWelcomePanel(
                            palette = palette,
                            callLabel = if (settings.callEngine == "doubao") "实时通话" else "语音通话",
                            onPickSuggestion = { question ->
                                keyboard?.hide()
                                vm.updateQuestion(question)
                                vm.send(context)
                            },
                        )
                    }
                }
                items(messages) { message ->
                    when (message.role) {
                        "user" -> LargeTextUserBubble(
                            palette = palette,
                            message = message,
                            onEdit = { vm.editMessage(message) },
                            onResend = { vm.resendMessage(context, message) },
                        )
                        else -> LargeTextAssistantCard(
                            palette = palette,
                            message = message,
                            app = app,
                            ttsActive = isTtsBusy,
                            onToggleSpeak = {
                                TtsPlayer.toggle(context, ChatViewModel.speakableText(message))
                            },
                            savedMemory = vm.evidenceTitle(message) in savedEvidenceTitles,
                            onToggleMemory = { vm.toggleSaveMemory(message) },
                        )
                    }
                }
                if (state.sending) {
                    item(key = "streaming") {
                        LargeTextStreamingCard(palette = palette, streamState = streamState)
                    }
                }
            }

            // ---------- 底部语音与输入控制台 ----------
            LargeTextBottomDock(
                palette = palette,
                state = state,
                holdState = holdState,
                wakeOneShot = wakeOneShot,
                autoSendIn = autoSendIn,
                canSend = canSend,
                holdModifier = holdToTalkModifier(autoSend = false),
                onQuestionChange = { vm.updateQuestion(it) },
                onClearQuestion = { vm.clearQuestion() },
                onInputFocus = { vm.cancelAutoSend() },
                onCancelAutoSend = { vm.cancelAutoSend() },
                onRemoveAttachment = { vm.removeAttachment(it) },
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
                onSend = {
                    keyboard?.hide()
                    vm.send(context)
                },
            )
        }
    }

    // ---------- 历史对话全屏浮层 ----------
    if (showHistory) {
        LargeTextHistoryOverlay(
            palette = palette,
            sessions = sessionList,
            currentId = currentSessionId,
            onPick = { id ->
                vm.switchSession(id) { showHistory = false }
            },
            onNew = {
                vm.newSession { showHistory = false }
            },
            onDelete = { id -> vm.deleteSession(id) },
            onClose = { showHistory = false },
        )
    }

    // ---------- 全屏通话浮层 ----------
    if (duplexUi.active && duplexUi.uiVisible) {
        DoubaoCallOverlay(
            state = duplexUi.duplexState,
            turns = duplexUi.turns,
            hint = duplexUi.hint,
            photos = duplexUi.photos,
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
            onRemovePhoto = { idx -> vm.callController.removePhoto(idx) },
            onBargeIn = { vm.callController.bargeIn() },
            onHangUp = { vm.callController.hangUp() },
            showPhotoActions = true,
            startedAtMs = duplexUi.startedAtMs,
            speakerphone = duplexUi.speakerphone,
            onToggleSpeakerphone = { vm.callController.toggleSpeakerphone() },
        )
    } else if (showClassicCall) {
        VoiceCallOverlay(
            messages = messages,
            streamState = streamState,
            sending = state.sending,
            attachments = state.attachments,
            listening = holdState.listening,
            recording = holdState.recording,
            recordTick = holdState.recordTick,
            ttsSpeaking = isTtsBusy,
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
                showClassicCall = false
            },
            showPhotoActions = true,
            holdState = holdState,
            defaultSpeakerphone = settings.callSpeakerphone,
        )
    } else if (!showHistory && !showSettings && !showUpdate) {
        // 按住说话全屏动态声波与上滑取消浮层
        HoldTalkFullScreenOverlay(state = holdState, releaseLabel = "记下来")
    }

    // ---------- 自动更新提示 / 设置 / 更新全屏页 ----------
    val autoUpdateState by com.lvyoubao.app.data.AutoUpdater.state.collectAsState()
    if (!showUpdate && autoUpdateState.downloadedApk != null && autoUpdateState.release != null &&
        autoUpdateState.dismissedTag != autoUpdateState.release?.tag_name
    ) {
        UpdateReadyDialog(autoUpdateState)
    }

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

/** 顶部双层适老化工作台：品牌标题+唤醒指示+设置退出 / 高频三宫格入口（新对话·历史·通话） / 状态横幅 */
@Composable
private fun LargeTextTopBar(
    palette: LargeTextPalette,
    compact: Boolean,
    wakeReady: Boolean,
    wakeWordEnabled: Boolean,
    sessionCount: Int,
    callLabel: String,
    newVersion: String?,
    alarmActive: Boolean,
    alarmText: String,
    timerRemainSec: Int,
    timerLabel: String,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartCall: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitApp: () -> Unit,
    onUpdateClick: () -> Unit,
    onStopAlarm: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    val pulseTransition = rememberInfiniteTransition(label = "wakePulse")
    val wakeAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "wakeDotAlpha",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            ),
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        color = palette.chromeBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            // 第一行：名称 + 待机呼吸指示 + 设置 + 退出
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "旅游宝",
                            fontSize = if (compact) 18.sp else 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = palette.primarySoft,
                        ) {
                            Text(
                                "大字版",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.primaryOnSoft,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!compact) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .alpha(if (wakeReady) wakeAlpha else 1f)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            wakeReady -> palette.wakeReady
                                            wakeWordEnabled -> palette.mutedText.copy(alpha = 0.5f)
                                            else -> palette.mutedText.copy(alpha = 0.35f)
                                        },
                                    ),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    wakeReady -> "语音待命中，喊「小源」可直接问"
                                    wakeWordEnabled -> "按住下方大按钮直接说话"
                                    else -> "语音唤醒已关闭 · 支持按住说话"
                                },
                                fontSize = 13.sp,
                                color = palette.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 设置按钮（带汉字，清晰好认）
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenSettings),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.softSlotBg,
                    border = BorderStroke(1.dp, palette.cardBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "设置",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 退出按钮（柔红高对比轮廓，防止与日常操作混淆）
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onExitApp),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.dangerSoft,
                    border = BorderStroke(1.dp, palette.danger.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spark_power),
                            contentDescription = "退出",
                            tint = palette.danger,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "退出",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            color = palette.danger,
                        )
                    }
                }
            }

            // 第二行：高频三宫格入口（新对话 · 历史 · 通话，文字+图标一眼即懂）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopActionPill(
                    modifier = Modifier.weight(1f),
                    height = if (compact) 40.dp else 48.dp,
                    bgColor = palette.softSlotBg,
                    borderColor = palette.cardBorder,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    label = "新对话",
                    onClick = onNewChat,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                TopActionPill(
                    modifier = Modifier.weight(1f),
                    height = if (compact) 40.dp else 48.dp,
                    bgColor = palette.softSlotBg,
                    borderColor = palette.cardBorder,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    label = if (sessionCount > 1) "历史($sessionCount)" else "历史",
                    onClick = onOpenHistory,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lt_history),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                TopActionPill(
                    modifier = Modifier.weight(1.1f),
                    height = if (compact) 40.dp else 48.dp,
                    bgColor = palette.primarySoft,
                    borderColor = palette.primary.copy(alpha = 0.35f),
                    contentColor = palette.primaryOnSoft,
                    label = callLabel,
                    onClick = onStartCall,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_spark_phone),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 闹钟响铃强提醒横幅（一键停止闹钟）
            AnimatedVisibility(visible = alarmActive) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.danger,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "提醒闹钟响了",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onError.copy(alpha = 0.85f),
                            )
                            Text(
                                alarmText.ifBlank { "时间到啦" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onError,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onStopAlarm),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.onError,
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "停止响铃",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.danger,
                                )
                            }
                        }
                    }
                }
            }

            // 倒计时实时状态条
            AnimatedVisibility(visible = timerRemainSec > 0 && !alarmActive) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.warmSoft,
                    border = BorderStroke(1.dp, palette.warmText.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append("倒计时 ")
                                append(formatTimerText(timerRemainSec))
                                if (timerLabel.isNotBlank()) append(" · $timerLabel")
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.warmText,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onCancelTimer),
                            shape = RoundedCornerShape(10.dp),
                            color = palette.chromeBg,
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("取消", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = palette.warmText)
                            }
                        }
                    }
                }
            }

            // 版本更新条
            if (newVersion != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onUpdateClick),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.primary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "发现新版本 $newVersion，点这里立即升级",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = palette.cardBorder.copy(alpha = 0.6f))
    }
}

@Composable
private fun TopActionPill(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp,
    bgColor: Color,
    borderColor: Color,
    contentColor: Color,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides contentColor,
            ) {
                icon()
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                )
            }
        }
    }
}

/** 空白欢迎页：时段问候 + 精炼指南 + 首屏完整呈现 2×2 大字场景卡片 */
@Composable
private fun LargeTextWelcomePanel(
    palette: LargeTextPalette,
    callLabel: String,
    onPickSuggestion: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 问候与操作指南卡片（精炼文案，确保下方 2×2 快捷卡片首屏全览不截断）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = palette.cardBg,
            border = BorderStroke(1.dp, palette.cardBorder),
            shadowElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    "${timeGreeting()}，有事您说话",
                    fontSize = 25.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "按住下方「按住说话」讲出问题，上滑可取消、松手记入框后再点「发送」；也可以点上方「$callLabel」像打电话一样聊。",
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    color = palette.mutedText,
                )
            }
        }

        // 2×2 大字常用问法卡片
        Text(
            "直接点下方卡片提问",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = palette.mutedText,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LargeTextQuickCards.chunked(2).forEach { rowCards ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowCards.forEach { card ->
                        val bgAccent = if (palette.isDark) card.accentBgDark else card.accentBgLight
                        val fgAccent = if (palette.isDark) card.accentFgDark else card.accentFgLight
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 90.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onPickSuggestion(card.question) },
                            shape = RoundedCornerShape(18.dp),
                            color = palette.cardBg,
                            border = BorderStroke(1.dp, palette.cardBorder),
                            shadowElevation = 1.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = bgAccent,
                                ) {
                                    Text(
                                        card.title,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = fgAccent,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    card.subtitle,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    color = palette.mutedText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 用户气泡：右对齐高对比气泡，带时间标记及清晰的 编辑/重发 矢量胶囊 */
@Composable
private fun LargeTextUserBubble(
    palette: LargeTextPalette,
    message: SessionMessage,
    onEdit: () -> Unit,
    onResend: () -> Unit,
) {
    val timeLabel = remember(message.ts) {
        if (message.ts > 0L) SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(message.ts)) else ""
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
        ) {
            Text(
                if (timeLabel.isNotEmpty()) "我 · $timeLabel" else "我",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.mutedText,
            )
        }
        SelectionContainer {
            Surface(
                shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.22f)),
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
                            (if (message.imagePaths.isEmpty() && message.hadImage) "【图片】" else "") + message.text,
                            fontSize = 21.sp,
                            lineHeight = 31.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEdit),
                shape = RoundedCornerShape(12.dp),
                color = palette.cardBg,
                border = BorderStroke(1.dp, palette.cardBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = palette.mutedText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("编辑", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onResend),
                shape = RoundedCornerShape(12.dp),
                color = palette.cardBg,
                border = BorderStroke(1.dp, palette.cardBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = palette.mutedText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重发", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 助手知识卡：身份头栏 + 舒适大字正文 + 编号圆标步骤 + 建议框 + 路线卡 + 突出的朗读/收藏/分享三钮 */
@Composable
private fun LargeTextAssistantCard(
    palette: LargeTextPalette,
    message: SessionMessage,
    app: TravelApp,
    ttsActive: Boolean,
    onToggleSpeak: () -> Unit,
    savedMemory: Boolean,
    onToggleMemory: () -> Unit,
) {
    val shareState = rememberCardShareState(message)
    val timeLabel = remember(message.ts) {
        if (message.ts > 0L) SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(message.ts)) else ""
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶部助手身份栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(palette.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (timeLabel.isNotEmpty()) "旅游宝回答 · $timeLabel" else "旅游宝回答",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primaryOnSoft,
                    modifier = Modifier.weight(1f),
                )
                if (savedMemory) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.warmSoft,
                    ) {
                        Text(
                            "已收藏",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.warmText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            SelectionContainer {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    RichContent(
                        text = message.text,
                        fontSize = 23.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Medium,
                        largeText = true,
                    )

                    if (message.steps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            message.steps.forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(palette.primarySoft),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.primaryOnSoft,
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        step,
                                        fontSize = 21.sp,
                                        lineHeight = 31.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    message.action?.let { actionText ->
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.warmSoft,
                            border = BorderStroke(1.dp, palette.warmText.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = palette.warmText,
                                    modifier = Modifier.padding(top = 3.dp).size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "贴心建议：$actionText",
                                    fontSize = 19.sp,
                                    lineHeight = 28.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.warmText,
                                )
                            }
                        }
                    }
                }
            }

            message.mapCard?.let { card ->
                Spacer(Modifier.height(8.dp))
                RouteMapCard(card = card, app = app, largeText = true)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.cardBorder.copy(alpha = 0.6f))

            // 底部三合一清晰操作条：朗读为主按钮，收藏与分享等重分列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1.25f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onToggleSpeak),
                    shape = RoundedCornerShape(14.dp),
                    color = if (ttsActive) palette.dangerSoft else palette.primarySoft,
                    border = BorderStroke(
                        1.dp,
                        if (ttsActive) palette.danger.copy(alpha = 0.45f) else palette.primary.copy(alpha = 0.3f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (ttsActive) R.drawable.ic_lt_stop else R.drawable.ic_lt_speaker),
                            contentDescription = null,
                            tint = if (ttsActive) palette.danger else palette.primaryOnSoft,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (ttsActive) "停止读" else "朗读",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (ttsActive) palette.danger else palette.primaryOnSoft,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onToggleMemory),
                    shape = RoundedCornerShape(14.dp),
                    color = if (savedMemory) palette.warmSoft else palette.softSlotBg,
                    border = BorderStroke(
                        1.dp,
                        if (savedMemory) palette.warmText.copy(alpha = 0.38f) else palette.cardBorder,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (savedMemory) Icons.Default.CheckCircle else Icons.Default.Star,
                            contentDescription = null,
                            tint = if (savedMemory) palette.warmText else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (savedMemory) "已收藏" else "收藏",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (savedMemory) palette.warmText else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { shareState.open() },
                    shape = RoundedCornerShape(14.dp),
                    color = palette.softSlotBg,
                    border = BorderStroke(1.dp, palette.cardBorder),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "分享",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    CardShareDialog(shareState)
    CardShareTipDialog(shareState)
}

/** 思考与流式回答卡 */
@Composable
private fun LargeTextStreamingCard(
    palette: LargeTextPalette,
    streamState: ChatViewModel.StreamState?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.4f)),
        shadowElevation = 2.dp,
    ) {
        SelectionContainer {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = palette.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            streamState?.answer?.isNotBlank() == true -> "正在为您回答…"
                            streamState?.reasoning?.isNotBlank() == true -> "正在仔细整理…"
                            else -> "正在帮您查资料与思考…"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryOnSoft,
                    )
                }
                if (streamState != null && streamState.answer.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    RichContent(
                        text = streamState.answer,
                        fontSize = 23.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Medium,
                        largeText = true,
                    )
                }
            }
        }
    }
}

/** 底部语音与输入控制台（悬浮圆角工作台） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LargeTextBottomDock(
    palette: LargeTextPalette,
    state: ChatViewModel.UiState,
    holdState: com.lvyoubao.app.ui.common.HoldTalkController.State,
    wakeOneShot: Boolean,
    autoSendIn: Int?,
    canSend: Boolean,
    holdModifier: Modifier,
    onQuestionChange: (String) -> Unit,
    onClearQuestion: () -> Unit,
    onInputFocus: () -> Unit,
    onCancelAutoSend: () -> Unit,
    onRemoveAttachment: (ChatViewModel.PendingAttachment) -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onSend: () -> Unit,
) {
    val micScale by animateFloatAsState(
        targetValue = if (holdState.isRecording) 0.96f else 1f,
        animationSpec = tween(120),
        label = "micHoldScale",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = palette.chromeBg,
        border = BorderStroke(1.dp, palette.cardBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 瞬态报错提醒
            state.error?.let { err ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.warmSoft,
                    border = BorderStroke(1.dp, palette.warmText.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = palette.warmText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            err,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = palette.warmText,
                        )
                    }
                }
            }

            // 2. 录音实时秒表 / 转写 / 智能纠错 / 自动发送倒计时反馈条
            AnimatedVisibility(
                visible = holdState.isRecording || holdState.listening || wakeOneShot || state.polishing || autoSendIn != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                when {
                    holdState.isRecording -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.dangerSoft,
                            border = BorderStroke(1.dp, palette.danger.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(palette.danger),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在听您说… ${holdState.recordTick} 秒（说完请松手）",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.danger,
                                )
                            }
                        }
                    }
                    holdState.listening || wakeOneShot -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.primarySoft,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.primaryOnSoft,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (wakeOneShot) "已唤醒，请直接说出您想问的问题…" else "正在把声音整理成文字…",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.primaryOnSoft,
                                )
                            }
                        }
                    }
                    state.polishing -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.warmSoft,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.warmText,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在帮您顺一下识别文字，马上就好…",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.warmText,
                                )
                            }
                        }
                    }
                    autoSendIn != null -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.primarySoft,
                            border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$autoSendIn 秒后自动发出去",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.primaryOnSoft,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(onClick = onCancelAutoSend),
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.chromeBg,
                                    border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.4f)),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "先别发",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.primaryOnSoft,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 已挂附件条
            if (state.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEach { a ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.softSlotBg,
                            border = BorderStroke(1.dp, palette.cardBorder),
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
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(a.name.take(14), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                IconButton(onClick = { onRemoveAttachment(a) }, modifier = Modifier.size(30.dp)) {
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
            }

            // 4. 文字输入框（大字易读，右侧配明确清空胶囊）
            val draftValue = rememberDraftValue(state.question)
            OutlinedTextField(
                value = draftValue.value,
                onValueChange = {
                    draftValue.value = it
                    onQuestionChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) onInputFocus() },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.softSlotBg,
                    unfocusedContainerColor = palette.softSlotBg,
                    focusedBorderColor = palette.primary,
                    unfocusedBorderColor = palette.cardBorder,
                ),
                placeholder = {
                    Text(
                        when {
                            holdState.isRecording -> "正在听您说…"
                            wakeOneShot -> "唤醒聆听中…"
                            else -> "说话或打字，内容记到这里"
                        },
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.mutedText,
                    )
                },
                maxLines = 4,
                textStyle = TextStyle(fontSize = 20.sp, lineHeight = 29.sp),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                trailingIcon = {
                    if (state.question.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onClearQuestion),
                            shape = RoundedCornerShape(10.dp),
                            color = palette.chromeBg,
                            border = BorderStroke(1.dp, palette.cardBorder),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("清空", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.mutedText)
                            }
                        }
                    }
                },
            )

            // 5. 核心操作主排：拍照 | 相册 | 按住说话(超大C位) | 发送
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockSquareActionButton(
                    palette = palette,
                    iconRes = R.drawable.ic_spark_camera,
                    label = "拍照",
                    onClick = onPickCamera,
                )
                DockSquareActionButton(
                    palette = palette,
                    iconRes = R.drawable.ic_spark_gallery,
                    label = "相册",
                    onClick = onPickGallery,
                )

                // 按住说话大主按钮（矢量话筒 + 大号粗体文字 + 按下回弹感）
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                        .scale(micScale)
                        .clip(RoundedCornerShape(20.dp))
                        .then(holdModifier),
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        holdState.isRecording -> palette.danger
                        holdState.listening || wakeOneShot -> palette.primarySoft
                        else -> palette.primary
                    },
                    border = if (holdState.listening || wakeOneShot) {
                        BorderStroke(1.5.dp, palette.primary)
                    } else null,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val contentColor = when {
                            holdState.isRecording -> MaterialTheme.colorScheme.onError
                            holdState.listening || wakeOneShot -> palette.primaryOnSoft
                            else -> MaterialTheme.colorScheme.onPrimary
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_spark_mic),
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                holdState.cancelling -> "松手取消"
                                holdState.isRecording -> "松手 (${holdState.recordTick}s)"
                                holdState.listening -> "整理文字…"
                                wakeOneShot -> "请说话…"
                                else -> "按住说话"
                            },
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 发送大按钮（尺寸固定防抖，状态分明）
                Surface(
                    modifier = Modifier
                        .width(74.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .then(if (canSend) Modifier.clickable(onClick = onSend) else Modifier),
                    shape = RoundedCornerShape(20.dp),
                    color = if (canSend) palette.primary else palette.softSlotBg,
                    border = if (!canSend) BorderStroke(1.dp, palette.cardBorder) else null,
                ) {
                    val sendTint = if (canSend) MaterialTheme.colorScheme.onPrimary else palette.mutedText.copy(alpha = 0.55f)
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = sendTint,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "发送",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = sendTint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockSquareActionButton(
    palette: LargeTextPalette,
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(width = 58.dp, height = 68.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = palette.softSlotBg,
        border = BorderStroke(1.dp, palette.cardBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** 历史对话全屏管理：清晰大字体、当前使用中标识、防误触二次确认删除 */
@Composable
private fun LargeTextHistoryOverlay(
    palette: LargeTextPalette,
    sessions: List<ChatSession>,
    currentId: String?,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDeleteId) {
        if (pendingDeleteId != null) {
            delay(3_000)
            pendingDeleteId = null
        }
    }

    Surface(Modifier.fillMaxSize(), color = palette.pageBg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 顶部导航栏
            Surface(
                color = palette.chromeBg,
                shadowElevation = 4.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onClose),
                            shape = RoundedCornerShape(12.dp),
                            color = palette.softSlotBg,
                            border = BorderStroke(1.dp, palette.cardBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("返回", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "历史对话",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Button(
                            onClick = onNew,
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新对话", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = palette.cardBorder.copy(alpha = 0.6f))
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有对话记录\n点右上角「新对话」开始吧",
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        color = palette.mutedText,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val selected = session.id == currentId
                        val confirmDel = pendingDeleteId == session.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onPick(session.id) },
                            shape = RoundedCornerShape(18.dp),
                            color = palette.cardBg,
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) palette.primary else palette.cardBorder,
                            ),
                            shadowElevation = 2.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (selected) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = palette.primarySoft,
                                            ) {
                                                Text(
                                                    "正在使用",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.primaryOnSoft,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            session.title,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (selected) palette.primaryOnSoft else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        session.messages.lastOrNull()?.text?.take(50)?.replace("\n", " ") ?: "（空对话）",
                                        fontSize = 16.sp,
                                        lineHeight = 23.sp,
                                        color = palette.mutedText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.updatedAt)) +
                                            " · 共 ${session.messages.size} 条",
                                        fontSize = 14.sp,
                                        color = palette.mutedText,
                                    )
                                }

                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    modifier = Modifier
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (confirmDel) {
                                                pendingDeleteId = null
                                                onDelete(session.id)
                                            } else {
                                                pendingDeleteId = session.id
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (confirmDel) palette.danger else palette.dangerSoft,
                                    border = BorderStroke(1.dp, palette.danger.copy(alpha = 0.35f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = if (confirmDel) 12.dp else 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除对话",
                                            tint = if (confirmDel) MaterialTheme.colorScheme.onError else palette.danger,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        if (confirmDel) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "确定删?",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onError,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun timeGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

private fun formatTimerText(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.CHINA, "%02d:%02d", m, s)
}

private fun newCameraImageUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
