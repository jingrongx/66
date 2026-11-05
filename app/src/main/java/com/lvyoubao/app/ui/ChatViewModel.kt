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
import com.lvyoubao.app.data.MapIntent
import com.lvyoubao.app.data.MapPoi
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.RouteMode
import com.lvyoubao.app.data.RuleValidator
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.WeatherClient
import java.io.ByteArrayOutputStream
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

    /** 清空草稿（语音/拍照重新开始） */
    fun clearQuestion() = _ui.update { it.copy(question = "") }

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

            // 天气类问题强制走专业数据源（Open-Meteo 数值预报），AI 只负责总结，不再依赖联网搜索
            var weatherBlock: String? = null
            if (WEATHER_INTENT.containsMatchIn(question) && LocationContext.hasPermission(context)) {
                runCatching {
                    val here = withContext(Dispatchers.IO) {
                        LocationContext.current(context)
                    }
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
                OutMessage("system", contextBlock),
            ) + (weatherBlock?.let { listOf(OutMessage("system", it)) } ?: emptyList()) + history + listOf(
                OutMessage(
                    role = "user",
                    text = userText,
                    images = state.attachments.filter { it.isImage }.mapNotNull { it.dataUrl },
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
                            violations = RuleValidator.validate(reply),
                            mapCard = mapCard,
                            ts = System.currentTimeMillis(),
                        ),
                    )
                    _stream.value = null
                    _ui.update { it.copy(sending = false, question = "", attachments = emptyList(), error = null) }
                },
                onFailure = { e ->
                    _stream.value = null
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
     * AI 输出的地图意图 → 地图卡：
     * route=解析起点（AI 给了起点名就按名解析，否则用当前位置）和终点坐标，规划步行路线；
     * place=按关键词搜附近 POI。地名解析都带上当前城市，避免"市图书馆"解析到外地。
     * 失败静默返回 null，不影响回答本身。
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
                val here = LocationContext.current(context)
                val city = here?.let { amap.regeocodeCity(it.lon, it.lat) }
                if (intent.type == "route" && intent.destination.isNotBlank()) {
                    val from = if (intent.origin.isNotBlank()) {
                        amap.geocode(intent.origin, city)
                    } else {
                        here?.let { it.lon to it.lat }
                    } ?: return@withContext null
                    // POI 优先解析（坐标更贴近实际入口，名称取高德实际命中的），拿不到再地理编码
                    val to = runCatching {
                        AmapClient.resolveDestination(amap, intent.destination, from.first, from.second)
                    }.getOrNull()
                        ?: amap.geocode(intent.destination, city)?.let {
                            AmapClient.ResolvedDest(it.first, it.second, AmapClient.cleanPlaceName(intent.destination))
                        }
                        ?: return@withContext null
                    val plan = amap.routePlans(RouteMode.WALK, from.first, from.second, to.lon, to.lat)
                        .firstOrNull() ?: return@withContext null
                    MapCardData(
                        kind = "route",
                        title = to.name,
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
        /** 天气类问题（强制注入专业气象数据） */
        val WEATHER_INTENT = Regex("天气|下雨|下雪|降雪|降雨|天晴|放晴|转晴|晴不晴|气温|温度|几度|冷不冷|热不热|穿什么|带伞|雨伞|刮风|风力|风速|湿度|降水")
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
            // 微信/网盘分享来的 HEIC 常常既没有扩展名、MIME 还是 octet-stream，只按名字判断会掉进文本分支
            val sniffed = sniffImageFormat(resolver, uri)
            val looksImage = sniffed != null ||
                mime.startsWith("image/") ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp").any { lowerName.endsWith(it) }
            return if (looksImage) {
                val dataUrl = imageToDataUrl(resolver, uri)
                if (dataUrl == null) {
                    val heifLike = sniffed == "heif" || mime.contains("heic") || mime.contains("heif") ||
                        lowerName.endsWith(".heic") || lowerName.endsWith(".heif")
                    Pair(
                        PendingAttachment(uri = uri, name = name, isImage = true),
                        if (heifLike) "这是 HEIC/HEIF 格式图片，当前手机系统解不出来（安卓 9 以下没有解码器），建议先用相册把它转成 JPG 再发"
                        else "这张图片读取失败（可能是不支持的格式），发送后 AI 可能看不到内容，建议换一张试试",
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

        private fun imageToDataUrl(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
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
            val b64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }.getOrNull()
    }
}
