package com.lvyoubao.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lvyoubao.app.data.AutoUpdater
import com.lvyoubao.app.data.CallTimer
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.NoteStore
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.SettingsRepository
import com.lvyoubao.app.data.TrackStore
import com.lvyoubao.app.data.observation.ObservationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TravelApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: SessionStore by lazy { SessionStore(this, settingsRepository) }
    val memoryStore: MemoryStore by lazy { MemoryStore(this) }
    val observationStore: ObservationStore by lazy { ObservationStore(this) }
    val trackStore: TrackStore by lazy { TrackStore(this) }
    val noteStore: NoteStore by lazy { NoteStore(this) }

    override fun onCreate() {
        super.onCreate()
        // 运行日志：朗读链路等关键事件落本机文件，设置页可一键导出分享（排查「为什么不播」）
        LogStore.init(filesDir)
        // 校时 offset 从磁盘恢复：必须同步，异步校时那 1~5 秒里机器人会报出厂日期
        com.lvyoubao.app.data.TimeSync.init(this)
        // 时间口径（自动校时/报时区制/间隔）同步读一次：首句问时间就要按设置走，
        // 不能等 Flow 异步到达——否则启动后第一句会用默认口径答。
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.lvyoubao.app.data.TimeSync.applySettings(settingsRepository.settings.first())
            }
        }
        // 通话 FC 的共享倒计时到点要开口说话，先喂 applicationContext
        CallTimer.attach(this)
        val ver = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName}(${pi.longVersionCode})"
        }.getOrNull() ?: "未知版本"
        LogStore.log("app", "应用启动 $ver 系统=Android${android.os.Build.VERSION.RELEASE} 设备=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        // 自动更新流水线：启动即查 + 每 30 分钟静默复查，发现新版自动后台下载，下好弹「安装」
        AutoUpdater.start(this)
        // 网络时间校准：开机先用磁盘里上次的 offset 报时，后台再对表更新（关掉自动校时则跳过）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.lvyoubao.app.data.TimeSync.syncIfAuto() }
        }
        // 回前台补一次校时：进程被后台杀掉后重启，或进程没死但 Activity 重建时
        // onCreate 不会再跑，只在启动时校时会让偏移一直用旧值。节流 1 分钟不打扰用户。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { com.lvyoubao.app.data.TimeSync.syncOnForeground() }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
