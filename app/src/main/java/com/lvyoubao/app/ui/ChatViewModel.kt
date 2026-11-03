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
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.MapCardData
import com.lvyoubao.app.data.MapPoi
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RuleValidator
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SessionStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(app: TravelApp) : ViewModel() {

    private val repo = app.settingsRepository
    private val sessions = app.sessionStore
    private val memory = app.memoryStore

    val settings = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())
    val sessionList = sessions.sessions
    val currentSessionId = sessions.currentSessionId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<SessionMessage>> =
        combine(sessions.sessions, currentSessionId) { list, id ->
            list.firstOrNull { it.id == id }?.messages ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class PendingAttachment(
        val uri: Uri,
        val name: String,
        val isImage: Boolean,
        val dataUrl: String? = null,
        val textContent: String? = null,
    )

    data class UiState(
        val question: String = "",
        val attachments: List<PendingAttachment> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun updateQuestion(value: String) = _ui.update { it.copy(question = value) }

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

    /** 进长辈模式默认打开自动播报（语音优先），用户仍可随时在顶部关掉 */
    fun setElderMode(value: Boolean) = updateSettings {
        it.copy(elderMode = value, autoSpeak = if (value) true else it.autoSpeak)
    }

    private fun updateSettings(transform: (AiSettings) -> AiSettings) {
        viewModelScope.launch { repo.save(transform(settings.value)) }
    }

    fun send(context: Context) {
        val state = _ui.value
        val question = state.question.trim()
        if ((question.isBlank() && state.attachments.isEmpty()) || state.sending) return

        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }

            val settingsValue = repo.settings.firstOrNull() ?: AiSettings()
            if (settingsValue.apiKey.isBlank()) {
                _ui.update { it.copy(sending = false, error = "还没有配置 API Key。点右上角设置，选好服务商（默认 DeepSeek）填入 Key 即可。") }
                return@launch
            }

            // 确保有当前会话
            var sid = currentSessionId.value
            if (sid == null || sessions.get(sid) == null) {
                sid = sessions.create().id
            }
            val sessionId = sid

            val now = System.currentTimeMillis()
            val hadImage = state.attachments.any { it.isImage }
            val textFiles = state.attachments.filter { !it.isImage }
            val displayText = question.ifBlank {
                if (hadImage) "（看看这个）" else "（附件）"
            }
            sessions.appendMessage(
                sessionId,
                SessionMessage(role = "user", text = displayText, hadImage = hadImage, ts = now),
            )

            // 组装上下文：位置 + 本地记忆（个人飞轮）
            val contextBlock = buildString {
                appendLine("【当前情境】")
                appendLine(
                    "时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date()),
                )
                if (LocationContext.hasPermission(context)) {
                    LocationContext.current(context)?.let { appendLine("位置：${it.description}") }
                } else {
                    appendLine("位置：未授权定位（不要假设用户位置，答不了就问）")
                }
                val memoryBlock = memory.relevantContext(question)
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

            val history = (sessions.get(sessionId)?.messages ?: emptyList())
                .dropLast(1)
                .takeLast(12)
                .map { OutMessage(role = it.role, text = it.text) }

            val payload = listOf(
                OutMessage("system", AiClient.SYSTEM_PROMPT),
                OutMessage("system", contextBlock),
            ) + history + listOf(
                OutMessage(
                    role = "user",
                    text = userText,
                    images = state.attachments.filter { it.isImage }.mapNotNull { it.dataUrl },
                ),
            )

            // 工具层捕获的地图卡（问路线/地点时由 get_route / search_poi 带回）
            val mapCards = java.util.Collections.synchronizedList(mutableListOf<MapCardData>())

            AiClient(settingsValue).chat(
                messages = payload,
                thinking = settingsValue.thinking,
                searchEnabled = settingsValue.searchEnabled,
                toolExecutor = { name, args ->
                    AppTools.execute(context, settingsValue, name, args) { card -> mapCards.add(card) }
                },
            ).fold(
                onSuccess = { reply ->
                    // 模型没调地图工具但问题明显在问地点时，兜底补一张地点卡
                    val mapCard = mapCards.lastOrNull()
                        ?: buildFallbackPlaceCard(context, settingsValue, question)
                    sessions.appendMessage(
                        sessionId,
                        SessionMessage(
                            role = "assistant",
                            text = reply.answer,
                            steps = reply.steps,
                            action = reply.action,
                            violations = RuleValidator.validate(reply),
                            mapCard = mapCard,
                            ts = System.currentTimeMillis(),
                        ),
                    )
                    _ui.update { it.copy(sending = false, question = "", attachments = emptyList(), error = null) }
                },
                onFailure = { e ->
                    _ui.update { it.copy(sending = false, error = e.message ?: "请求失败") }
                },
            )
        }
    }

    fun newSession(onReady: (String) -> Unit = {}) {
        viewModelScope.launch { onReady(sessions.create().id) }
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
                val place = LocationContext.current(context) ?: return@withContext null
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
        /** 拼一条消息的可朗读文本（回答 + 要点 + 建议），会话页与长辈模式共用 */
        fun speakableText(message: SessionMessage): String = buildString {
            append(message.text)
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
            val looksImage = mime.startsWith("image/") ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp").any { lowerName.endsWith(it) }
            return if (looksImage) {
                val dataUrl = imageToDataUrl(resolver, uri)
                if (dataUrl == null) {
                    Pair(
                        PendingAttachment(uri = uri, name = name, isImage = true),
                        "这张图片读取失败（可能是不支持的格式），发送后 AI 可能看不到内容，建议换一张试试",
                    )
                } else {
                    Pair(PendingAttachment(uri = uri, name = name, isImage = true, dataUrl = dataUrl), null)
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

        private fun imageToDataUrl(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            bitmap.recycle()
            val b64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }.getOrNull()
    }
}
