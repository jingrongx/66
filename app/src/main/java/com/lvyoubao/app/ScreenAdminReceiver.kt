package com.lvyoubao.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.ComponentName

/**
 * 设备管理器：只为「彻底熄屏」要一条 force-lock 权限。
 *
 * 为什么普通应用熄不了屏：这台机器人的固件改过 PowerManagerService——
 * 到点不执行睡眠，而是每隔 30 秒发一次 `com.android.server.ACTION_TRIGGER_IDLE` 广播，
 * 把「要不要睡」交给应用决定（实测应用被 force-stop 后屏幕照样常亮）。
 * 而 `PowerManager.goToSleep()` 要 DEVICE_POWER（系统签名权限），普通应用拿不到；
 * `DevicePolicyManager.lockNow()` 是唯一不需要系统签名的正规路子。
 *
 * 只申请 force-lock 一项，不涉及擦除数据、密码策略、相机禁用等任何其它能力。
 */
class ScreenAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, ScreenAdminReceiver::class.java)
    }
}
