package com.lvyoubao.app

import android.app.Application
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
        // 通话 FC 的共享倒计时到点要开口说话，先喂 applicationContext
        CallTimer.attach(this)
        val ver = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName}(${pi.longVersionCode})"
        }.getOrNull() ?: "未知版本"
        LogStore.log("app", "应用启动 $ver 系统=Android${android.os.Build.VERSION.RELEASE} 设备=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        // 自动更新流水线：启动即查 + 每 30 分钟静默复查，发现新版自动后台下载，下好弹「安装」
        AutoUpdater.start(this)
        // 网络时间校准：老固件断电后系统钟会漂，问「现在几点」必须先对表
        // （偏差大有 root 直接修系统钟；没 root 就记偏移，报时间时补偿）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.lvyoubao.app.data.TimeSync.sync() }
        }
    }
}
