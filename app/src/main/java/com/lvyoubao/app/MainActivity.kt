package com.lvyoubao.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lvyoubao.app.ui.ask.AskScreen
import com.lvyoubao.app.ui.settings.SettingsScreen
import com.lvyoubao.app.ui.theme.LvyoubaoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LvyoubaoTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val app = LocalContext.current.applicationContext as TravelApp
    var showSettings by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showSettings,
        transitionSpec = { fadeIn().togetherWith(fadeOut()) },
        label = "root",
    ) { settingsVisible ->
        if (settingsVisible) {
            SettingsScreen(onClose = { showSettings = false }, app = app)
        } else {
            AskScreen(onOpenSettings = { showSettings = true }, app = app)
        }
    }
}
