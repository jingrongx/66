package com.lvyoubao.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lvyoubao.app.data.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * 开机完成广播（设置 → 桌面与后台 → 「开机自启」开关控制）：
 * - 「后台常驻」开着：拉起前台服务（不受安卓 10+ 后台启 Activity 限制）；
 * - 「开机自启」开着：尝试直接拉起主界面（部分 ROM 限制后台启 Activity，会静默失败——
 *   若本应用已设为默认桌面，系统开机本来就会拉起，天然兜底）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val settings = runBlocking {
            runCatching { SettingsRepository(appContext).settings.firstOrNull() }.getOrNull()
        } ?: return

        if (settings.keepAliveEnabled || settings.bootStartEnabled) {
            KeepAliveService.start(appContext)
        }
        if (settings.bootStartEnabled) {
            runCatching {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
