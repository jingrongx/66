package com.lvyoubao.app.data

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.lvyoubao.app.data.duplex.DuplexAudioEngine
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlin.math.roundToInt

/**
 * 音量控制（语音「大点声/小点声/静音」等指令的执行器）：
 * 平时调系统 STREAM_MUSIC（小源朗读 AudioTrack/系统 TTS/物理音量键同一流），
 * 全双工通话期间自动切换调系统 STREAM_VOICE_CALL，使语音指令与通话音量实时同步。
 * 步长 15%：一次口令有明显感知，静音到最大约 7 次。
 */
object DeviceVolume {

    enum class Action { UP, DOWN, MUTE, MAX, RESTORE, SET }

    private const val STEP_PERCENT = 15

    /** 根据当前音量与动作计算目标档位（0..100 百分比 -> 实际设备 [minVol..maxVol] 刻度） */
    internal fun computeTargetStep(
        action: Action,
        cur: Int,
        minVol: Int,
        maxVol: Int,
        percent: Int = 60,
    ): Pair<Int, Int> {
        val curPct = if (maxVol > 0) (cur * 100f / maxVol).roundToInt().coerceIn(0, 100) else 0
        val targetPct = when (action) {
            Action.UP -> if (curPct <= 0) 25 else (curPct + STEP_PERCENT).coerceAtMost(100)
            Action.DOWN -> (curPct - STEP_PERCENT).coerceAtLeast(0)
            Action.MUTE -> 0
            Action.MAX -> 100
            Action.RESTORE -> 60
            Action.SET -> percent.coerceIn(0, 100)
        }
        val step = (targetPct * maxVol / 100f).roundToInt().coerceIn(minVol, maxVol)
        return step to targetPct
    }

    /** 执行并返回话术。静音返回「已静音」——调用方不要再朗读（自相矛盾），用状态条显示 */
    fun apply(context: Context, action: Action, percent: Int = 60): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "这台设备调不了音量"
        val inVoiceCall = DuplexAudioEngine.isCallAudioActive.value ||
            TtsPlayer.useVoiceCallStream.value ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION
        val stream = if (inVoiceCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return "这台设备调不了音量"
        val minVol = if (stream == AudioManager.STREAM_VOICE_CALL) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { am.getStreamMinVolume(stream) }.getOrDefault(1)
            } else {
                1
            }
        } else {
            0
        }
        val cur = am.getStreamVolume(stream)
        val (targetStep, targetPct) = computeTargetStep(action, cur, minVol, max, percent)
        runCatching { am.setStreamVolume(stream, targetStep, 0) }
        val actualPct = (targetStep * 100f / max).roundToInt().coerceIn(0, 100)
        return when {
            targetStep == 0 -> "已静音"
            action == Action.MUTE && minVol > 0 -> "通话音量已调到最小"
            action == Action.MAX -> "音量调到最大了"
            action == Action.RESTORE -> "音量恢复到百分之$actualPct"
            cur == 0 -> "音量调到了百分之$actualPct"
            else -> "音量调到百分之${if (targetPct == 0 && minVol > 0) actualPct else targetPct}"
        }
    }
}
