package com.lvyoubao.app.ui.spark

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.ui.common.SpeechInput
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SparkEyeState {
    IDLE,        // 待机呼吸，日常俏皮
    LISTENING,   // 聆听中，专注睁大
    THINKING,    // 思考中，眼神转动
    SPEAKING,    // 播报中，随语调灵动闪烁
    CAPTURING,   // 拍照快门眨眼反馈
    MUTED,       // 静默安睡模式，月牙眯眼
}

/** 会话流中的一条消息：photo 仅在拍照/选图那一轮携带，随消息滚走，不再常驻顶部 */
data class SparkMessage(
    val role: String, // "user" / "assistant"
    val text: String,
    val photo: Bitmap? = null,
)

class SparkViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository

    // 必须 Eagerly：灵动屏界面从不收集这个流，WhileSubscribed 会让上游永不启动，
    // settings.value 永远停在默认空配置——曾导致退出灵动屏把默认空配置整体写回、
    // 清空用户全部配置，以及误报「未配置语音识别 Key」
    val settings: StateFlow<AiSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiSettings())

    private val _eyeState = MutableStateFlow(SparkEyeState.IDLE)
    val eyeState: StateFlow<SparkEyeState> = _eyeState.asStateFlow()

    // 界面显示切换：true 显示可爱大眼动画；false 显示会话文字与照片框
    private val _showEyes = MutableStateFlow(true)
    val showEyes: StateFlow<Boolean> = _showEyes.asStateFlow()

    private val _statusHint = MutableStateFlow<String?>(null)
    val statusHint: StateFlow<String?> = _statusHint.asStateFlow()

    // 按住说话录音状态
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordTick = MutableStateFlow(0)
    val recordTick: StateFlow<Int> = _recordTick.asStateFlow()

    // 完整会话流（流式滚动展示，上下文可回看）；上限 40 条防图片占内存
    private val _messages = MutableStateFlow<List<SparkMessage>>(emptyList())
    val messages: StateFlow<List<SparkMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // 完整对话历史（48,000 字符最大预算）
    private val conversationHistory = mutableListOf<OutMessage>()

    private var activeRecording: SpeechInput.Recording? = null
    private var recordTimerJob: Job? = null
    private var aiRequestJob: Job? = null

    init {
        // 监听 TTS 状态：播报完毕自动复位为 IDLE
        viewModelScope.launch {
            TtsPlayer.state.collect { ttsState ->
                if (_eyeState.value == SparkEyeState.SPEAKING && ttsState is TtsPlayer.State.Idle) {
                    _eyeState.value = SparkEyeState.IDLE
                } else if (_eyeState.value == SparkEyeState.THINKING && ttsState is TtsPlayer.State.Speaking) {
                    _eyeState.value = SparkEyeState.SPEAKING
                }
            }
        }
    }

    fun setTransientHint(msg: String?) {
        _statusHint.value = msg
    }

    fun toggleShowEyes() {
        _showEyes.value = !_showEyes.value
    }

    fun setShowEyes(show: Boolean) {
        _showEyes.value = show
    }

    /** 追加一条会话消息（超上限丢最旧的，防图片占内存） */
    private fun appendMessage(msg: SparkMessage) {
        _messages.value = (_messages.value + msg).takeLast(40)
    }

    /** 流式更新最后一条 assistant 消息 */
    private fun updateLastAssistant(text: String) {
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant") {
            list[list.lastIndex] = list.last().copy(text = text)
            _messages.value = list
        }
    }

    /**
     * 按住说话：按下开始录音
     * 操作逻辑完全对齐主模式与大字模式：按住录音、松手即问
     * 按下时显示大眼睛动画（对应需求：按住按钮说话的时候就显示眼睛动画）
     */
    fun startHoldTalk(context: Context, onRequestRecordPermission: () -> Unit) {
        if (!SpeechInput.hasRecordPermission(context)) {
            onRequestRecordPermission()
            return
        }

        // 停止之前的朗读
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.LISTENING
        _showEyes.value = true // 按下时立刻呈现大眼睛动画
        _statusHint.value = "正在听你说，松手发送…"

        val curSettings = settings.value

        // 如果未配置 ASR Key，提示填写
        if (curSettings.asrKey.isBlank()) {
            _statusHint.value = "提示：可在设置里填入语音识别Key（硅基流动免费）"
        }

        SpeechInput.startRecording(context).fold(
            onSuccess = { rec ->
                activeRecording = rec
                _isRecording.value = true
                _recordTick.value = 0
                recordTimerJob?.cancel()
                recordTimerJob = viewModelScope.launch {
                    while (_isRecording.value) {
                        delay(1000)
                        _recordTick.value += 1
                    }
                }
            },
            onFailure = { err ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = err.message ?: "麦克风启动失败"
            },
        )
    }

    /**
     * 按住说话：松手发送
     * 停止录音 -> 转写文字 -> 切换为会话框显示文字 -> DeepSeek 回答 + TTS
     */
    fun endHoldTalk(context: Context) {
        val rec = activeRecording
        activeRecording = null
        _isRecording.value = false
        recordTimerJob?.cancel()

        if (rec == null) {
            _eyeState.value = SparkEyeState.IDLE
            return
        }

        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "正在识别语音…"

        viewModelScope.launch {
            // 与 sendAiRequest 同口径：读仓库实时配置，不依赖界面是否订阅过 settings 流
            val curSettings = repo.settings.firstOrNull() ?: settings.value
            val asrKey = curSettings.asrKey.trim()

            if (asrKey.isBlank()) {
                // 没有配置 ASR Key
                _eyeState.value = SparkEyeState.IDLE
                appendMessage(SparkMessage("user", "（未配置语音识别 Key）"))
                appendMessage(
                    SparkMessage("assistant", "请先在「设置」中配置语音识别 Key（硅基流动 SenseVoice 免费），即可畅享毫秒级语音对话。"),
                )
                _showEyes.value = false
                _statusHint.value = "请在设置中配置语音识别 Key"
                SpeechInput.cancelRecording(rec)
                return@launch
            }

            SpeechInput.stopAndTranscribe(rec, asrKey, curSettings.dialectMode).fold(
                onSuccess = { spoken ->
                    val clean = HotwordCorrector.correct(spoken.trim())
                    if (clean.isNotBlank()) {
                        appendMessage(SparkMessage("user", clean))
                        _showEyes.value = false // 提问完成，自动切换为会话流展示
                        sendAiRequest(context, clean, emptyList())
                    } else {
                        _eyeState.value = SparkEyeState.IDLE
                        _statusHint.value = "没听清你说什么，可以再试一次"
                    }
                },
                onFailure = { err ->
                    _eyeState.value = SparkEyeState.IDLE
                    appendMessage(SparkMessage("user", "（识别未完成）"))
                    appendMessage(SparkMessage("assistant", err.message ?: "语音转文字失败，请稍后重试"))
                    _showEyes.value = false
                    _statusHint.value = err.message
                },
            )
        }
    }

    /**
     * 按需拍照识别：
     * 抓拍单帧图片 -> 保存 Bitmap 供界面展示缩略图 -> 切换为会话框 -> 提交 DeepSeek 视觉解析
     */
    fun captureAndAnalyze(context: Context) {
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.CAPTURING
        _statusHint.value = "正在拍照…"

        viewModelScope.launch {
            val result = CameraSnapshotHelper.takeSnapshot(context)
            if (result == null) {
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = "拍照失败，请检查摄像头权限"
                return@launch
            }

            // 照片只属于拍照这一轮：随消息进会话流，说话的新一轮不会再带着旧照片
            appendMessage(SparkMessage("user", "📷 帮我看看拍到了什么", result.bitmap))
            appendMessage(SparkMessage("assistant", "小源正在观察画面…"))
            _showEyes.value = false // 切换为会话流，露出刚刚拍到的照片和回答

            _eyeState.value = SparkEyeState.THINKING
            _statusHint.value = "小源正在观察…"

            sendAiRequest(
                context = context,
                userText = "请结合这张刚刚拍摄的照片，用通俗易懂的自然口语简要说明画面里是什么内容。",
                imageDataUrls = listOf(result.dataUrl),
            )
        }
    }

    /**
     * 选择相册图片识别：
     * 读取 Uri 解码 Bitmap 供界面展示 -> 切换为会话框 -> 提交 DeepSeek 视觉解析
     */
    fun handlePickedImage(context: Context, uri: android.net.Uri) {
        TtsPlayer.stop()
        _statusHint.value = "正在读取相册…"

        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
                sample *= 2
            }

            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }

            if (bitmap == null) {
                _statusHint.value = "图片读取失败"
                return@launch
            }

            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
            val bytes = out.toByteArray()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            appendMessage(SparkMessage("user", "🖼 帮我看看这张相册照片", bitmap))
            appendMessage(SparkMessage("assistant", "小源正在观察相册图片…"))
            _showEyes.value = false // 切换为会话流

            _eyeState.value = SparkEyeState.THINKING
            _statusHint.value = "小源正在观察…"

            sendAiRequest(
                context = context,
                userText = "请结合这张选自相册的照片，用通俗易懂的自然口语简要说明画面里是什么内容。",
                imageDataUrls = listOf(dataUrl),
            )
        }
    }

    private fun sendAiRequest(
        context: Context,
        userText: String,
        imageDataUrls: List<String>,
    ) {
        aiRequestJob?.cancel()
        // 先占一条 assistant 消息，后续流式/结果/出错都更新它
        appendMessage(SparkMessage("assistant", ""))
        aiRequestJob = viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repo.settings.firstOrNull() ?: AiSettings()
            if (curSettings.apiKey.isBlank()) {
                _eyeState.value = SparkEyeState.IDLE
                _isStreaming.value = false
                updateLastAssistant("未配置 API Key，请去「设置」选择服务商并填写 Key。")
                _statusHint.value = "未配置 API Key"
                return@launch
            }

            val contextInfo = buildString {
                val cal = CalendarContext.block()
                if (!cal.isNullOrBlank()) appendLine(cal)
                if (LocationContext.hasPermission(context)) {
                    LocationContext.current(context)?.description?.let { appendLine("位置：$it") }
                }
            }

            val systemPrompt = """
                你是灵动屏智能伴侣「小源」，搭载于小屏随身智能设备。
                你的回应将直接转换为语音播放，并显示在屏幕会话框中。
                【表达原则】
                1. 语气亲切生动、自然口语化，适合小屏阅读与语音朗读；
                2. 回答要极其简练扼要，直接说出答案或关键要点，通常在2至4句话以内；
                3. 严禁输出任何 Markdown 标记（如加粗**、标题#、列表项、代码块等），全部使用自然口语排版；
                4. 若涉及视觉看图，生动具体地告诉用户拍到了什么。
            """.trimIndent()

            val historyPayload = selectHistoryBudget(conversationHistory, 48_000)

            val currentUserMsg = OutMessage(
                role = "user",
                text = userText,
                images = imageDataUrls,
            )

            val fullPayload = listOfNotNull(
                OutMessage("system", systemPrompt),
                if (contextInfo.isNotBlank()) OutMessage("system", "【情境】\n$contextInfo") else null,
            ) + historyPayload + listOf(currentUserMsg)

            val speechBuffer = StringBuilder()
            var hasStartedTts = false
            _isStreaming.value = true

            AiClient(curSettings).chat(
                messages = fullPayload,
                thinking = false,
                searchEnabled = false,
                onAnswer = { chunk ->
                    speechBuffer.append(chunk)
                    updateLastAssistant(speechBuffer.toString())

                    if (!hasStartedTts && speechBuffer.length >= 6) {
                        hasStartedTts = true
                        _eyeState.value = SparkEyeState.SPEAKING
                        _statusHint.value = null
                        TtsPlayer.speak(context, speechBuffer.toString())
                    }
                },
            ).fold(
                onSuccess = { reply ->
                    val finalAnswer = reply.answer.trim()
                    updateLastAssistant(finalAnswer)
                    _isStreaming.value = false

                    conversationHistory.add(currentUserMsg)
                    conversationHistory.add(OutMessage("assistant", finalAnswer))

                    _eyeState.value = SparkEyeState.SPEAKING
                    _statusHint.value = null
                    TtsPlayer.speak(context, finalAnswer)
                },
                onFailure = { err ->
                    _eyeState.value = SparkEyeState.IDLE
                    _isStreaming.value = false
                    updateLastAssistant("请求出错啦：${err.message ?: "网络超时"}")
                    _statusHint.value = "请求失败"
                },
            )
        }
    }

    private fun selectHistoryBudget(all: List<OutMessage>, budget: Int): List<OutMessage> {
        if (all.isEmpty()) return emptyList()
        var used = 0
        var count = 0
        for (msg in all.asReversed()) {
            val cost = msg.text.length + 100 + msg.images.size * 2000
            if (count >= 2 && used + cost > budget) break
            used += cost
            count++
        }
        return all.takeLast(count)
    }

    /**
     * 退出灵动屏 = 退出应用（对齐大字模式）：
     * 模式切换只认设置页开关，这里只做收尾（停朗读、停录音），退出交给调用方 finishAffinity
     */
    fun exitApp() {
        TtsPlayer.stop()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRecording?.let {
            SpeechInput.cancelRecording(it)
            activeRecording = null
        }
        TtsPlayer.stop()
    }
}
