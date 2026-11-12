package com.lvyoubao.app

import android.app.Application
import com.lvyoubao.app.data.AutoUpdater
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.NoteStore
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.SettingsRepository
import com.lvyoubao.app.data.TrackStore
import com.lvyoubao.app.data.observation.ObservationStore

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
        val ver = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName}(${pi.longVersionCode})"
        }.getOrNull() ?: "未知版本"
        LogStore.log("app", "应用启动 $ver 系统=Android${android.os.Build.VERSION.RELEASE} 设备=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        // 自动更新流水线：启动即查 + 每 30 分钟静默复查，发现新版自动后台下载，下好弹「安装」
        AutoUpdater.start(this)
    }
}
