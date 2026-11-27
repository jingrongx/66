package com.lvyoubao.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.ChatViewModel
import kotlinx.coroutines.delay

/** 仅朗读最近 20 秒内生成的新回答，切换会话或重进页面时不重读历史消息 */
internal const val AUTO_SPEAK_FRESH_WINDOW_MS = 20_000L

/**
 * 判定界面层是否应触发整段兜底朗读（供普通模式与大字模式共用）：
 * 1. 仅朗读助手回复，且距离生成时间在 [AUTO_SPEAK_FRESH_WINDOW_MS] 内；
 * 2. 正在豆包全双工通话，或消息本身标记为全双工通话产出（[SessionMessage.fromDuplexCall]）时严禁再次播报；
 * 3. 界面存在遮挡弹窗（[suppressed]）时不播报；
 * 4. 同一条消息 [SessionMessage.ts] 只允许触发一次。
 */
internal fun shouldAutoSpeakFallback(
    msg: SessionMessage?,
    autoSpeak: Boolean,
    inClassicCall: Boolean,
    inDuplexCall: Boolean,
    suppressed: Boolean,
    lastSpokenTs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (msg == null || msg.role != "assistant") return false
    if (inDuplexCall || msg.fromDuplexCall || suppressed) return false
    if (!inClassicCall && !autoSpeak) return false
    if (msg.ts == lastSpokenTs) return false
    val ageMs = nowMs - msg.ts
    return ageMs in 0L until AUTO_SPEAK_FRESH_WINDOW_MS
}

/**
 * 普通模式与大字模式共用的自动播报协调器：
 * 开关打开或经典通话中，AI 边生成边按句首播；若流式未出声则整段兜底。
 * 豆包全双工通话本身直接原声播报，通话中及挂断切回时一律锁定 [spokenTs] 跳过微软 TTS，避免重复朗读。
 */
@Composable
fun AutoSpeakEffect(
    vm: ChatViewModel,
    lastAssistant: SessionMessage?,
    autoSpeak: Boolean,
    inClassicCall: Boolean,
    inDuplexCall: Boolean,
    suppressed: Boolean = false,
) {
    val context = LocalContext.current
    var spokenTs by remember { mutableLongStateOf(0L) }
    val liveSpeak = !inDuplexCall && (inClassicCall || autoSpeak)
    LaunchedEffect(liveSpeak) { vm.setLiveSpeakEnabled(liveSpeak) }

    LaunchedEffect(
        lastAssistant?.ts,
        lastAssistant?.fromDuplexCall,
        autoSpeak,
        inClassicCall,
        inDuplexCall,
        suppressed,
    ) {
        val msg = lastAssistant ?: return@LaunchedEffect
        // 通话期间或通话产出的回复已由豆包 S2S 直接播完，立即消费掉该条 ts，防止挂断通话后因状态切换再次触发播报
        if (inDuplexCall || msg.fromDuplexCall) {
            spokenTs = msg.ts
            LogStore.log(
                "autospeak",
                "跳过全双工通话回复：ts=${msg.ts} 通话中=$inDuplexCall 来源通话=${msg.fromDuplexCall}",
            )
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        if (!shouldAutoSpeakFallback(msg, autoSpeak, inClassicCall, inDuplexCall, suppressed, spokenTs, now)) {
            LogStore.log(
                "autospeak",
                "跳过本次：开关=$autoSpeak 通话=$inClassicCall 遮挡=$suppressed 已读过=${msg.ts == spokenTs} 年龄=${now - msg.ts}ms",
            )
            return@LaunchedEffect
        }
        spokenTs = msg.ts
        // 等这一轮的播报裁决：PENDING = 流式还在接第一句（4 字首句实测约 1.7 秒出声），
        // 最多等 4.5 秒；已经出声就跳过，只有彻底没出声才整段兜底
        val deadline = System.currentTimeMillis() + 4_500L
        while (System.currentTimeMillis() < deadline && vm.speechState.value == ChatViewModel.Speech.PENDING) {
            delay(120)
        }
        if (vm.speechState.value == ChatViewModel.Speech.STREAMING) {
            LogStore.log("autospeak", "流式播报已接手，跳过整段朗读")
            return@LaunchedEffect
        }
        val text = ChatViewModel.speakableText(msg)
        LogStore.log(
            "autospeak",
            "整段兜底播报：回答 ts=${msg.ts} 长度=${text.length} 字",
        )
        TtsPlayer.speak(context, text)
    }
}
