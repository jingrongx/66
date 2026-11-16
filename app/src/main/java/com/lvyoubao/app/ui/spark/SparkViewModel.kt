package com.lvyoubao.app.ui.spark

import android.content.Context
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

class SparkViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository
    private val appContext: Context = app.applicationContext

    val settings: StateFlow<AiSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())

    private val _eyeState = MutableStateFlow(SparkEyeState.IDLE)
    val eyeState: StateFlow<SparkEyeState> = _eyeState.asStateFlow()

    private val _statusHint = MutableStateFlow<String?>(null)
    val statusHint: StateFlow<String?> = _statusHint.asStateFlow()

    // 内存中维护完整对话历史（最大字符预算截取，不硬限制轮数）
    private val conversationHistory = mutableListOf<OutMessage>()

    // 云端按键录音中状态
    private var cloudRecording: SpeechInput.Recording? = null
    private var activeJob: Job? = null

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

    /** 单击眼部：如果正在说话则打断静音；静默则唤醒；否则开始自然聆听 */
    fun onEyeClicked(context: Context, onRequestRecordPermission: () -> Unit) {
        when (_eyeState.value) {
            SparkEyeState.MUTED -> {
                // 静默中点一下唤醒
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = "已唤醒"
            }
            SparkEyeState.SPEAKING -> {
                // 打断播报
                TtsPlayer.stop()
                _eyeState.value = SparkEyeState.IDLE
            }
            SparkEyeState.LISTENING -> {
                // 再次点按：停止录音并发送
                finishListening(context)
            }
            SparkEyeState.THINKING, SparkEyeState.CAPTURING -> {
                // 正在处理中，忽略单按
            }
            SparkEyeState.IDLE -> {
                startListening(context, onRequestRecordPermission)
            }
        }
    }

    /** 长按：静默模式切换 */
    fun toggleMute() {
        if (_eyeState.value == SparkEyeState.MUTED) {
            _eyeState.value = SparkEyeState.IDLE
            _statusHint.value = "已退出静默"
        } else {
            TtsPlayer.stop()
            cloudRecording?.let {
                SpeechInput.cancelRecording(it)
                cloudRecording = null
            }
            _eyeState.value = SparkEyeState.MUTED
            _statusHint.value = "静默模式 (长按恢复)"
        }
    }

    /** 双击：触发摄像头识别当前画面 */
    fun onDoubleClicked(context: Context, onRequestCameraPermission: () -> Unit) {
        if (_eyeState.value == SparkEyeState.MUTED) return
        if (!CameraSnapshotHelper.hasCameraPermission(context)) {
            onRequestCameraPermission()
            return
        }
        captureAndAnalyze(context, "请帮我看看这是什么，用两三句话简洁描述。")
    }

    private fun startListening(context: Context, onRequestRecordPermission: () -> Unit) {
        if (!SpeechInput.hasRecordPermission(context)) {
            onRequestRecordPermission()
            return
        }

        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.LISTENING
        _statusHint.value = "请说话…"

        val curSettings = settings.value
        // 若支持系统识别且未配 ASR key，走系统内置断句识别
        if (curSettings.asrKey.isBlank() && SpeechInput.isAvailable(context)) {
            activeJob?.cancel()
            activeJob = viewModelScope.launch {
                val res = SpeechInput.listen(context, "请说话")
                res.fold(
                    onSuccess = { rawText ->
                        val clean = HotwordCorrector.correct(rawText.trim())
                        if (clean.isNotBlank()) {
                            sendQuestion(context, clean)
                        } else {
                            _eyeState.value = SparkEyeState.IDLE
                            _statusHint.value = null
                        }
                    },
                    onFailure = { err ->
                        _eyeState.value = SparkEyeState.IDLE
                        _statusHint.value = err.message
                    },
                )
            }
        } else {
            // 走 SenseVoice 录音
            SpeechInput.startRecording(context).fold(
                onSuccess = { rec ->
                    cloudRecording = rec
                },
                onFailure = { e ->
                    _eyeState.value = SparkEyeState.IDLE
                    _statusHint.value = e.message ?: "麦克风启动失败"
                },
            )
        }
    }

    private fun finishListening(context: Context) {
        val rec = cloudRecording
        if (rec != null) {
            cloudRecording = null
            _eyeState.value = SparkEyeState.THINKING
            _statusHint.value = "正在识别…"
            viewModelScope.launch {
                val curSettings = settings.value
                SpeechInput.stopAndTranscribe(rec, curSettings.asrKey, curSettings.dialectMode)
                    .fold(
                        onSuccess = { spoken ->
                            val clean = HotwordCorrector.correct(spoken.trim())
                            if (clean.isNotBlank()) {
                                sendQuestion(context, clean)
                            } else {
                                _eyeState.value = SparkEyeState.IDLE
                                _statusHint.value = null
                            }
                        },
                        onFailure = { err ->
                            _eyeState.value = SparkEyeState.IDLE
                            _statusHint.value = err.message
                        },
                    )
            }
        }
    }

    fun captureAndAnalyze(context: Context, prompt: String = "请简洁描述画面内容") {
        TtsPlayer.stop()
        _eyeState.value = SparkEyeState.CAPTURING
        _statusHint.value = "正在拍照…"

        viewModelScope.launch {
            val dataUrl = CameraSnapshotHelper.takeSnapshotDataUrl(context)
            if (dataUrl.isNullOrBlank()) {
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = "拍照失败，请重试"
                return@launch
            }

            _eyeState.value = SparkEyeState.THINKING
            _statusHint.value = "小源正在观察…"

            sendAiRequest(
                context = context,
                userText = prompt,
                imageDataUrls = listOf(dataUrl),
            )
        }
    }

    fun sendQuestion(context: Context, question: String) {
        _eyeState.value = SparkEyeState.THINKING
        _statusHint.value = "思考中…"

        viewModelScope.launch {
            sendAiRequest(context = context, userText = question, imageDataUrls = emptyList())
        }
    }

    private suspend fun sendAiRequest(
        context: Context,
        userText: String,
        imageDataUrls: List<String>,
    ) = withContext(Dispatchers.IO) {
        val curSettings = repo.settings.firstOrNull() ?: AiSettings()
        if (curSettings.apiKey.isBlank()) {
            _eyeState.value = SparkEyeState.IDLE
            _statusHint.value = "未配置 API Key，请去设置填写"
            return@withContext
        }

        // 组装简练情境
        val contextInfo = buildString {
            val cal = CalendarContext.block()
            if (!cal.isNullOrBlank()) appendLine(cal)
            if (LocationContext.hasPermission(context)) {
                LocationContext.current(context)?.description?.let { appendLine("位置：$it") }
            }
        }

        val systemPrompt = """
            你是灵动屏智能伴侣「小源」，搭载于便携横屏小设备上。
            【交互特点】你的回应将直接转换为语音播放，屏幕上只有一双可爱的眼睛。
            【表达原则】
            1. 语气亲切、自然可爱、生动活泼；
            2. 回答要极其简练扼要，直接说出答案或重点，一般控制在1至3句话以内；
            3. 坚决不要输出任何 Markdown 格式（如加粗**、标题#、列表项、代码块等），全部使用自然口语；
            4. 若涉及视觉看图，直接生动说出图中是什么或关键信息。
        """.trimIndent()

        // 按字符预算裁剪历史，保持最大有效上下文（同主工程 48,000 字符预算）
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

        AiClient(curSettings).chat(
            messages = fullPayload,
            thinking = false,
            searchEnabled = false,
            onAnswer = { chunk ->
                speechBuffer.append(chunk)
                // 收到首段文字后迅速启动流式语音朗读
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
                conversationHistory.add(currentUserMsg)
                conversationHistory.add(OutMessage("assistant", finalAnswer))

                // 播放完整最终文本（若之前没触发，或补全最后文本）
                _eyeState.value = SparkEyeState.SPEAKING
                _statusHint.value = null
                TtsPlayer.speak(context, finalAnswer)
            },
            onFailure = { err ->
                _eyeState.value = SparkEyeState.IDLE
                _statusHint.value = "出错啦：${err.message ?: "网络超时"}"
            },
        )
    }

    /** 48,000 字符预算的历史连续装入 */
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

    fun exitSparkMode(onExit: () -> Unit) {
        TtsPlayer.stop()
        cloudRecording?.let {
            SpeechInput.cancelRecording(it)
            cloudRecording = null
        }
        viewModelScope.launch {
            repo.save(settings.value.copy(sparkMode = false))
            onExit()
        }
    }

    override fun onCleared() {
        super.onCleared()
        cloudRecording?.let {
            SpeechInput.cancelRecording(it)
            cloudRecording = null
        }
        TtsPlayer.stop()
    }
}
