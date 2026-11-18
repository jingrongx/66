package com.lvyoubao.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 本机电池状态读取（问电量/主动提醒共用）：
 * 优先 BatteryManager 容量属性，兜底 ACTION_BATTERY_CHANGED 粘性广播（registerReceiver 传 null 只查询不注册）。
 * 都拿不到返回 null——没电池的设备（部分车机/盒子）读不到就如实说读不到，绝不编数字。
 */
object DeviceBattery {

    data class BatteryState(val percent: Int, val charging: Boolean)

    fun read(context: Context): BatteryState? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val percent = when {
                capacity in 1..100 -> capacity
                level >= 0 && scale > 0 -> level * 100 / scale
                else -> -1
            }
            if (percent in 0..100) return BatteryState(percent, charging)
        }
        if (capacity in 0..100) return BatteryState(capacity, false)
        return null
    }
}
