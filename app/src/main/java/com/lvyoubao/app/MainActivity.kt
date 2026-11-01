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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.ui.ask.AskScreen
import com.lvyoubao.app.ui.memory.MemoryScreen
import com.lvyoubao.app.ui.observation.ObservationScreen
import com.lvyoubao.app.ui.observation.ObservationViewModel
import com.lvyoubao.app.ui.sessions.SessionListScreen
import com.lvyoubao.app.ui.settings.SettingsScreen
import com.lvyoubao.app.ui.theme.LvyoubaoTheme
import com.lvyoubao.app.ui.update.UpdateScreen
import com.lvyoubao.app.ui.update.UpdateViewModel

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
    var showUpdate by remember { mutableStateOf(false) }
    var showObservation by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }

    val updateVm: UpdateViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { UpdateViewModel(app) } } },
    )
    val updateState by updateVm.ui.collectAsState()

    // 启动静默检查更新：发现新版本才在主页出提示，失败不打扰
    LaunchedEffect(Unit) { updateVm.autoCheck() }

    AnimatedContent(
        targetState = showSettings to (showUpdate to (showObservation to (showSessions to showMemory))),
        transitionSpec = { fadeIn().togetherWith(fadeOut()) },
        label = "root",
    ) { (settings, updateStack) ->
        val (update, obsStack) = updateStack
        val (observation, sessionStack) = obsStack
        val (sessions, memory) = sessionStack
        when {
            update -> UpdateScreen(onClose = { showUpdate = false }, vm = updateVm)
            observation -> ObservationScreen(onClose = { showObservation = false }, app = app)
            memory -> MemoryScreen(onClose = { showMemory = false }, app = app)
            sessions -> SessionListScreen(
                onClose = { showSessions = false },
                onOpenMemory = { showMemory = true },
                app = app,
            )
            settings -> SettingsScreen(
                onClose = { showSettings = false },
                onOpenUpdate = { showUpdate = true },
                app = app,
            )
            else -> AskScreen(
                onOpenSettings = { showSettings = true },
                onOpenSessions = { showSessions = true },
                app = app,
                updateAvailable = updateState.release != null,
                onUpdateClick = { showUpdate = true },
                onOpenObservation = { showObservation = true },
            )
        }
    }
}
