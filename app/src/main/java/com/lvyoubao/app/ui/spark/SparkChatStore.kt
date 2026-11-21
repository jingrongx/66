package com.lvyoubao.app.ui.spark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.OutMessage
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 灵动模式会话仓库（进程内单例）：
 * 低内存设备上系统重建 Activity/ViewModel 时会话不丢（曾出现按住说话发出去、
 * 界面重建后「聊天」页变空的情况——消息原来挂在 ViewModel 上，一重建就归零）。
 * 进程被杀则随进程消失（灵动模式会话是轻量即时的，不做磁盘持久化）。
 */
object SparkChatStore {

    val messages = MutableStateFlow<List<SparkMessage>>(emptyList())

    // 供 LLM 的完整对话历史（OutMessage），与界面消息分开维护
    private val history = mutableListOf<OutMessage>()

    // 全双工一轮的幂等标记：同一轮被重复下发时只落一次（防「两条重复卡片」）
    private var lastTurnSig: String = ""
    private var lastTurnAt: Long = 0L
    private var lastAnswerText: String = ""
    private var lastAnswerAt: Long = 0L
    private var lastCommittedAsr: String = ""
    private var lastCommittedAt: Long = 0L


    // ---------- 与 SessionStore 打通（三种模式共用同一份会话数据）----------
    // 原则：三种模式只是界面不同，数据必须一致。灵动的历史以前只活在内存里，
    // 换模式看不到、进程一死就没了；现在写穿到 SessionStore 的「当前会话」。
    private var store: SessionStore? = null
    private var sessionId: String? = null
    private var scope: CoroutineScope? = null
    private var appCtx: Context? = null
    /** 当前这条 assistant 是否已经落库（流式只落一次，之后都是就地更新） */
    private var assistantPersisted = false

    /** 绑定到当前会话：把磁盘上的历史读进来，同时把 LLM 上下文也补齐 */
    fun bind(context: Context, sessionStore: SessionStore, sid: String, coroutineScope: CoroutineScope) {
        appCtx = context.applicationContext
        store = sessionStore
        sessionId = sid
        scope = coroutineScope
        val loaded = sessionStore.get(sid)?.messages.orEmpty()
        messages.value = loaded.map { it.toSpark() }
        history.clear()
        loaded.forEach { history += OutMessage(role = it.role, text = it.toChatText()) }
        assistantPersisted = loaded.lastOrNull()?.let { it.role == "assistant" && it.text.isNotBlank() } == true
        LogStore.log("spark", "会话已打通：载入 " + loaded.size + " 条历史（session=" + sid.take(8) + "）")
    }

    /** 会话消息 → 灵动界面消息（图片按 1/2 降采样解码，别把内存吃穿） */
    private fun SessionMessage.toSpark(): SparkMessage {
        val bitmaps = imagePaths.mapNotNull { path ->
            runCatching {
                val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(path, opt)
            }.getOrNull()
        }
        return SparkMessage(role = role, text = text, photos = bitmaps)
    }

    /** 照片落盘（和普通模式共用 filesDir/sent_images，切模式互相看得到） */
    private fun savePhotos(photos: List<Bitmap>): List<String> {
        val ctx = appCtx ?: return emptyList()
        if (photos.isEmpty()) return emptyList()
        val dir = File(ctx.filesDir, "sent_images").apply { runCatching { mkdirs() } }
        return photos.mapNotNull { bmp ->
            runCatching {
                val f = File(dir, "spark_" + System.currentTimeMillis() + "_" + (0..999).random() + ".jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                f.absolutePath
            }.getOrNull()
        }
    }

    private fun persistAppend(msg: SparkMessage) {
        val sid = sessionId ?: return
        val st = store ?: return
        val sc = scope ?: return
        // assistant 的空占位不落库：请求失败/被打断时不能给历史留一张空卡
        if (msg.role == "assistant" && msg.text.isBlank()) return
        val paths = savePhotos(msg.photos)
        assistantPersisted = msg.role == "assistant"
        sc.launch {
            st.appendMessage(
                sid,
                SessionMessage(
                    role = msg.role,
                    text = msg.text,
                    hadImage = paths.isNotEmpty(),
                    imagePaths = paths,
                    ts = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 流式正文就地更新到最后一条 assistant（没落过库就先落一条） */
    private fun persistAssistantText(text: String) {
        if (text.isBlank()) return
        val sid = sessionId ?: return
        val st = store ?: return
        val sc = scope ?: return
        if (assistantPersisted) {
            sc.launch { st.updateLastAssistant(sid, text) }
        } else {
            assistantPersisted = true
            sc.launch {
                st.appendMessage(
                    sid,
                    SessionMessage(role = "assistant", text = text, ts = System.currentTimeMillis()),
                )
            }
        }
    }

    fun append(msg: SparkMessage) {
        messages.value = (messages.value + msg).takeLast(40) // 上限防照片占内存
        persistAppend(msg)
    }

    fun updateLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == "assistant") {
            list[list.lastIndex] = list.last().copy(text = text)
            messages.value = list
        }
        persistAssistantText(text)
    }

    /**
     * 清掉历史里空白的 assistant 占位。请求先 append 一条空卡片再流式填字，
     * 若这次请求被新问题顶掉 / 超时没收尾，那条空卡片就永远停在「…」上——
     * 用户看到一串点点，会以为整个界面卡死了。
     */
    fun clearStalePlaceholders() {
        val list = messages.value
        if (list.none { it.role == "assistant" && it.text.isBlank() }) return
        messages.value = list.filterNot { it.role == "assistant" && it.text.isBlank() }
    }

    /** 去掉空白再做比较：豆包下发常带尾随空格/换行，直接 == 去不了重 */
    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    /**
     * 全双工通话的一轮落地（用户识别 + 小源回答）：
     * 通话界面自己没有会话流，挂断后到「聊天」里能回看，LLM 下一轮也带得上。
     *
     * 去重口径（同一个问题出现过两种下发，都会造成两张一样的卡）：
     * 1) 同一轮被整体重发（ASR 二次 final、TextDone 重发）→ 按 (识别|回答) 指纹幂等吞掉；
     * 2) 本机能力（handleAssistantIntent）自己已 appendMessage 过确认话术 → 按文本去重；
     * 3) 回答分段下发（两段各自 done）→ 补全上一张卡，而不是再叠一张。
     */
    @Synchronized
    fun appendDuplexTurn(asr: String, answer: String) {
        if (asr.isBlank() && answer.isBlank()) return
        val now = System.currentTimeMillis()
        val sig = norm(asr) + "\u0001" + norm(answer)
        // 一次性下发会在一两秒内重复到达，6 秒足够覆盖，又不会误吞「真的又问了一遍」
        if (sig == lastTurnSig && now - lastTurnAt < 6_000L) return
        lastTurnSig = sig
        lastTurnAt = now

        if (asr.isNotBlank()) {
            val na = norm(asr)
            val dup = messages.value.takeLast(3).any { it.role == "user" && norm(it.text) == na }
            if (!dup) {
                append(SparkMessage(role = "user", text = asr))
                appendHistory(OutMessage(role = "user", text = asr))
            }
        }
        if (answer.isNotBlank()) {
            // 同一次说话又下发了另一条回答（模型先垫一句再接正文、或回答被拆成两段）：
            // 合成同一张卡，绝不并排出现两张。
            val sameUtterance = asr.isNotBlank() &&
                lastCommittedAsr.isNotEmpty() &&
                lastCommittedAsr == norm(asr) &&
                now - lastCommittedAt < 20_000L
            if (sameUtterance) {
                val merged = mergeText(lastAnswerText, answer)
                lastAnswerText = merged
                lastAnswerAt = now
                replaceLastAssistant(merged)
            } else {
                appendAnswer(answer, now)
            }
            if (asr.isNotBlank()) {
                lastCommittedAsr = norm(asr)
                lastCommittedAt = now
            }
        }
    }

    /** 两段回答合成一张卡：后者是前者的延续就取后者，否则换行接上 */
    private fun mergeText(prev: String, next: String): String {
        val p = norm(prev)
        val n = norm(next)
        return when {
            p.isEmpty() -> next
            n.startsWith(p) -> next
            p.startsWith(n) -> prev
            else -> prev.trimEnd() + "\n" + next.trimStart()
        }
    }

    private fun appendAnswer(answer: String, now: Long) {
        val na = norm(answer)
        // 只跟「本轮刚落的回答」合并，避免把上一轮的回答误补全
        val recent = now - lastAnswerAt < 8_000L
        val prev = if (recent) norm(lastAnswerText) else ""
        when {
            recent && prev.isNotEmpty() && prev == na -> Unit            // 完全重复：只留一张卡
            recent && prev.isNotEmpty() && prev.startsWith(na) -> Unit   // 新的是旧的前缀：丢弃
            recent && prev.isNotEmpty() && na.startsWith(prev) -> {      // 分段下发：就地补全
                lastAnswerText = answer
                lastAnswerAt = now
                replaceLastAssistant(answer)
            }
            else -> {
                lastAnswerText = answer
                lastAnswerAt = now
                append(SparkMessage(role = "assistant", text = answer))
                appendHistory(OutMessage(role = "assistant", text = answer))
            }
        }
    }

    private fun replaceLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx < 0) return
        list[idx] = list[idx].copy(text = text)
        messages.value = list
        persistAssistantText(text)
    }

    @Synchronized
    fun appendHistory(msg: OutMessage) {
        history.add(msg)
    }

    /** 从尾部往前按字符预算挑历史（48,000 字符），头两条必带 */
    @Synchronized
    fun historyBudget(budget: Int): List<OutMessage> {
        if (history.isEmpty()) return emptyList()
        var used = 0
        var count = 0
        for (msg in history.asReversed()) {
            val cost = msg.text.length + 100 + msg.images.size * 2000
            if (count >= 2 && used + cost > budget) break
            used += cost
            count++
        }
        return history.takeLast(count)
    }
}
