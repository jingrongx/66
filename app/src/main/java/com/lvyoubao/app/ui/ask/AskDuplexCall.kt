package com.lvyoubao.app.ui.ask

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.DeviceTools
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.duplex.DoubaoDuplexClient
import com.lvyoubao.app.data.duplex.DuplexCallSetup
import com.lvyoubao.app.ui.ChatViewModel
import com.lvyoubao.app.ui.common.NetKeep
import com.lvyoubao.app.ui.common.ScreenAwake
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.largetext.DoubaoCallOverlay
import com.lvyoubao.app.ui.largetext.DuplexTurnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 普通模式的豆包全双工通话覆盖层：由唤醒词（唤醒后=直接通话）拉起，
 * 事件链与大字模式同款（会话流排版、FC 工具、即时急停、落库+记忆总结）。
 */
@Composable
fun AskDuplexCall(vm: ChatViewModel, settings: AiSettings, sid: String?, onHangUp: () -> Unit) {
    val context = LocalContext.current
    val duplexClient = remember { DoubaoDuplexClient(context) }
    val duplexState by duplexClient.state.collectAsState()
    val duplexTurns = remember { mutableStateListOf<DuplexTurnUi>() }
    val duplexMapCards = remember { mutableStateListOf<MapCardData>() }
    var duplexHint by remember { mutableStateOf<String?>(null) }
    var lastDuplexTurnKey by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun duplexNewUserTurn() {
        duplexTurns.add(DuplexTurnUi("user", "", streaming = true))
        if (duplexTurns.size > 30) duplexTurns.removeAt(0)
    }
    fun duplexUpdateUser(text: String) {
        val i = duplexTurns.lastIndex
        if (i >= 0 && duplexTurns[i].role == "user") duplexTurns[i] = duplexTurns[i].copy(text = text)
        else { duplexNewUserTurn(); duplexUpdateUser(text) }
    }
    var answerBuf by remember { mutableStateOf("") }
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
                is DoubaoDuplexClient.Event.AsrStarted -> duplexNewUserTurn()
                is DoubaoDuplexClient.Event.Asr -> duplexUpdateUser(ev.text)
                is DoubaoDuplexClient.Event.Answer -> {
                    answerBuf = if (ev.isFinal) ev.text
                    else if (answerBuf.isNotEmpty() && ev.text.startsWith(answerBuf)) ev.text
                    else answerBuf + ev.text
                    duplexUpdateAssistant(answerBuf, ev.isFinal)
                }
                is DoubaoDuplexClient.Event.ToolCall -> {
                    val ackJob = scope.launch {
                        delay(1_200)
                        duplexClient.speak("我查一下哈")
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
                is DoubaoDuplexClient.Event.StopRequested -> {
                    scope.launch { DeviceTools.stopRobot(context, settings) }
                }
                is DoubaoDuplexClient.Event.TurnDone -> {
                    duplexUpdateAssistant(ev.answer, done = true)
                    if (ev.answer.isNotBlank()) {
                        val key = ev.asr.filterNot { it.isWhitespace() } + "|" + ev.answer.filterNot { it.isWhitespace() }
                        if (key != lastDuplexTurnKey) {
                            lastDuplexTurnKey = key
                            vm.appendDuplexTurn(ev.asr, ev.answer)
                            vm.summarizeCallTurns()
                        }
                    }
                    duplexMapCards.lastOrNull()?.let { vm.attachDuplexMapCard(it) }
                    duplexMapCards.clear()
                }
                is DoubaoDuplexClient.Event.SessionReady -> {
                    duplexHint = null
                    duplexTurns.clear()
                    duplexMapCards.clear()
                    lastDuplexTurnKey = ""
                }
                is DoubaoDuplexClient.Event.Error -> duplexHint = when (ev.code) {
                    "CONFIG" -> "未配置豆包实时语音 Key，请到设置里填写后再通话"
                    else -> "通话出错：${ev.message}"
                }
                else -> Unit
            }
        }
    }

    DisposableEffect(Unit) {
        TtsPlayer.stop() // classic 朗读先闭嘴
        // 通话期间不熄屏 + 压住 WiFi 省电（与大字/灵动同口径）
        ScreenAwake.pin("豆包通话")
        NetKeep.acquire(context, "doubao-call")
        if (settings.duplexApiKey.isBlank()) {
            duplexHint = "未配置豆包实时语音 Key，请到设置里填写后再通话"
        } else if (!SpeechInput.hasRecordPermission(context)) {
            duplexHint = "需要麦克风权限才能全双工通话"
        } else {
            val setup = DuplexCallSetup.build(
                context,
                persona = DuplexCallSetup.PERSONA_TRAVEL,
                history = vm.duplexHistory(sid),
                settings = settings,
                deviceTools = true, // 普通模式没有本地命令链，机器人/计时等靠 FC 补齐
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
        onDispose {
            duplexClient.close()
            duplexClient.destroy()
            NetKeep.release("doubao-call")
            ScreenAwake.unpin("豆包通话")
        }
    }

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
            onHangUp = onHangUp,
            showPhotoActions = false,
        )
    }
}
