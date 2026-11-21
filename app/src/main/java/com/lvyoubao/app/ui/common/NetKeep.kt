package com.lvyoubao.app.ui.common

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.lvyoubao.app.data.LogStore

/**
 * 长连接场景（全双工通话）的网络 + CPU 保活。
 *
 * 为什么需要：这台设备的固件息屏后会把 WiFi 打进省电态（实测息屏几十秒后设备就 ping 不通），
 * 豆包实时语音是长 WebSocket，底层网络一停，表现就是「黑屏之后听不到声音」。
 * 光靠「别熄屏」不够——通话界面收起、用户手动按电源键，都可能让屏幕黑掉，
 * 所以这里再压一层 WIFI_MODE_FULL_HIGH_PERF + PARTIAL_WAKE_LOCK，
 * 即使屏幕灭了，音频流和 CPU 也还活着。
 *
 * 只在通话期间持有（分钟级），结束后立刻释放，不占整夜电量。
 */
object NetKeep {

    private const val TAG = "netkeep"

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var holds = 0

    /** 引用计数获取；重复调用不会重复加锁 */
    fun acquire(context: Context, reason: String) {
        holds++
        if (holds > 1) return
        val app = context.applicationContext
        runCatching {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lvyoubao:$reason")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            wifiLock = lock
        }.onFailure { LogStore.log(TAG, "WiFi 高性能锁获取失败：${it.message}") }
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lvyoubao:$reason")
            wl?.setReferenceCounted(false)
            wl?.acquire()
            wakeLock = wl
        }.onFailure { LogStore.log(TAG, "PARTIAL_WAKE_LOCK 获取失败：${it.message}") }
        LogStore.log(TAG, "长连接保活开启：$reason（WiFi 高性能 + CPU 唤醒锁）")
    }

    /** 释放；计数归零才真正解锁 */
    fun release(reason: String) {
        if (holds > 0) holds--
        if (holds > 0) return
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        val hadWifi = wifiLock != null
        wifiLock = null
        wakeLock = null
        if (hadWifi) LogStore.log(TAG, "长连接保活关闭：$reason")
    }
}
