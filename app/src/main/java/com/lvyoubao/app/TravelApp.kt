package com.lvyoubao.app

import android.app.Application

class TravelApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
