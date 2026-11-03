package com.lvyoubao.app.ui.elder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.launch

/**
 * 长辈模式：语音优先、超大字体、回答自动朗读——推演方案"老人端"的实现。
 * 宁可少答，不可错答：输出仍然经过同一套规则校验与高可信数据链路。
 */
@Composable
fun ElderScreen(app: TravelApp, onExit: () -> Unit) {
    val vm: ChatViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ChatViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var spokenTs by remember { mutableStateOf(0L) }
    var listening by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<SpeechInput.Recording?>(null) }

    BackHandler {
        TtsPlayer.stop()
        vm.setElderMode(false)
        onExit()
    }

    fun beginCloudRecord() {
        SpeechInput.startRecording(context)
            .onSuccess { recording = it }
            .onFailure { vm.setTransientError("录音启动失败，请再试一次") }
    }

    val recordPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginCloudRecord() else vm.setTransientError("没有录音权限，无法把语音转成文字")
    }

    fun startVoice() {
        if (listening || recording != null || state.sending) return
        if (settings.asrKey.isNotBlank()) {
            // 配了大模型 Key 就走云端转文字，绕开手机自带识别的兼容问题
            if (SpeechInput.hasRecordPermission(context)) beginCloudRecord()
            else recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else if (SpeechInput.isAvailable(context)) {
            listening = true
            scope.launch {
                SpeechInput.listen(context, "请说话")
                    .onSuccess { spoken ->
                        vm.updateQuestion(spoken)
                        vm.send(context)
                    }
                    .onFailure { vm.setTransientError(it.message ?: "语音识别失败，请再试一次") }
                listening = false
            }
        } else {
            vm.setTransientError("这台手机没有语音识别。可让家人在设置里填「语音识别 Key」（免费），或点下面的「打字提问」")
        }
    }

    fun stopVoice() {
        val rec = recording ?: return
        recording = null
        listening = true
        scope.launch {
            SpeechInput.stopAndTranscribe(rec, settings.asrKey)
                .onSuccess { spoken ->
                    vm.updateQuestion(spoken)
                    vm.send(context)
                }
                .onFailure { vm.setTransientError(it.message ?: "语音识别失败，请再试一次") }
            listening = false
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { recording?.let { SpeechInput.cancelRecording(it) } }
    }

    val lastAssistant = messages.lastOrNull { it.role == "assistant" }

    // 回答自动朗读（只读一次；顶部「自动播报」开关关闭时不读）
    LaunchedEffect(lastAssistant?.ts, settings.autoSpeak) {
        val msg = lastAssistant ?: return@LaunchedEffect
        if (settings.autoSpeak && msg.ts != spokenTs) {
            spokenTs = msg.ts
            TtsPlayer.speak(context, ChatViewModel.speakableText(msg))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "旅游宝 · 长辈模式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            // 自动播报开关（进长辈模式默认已打开，可随时关）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动播报", fontSize = 20.sp)
                androidx.compose.material3.Switch(
                    checked = settings.autoSpeak,
                    onCheckedChange = { vm.setAutoSpeak(it) },
                    modifier = Modifier.scale(0.85f),
                )
            }
            TextButton(onClick = {
                TtsPlayer.stop()
                vm.setElderMode(false)
                onExit()
            }) { Text("退出长辈模式") }
        }
        Spacer(Modifier.height(14.dp))

        // 巨大语音按钮（录音中再点一下即停止并转文字）
        Box(contentAlignment = Alignment.Center) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.size(120.dp))
            }
            FilledIconButton(
                onClick = { if (recording != null) stopVoice() else startVoice() },
                enabled = !state.sending && !listening,
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(60.dp),
            ) {
                Text(if (recording != null) "⏹" else "🎤", fontSize = 44.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                listening -> "正在帮你问…"
                recording != null -> "正在听你说，说完再点一下"
                else -> "点一下，说话就行"
            },
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        val ttsState by TtsPlayer.state.collectAsState()
        (ttsState as? TtsPlayer.State.Unavailable)?.let { unavailable ->
            Spacer(Modifier.height(8.dp))
            Text(unavailable.reason, fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
        }
        state.question.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text("你问：$it", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.error?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                error,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        lastAssistant?.let { msg ->
            Spacer(Modifier.height(14.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(msg.text, fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                    msg.steps.forEachIndexed { index, step ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${index + 1}. $step",
                            fontSize = 22.sp,
                            lineHeight = 32.sp,
                        )
                    }
                    msg.action?.let {
                        Spacer(Modifier.height(14.dp))
                        Text("👉 $it", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // 问路线/地点时：同样内嵌地图卡（大字号版）
            msg.mapCard?.let { card ->
                com.lvyoubao.app.ui.map.RouteMapCard(card = card, app = app, elder = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { TtsPlayer.speak(context, ChatViewModel.speakableText(msg)) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("再读一遍", fontSize = 18.sp) }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (typing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.question,
                    onValueChange = vm::updateQuestion,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("打字提问…", fontSize = 18.sp) },
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        keyboard?.hide()
                        vm.send(context)
                        typing = false
                    },
                    enabled = state.question.isNotBlank() && !state.sending,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("问", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        } else {
            TextButton(onClick = { typing = true }) { Text("打字提问", fontSize = 20.sp) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
