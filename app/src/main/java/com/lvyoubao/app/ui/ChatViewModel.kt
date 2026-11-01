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
import com.lvyoubao.app.data.LocationContext
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

            AiClient(settingsValue).chat(
                messages = payload,
                thinking = settingsValue.thinking,
                searchEnabled = settingsValue.searchEnabled,
            ).fold(
                onSuccess = { reply ->
                    sessions.appendMessage(
                        sessionId,
                        SessionMessage(
                            role = "assistant",
                            text = reply.answer,
                            steps = reply.steps,
                            action = reply.action,
                            violations = RuleValidator.validate(reply),
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

    companion object {
        /** 从 Uri 构建附件：图片压缩为 base64 data URL；文本文件读前 60KB */
        fun readAttachment(context: Context, uri: Uri): PendingAttachment? {
            return runCatching {
                val resolver = context.contentResolver
                val name = queryName(resolver, uri) ?: "附件"
                val mime = resolver.getType(uri) ?: ""
                if (mime.startsWith("image/")) {
                    val dataUrl = imageToDataUrl(resolver, uri) ?: return null
                    PendingAttachment(uri = uri, name = name, isImage = true, dataUrl = dataUrl)
                } else {
                    val text = resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val head = bytes.take(60 * 1024).toByteArray()
                        String(head, Charsets.UTF_8) + if (bytes.size > 60 * 1024) "\n（内容过长，已截断）" else ""
                    }
                    PendingAttachment(uri = uri, name = name, isImage = false, textContent = text?.takeIf { it.isNotBlank() && isMostlyPrintable(it) })
                }
            }.getOrNull()
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

        private fun imageToDataUrl(resolver: android.content.ContentResolver, uri: Uri): String? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
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
            return "data:image/jpeg;base64,$b64"
        }
    }
}
