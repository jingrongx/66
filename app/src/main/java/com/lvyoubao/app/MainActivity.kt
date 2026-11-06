package com.lvyoubao.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.unit.Density
import com.lvyoubao.app.data.AiSettings
import com.lvyoubao.app.ui.ask.AskScreen
import com.lvyoubao.app.ui.largetext.LargeTextScreen
import com.lvyoubao.app.ui.map.MapScreen
import com.lvyoubao.app.ui.memory.MemoryScreen
import com.lvyoubao.app.ui.notes.NotesScreen
import com.lvyoubao.app.ui.observation.ObservationScreen
import com.lvyoubao.app.ui.observation.ObservationViewModel
import com.lvyoubao.app.ui.settings.SettingsScreen
import com.lvyoubao.app.ui.theme.LvyoubaoTheme
import com.lvyoubao.app.ui.update.UpdateScreen
import com.lvyoubao.app.ui.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = LocalContext.current.applicationContext as TravelApp
            val appSettings by app.settingsRepository.settings
                .collectAsState(initial = AiSettings())
            // 把语音识别 Key 同时作为云端朗读兜底的 Key（同一硅基流动账号）：
            // Edge 朗读中途失败时换云端接力，剩余段落不再陪葬
            LaunchedEffect(appSettings.asrKey) {
                com.lvyoubao.app.ui.common.TtsPlayer.setCloudKey(appSettings.asrKey)
            }
            val density = LocalDensity.current
            // 锁定系统字体缩放（关怀模式/大字体把 fontScale 放大后，固定字号的设计布局会被二次
            // 放大挤爆：按钮文字换行、标题溢出、底栏挤成一团）。放大字号由应用自己的「大字模式」
            // 负责并按设计排版；dp 尺寸仍随系统"显示大小"整体等比缩放，无障碍不受影响。
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1f),
            ) {
                LvyoubaoTheme(themeMode = appSettings.themeMode) {
                    // Surface 统一提供主题背景色和内容色（LocalContentColor）：
                    // 否则未显式指定颜色的标题/图标会用默认黑色，深色主题下不可读
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (appSettings.largeTextMode) {
                            // 大字模式是启动默认模式：设置里开着就直接进大字模式，关了就是普通模式；
                            // 退出（按钮/返回键）= 退出应用，不再从这里切回普通模式
                            LargeTextScreen(app = app, onExitApp = { finishAffinity() })
                        } else {
                            AppRoot(app)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(app: TravelApp) {
    var showSettings by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var showObservation by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }

    val updateVm: UpdateViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { UpdateViewModel(app) } } },
    )
    val updateState by updateVm.ui.collectAsState()

    // 启动静默检查更新：发现新版本才在主页出提示，失败不打扰
    LaunchedEffect(Unit) { updateVm.autoCheck() }

    AnimatedContent(
        targetState = showSettings to (showUpdate to (showObservation to (showMemory to (showMap to showNotes)))),
        transitionSpec = { fadeIn().togetherWith(fadeOut()) },
        label = "root",
    ) { (settings, updateStack) ->
        val (update, obsStack) = updateStack
        val (observation, memStack) = obsStack
        val (memory, mapStack) = memStack
        val (map, notes) = mapStack
        when {
            update -> UpdateScreen(onClose = { showUpdate = false }, vm = updateVm)
            observation -> ObservationScreen(onClose = { showObservation = false }, app = app)
            memory -> MemoryScreen(onClose = { showMemory = false }, app = app)
            map -> MapScreen(onClose = { showMap = false }, app = app)
            notes -> NotesScreen(onClose = { showNotes = false }, app = app)
            settings -> SettingsScreen(
                onClose = { showSettings = false },
                onOpenUpdate = { showUpdate = true },
                app = app,
            )
            else -> AskScreen(
                onOpenSettings = { showSettings = true },
                onOpenMemory = { showMemory = true },
                app = app,
                updateAvailable = updateState.release != null,
                onUpdateClick = { showUpdate = true },
                onOpenObservation = { showObservation = true },
                onOpenMap = { showMap = true },
                onOpenNotes = { showNotes = true },
            )
        }
    }
}
