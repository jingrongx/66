package com.lvyoubao.app.ui.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.KeepAliveService
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.FullDiagnostics
import com.lvyoubao.app.data.LogStore
import com.lvyoubao.app.data.RobotClient
import com.lvyoubao.app.data.SavedAddress
import com.lvyoubao.app.ui.SettingsViewModel
import com.lvyoubao.app.ui.sync.QrCodeImage
import com.lvyoubao.app.ui.sync.QrScanScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页——严格按 .workbuddy/tmp/settings-design.html 的 10 个区块重排：
 * ① 模式与系统 ② 扫码同步 ③ 8 家凭证中心 ④ 核心能力与模型调度（文本 + 语音两块并存）
 * ⑤ 常用地址 ⑥ 桌面与后台 ⑦ 诊断与日志 ⑧ 机器人控制 ⑨ 数据管理与飞轮 ⑩ 关于
 *
 * 配色/间距/字号/控件形状逐项对齐设计稿；日间模式用同一套版式的浅色令牌。
 * 设计稿顶部与底部的「保存全部」按钮按要求不做——改动即自动保存，标题旁只留轻量反馈。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenUpdate: () -> Unit,
    app: TravelApp,
) {
    val vm: SettingsViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { SettingsViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSyncQr by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showVoiceFix by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var styleDialog by remember { mutableStateOf(false) }
    var prefsDialog by remember { mutableStateOf(false) }
    var largeFontDialog by remember { mutableStateOf(false) }
    var sparkFontDialog by remember { mutableStateOf(false) }
    var screenOffDialog by remember { mutableStateOf(false) }
    var screenOrientDialog by remember { mutableStateOf(false) }
    // 授权「修改系统设置」回来后补一次：否则改完超时值要重启才生效
    val writeSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (android.provider.Settings.System.canWrite(context)) {
            com.lvyoubao.app.ui.common.ScreenAwake.applySystemTimeout(
                context,
                state.draft.sparkScreenOffSec,
            )
        }
    }
    // 设备管理器（只为熄屏）：本机固件不执行系统睡眠，普通应用只能靠 lockNow()
    var isScreenAdmin by remember { mutableStateOf(false) }
    val screenAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { isScreenAdmin = isScreenAdminNow(context) }
    LaunchedEffect(Unit) { isScreenAdmin = isScreenAdminNow(context) }

    // 桌面与后台：默认桌面角色
    var isDefaultHome by remember { mutableStateOf(false) }
    val notiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val homeRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { isDefaultHome = isDefaultHomeNow(context) }
    LaunchedEffect(Unit) { isDefaultHome = isDefaultHomeNow(context) }

    BackHandler { onClose() }

    // 纠正记录条数：打开/关闭管理页时刷新
    val fixCount = remember(showVoiceFix) {
        com.lvyoubao.app.data.HotwordCorrector.learnedFixList(app.applicationContext).size
    }

    var dataManageMsg by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                dataManageMsg = "正在导入对话记录…"
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            app.sessionStore.importFromStream(stream)
                        }
                    }.getOrNull() ?: 0
                }
                dataManageMsg = if (count > 0) "成功导入 $count 个会话 ✓" else "未识别到有效的对话记录"
            }
        }
    }
    val exportSessions = {
        scope.launch {
            runCatching {
                dataManageMsg = "正在生成备份 ZIP…"
                val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                val exportFile = File(exportDir, "deepseek_data_$timeTag.zip")
                withContext(Dispatchers.IO) { app.sessionStore.exportToZip(exportFile) }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", exportFile,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, "导出会话记录")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                dataManageMsg = "备份生成完成，请选择保存或分享途径"
            }.onFailure { dataManageMsg = "导出失败：${it.message}" }
        }
    }

    val versionLabel = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "v${info.versionName}($code)"
        }.getOrDefault("v?")
    }

    val dark = when (state.draft.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    ProvideSettingsPalette(dark) {
        val p = LocalSettingsPalette.current

        // 状态栏图标跟着设置页配色走：外观选深色时用浅色图标，选浅色时用深色图标，
        // 否则「系统浅色 + 设置页深色」会出现深底深字看不见
        val view = LocalView.current
        DisposableEffect(dark) {
            val activity = view.context.findActivity()
            val window = activity?.window
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            val prevStatus = controller?.isAppearanceLightStatusBars
            val prevNav = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
            onDispose {
                if (prevStatus != null) controller?.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) controller?.isAppearanceLightNavigationBars = prevNav
            }
        }

        Box(Modifier.fillMaxSize().background(p.page)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {

                // ---------------- 顶部导航（设计稿：返回 + 设置 + 保存状态） ----------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.topBar)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = p.textTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "设置",
                        color = p.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.saveFeedback != SettingsViewModel.SaveFeedback.IDLE) {
                        Text(
                            text = when (state.saveFeedback) {
                                SettingsViewModel.SaveFeedback.SAVING -> "保存中…"
                                else -> "✓ 已保存"
                            },
                            color = if (state.saveFeedback == SettingsViewModel.SaveFeedback.SAVED) p.accent else p.dim,
                            fontSize = 11.sp,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))

                // ---------------- 主滚动区 ----------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ==================== 1. 模式与系统 ====================
                    SettingsSectionTitle("模式与系统", SectionAccent.Purple)
                    SettingsCard {
                        ToggleRowWide(
                            title = "大字模式",
                            subtitle = "超大字体 · 语音优先 · 启动默认进大字界面",
                            checked = state.draft.largeTextMode,
                            onCheckedChange = { checked ->
                                vm.updateDraft {
                                    it.copy(
                                        largeTextMode = checked,
                                        autoSpeak = if (checked) true else it.autoSpeak,
                                        sparkMode = if (checked) false else it.sparkMode,
                                    )
                                }
                                vm.save(onSaved = {})
                            },
                        )
                        PillRow(
                            label = "大字模式字号调节",
                            value = fontScaleLabel(state.draft.largeTextFontScale),
                            onClick = { largeFontDialog = true },
                        )
                        Spacer(Modifier.height(12.dp))
                        CardDivider()
                        Spacer(Modifier.height(12.dp))
                        ToggleRowWide(
                            title = "灵动模式",
                            subtitle = "手表/小屏适配 · 灵动双眼 · 点击唤醒 · 双击识图",
                            checked = state.draft.sparkMode,
                            onCheckedChange = { checked ->
                                vm.updateDraft {
                                    it.copy(
                                        sparkMode = checked,
                                        largeTextMode = if (checked) false else it.largeTextMode,
                                    )
                                }
                                vm.save(onSaved = {})
                            },
                        )
                        PillRow(
                            label = "灵动模式字号调节",
                            value = fontScaleLabel(state.draft.sparkFontScale),
                            onClick = { sparkFontDialog = true },
                        )
                        PillRow(
                            label = "屏幕方向",
                            value = when (state.draft.screenOrientation) {
                                "landscape" -> "锁定横屏"
                                "portrait" -> "锁定竖屏"
                                else -> "跟随系统"
                            },
                            onClick = { screenOrientDialog = true },
                        )
                        PillRow(
                            label = "空闲熄屏时间",
                            value = screenOffLabel(state.draft.sparkScreenOffSec),
                            onClick = { screenOffDialog = true },
                        )
                        // 本机固件改了电源管理：到点不睡眠、广播让应用自己决定。
                        // 普通应用拿不到 DEVICE_POWER，只能借设备管理器的 lockNow() 熄屏。
                        if (state.draft.sparkMode && state.draft.sparkScreenOffSec > 0) {
                            NavRow(
                                title = "熄屏权限",
                                subtitle = if (isScreenAdmin) {
                                    "已授权 · 只用于关闭屏幕，不涉及数据或密码"
                                } else {
                                    "本机固件不允许普通应用熄屏，需激活一次设备管理器"
                                },
                                value = if (isScreenAdmin) "已授权" else "去激活",
                                onClick = {
                                    if (!isScreenAdmin) {
                                        runCatching {
                                            screenAdminLauncher.launch(
                                                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                                    putExtra(
                                                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                                        com.lvyoubao.app.ScreenAdminReceiver.component(context),
                                                    )
                                                    putExtra(
                                                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                                        "只申请「锁定屏幕」一项权限，用于空闲时熄屏省电；不会抹除数据、不会改密码。",
                                                    )
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        CardDivider()
                        NavRow(
                            title = "外观",
                            value = when (state.draft.themeMode) {
                                "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统"
                            },
                            onClick = { themeDialog = true },
                        )
                        CardDivider()
                        NavRow(
                            title = "回答风格",
                            value = when (state.draft.answerStyle) {
                                "medium" -> "中等"; "detailed" -> "详尽"; else -> "简洁"
                            },
                            onClick = { styleDialog = true },
                        )
                        CardDivider()
                        NavRow(
                            title = "语音与提醒偏好",
                            subtitle = "唤醒词 · 插话灵敏度 · 主动提醒",
                            value = "去调整",
                            onClick = { prefsDialog = true },
                        )
                    }

                    // ==================== 2. 扫码同步配置 ====================
                    SettingsSectionTitle(
                        "扫码同步配置 (车机/手机互联)",
                        SectionAccent.Amber,
                        badge = {
                            SettingsBadge(
                                "全量同步",
                                fg = if (dark) Color(0xFFFCD34D) else Color(0xFFB45309),
                                bg = if (dark) Color(0xFF451A03) else Color(0xFFFEF3C7),
                                border = if (dark) Color(0xFF92400E) else Color(0xFFFCD34D),
                            )
                        },
                    )
                    SettingsCard {
                        Text(
                            buildAnnotatedString {
                                append("在手机上把设置配好，一键把")
                                withStyle(SpanStyle(color = p.textSecondary, fontWeight = FontWeight.Medium)) {
                                    append("全部配置")
                                }
                                append("整体推送到车机/另一台设备，接收方无需逐项重填。车机点「生成同步二维码」，手机点「扫码推送」扫它即写入（需同一 Wi-Fi 或热点）。")
                            },
                            color = p.muted,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "覆盖：模式与系统（大字/灵动开关·字号·外观·回答风格） · 8 家服务商凭证 · 核心能力与模型调度（LLM / ASR / TTS · 通话引擎） · 常用地址 · 桌面与后台",
                            color = if (dark) Color(0xFF22D3EE).copy(alpha = 0.9f) else p.accent,
                            fontSize = 10.sp,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(
                                text = "生成同步二维码",
                                onClick = {
                                    vm.clearSyncResult()
                                    vm.startSyncServer()
                                    if (vm.ui.value.syncQrContent != null) showSyncQr = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryButton(
                                text = "扫码推送",
                                onClick = { vm.clearSyncResult(); showScanner = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        state.syncPushResult?.let { msg ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                msg,
                                color = if (state.syncPushFailed) p.danger else p.accent,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }

                    // ==================== 3. 服务商与 API 密钥集中管理 ====================
                    SettingsSectionTitle(
                        "服务商与 API 密钥集中管理",
                        SectionAccent.Blue,
                        badge = {
                            SettingsBadge(
                                "纯凭证配置 (共 8 家)",
                                fg = p.dim,
                                bg = if (dark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                            )
                        },
                    )
                    ProviderCredentialsCard(state, vm)

                    // ==================== 4. 核心能力与模型调用方案 ====================
                    SettingsSectionTitle(
                        "核心能力与模型调用方案",
                        SectionAccent.Teal,
                        badge = {
                            SettingsBadge(
                                "模型能力调度",
                                fg = if (dark) Color(0xFF22D3EE) else Color(0xFF0E7490),
                                bg = if (dark) Color(0xFF083344) else Color(0xFFECFEFF),
                                border = if (dark) Color(0xFF155E75) else Color(0xFFA5F3FC),
                            )
                        },
                    )
                    SettingsCard {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextCapabilityCard(state, vm)
                            VoiceConfigCard(state, vm)
                        }
                    }

                    // ==================== 5. 常用地址管理 ====================
                    SettingsSectionTitle("常用地址管理", SectionAccent.Emerald)
                    SettingsCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "说「回XX」自动以该地址为目的地规划导航",
                                color = p.textTertiary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text("支持添加任意个", color = p.dim, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        AddressListEditor(
                            addresses = state.draft.savedAddresses,
                            onChange = { list -> vm.updateDraft { it.copy(savedAddresses = list) } },
                        )
                    }

                    // ==================== 6. 桌面与后台 ====================
                    SettingsSectionTitle("桌面与后台", SectionAccent.Indigo)
                    SettingsCard {
                        ToggleRowWide(
                            title = "后台常驻运行",
                            subtitle = "前台服务保活不被系统回收 · 通知栏常驻",
                            checked = state.draft.keepAliveEnabled,
                            onCheckedChange = { checked ->
                                if (checked) notiPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                vm.updateDraft { it.copy(keepAliveEnabled = checked) }
                                vm.save(onSaved = {})
                                if (checked) KeepAliveService.start(context) else KeepAliveService.stop(context)
                            },
                        )
                        CardDivider()
                        ToggleRowWide(
                            title = "开机自启",
                            subtitle = "开机后自动拉起服务并尝试进入应用",
                            checked = state.draft.bootStartEnabled,
                            onCheckedChange = { checked ->
                                vm.updateDraft { it.copy(bootStartEnabled = checked) }
                                vm.save(onSaved = {})
                            },
                        )
                        CardDivider()
                        NavRow(
                            title = "设为默认桌面（开机即进）",
                            subtitle = if (isDefaultHome) {
                                "已是默认桌面 · 点这里去系统设置改回其他桌面"
                            } else {
                                "开机直接进小源，不用先找应用图标"
                            },
                            value = if (isDefaultHome) "已是默认" else "去设置",
                            // 已经是默认桌面时**不能什么都不做**：用户想换回系统桌面，
                            // 只能从这里进系统设置；老实现 `if (!isDefaultHome)` 把这条路堵死了。
                            onClick = {
                                if (isDefaultHome) {
                                    openHomeSettings(context)
                                } else {
                                    requestDefaultHome(context, homeRoleLauncher)
                                }
                            },
                            valueColor = p.accent,
                        )
                    }

                    // ==================== 7. 诊断与日志 ====================
                    SettingsSectionTitle(
                        "诊断与日志",
                        SectionAccent.Orange,
                        badge = {
                            SettingsBadge(
                                "全链路排查",
                                fg = p.dim,
                                bg = if (dark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                            )
                        },
                    )
                    SettingsCard {
                        Text(
                            "诊断涵盖 API 连通性、按住说话 ASR、TTS 朗读播报、豆包全双工通话、高德地图周边检索及网络权限全维度体检。",
                            color = p.muted,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        var diagRunning by remember { mutableStateOf(false) }
                        var diagReport by remember { mutableStateOf<String?>(null) }
                        SecondaryButton(
                            text = if (diagRunning) "诊断中…（约 10 秒）" else "🔍  全链路综合诊断（一键体检：API / 语音 / 地图 / 通话）",
                            onClick = {
                                if (diagRunning) return@SecondaryButton
                                diagRunning = true
                                diagReport = null
                                scope.launch {
                                    diagReport = FullDiagnostics.run(context, state.draft)
                                    diagRunning = false
                                }
                            },
                            enabled = !diagRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        // 导出日志：cyan-950 底 + cyan-800 描边 + cyan-300 字
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.accentSoft)
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            val f = withContext(Dispatchers.IO) {
                                                LogStore.export(File(context.filesDir, "exports"))
                                            }
                                            val uri = FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", f,
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(intent, "导出运行日志")
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "📤  导出全部运行日志（汇总系统、网络与业务全量日志发开发者）",
                                color = p.accentSoftText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        diagReport?.let { report ->
                            Spacer(Modifier.height(10.dp))
                            Text(report, color = p.textTertiary, fontSize = 11.sp, lineHeight = 18.sp)
                        }
                    }

                    // ==================== 8. 机器人控制 ====================
                    SettingsSectionTitle("机器人控制", SectionAccent.Fuchsia)
                    SettingsCard {
                        ToggleRow(
                            title = "语音指挥机器人",
                            subtitle = "说「前进30厘米」「左转90度」「抬头」「停」「看看前面有什么」，AI 听懂就直接做 · 先支持优必选小方头",
                            checked = state.draft.robotEnabled,
                            onCheckedChange = { checked ->
                                vm.updateDraft { it.copy(robotEnabled = checked) }
                                vm.save(onSaved = {})
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        CardDivider()
                        Spacer(Modifier.height(10.dp))
                        CsFieldLabel("机器人中枢地址（机器人本机可填 127.0.0.1）")
                        Spacer(Modifier.height(6.dp))
                        var robotTesting by remember { mutableStateOf(false) }
                        var robotResult by remember { mutableStateOf<String?>(null) }
                        var robotOk by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SettingsField(
                                value = state.draft.robotHost,
                                onValueChange = { v -> vm.updateDraft { it.copy(robotHost = v.trim()) } },
                                placeholder = "192.168.3.102",
                                mono = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            PrimaryButton(
                                text = if (robotTesting) "检测中" else "检测",
                                onClick = {
                                    robotTesting = true
                                    robotResult = null
                                    scope.launch {
                                        val target = RobotClient.normalizeHost(state.draft.robotHost) ?: "127.0.0.1"
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { RobotClient(target).ping() }
                                        }
                                        val ok = result.isSuccess
                                        robotOk = ok
                                        robotResult = if (ok) "✓ 已连接：${result.getOrNull()}"
                                        else "✗ 连接失败：${result.exceptionOrNull()?.message ?: "连不上"}"
                                        robotTesting = false
                                    }
                                },
                                enabled = !robotTesting,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            robotResult ?: "本机没有检测到中枢（旅游宝和机器人不在同一台设备），请填机器人的局域网 IP",
                            color = if (robotResult != null && robotOk) p.accent else p.danger,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = "",
                            onClick = { vm.updateDraft { it.copy(robotHost = "127.0.0.1") } },
                            content = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                                    Spacer(Modifier.width(6.dp))
                                    Text("本机 127.0.0.1", color = p.textTertiary, fontSize = 11.sp)
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "改动自动保存。打开开关会先自动检测本机有没有中枢：检测到就直接填好 127.0.0.1，检测不到再填机器人的局域网 IP。" +
                                "机器人上需安装 xrobotctl 控制中枢（github.com/jingrongx/xrobotctl）并打开一次启动常驻服务；三台设备需连同一个 Wi-Fi。",
                            color = p.dim,
                            fontSize = 10.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    // ==================== 9. 数据管理与飞轮 ====================
                    SettingsSectionTitle("数据管理与飞轮", SectionAccent.Rose)
                    SettingsCard {
                        NavRow(
                            title = "纠正记录",
                            subtitle = "用户手动修改的语音识别稿飞轮",
                            value = "查看记录",
                            onClick = { showVoiceFix = true },
                        )
                        CardDivider()
                        NavRow(
                            title = "导出对话备份 (ZIP 互通格式)",
                            value = "导出",
                            valueColor = p.accent,
                            onClick = { exportSessions() },
                        )
                        CardDivider()
                        NavRow(
                            title = "导入对话记录 (支持 DeepSeek 格式)",
                            value = "导入",
                            valueColor = p.accent,
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "application/zip", "*/*"))
                            },
                        )
                        dataManageMsg?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(msg, color = p.accent, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }

                    // ==================== 10. 关于（设计稿此处无彩色竖条） ====================
                    SettingsSectionTitle("关于")
                    SettingsCard {
                        NavRow(
                            title = "检查更新",
                            value = versionLabel,
                            onClick = onOpenUpdate,
                        )
                        CardDivider()
                        NavRow(
                            title = "项目主页",
                            value = "GitHub",
                            valueColor = p.accent,
                            chevron = false,
                            arrowUp = true,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/jingrongx/66"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ==================== 弹窗 ====================

        if (showSyncQr) {
            // 车机 / 机器人这类小屏（308×240）用 AlertDialog 会把二维码切掉下半截。
            // Dialog 窗口本身没有高度约束，这里直接用屏幕尺寸（Configuration）算码的边长，
            // 小屏走紧凑布局（标题行带「完成」+ 二维码吃满剩余高度）。
            val cfg = LocalConfiguration.current
            val compact = cfg.screenHeightDp < 360
            val qrSize = if (compact) {
                minOf(cfg.screenHeightDp.dp - 118.dp, cfg.screenWidthDp.dp - 40.dp).coerceAtLeast(88.dp)
            } else {
                minOf(260.dp, cfg.screenHeightDp.dp - 240.dp, cfg.screenWidthDp.dp - 64.dp).coerceAtLeast(160.dp)
            }
            Dialog(
                onDismissRequest = { showSyncQr = false; vm.stopSyncServer() },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .heightIn(max = cfg.screenHeightDp.dp - 20.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(p.card)
                            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (compact) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (state.syncReceived) "已收到配置 ✓" else "等待手机扫码",
                                    color = if (state.syncReceived) p.accent else p.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "完成",
                                    color = p.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSyncQr = false; vm.stopSyncServer() }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Text("等待手机扫码", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                        }

                        state.syncQrContent?.let { content -> QrCodeImage(content, size = qrSize) }

                        if (!compact) {
                            Spacer(Modifier.height(10.dp))
                            if (state.syncReceived) {
                                Text("已收到手机推送的配置并自动保存 ✓", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            } else {
                                Text(
                                    "用手机上安装的本应用，点「扫码推送」扫此码。手机里已填好的配置会直接传到本机并自动保存。",
                                    color = p.dim,
                                    fontSize = 12.sp,
                                )
                            }
                            TextButton(onClick = { showSyncQr = false; vm.stopSyncServer() }) { Text("完成") }
                        }
                    }
                }
            }
        }

        if (showScanner) {
            QrScanScreen(
                onResult = { content ->
                    showScanner = false
                    vm.onQrScanned(content)
                },
                onClose = { showScanner = false },
            )
        }

        if (showVoiceFix) {
            VoiceFixScreen(app = app, onClose = { showVoiceFix = false })
        }

        if (largeFontDialog) {
            FontScaleDialog(
                title = "大字模式字号",
                current = state.draft.largeTextFontScale,
                onPick = { v ->
                    vm.updateDraft { it.copy(largeTextFontScale = v) }
                    vm.save(onSaved = {})
                },
                onDismiss = { largeFontDialog = false },
            )
        }
        if (sparkFontDialog) {
            FontScaleDialog(
                title = "灵动模式字号",
                current = state.draft.sparkFontScale,
                onPick = { v ->
                    vm.updateDraft { it.copy(sparkFontScale = v) }
                    vm.save(onSaved = {})
                },
                onDismiss = { sparkFontDialog = false },
            )
        }

        if (screenOffDialog) {
            ChoiceDialog(
                title = "空闲息屏",
                options = listOf(
                    Triple("0", "常亮", "一直亮屏，最费电（插电使用时可选）"),
                    Triple("15", "15 秒", "待机 15 秒熄屏，最省电"),
                    Triple("30", "30 秒", "待机 30 秒熄屏（推荐）"),
                    Triple("60", "1 分钟", "待机 1 分钟熄屏"),
                    Triple("120", "2 分钟", "待机 2 分钟熄屏"),
                    Triple("300", "5 分钟", "待机 5 分钟熄屏，适合长时间看屏幕"),
                    Triple("600", "10 分钟", "待机 10 分钟熄屏，几乎等于常亮但更省电"),
                ),
                selected = state.draft.sparkScreenOffSec.toString(),
                onPick = { value ->
                    val sec = value.toIntOrNull() ?: 30
                    vm.updateDraft { it.copy(sparkScreenOffSec = sec) }
                    vm.save(onSaved = {})
                    if (sec > 0 && !android.provider.Settings.System.canWrite(context)) {
                        // 没有这个权限时，应用只能撤掉「保持亮屏」，系统若设成「永不熄屏」就不会灭
                        runCatching {
                            writeSettingsLauncher.launch(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    } else if (sec > 0) {
                        com.lvyoubao.app.ui.common.ScreenAwake.applySystemTimeout(context, sec)
                    }
                },
                onDismiss = { screenOffDialog = false },
            )
        }

        if (screenOrientDialog) {
            ChoiceDialog(
                title = "屏幕方向",
                options = listOf(
                    Triple("auto", "跟随系统", "由系统自动旋转决定（手机上一般选这个）"),
                    Triple("landscape", "锁定横屏", "优必选这类横屏机器人建议锁定，避免翻来翻去"),
                    Triple("portrait", "锁定竖屏", "始终竖屏显示"),
                ),
                selected = state.draft.screenOrientation,
                onPick = { value ->
                    vm.updateDraft { it.copy(screenOrientation = value) }
                    vm.save(onSaved = {})
                },
                onDismiss = { screenOrientDialog = false },
            )
        }

        if (themeDialog) {
            ChoiceDialog(
                title = "外观",                options = listOf(
                    Triple("system", "跟随系统", "跟随手机的深色/浅色开关"),
                    Triple("light", "浅色", "日间模式：白卡片 + 浅灰底"),
                    Triple("dark", "深色", "夜间模式：slate 深色卡片"),
                ),
                selected = state.draft.themeMode,
                onPick = { value ->
                    vm.updateDraft { it.copy(themeMode = value) }
                    vm.save(onSaved = {})
                },
                onDismiss = { themeDialog = false },
            )
        }

        if (styleDialog) {
            ChoiceDialog(
                title = "回答风格",
                options = listOf(
                    Triple("concise", "简洁", "平时问答一两句，问「详细」才展开"),
                    Triple("medium", "中等", "攻略类给 3-5 条要点"),
                    Triple("detailed", "详尽", "攻略类 5-8 条要点讲全"),
                ),
                selected = state.draft.answerStyle,
                onPick = { value -> vm.updateDraft { it.copy(answerStyle = value) } },
                onDismiss = { styleDialog = false },
            )
        }

        // 语音与提醒偏好：设计稿未单列的三项，收进一个弹窗，主页面保持与设计稿一致
        if (prefsDialog) {
            AlertDialog(
                onDismissRequest = { prefsDialog = false },
                title = { Text("语音与提醒偏好") },
                text = {
                    // 三项加间距在 320dp 高的屏上正好顶满，稍一放大就被裁掉——统一给上限 + 滚动
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 170.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ToggleRow(
                            title = "待机唤醒词监听",
                            subtitle = "三种模式待机时喊「小源小源」或「嗨小源」直接唤醒（普通/大字走端侧识别，零配额）",
                            checked = state.draft.wakeWordEnabled,
                            onCheckedChange = { checked -> vm.updateDraft { it.copy(wakeWordEnabled = checked) } },
                        )
                        Spacer(Modifier.height(10.dp))
                        CsLabel("唤醒后")
                        Spacer(Modifier.height(6.dp))
                        PillGroup(
                            options = listOf("chat", "call"),
                            selected = state.draft.wakeAction,
                            label = { if (it == "call") "直接通话" else "单次语音" },
                            onSelect = { v -> vm.updateDraft { it.copy(wakeAction = v) } },
                            itemPaddingH = 12,
                            itemPaddingV = 5,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.draft.wakeAction == "call")
                                "唤醒后直接进入豆包全双工通话（免按键自由聊，需已配置豆包语音 Key）"
                            else "唤醒后听一句话，说完自动发出去提问",
                            fontSize = 11.sp,
                            color = p.muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        CsLabel("语音打断灵敏度（连续对话）")
                        Spacer(Modifier.height(6.dp))
                        PillGroup(
                            options = listOf(0, 1, 2),
                            selected = state.draft.voiceBargeIn,
                            label = { when (it) { 0 -> "关"; 2 -> "灵敏"; else -> "标准" } },
                            onSelect = { v -> vm.updateDraft { it.copy(voiceBargeIn = v) } },
                            itemPaddingH = 12,
                            itemPaddingV = 5,
                        )
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(
                            title = "语音播报复用长连接",
                            subtitle = "首句更快出声；某台设备出现没声音时关掉，退回每句重新连接",
                            checked = state.draft.ttsReuseSocket,
                            onCheckedChange = { checked -> vm.updateDraft { it.copy(ttsReuseSocket = checked) } },
                        )
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(
                            title = "主动提醒",
                            subtitle = "电量低/充满时小源自己说一句 · 只在空闲时说",
                            checked = state.draft.proactiveReminders,
                            onCheckedChange = { checked -> vm.updateDraft { it.copy(proactiveReminders = checked) } },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.save(onSaved = {}); prefsDialog = false }) { Text("完成") }
                },
            )
        }
    }
}

/** 从 Compose 的 Context 里找到宿主 Activity（状态栏图标外观要用 window） */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 字号行：左说明 + 右侧青色「标准 (1.0x) ❯」 */
@Composable
private fun PillRow(label: String, value: String, onClick: () -> Unit) {
    val p = LocalSettingsPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.inset)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("$value ❯", color = p.accent, fontSize = 11.sp)
    }
}

/** 单选弹窗：一行一项，左侧单选点 */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Triple<String, String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            // 小屏（这台 308×240，约 320dp 高）上选项一多就溢出，而弹窗内容本身不会滚，
            // 症状是「划不动、选不到下面的项」——必须显式给上限 + 纵向滚动。
            Column(
                Modifier
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { (value, label, desc) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = { onPick(value) })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(label, fontSize = 14.sp)
                            Text(desc, fontSize = 11.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 常用地址列表：任意个「标签 + 地址」，可增删，改动即保存 */
@Composable
private fun AddressListEditor(
    addresses: List<SavedAddress>,
    onChange: (List<SavedAddress>) -> Unit,
) {
    val p = LocalSettingsPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        addresses.forEachIndexed { index, addr ->
            val labelColor = when (index) {
                0 -> Color(0xFF22D3EE)
                1 -> Color(0xFF34D399)
                else -> Color(0xFFFBBF24)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.inset)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsField(
                    value = addr.label,
                    onValueChange = { v ->
                        onChange(addresses.toMutableList().also { it[index] = addr.copy(label = v.take(6)) })
                    },
                    placeholder = "标签",
                    modifier = Modifier.width(56.dp),
                    minHeight = 30,
                    fill = if (p.isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    centered = true,
                    textColor = labelColor,
                    bold = true,
                )
                Spacer(Modifier.width(8.dp))
                SettingsField(
                    value = addr.address,
                    onValueChange = { v ->
                        onChange(addresses.toMutableList().also { it[index] = addr.copy(address = v) })
                    },
                    placeholder = "输入详细地址",
                    modifier = Modifier.weight(1f),
                    minHeight = 30,
                    fill = Color.Transparent,
                    stroke = Color.Transparent,
                )
                Text(
                    "✕",
                    color = p.dim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onChange(addresses.toMutableList().also { it.removeAt(index) }) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        DashedAddButton(
            text = "+ 添加常用自定义地址",
            onClick = { if (addresses.size < 12) onChange(addresses + SavedAddress("", "")) },
        )
    }
}

/** 设备管理器是否已激活（只为熄屏申请 force-lock 一项） */
internal fun isScreenAdminNow(context: Context): Boolean = runCatching {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
        as? android.app.admin.DevicePolicyManager ?: return false
    dpm.isAdminActive(com.lvyoubao.app.ScreenAdminReceiver.component(context))
}.getOrDefault(false)

/** 是否已被设为默认桌面（HOME 角色）；Android 9- 走默认解析器比对包名 */
internal fun isDefaultHomeNow(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
    runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrNull() ?: false
} else {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val pkg = runCatching {
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()
    pkg == context.packageName
}

/** 发起「设为默认桌面」系统请求；无 HOME 角色能力的 ROM 退回系统默认应用设置页 */
internal fun requestDefaultHome(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
            return
        }
    }
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 打开系统的「默认桌面」设置页（已经是我们自己当桌面时用）。
 *
 * 坑在这里：应用本身是 HOME，`Intent(ACTION_MAIN, CATEGORY_HOME)` 会被解析回自己，
 * 点「设置」又回到小源，看着就是「点了没反应 / 进不去」。所以这里固定指向 Settings 的
 * HOME_SETTINGS 页，并逐个校验解析结果不是本应用；精简 ROM 没有这一页时依次退到
 * 默认应用页、应用详情页、系统设置首页。
 */
internal fun openHomeSettings(context: Context): Boolean {
    val candidates = listOf(
        Intent("android.settings.HOME_SETTINGS"),
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}")),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (raw in candidates) {
        val intent = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (resolved != null && resolved == context.packageName) continue
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return false
}

/** 字号系数显示名（未知自定义值兜底显示百分比） */
internal fun fontScaleLabel(v: Float): String = when (v) {
    0.85f -> "小一档 85%"
    1.0f -> "标准 100%"
    1.15f -> "大一档 115%"
    1.3f -> "特大 130%"
    else -> "${(v * 100).toInt()}%"
}

/** 空闲息屏时长文案：0 = 常亮，其余按秒显示（满 60 秒进位成分钟） */
internal fun screenOffLabel(sec: Int): String = when {
    sec <= 0 -> "常亮"
    sec < 60 -> "$sec 秒"
    sec % 60 == 0 -> "${sec / 60} 分钟"
    else -> "$sec 秒"
}

/**
 * 字号选择弹窗（大字模式/灵动模式各一）：
 * 选中即保存并生效——该模式全部 sp 文字经 fontScale 整体缩放，dp 骨架不动。
 */
@Composable
private fun FontScaleDialog(
    title: String,
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            // 同上：4 档字号 + 说明，在 320dp 高的屏上会顶到边界，加滚动保险
            Column(
                Modifier
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                listOf(0.85f to "小一档", 1.0f to "标准", 1.15f to "大一档", 1.3f to "特大").forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == value, onClick = { onPick(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                        Spacer(Modifier.weight(1f))
                        Text("${(value * 100).toInt()}%", fontSize = 12.sp)
                    }
                }
                Text("调整后该模式全部文字整体放大/缩小，立即生效", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
