package com.lvyoubao.app

import android.app.Application
import com.lvyoubao.app.data.AutoUpdater
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
        // 自动更新流水线：启动即查 + 每 30 分钟静默复查，发现新版自动后台下载，下好弹「安装」
        AutoUpdater.start(this)
    }
}
