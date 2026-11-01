package com.lvyoubao.app

import android.app.Application
import com.lvyoubao.app.data.MemoryStore
import com.lvyoubao.app.data.SessionStore
import com.lvyoubao.app.data.SettingsRepository
import com.lvyoubao.app.data.observation.ObservationStore

class TravelApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: SessionStore by lazy { SessionStore(this, settingsRepository) }
    val memoryStore: MemoryStore by lazy { MemoryStore(this) }
    val observationStore: ObservationStore by lazy { ObservationStore(this) }
}
