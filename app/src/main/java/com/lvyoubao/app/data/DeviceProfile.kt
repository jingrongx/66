package com.lvyoubao.app.data

import android.os.Build

/**
 * 机型特征。同一套 APK 既要跑手机也要跑机器人，屏幕方向的默认值不能一刀切。
 */
object DeviceProfile {

    /** 横屏机器人（优必选 Visbot 这类）：面板本来就是 308×240 的横向屏 */
    val isLandscapeRobot: Boolean by lazy {
        val s = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.BRAND).lowercase()
        s.contains("visbot") || s.contains("ubtech") || s.contains("优必选")
    }

    /** 屏幕方向的出厂默认值：机器人锁横屏，手机跟随系统 */
    val defaultOrientation: String
        get() = if (isLandscapeRobot) "landscape" else "auto"
}
