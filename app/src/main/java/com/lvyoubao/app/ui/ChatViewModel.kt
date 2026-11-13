package com.lvyoubao.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AiClient
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.data.AmapClient
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.CalendarContext
import com.lvyoubao.app.data.HotwordCorrector
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.RichBlocks
import com.lvyoubao.app.data.observation.FatigueEngine
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.MapIntent
import com.lvyoubao.app.data.MapPoi
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RouteMode
import com.lvyoubao.app.data.RuleValidator
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.WeatherClient
import com.lvyoubao.app.data.presetById
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository
    private val sessions = app.sessionStore
    private val memory = app.memoryStore
    private val noteStore = app.noteStore

    init {
        // 纠错飞轮：加载用户纠正记录（语音稿 vs 用户实际发送的差异对），AI 纠错时注入提示词
        HotwordCorrector.loadLearnedFixes(app.applicationContext)
        // 图片垃圾回收：清理未被任何会话引用的孤立落盘图片
        viewModelScope.launch(Dispatchers.IO) {
            sessions.cleanOrphanImages()
        }
    }

    val settings = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())
    val sessionList = sessions.sessions
    val currentSessionId = sessions.currentSessionId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 热词词库语料：随手记 + 记忆画像（语音界面构词库时取用，越用越懂用户的口音词汇） */
    val quickNotes get() = noteStore.notes.value
    val memoryFacts
        get() = memory.profileFacts() +
            memory.entries.value.filter { it.kind == MemoryStore.MemoryEntry.EVIDENCE }.map { it.title }

    val messages: StateFlow<List<SessionMessage>> =
        combine(sessions.sessions, currentSessionId) { list, id ->
            list.firstOrNull { it.id == id }?.messages ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class PendingAttachment(
        // 从已发送消息恢复的附件没有原始 uri（编辑重发用），发送只依赖 dataUrl/localPath
        val uri: Uri = Uri.EMPTY,
        val name: String = "附件",
        val isImage: Boolean = false,
        val dataUrl: String? = null,
        // 发送时压缩图落盘的路径：会话里显示缩略图/点击看大图，下一轮随历史重发给模型
        val localPath: String? = null,
        val textContent: String? = null,
    )

    data class UiState(
        val question: String = "",
        val attachments: List<PendingAttachment> = emptyList(),
        val sending: Boolean = false,
        // 语音稿 AI 智能纠错进行中：发送禁用，改完自动回填（8 秒超时放行）
        val polishing: Boolean = false,
        val error: String? = null,
    )

    /** 流式输出：reasoning=思考内容，answer=正文增量累积 */
    data class StreamState(val reasoning: String = "", val answer: String = "")

    private val _stream = MutableStateFlow<StreamState?>(null)
    val stream: StateFlow<StreamState?> = _stream.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun updateQuestion(value: String) = _ui.update { it.copy(question = value) }

    /**
     * 按住说话的多段累积：松手一次就往草稿里追加一段，想分几句说都行，
     * 全部说完再点「发送」。两段之间自动补标点，避免句子粘在一起。
     */
    fun appendQuestion(value: String) {
        val piece = value.trim()
        if (piece.isEmpty()) return
        _ui.update { cur ->
            val old = cur.question.trimEnd()
            // 上一段以文字结尾（还没断句）就补个逗号再接下一段；以标点结尾则直接接
            val last = old.lastOrNull()
            val needsPunct = last != null && last.isLetterOrDigit()
            cur.copy(question = if (old.isEmpty()) piece else old + (if (needsPunct) "，" else "") + piece)
        }
    }

    /**
     * 语音稿两段式纠错：先本地热词（拼音同音替换，零延迟、离线可用），再交给 AI 结合
     * 最近对话与记忆画像做一次「识别纠错」——本地拼音法只能修纯汉字的同音词，修不了
     * 「90酸伐尼克兰→酒石酸伐尼克兰」这种数字混写。AI 修完回填输入框；用户已手动改动
     * 或已发送则不覆盖，绝不抢用户的编辑。
     */
    fun appendVoiceTranscript(text: String, polish: Boolean = true) {
        appendQuestion(HotwordCorrector.correct(text))
        // 记下「语音稿版本」：用户发送前若手动改过，发送稿与它的差异就是识别错在哪（纠错飞轮）
        lastVoiceText = _ui.value.question
        if (polish) polishDraftWithAi()
    }

    private val polishInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 语音稿版本（本地热词纠完、AI 未动时的整段草稿）：发送时与用户认可稿对比，喂纠错飞轮 */
    private var lastVoiceText: String? = null

    /**
     * AI 智能纠错：结合最近对话与记忆画像修语音稿的识别错误（同音字、数字混写、断句粘连）。
     * 约束极其严格，宁可不改、不可改错：
     * - 只在语音入口触发（手动输入永不触碰）；纠错期间 UiState.polishing=true，发送被禁用，
     *   改完自动回填输入框，杜绝「发出去的还是错的」；
     * - 8 秒超时兜底（网络差就保留本地热词结果，放行发送）；
     * - 机械护栏：纠正稿长度必须与原稿基本一致、原文的词基本都还在——防 AI 把问题改写成回答
     *   或夹带无关内容；用户已手动改动/已发送则不覆盖。
     */
    private fun polishDraftWithAi() {
        viewModelScope.launch {
            if (!polishInFlight.compareAndSet(false, true)) return@launch
            try {
                val settingsValue = repo.settings.firstOrNull() ?: return@launch
                if (settingsValue.apiKey.isBlank()) return@launch
                val draft = _ui.value.question
                if (draft.length < 4) return@launch
                _ui.update { it.copy(polishing = true) }
                val recent = currentSessionId.value
                    ?.let { sessions.get(it) }
                    ?.messages
                    ?.takeLast(6)
                    ?.joinToString("\n") { m ->
                        (if (m.role == "user") "用户" else "助手") + "：" + m.text.take(80)
                    }
                    .orEmpty()
                val facts = memory.profileFacts().take(8).joinToString("；")
                val prompt = buildString {
                    appendLine("【最近对话】（专有名词写法的参考来源）")
                    appendLine(recent.ifBlank { "（无）" })
                    if (facts.isNotBlank()) appendLine("【用户常用词】$facts")
                    val learned = HotwordCorrector.learnedFixBlock()
                    if (learned.isNotBlank()) {
                        appendLine()
                        append(learned)
                    }
                    appendLine()
                    appendLine("【刚识别出的语音稿】$draft")
                    append(
                        "任务：语音识别纠错。只把语音稿里明显的识别错误改掉：同音错字、数字混写" +
                            "（如「酒石」被识别成「90」）、粘连断句、多余标点；专有名词（地名/药名/人名/景点）" +
                            "优先采用最近对话里出现过的写法。\n" +
                            "铁律：1.只输出纠正后的语音稿原文，一个字都不能多、一个字都不能少；" +
                            "2.绝不回答问题、绝不补充任何内容、绝不解释；" +
                            "3.拿不准的一律保持原样（宁可不改，不可改错）；" +
                            "4.改动幅度必须很小（只动个别字词），长度基本不变。",
                    )
                }
                val reply = try {
                    kotlinx.coroutines.withTimeout(8_000) {
                        AiClient(settingsValue).chatPlain(
                            listOf(
                                OutMessage(
                                    "system",
                                    "你是语音识别纠错器，唯一的职责是把语音稿里的识别错字改对。" +
                                        "只输出纠正后的语音稿原文本身；绝不回答问题、绝不解释、绝不加引号或任何多余文字。" +
                                        "你不是助手，是纠错过滤器。",
                                ),
                                OutMessage("user", prompt),
                            ),
                            thinking = false,
                        ).getOrNull()
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    null   // 超时放行：保留本地热词结果，不拦着用户发送
                }
                val polished = reply?.trim()?.trimMatching('「', '」', '“', '”', '"') ?: return@launch
                if (polished.isEmpty() || polished == draft) return@launch
                // 机械护栏：改动必须是"小修"——长度基本一致、原文的词基本都还在
                val lengthOk = polished.length >= draft.length * 0.6 && polished.length <= draft.length * 1.4
                val grams = draft.windowed(2).toSet()
                val keptRatio = if (grams.isEmpty()) 1f else grams.count { polished.contains(it) } * 1f / grams.size
                if (!lengthOk || keptRatio < 0.5) return@launch
                // 用户没动过输入框、也还没发送才回填；动过就尊重用户的版本
                if (_ui.value.question == draft && !_ui.value.sending) {
                    _ui.update { it.copy(question = polished) }
                }
            } finally {
                polishInFlight.set(false)
                _ui.update { it.copy(polishing = false) }
            }
        }
    }

    private fun String.trimMatching(vararg quotes: Char): String {
        var t = trim()
        for (q in quotes) {
            if (t.length >= 2 && t.first() == q && t.last() == q) t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    /** 清空草稿（语音/拍照重新开始） */
    fun clearQuestion() {
        lastVoiceText = null
        _ui.update { it.copy(question = "") }
    }

    // ---------- 用户消息的编辑/重发 ----------
    // 编辑：撤回这条及其后的所有消息，文本与图片回到输入框，改完再发；
    // 重发：撤回后原样再问一遍（对坏答案/空卡片最直接的补救）。

    /** 把已发送消息恢复成草稿：文本回输入框，带过的图从落盘文件恢复成附件 */
    private fun restoreToDraft(message: SessionMessage) {
        val restored = message.imagePaths.mapNotNull { path ->
            val dataUrl = imageFileToDataUrl(path) ?: return@mapNotNull null
            PendingAttachment(
                name = File(path).name,
                isImage = true,
                dataUrl = dataUrl,
                localPath = path,
            )
        }
        lastVoiceText = null
        _ui.update { it.copy(question = message.text, attachments = restored, error = null) }
    }

    fun editMessage(message: SessionMessage) {
        val state = _ui.value
        if (state.sending || state.polishing) return
        val sid = currentSessionId.value ?: return
        viewModelScope.launch {
            sessions.truncateFrom(sid, message.ts)
            restoreToDraft(message)
        }
    }

    fun resendMessage(context: Context, message: SessionMessage) {
        val state = _ui.value
        if (state.sending || state.polishing) return
        val sid = currentSessionId.value ?: return
        viewModelScope.launch {
            sessions.truncateFrom(sid, message.ts)
            restoreToDraft(message)
            send(context)
        }
    }

    /** 语音识别不支持等瞬时提示，走统一 error 条展示 */
    fun setTransientError(message: String) = _ui.update { it.copy(error = message) }

    fun addAttachment(attachment: PendingAttachment) =
        _ui.update { it.copy(attachments = it.attachments + attachment) }

    fun removeAttachment(attachment: PendingAttachment) =
        _ui.update { it.copy(attachments = it.attachments - attachment) }

    fun setThinking(value: Boolean) = updateSettings { it.copy(thinking = value) }

    fun setSearchEnabled(value: Boolean) = updateSettings { it.copy(searchEnabled = value) }

    /** 顶部「自动播报」开关：打开后每条新回答自动朗读 */
    fun setAutoSpeak(value: Boolean) = updateSettings { it.copy(autoSpeak = value) }

    private fun updateSettings(transform: (AiSettings) -> AiSettings) {
        viewModelScope.launch { repo.save(transform(settings.value)) }
    }

    fun send(context: Context) {
        val state = _ui.value
        val question = state.question.trim()
        // 纠错中禁发：发出去的就是没改对的稿子；等改完回填或 8 秒超时放行
        if ((question.isBlank() && state.attachments.isEmpty()) || state.sending || state.polishing) return

        // 纠错飞轮：语音稿版本 vs 用户认可发送稿——差异就是识别错在哪，成对记下来，
        // 下次 AI 纠错时注入提示词直接照改；用户没改（语音稿=发送稿）不记
        val voiceVersion = lastVoiceText
        lastVoiceText = null
        if (voiceVersion != null && question.isNotBlank() && voiceVersion.trim() != question) {
            runCatching { HotwordCorrector.learnFix(context, voiceVersion, question) }
        }

        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }

            val settingsValue = repo.settings.firstOrNull() ?: AiSettings()
            if (settingsValue.apiKey.isBlank()) {
                _ui.update { it.copy(sending = false, error = "还没有配置 API Key。点右上角设置，选好服务商（默认 DeepSeek）填入 Key 即可。") }
                return@launch
            }

            // 图片上限按供应商预设：DeepSeek 系纯文本模型直接提示，多模态超限自动截断前 N 张
            val preset = presetById(settingsValue.presetId)
            val pickedImages = state.attachments.filter { it.isImage }
            if (pickedImages.isNotEmpty() && preset.maxImages == 0) {
                _ui.update {
                    it.copy(
                        sending = false,
                        error = "当前服务商（${preset.label}）的模型不支持看图：去掉图片再发，或在设置里换智谱 GLM / 通义千问等多模态模型",
                    )
                }
                return@launch
            }
            val imageAttachments = if (pickedImages.size > preset.maxImages) {
                _ui.update { it.copy(error = "${preset.label}的模型一次最多 ${preset.maxImages} 张图，已只用前 ${preset.maxImages} 张") }
                pickedImages.take(preset.maxImages)
            } else pickedImages

            // 确保有当前会话
            var sid = currentSessionId.value
            if (sid == null || sessions.get(sid) == null) {
                sid = sessions.create().id
            }
            val sessionId = sid

            val now = System.currentTimeMillis()
            val hadImage = imageAttachments.isNotEmpty()
            val textFiles = state.attachments.filter { !it.isImage }
            val displayText = question.ifBlank {
                if (hadImage) "（看看这个）" else "（附件）"
            }
            val sentImagePaths = imageAttachments.mapNotNull { it.localPath }
            sessions.appendMessage(
                sessionId,
                SessionMessage(
                    role = "user",
                    text = displayText,
                    hadImage = hadImage,
                    imagePaths = sentImagePaths,
                    ts = now,
                ),
            )

            // 组装上下文：位置 + 本地记忆（个人飞轮）
            val contextBlock = buildString {
                appendLine("【当前情境】")
                // 历法精确数据：北京时间/当地时区/农历/节气（到秒）/时辰起止/节日本地推算——大模型口算不可靠
                val calendarBlock = CalendarContext.block()
                if (calendarBlock != null) {
                    appendLine(calendarBlock)
                } else {
                    appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date()))
                }
                if (LocationContext.hasPermission(context)) {
                    // fresh：缓存过期就主动定位一次，别拿几小时前的旧位置答「附近」
                    LocationContext.fresh(context)?.let { appendLine("位置：${it.description}") }
                } else {
                    appendLine("位置：未授权定位（不要假设用户位置，答不了就问）")
                }
                val home = settingsValue.homeAddress.trim()
                if (home.isNotBlank()) {
                    appendLine()
                    appendLine("【常用地址】家的地址：$home")
                    appendLine("用户问回家/回家的路怎么走时，目的地就是它：map 字段 type=route、destination 填这个地址、origin 留空（=当前位置），answer 一句话报距离和预计时间；绝不要反问回哪，也不要联想成歌名/游戏/影视。")
                }
                val profileBlock = memory.profileContext()
                if (profileBlock.isNotBlank()) {
                    appendLine()
                    append(profileBlock)
                }
                // 画像已整体注入，此处排除 PROFILE 避免同一条记忆在 Prompt 里重复出现两次
                val memoryBlock = memory.relevantContext(question, excludeKinds = setOf(MemoryStore.MemoryEntry.PROFILE))
                if (memoryBlock.isNotBlank()) {
                    appendLine()
                    append(memoryBlock)
                }
            }

            val userText = buildString {
                append(question)
                textFiles.forEach { f ->
                    append("\n\n【附件：${f.name}】\n${f.textContent ?: "（无法读取该文件内容）"}")
                }
                if (hadImage && question.isBlank()) append("（请结合图片回答）")
            }

            // 历史轮次带上下文：按字符预算从最新往旧连续装（selectHistory），不再固定条数——
            // 短问答能带近百轮，长文本自动少带；预算外部分由滚动摘要压缩兜底。图片只在发送
            // 那一轮可见的教训保留：最近一轮带图的消息把落盘的压缩图随历史重发（最多 2 张，
            // 控制视觉请求体积）；更早的带图轮次补一句文字标注，免得模型误以为还看得见图
            val allMsgs = sessions.get(sessionId)?.messages ?: emptyList()
            val selection = selectHistory(allMsgs.dropLast(1), HISTORY_CHAR_BUDGET)
            val historyMessages = selection.kept
            val lastImageTurn = historyMessages.lastOrNull { it.role == "user" && it.imagePaths.isNotEmpty() }
            val history = historyMessages.map { m ->
                when {
                    m === lastImageTurn -> OutMessage(
                        role = m.role,
                        text = m.text,
                        images = m.imagePaths.take(2).mapNotNull { imageFileToDataUrl(it) },
                    )
                    m.role == "user" && m.imagePaths.isNotEmpty() ->
                        OutMessage(role = m.role, text = m.text + "\n（该轮用户发过图片，图片内容本轮未携带）")
                    else -> OutMessage(role = m.role, text = m.text)
                }
            }
            // 长会话滚动摘要：预算装不下的更早对话，由 AI 在上一轮回答后提前浓缩并持久化——
            // 杀进程/重启后依然有效，不会出现断崖式失忆
            val droppedCount = selection.droppedCount
            val sessionObj = sessions.get(sessionId)
            val summaryBlock = sessionObj
                ?.takeIf { droppedCount > 0 && it.summaryDroppedCount >= droppedCount && it.summaryText.isNotBlank() }
                ?.summaryText
                ?.let {
                    listOf(
                        OutMessage(
                            "system",
                            "【本会话更早内容的摘要】（这段发生在本轮携带的对话记录之前，用于延续话题）\n$it",
                        ),
                    )
                }
                ?: emptyList()

            // 天气类问题强制走专业数据源（Open-Meteo 数值预报），AI 只负责总结，不再依赖联网搜索
            var weatherBlock: String? = null
            if (WEATHER_INTENT.containsMatchIn(question) && LocationContext.hasPermission(context)) {
                runCatching {
                    val here = LocationContext.fresh(context)
                    if (here != null) {
                        val fc = withContext(Dispatchers.IO) {
                            WeatherClient.forecast(here.lon, here.lat)
                        }
                        if (fc.isNotBlank()) {
                            weatherBlock = buildString {
                                appendLine("【气象数据 · Open-Meteo 数值预报（更新于 " +
                                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()) + "）】")
                                appendLine("位置：${here.description}")
                                append(fc)
                                appendLine()
                                append("回答天气类问题必须以上述数据为准：直接说哪天转晴/下雨、温度区间、降水概率；数据里没有的就明说，不要再去搜索。")
                            }
                        }
                    }
                }
            }

            val payload = listOf(
                OutMessage("system", AiClient.SYSTEM_PROMPT),
                // 回答详略档（设置页：简洁/中等/详尽），优先级高于基础提示词里的长度要求
                OutMessage("system", AiClient.answerStyleRule(settingsValue.answerStyle)),
                OutMessage("system", contextBlock),
            ) + (weatherBlock?.let { listOf(OutMessage("system", it)) } ?: emptyList()) + summaryBlock + history + listOf(
                OutMessage(
                    role = "user",
                    text = userText,
                    images = imageAttachments.mapNotNull { it.dataUrl },
                ),
            )

            // 工具层捕获的地图卡（问路线/地点时由 get_route / search_poi 带回）
            val mapCards = java.util.Collections.synchronizedList(mutableListOf<MapCardData>())

            _stream.value = StreamState()
            AiClient(settingsValue).chat(
                messages = payload,
                thinking = settingsValue.thinking,
                searchEnabled = settingsValue.searchEnabled,
                toolExecutor = { name, args ->
                    AppTools.execute(context, settingsValue, name, args) { card -> mapCards.add(card) }
                },
                onAnswer = { d -> _stream.update { cur -> val s = cur ?: StreamState(); s.copy(answer = s.answer + d) } },
                onReasoning = { d -> _stream.update { cur -> val s = cur ?: StreamState(); s.copy(reasoning = s.reasoning + d) } },
                // 退化输出自动重试时复位流式卡：重试的内容从头累积，不和上一轮的垃圾拼在一起
                onRetry = { _stream.value = StreamState() },
            ).fold(
                onSuccess = { reply ->
                    // 地图卡三级来源：工具捕获 > AI 输出的地图意图 > 关键词兜底
                    val mapCard = mapCards.lastOrNull()
                        ?: reply.map?.let { buildMapCardFromIntent(context, settingsValue, it) }
                        ?: buildFallbackPlaceCard(context, settingsValue, question)
                    sessions.appendMessage(
                        sessionId,
                        SessionMessage(
                            role = "assistant",
                            text = reply.answer,
                            steps = reply.steps,
                            action = reply.action,
                            // 兜底提示语本身不该再被规则校验挑刺（「没带数字」）
                            violations = if (reply.answer == AiClient.DEGENERATE_ANSWER) emptyList() else RuleValidator.validate(reply),
                            mapCard = mapCard,
                            ts = System.currentTimeMillis(),
                        ),
                    )
                    _stream.value = null
                    _ui.update { it.copy(sending = false, question = "", attachments = emptyList(), error = null) }
                    maybeSummarizeSession(sessionId, settingsValue)
                    refreshSessionSummary(sessionId, settingsValue)
                },
                onFailure = { e ->
                    _stream.value = null
                    _ui.update { it.copy(sending = false, error = e.message ?: "请求失败") }
                },
            )
        }
    }

    fun newSession(onReady: (String) -> Unit = {}) {
        viewModelScope.launch {
            // 会话收尾：把还没总结过的内容补进画像，再开新会话
            currentSessionId.value?.let {
                maybeSummarizeSession(it, repo.settings.firstOrNull() ?: AiSettings(), force = true)
            }
            onReady(sessions.create().id)
        }
    }

    fun switchSession(id: String, onReady: () -> Unit = {}) {
        viewModelScope.launch {
            sessions.setCurrent(id)
            onReady()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch { sessions.delete(id) }
    }

    /** 窗口选取结果：kept 为从新往旧的连续历史段（时间正序），droppedCount 为预算外条数（交滚动摘要兜底） */
    private data class HistorySelection(val kept: List<SessionMessage>, val droppedCount: Int)

    /**
     * 历史窗口选取：按字符预算从最新往旧连续装入，装不下为止（不再固定条数）。
     * 模型上下文再大（deepseek-flash 1M），请求体积也要封顶——长会话的费用与首字延迟才有上界。
     * 整条消息参与预算、不做条内截断（截断丢的恰恰是用户要的关键信息）；预算装不下的整条
     * 让位给更新的消息，交给滚动摘要兜底。prior 传「去掉当前用户消息后的历史」（发送侧）
     * 或全量（摘要预排侧），两条路径必须同源，口径漂移就会回到摘要永远对不上号的老坑
     */
    private fun selectHistory(prior: List<SessionMessage>, charBudget: Int): HistorySelection {
        if (prior.isEmpty()) return HistorySelection(emptyList(), 0)
        var used = 0
        var keptCount = 0
        for (m in prior.asReversed()) {
            val cost = m.text.length + HISTORY_MSG_OVERHEAD_CHARS + m.imagePaths.size * HISTORY_IMAGE_COST_CHARS
            if (keptCount >= HISTORY_MIN_MESSAGES && used + cost > charBudget) break
            used += cost
            keptCount++
        }
        return HistorySelection(prior.takeLast(keptCount), prior.size - keptCount)
    }

    // ---------- 长会话增量递归摘要（补字符预算窗口外的上下文） ----------
    // 借鉴 Anthropic 与 MemGPT 递归摘要思路：交互间隙做「离线巩固」——每轮回答完在后台把
    // 预算外被截掉的对话浓缩成摘要并持久化，等下一轮用户提问时直接取现成的注入，不阻塞发送。
    // 支持增量递归（已有旧摘要 + 新滑出的对话 -> 更新摘要），杜绝跳过中段消息的信息黑洞。
    private val summaryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 每轮回答完成后后台刷新摘要：增量递归摘要并持久化，预算外条数变了才重新生成 */
    private fun refreshSessionSummary(sessionId: String, settings: AiSettings) {
        if (settings.apiKey.isBlank()) return
        val currentSession = sessions.get(sessionId) ?: return
        val msgs = currentSession.messages
        val targetDropped = selectHistory(msgs, HISTORY_CHAR_BUDGET - HISTORY_NEXT_TURN_RESERVE_CHARS).droppedCount
        if (targetDropped < 2) return
        if (currentSession.summaryDroppedCount >= targetDropped && currentSession.summaryText.isNotBlank()) return
        if (!summaryInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val previousSummary = currentSession.summaryText
                val previousDropped = currentSession.summaryDroppedCount
                // 增量递归摘要：若已有上一轮摘要，将 [旧摘要 + 本次新滑出的对话] 结合浓缩，防止信息丢失；
                // 若首次摘要，则取前 targetDropped 条逐条浓缩
                val prompt = if (previousSummary.isNotBlank() && previousDropped in 1 until targetDropped) {
                    val newlyDropped = msgs.subList(previousDropped, targetDropped)
                    val newTranscript = newlyDropped.joinToString("\n") { m ->
                        (if (m.role == "user") "用户" else "助手") + "：" + m.text.take(if (m.role == "user") 120 else 60)
                    }.take(3000)
                    buildString {
                        appendLine("【已有较早对话的摘要】")
                        appendLine(previousSummary)
                        appendLine()
                        appendLine("【新滑出窗口的较早对话】")
                        appendLine(newTranscript)
                        appendLine()
                        append(
                            "任务：将【已有较早对话的摘要】与【新滑出窗口的较早对话】合并，浓缩为一段新的连贯摘要（不超过 300 字）。" +
                                "必须保留：讨论过的主题、提到的专有名词与关键数字（地名/药名/人名/金额/时间）、已达成的结论、用户表达的偏好。" +
                                "只输出摘要本身，不回答问题、不解释。",
                        )
                    }
                } else {
                    val droppedMsgs = msgs.take(targetDropped)
                    // 会话开头 2 条 + 最近被截掉的消息，按时间正序覆盖
                    val corpus = if (droppedMsgs.size <= 32) droppedMsgs else (droppedMsgs.take(2) + droppedMsgs.takeLast(30)).distinctBy { it.ts }
                    val transcript = corpus.joinToString("\n") { m ->
                        (if (m.role == "user") "用户" else "助手") + "：" + m.text.take(if (m.role == "user") 120 else 60)
                    }.take(4000)
                    buildString {
                        appendLine("【较早对话】")
                        appendLine(transcript)
                        append(
                            "任务：把上面这段较早的对话浓缩成一段摘要（不超过 200 字），必须保留：讨论过的主题、" +
                                "提到的专有名词与关键数字（地名/药名/人名/金额/时间）、已达成的结论、用户表达的偏好。" +
                                "只输出摘要本身，不回答问题、不解释。",
                        )
                    }
                }

                val reply = AiClient(settings).chatPlain(
                    listOf(
                        OutMessage("system", "你是会话摘要器：只输出摘要本身，绝不回答问题、绝不解释。"),
                        OutMessage("user", prompt),
                    ),
                    thinking = false,
                ).getOrNull()?.trim() ?: return@launch
                if (reply.length in 20..800) {
                    sessions.updateSummary(sessionId, targetDropped, reply)
                }
            } finally {
                summaryInFlight.set(false)
            }
        }
    }

    /** 个人飞轮：把一条有价值的回答收藏为证据 */
    fun saveToMemory(message: SessionMessage) {
        viewModelScope.launch {
            memory.add(
                kind = MemoryStore.MemoryEntry.EVIDENCE,
                title = message.text.ifBlank { "无标题" },
                detail = (message.steps.takeIf { it.isNotEmpty() }?.joinToString("；") ?: message.text),
                source = "AI 回答（用户收藏）",
            )
            _ui.update { it.copy(error = null) }
        }
    }

    /**
     * 记忆飞轮：攒够 3 句新用户消息就把会话交给 AI 总结成画像，越聊越懂用户；
     * 进度持久化在 Session 中，杀后台/重启不归零；总结成功才递增计数，失败不吞进度。
     */
    private val summarizeMutex = Mutex()

    private fun maybeSummarizeSession(sessionId: String, settings: AiSettings, force: Boolean = false) {
        if (settings.apiKey.isBlank()) return
        viewModelScope.launch {
            val session = sessions.get(sessionId) ?: return@launch
            val userCount = session.messages.count { it.role == "user" }
            val done = session.summarizedUserCount
            if (userCount == 0 || userCount <= done) return@launch
            if (!force && userCount - done < 3) return@launch
            summarizeMutex.withLock {
                val ok = runCatching { memory.updateProfileFromSession(session, settings) }.getOrDefault(false)
                if (ok) {
                    sessions.updateSummarizedUserCount(sessionId, userCount)
                }
            }
        }
    }

    /**
     * AI 输出的地图意图 → 地图卡：
     * route=解析起点（AI 给了起点名就按名解析，否则用当前位置）和终点坐标，规划路线；
     * place=按关键词搜附近 POI。地名解析都带上当前城市，避免"市图书馆"解析到外地。
     * 目的地是「家」类词时直接用设置的家庭地址（AI 偶尔不按提示词填完整地址，
     * 只传一个"家"字会被附近 POI 检索命中成别家店——跳高德就带错了地址）。
     * 出行方式按起终点直线距离自动选：超过 2 公里用驾车（"回家的路"跨城几百公里，
     * 之前的步行规划既不合理也容易失败）。失败静默返回 null，不影响回答本身。
     */
    private suspend fun buildMapCardFromIntent(
        context: Context,
        settings: AiSettings,
        intent: MapIntent,
    ): MapCardData? {
        if (settings.amapKey.isBlank()) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val amap = AmapClient(settings.amapKey)
                val here = LocationContext.fresh(context)
                val city = here?.let { amap.regeocodeCity(it.lon, it.lat) }
                if (intent.type == "route" && intent.destination.isNotBlank()) {
                    val home = settings.homeAddress.trim()
                    val destinationText = if (AmapClient.isHomeDestination(intent.destination) && home.isNotBlank()) home else intent.destination
                    val from = if (intent.origin.isNotBlank()) {
                        amap.geocode(intent.origin, city)
                    } else {
                        here?.let { it.lon to it.lat }
                    } ?: return@withContext null
                    // 家在几百公里外时，以当前位置为中心的 POI 检索必然落空甚至误命中，直接地理编码
                    val to = if (destinationText == home) {
                        amap.geocode(destinationText)?.let {
                            AmapClient.ResolvedDest(it.first, it.second, AmapClient.cleanPlaceName(destinationText))
                        }
                    } else {
                        // POI 优先解析（坐标更贴近实际入口，名称取高德实际命中的），拿不到再地理编码
                        runCatching {
                            AmapClient.resolveDestination(amap, destinationText, from.first, from.second)
                        }.getOrNull()
                            ?: amap.geocode(destinationText, city)?.let {
                                AmapClient.ResolvedDest(it.first, it.second, AmapClient.cleanPlaceName(destinationText))
                            }
                    } ?: return@withContext null
                    val straightKm = FatigueEngine.haversine(from.second, from.first, to.lat, to.lon) / 1000.0
                    val routeMode = if (straightKm > 2.0) RouteMode.DRIVE else RouteMode.WALK
                    val plan = amap.routePlans(routeMode, from.first, from.second, to.lon, to.lat)
                        .firstOrNull() ?: return@withContext null
                    MapCardData(
                        kind = "route",
                        title = to.name,
                        mode = routeMode.name,
                        fromLat = from.second,
                        fromLon = from.first,
                        toLat = to.lat,
                        toLon = to.lon,
                        distanceM = plan.distanceM,
                        durationMin = plan.durationMin,
                        points = plan.points.map { listOf(it.first, it.second) },
                        steps = plan.steps,
                    )
                } else {
                    val keyword = AmapClient.cleanPlaceName(intent.keyword.ifBlank { intent.destination })
                    val p = here ?: return@withContext null
                    val pois = amap.poiSearch(keyword, p.lon, p.lat)
                    if (pois.isEmpty()) return@withContext null
                    MapCardData(
                        kind = "place",
                        title = keyword,
                        fromLat = p.lat,
                        fromLon = p.lon,
                        toLat = pois.first().lat,
                        toLon = pois.first().lon,
                        pois = pois.map { MapPoi(it.name, it.address, it.lat, it.lon, it.distanceM) },
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * 兜底地点卡：问题明显在问「怎么去 / 在哪」但模型没调地图工具时，
     * 直接拿提取出的关键词搜一次 POI，把地点卡补上（失败静默，不打扰回答）。
     */
    private suspend fun buildFallbackPlaceCard(
        context: Context,
        settings: AiSettings,
        question: String,
    ): MapCardData? {
        val keyword = extractPlaceKeyword(question) ?: return null
        if (settings.amapKey.isBlank()) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val place = LocationContext.fresh(context) ?: return@withContext null
                val pois = AmapClient(settings.amapKey).poiSearch(keyword, place.lon, place.lat)
                if (pois.isEmpty()) return@withContext null
                MapCardData(
                    kind = "place",
                    title = keyword,
                    fromLat = place.lat,
                    fromLon = place.lon,
                    toLat = pois.first().lat,
                    toLon = pois.first().lon,
                    pois = pois.map { MapPoi(it.name, it.address, it.lat, it.lon, it.distanceM) },
                )
            }.getOrNull()
        }
    }

    private fun extractPlaceKeyword(question: String): String? {
        val text = question.trim()
        val hasIntent = Regex("怎么走|怎么去|怎么到|怎么前往|如何去|如何到达|在哪|在哪儿|在哪里|多远|远不远|导航|带我去|路线").containsMatchIn(text)
        if (!hasIntent) return null
        var kw = text
            .replace(Regex("请问|麻烦|帮我|我想|我要|现在|附近|一下|查询|查下|看看|搜索|导航到|导航去|带我去"), "")
            .replace(Regex("怎么走|怎么去|怎么到|怎么前往|如何去|如何到达|在哪|在哪儿|在哪里|多远|远不远|路线"), "")
            .replace(Regex("[?？!！。,，、\\s]+"), "")
            .trim()
        kw = kw.removePrefix("去").removePrefix("到").removePrefix("前往").removePrefix("回")
        return kw.takeIf { it.length in 2..20 }
    }

    companion object {
        /** 相册一次最多选几张（发送时的真正上限由各供应商预设 maxImages 把控） */
        const val MAX_PICK_IMAGES = 4

        /**
         * 历史窗口字符预算：从最新往旧连续装入，超出部分由滚动摘要压缩兜底。
         * 中文约 0.6~0.7 token/字符，48k 字符约合 3 万 token 出头——主流 128K 以上上下文的
         * 模型只占零头，短问答能带近百轮；请求体积封顶，长会话的费用和首字延迟才有上界
         */
        const val HISTORY_CHAR_BUDGET = 48_000

        /** 历史窗口保底条数：预算再紧也至少带最近一轮问答，否则指代类追问（「再把表格改改」）完全失去依托 */
        const val HISTORY_MIN_MESSAGES = 2

        /** 每条历史消息的固定开销当量（role/分隔等杂项） */
        const val HISTORY_MSG_OVERHEAD_CHARS = 16

        /** 每张随历史重发的图片按此字符当量计入预算（视觉 token 的粗略折算） */
        const val HISTORY_IMAGE_COST_CHARS = 8_000

        /** 摘要预排时给下一轮「新提问」预留的字符量（语音提问通常几十字，余量放大到一屏文本） */
        const val HISTORY_NEXT_TURN_RESERVE_CHARS = 4_000

        /** 天气类问题（强制注入专业气象数据） */
        val WEATHER_INTENT = Regex("天气|下雨|下雪|降雪|降雨|天晴|放晴|转晴|晴不晴|气温|温度|几度|冷不冷|热不热|穿什么|带伞|雨伞|刮风|风力|风速|湿度|降水")
        /** 拼一条消息的可朗读文本（回答 + 要点 + 建议），会话页与大字模式共用 */
        fun speakableText(message: SessionMessage): String = buildString {
            // v0.8.48：正文里的表格读成「列头…第1行…」、图表跳过（读代码没意义），其余文本原样
            append(RichBlocks.speakableText(message.text))
            message.steps.forEachIndexed { i, s -> append("。第${i + 1}条，$s") }
            message.action?.let { append("。建议：$it") }
        }

        /**
         * 从 Uri 构建附件：图片压缩为 base64 data URL；文本文件读前 60KB。
         * 失败不再静默返回 null，而是带原因回传，界面明示用户。
         */
        fun readAttachment(context: Context, uri: Uri): Pair<PendingAttachment, String?> {
            val resolver = context.contentResolver
            val name = queryName(resolver, uri) ?: "附件"
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val lowerName = name.lowercase()
            // 微信/网盘分享来的 HEIC 常常既没有扩展名、MIME 还是 octet-stream，只按名字判断会掉进文本分支
            val sniffed = sniffImageFormat(resolver, uri)
            val looksImage = sniffed != null ||
                mime.startsWith("image/") ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp").any { lowerName.endsWith(it) }
            return if (looksImage) {
                val compressed = compressImageForSend(resolver, uri)
                if (compressed == null) {
                    val heifLike = sniffed == "heif" || mime.contains("heic") || mime.contains("heif") ||
                        lowerName.endsWith(".heic") || lowerName.endsWith(".heif")
                    Pair(
                        PendingAttachment(uri = uri, name = name, isImage = true),
                        if (heifLike) "这是 HEIC/HEIF 格式图片，当前手机系统解不出来（安卓 9 以下没有解码器），建议先用相册把它转成 JPG 再发"
                        else "这张图片读取失败（可能是不支持的格式），发送后 AI 可能看不到内容，建议换一张试试",
                    )
                } else {
                    Pair(
                        PendingAttachment(
                            uri = uri,
                            name = name,
                            isImage = true,
                            dataUrl = compressed.first,
                            localPath = saveSentImage(context, compressed.second),
                        ),
                        null,
                    )
                }
            } else {
                val text = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val head = bytes.take(60 * 1024).toByteArray()
                        String(head, Charsets.UTF_8) + if (bytes.size > 60 * 1024) "\n（内容过长，已截断）" else ""
                    }
                }.getOrNull()
                val printable = text?.takeIf { it.isNotBlank() && isMostlyPrintable(it) }
                if (printable == null) {
                    Pair(
                        PendingAttachment(uri = uri, name = name, isImage = false, textContent = null),
                        "「$name」读不出文本内容（目前支持图片和文本文件），先带着附件试试",
                    )
                } else {
                    Pair(PendingAttachment(uri = uri, name = name, isImage = false, textContent = printable), null)
                }
            }
        }

        private fun isMostlyPrintable(text: String): Boolean {
            if (text.isEmpty()) return false
            val printable = text.count { it.code in 9 until 0xFFFD && it.code != 0xFEFF }
            return printable.toFloat() / text.length > 0.7f
        }

        private fun queryName(resolver: android.content.ContentResolver, uri: Uri): String? =
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }

        /** 读文件头判断真实图片格式：HEIC/HEIF 容器是 ftyp box，brand 区分照片与视频 */
        private fun sniffImageFormat(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(16)
                var filled = 0
                while (filled < head.size) {
                    val read = input.read(head, filled, head.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < 12) return@use null
                fun at(off: Int, text: String): Boolean {
                    if (filled < off + text.length) return false
                    for (i in text.indices) if (head[off + i] != text[i].code.toByte()) return false
                    return true
                }
                when {
                    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpeg"
                    at(0, "\u0089PNG") -> "png"
                    at(0, "GIF8") -> "gif"
                    at(0, "BM") -> "bmp"
                    at(0, "RIFF") && at(8, "WEBP") -> "webp"
                    at(4, "ftyp") -> {
                        val brand = String(head, 8, 4, Charsets.US_ASCII).lowercase()
                        // mif1/msf1 是 HEIF 容器通用 brand，heic/heix/hevc/heim/heis/avif 是具体编码
                        if (brand in setOf("heic", "heix", "hevc", "hevx", "heif", "heim", "heis", "mif1", "msf1", "avif", "avis")) "heif" else null
                    }
                    else -> null
                }
            }
        }.getOrNull()

        /**
         * 图片统一压缩：长边压到 1280 内转 JPEG（q75），返回 (data URL, JPEG 字节)——
         * 字节同时用于落盘（气泡缩略图/看大图/下一轮随历史重发）。
         */
        private fun compressImageForSend(
            resolver: android.content.ContentResolver,
            uri: Uri,
        ): Pair<String, ByteArray>? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 注意：inJustDecodeBounds=true 时 decodeStream 恒返回 null，只读 bounds；
            // 这里不能用返回值做判空，否则所有图片都会被误判为"读取失败"
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var bitmap: Bitmap? = null
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
            if (bitmap == null && android.os.Build.VERSION.SDK_INT >= 28) {
                // 兜底：ImageDecoder 对 HEIC/HEIF 与部分厂商私有 JPEG 变体更宽容。
                // BitmapFactory 连尺寸都读不出的格式恰恰最需要这条兜底，所以 bounds 失败不能提前返回
                bitmap = runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(resolver, uri),
                    ) { decoder, info, _ ->
                        val largest = maxOf(info.size.width, info.size.height)
                        if (largest > 1280) decoder.setTargetSampleSize((largest + 1279) / 1280)
                    }
                }.getOrNull()
            }
            val bmp = bitmap ?: return null
            val output = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, output)
            bmp.recycle()
            val bytes = output.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64" to bytes
        }.getOrNull()

        /** 压缩图落盘（filesDir/sent_images）：气泡缩略图、点击看大图、下一轮随历史重发都读这份文件 */
        private fun saveSentImage(context: Context, bytes: ByteArray): String? = runCatching {
            val dir = File(context.filesDir, "sent_images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}_${(0..999).random()}.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()

        /** 历史轮次重发图片：把落盘的压缩图读回 base64 data URL；文件被清理/不存在返回 null */
        private fun imageFileToDataUrl(path: String): String? = runCatching {
            val file = File(path)
            if (!file.exists() || file.length() <= 0) return null
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
