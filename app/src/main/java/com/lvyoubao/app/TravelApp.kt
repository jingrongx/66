package com.lvyoubao.app

import android.app.Application
import com.lvyoubao.app.data.SettingsRepository
import com.lvyoubao.app.data.observation.ObservationStore

class TravelApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val observationStore: ObservationStore by lazy { ObservationStore(this) }
}
