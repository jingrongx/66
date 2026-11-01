package com.lvyoubao.app

import android.app.Application
import com.lvyoubao.app.data.SettingsRepository

class TravelApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
