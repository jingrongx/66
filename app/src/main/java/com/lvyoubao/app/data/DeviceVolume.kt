package com.lvyoubao.app.data

import android.content.Context
import android.media.AudioManager

/**
 * 音量控制（语音「大点声/小点声/静音」等指令的执行器）：
 * 直接调系统 STREAM_MUSIC 流（小源朗读的 AudioTrack/系统 TTS 都走这条流，物理音量键也是它），
 * 手动按键与语音控制天然同步，无需自存设置。
 * 步长 15%：一次口令有明显感知，静音到最大约 7 次。
 */
object DeviceVolume {

    enum class Action { UP, DOWN, MUTE, MAX, RESTORE, SET }

    private const val STEP_PERCENT = 15

    /** 执行并返回话术。静音返回「已静音」——调用方不要再朗读（自相矛盾），用状态条显示 */
    fun apply(context: Context, action: Action, percent: Int = 60): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "这台设备调不了音量"
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return "这台设备调不了音量"
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val target = when (action) {
            Action.UP -> if (cur <= 0) 25 else (cur + STEP_PERCENT).coerceAtMost(100)
            Action.DOWN -> (cur - STEP_PERCENT).coerceAtLeast(0)
            Action.MUTE -> 0
            Action.MAX -> 100
            Action.RESTORE -> 60
            Action.SET -> percent.coerceIn(0, 100)
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target * max / 100, 0) }
        return when {
            target == 0 -> "已静音"
            action == Action.MAX -> "音量调到最大了"
            action == Action.RESTORE -> "音量恢复到百分之$target"
            cur == 0 -> "音量调到了百分之$target"
            else -> "音量调到百分之$target"
        }
    }
}
