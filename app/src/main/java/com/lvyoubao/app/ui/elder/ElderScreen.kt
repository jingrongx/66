package com.lvyoubao.app.ui.elder

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
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var spokenTs by remember { mutableStateOf(0L) }
    var listening by remember { mutableStateOf(false) }

    fun startVoice() {
        if (listening || state.sending) return
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
    }

    val lastAssistant = messages.lastOrNull { it.role == "assistant" }

    // 回答自动朗读（只读一次）
    LaunchedEffect(lastAssistant?.ts) {
        val msg = lastAssistant ?: return@LaunchedEffect
        if (msg.ts != spokenTs) {
            spokenTs = msg.ts
            TtsPlayer.speak(
                context,
                buildString {
                    append(msg.text)
                    msg.steps.forEachIndexed { i, s -> append("。第${i + 1}条，$s") }
                    msg.action?.let { append("。建议：$it") }
                },
            )
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
            TextButton(onClick = {
                TtsPlayer.stop()
                vm.setElderMode(false)
                onExit()
            }) { Text("退出长辈模式") }
        }
        Spacer(Modifier.height(24.dp))

        // 巨大语音按钮
        Box(contentAlignment = Alignment.Center) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.size(150.dp))
            }
            FilledIconButton(
                onClick = { startVoice() },
                enabled = !state.sending,
                modifier = Modifier.size(150.dp),
                shape = RoundedCornerShape(75.dp),
            ) {
                Text("🎤", fontSize = 56.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                listening -> "正在听你说…"
                state.sending -> "正在帮你问…"
                else -> "点一下，说话就行"
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        val ttsState by TtsPlayer.state.collectAsState()
        (ttsState as? TtsPlayer.State.Unavailable)?.let { unavailable ->
            Spacer(Modifier.height(8.dp))
            Text(unavailable.reason, fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
        }
        state.question.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text("你问：$it", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        lastAssistant?.let { msg ->
            Spacer(Modifier.height(24.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(msg.text, fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                    msg.steps.forEachIndexed { index, step ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${index + 1}. $step",
                            fontSize = 26.sp,
                            lineHeight = 38.sp,
                        )
                    }
                    msg.action?.let {
                        Spacer(Modifier.height(14.dp))
                        Text("👉 $it", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        TtsPlayer.speak(
                            context,
                            buildString {
                                append(msg.text)
                                msg.steps.forEachIndexed { i, s -> append("。第${i + 1}条，$s") }
                                msg.action?.let { append("。建议：$it") }
                            },
                        )
                    },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("再读一遍", fontSize = 20.sp) }
            }
        }

        Spacer(Modifier.height(28.dp))
        if (typing) {
            OutlinedTextField(
                value = state.question,
                onValueChange = vm::updateQuestion,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("打字提问…", fontSize = 22.sp) },
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    keyboard?.hide()
                    vm.send(context)
                    typing = false
                },
                enabled = state.question.isNotBlank() && !state.sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("问", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        } else {
            TextButton(onClick = { typing = true }) { Text("打字提问", fontSize = 20.sp) }
        }
        Spacer(Modifier.height(32.dp))
    }
}
