package com.lvyoubao.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lvyoubao.app.data.DeviceEnvTools
import com.lvyoubao.app.data.DeviceEnvTools.GoogleWakeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「设备兼容与网络 ADB」卡片：
 * 1. Google 语音唤醒占麦排查与禁用（Root 一键禁麦，无 Root 清楚说明权限边界并提供直达系统关闭入口与 ADB 命令）；
 * 2. 网络 ADB 状态检查与开启（Root 一键开 5555 端口，无 Root 说明并引导开发者选项或 USB 首次激活指令）。
 */
@Composable
internal fun DeviceToolsCard() {
    val p = LocalSettingsPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var googleStatus by remember { mutableStateOf<DeviceEnvTools.GoogleWakeStatus?>(null) }
    var adbStatus by remember { mutableStateOf<DeviceEnvTools.NetworkAdbStatus?>(null) }
    var googleFeedback by remember { mutableStateOf<String?>(null) }
    var googleFeedbackOk by remember { mutableStateOf(false) }
    var adbFeedback by remember { mutableStateOf<String?>(null) }
    var adbFeedbackOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            val (g, a) = withContext(Dispatchers.IO) {
                DeviceEnvTools.inspectGoogleWakeStatus(context) to DeviceEnvTools.inspectNetworkAdbStatus(context)
            }
            googleStatus = g
            adbStatus = a
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    // 从系统设置页/权限页返回时自动刷新检测状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCard {
        // ==================== 1. 防 Google 语音唤醒占麦 ====================
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CsLabel("防 Google 语音唤醒抢占麦克风", modifier = Modifier.weight(1f))
            val gBadge = when (googleStatus?.state) {
                GoogleWakeState.NOT_INSTALLED -> Triple("未安装", p.accent, p.accentSoft)
                GoogleWakeState.APP_DISABLED -> Triple("已停用", p.accent, p.accentSoft)
                GoogleWakeState.MIC_IGNORED -> Triple("已禁麦 ✓", p.accent, p.accentSoft)
                GoogleWakeState.VOICE_INTERACTION_ACTIVE -> Triple(
                    "唤醒激活",
                    p.danger,
                    if (p.isDark) Color(0xFF450A0A) else Color(0xFFFEE2E2),
                )
                GoogleWakeState.INSTALLED_INACTIVE -> Triple(
                    "已装待查",
                    if (p.isDark) Color(0xFFFCD34D) else Color(0xFFB45309),
                    if (p.isDark) Color(0xFF451A03) else Color(0xFFFEF3C7),
                )
                null -> Triple("检测中…", p.dim, p.inset)
            }
            SettingsBadge(gBadge.first, fg = gBadge.second, bg = gBadge.third)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "部分固件上 Google 语音唤醒（:interactor）会在后台持续或每 5 秒抢占硬件录音接口，导致旅游宝和系统录音应用均录不了音。" +
                "有 Root 的设备可在本页直接一键关闭 Google 录音权限；无 Root 的普通手机受安卓安全沙箱限制，应用无权静默修改 Google 权限，需点击下方按钮手动关闭一次（推荐进系统权限页关闭「麦克风」）。",
            color = p.muted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))

        val gSummary = when (val st = googleStatus) {
            null -> "正在检测 Google 语音交互服务…"
            else -> when (st.state) {
                GoogleWakeState.NOT_INSTALLED -> "本机未安装 Google 搜索应用，不存在 Google 热词抢麦问题。"
                GoogleWakeState.APP_DISABLED -> "Google 搜索应用处于停用状态，不会抢占麦克风。"
                GoogleWakeState.MIC_IGNORED -> "已确认 Google 录音权限被拒绝（ignore），不再占用硬件麦克风。"
                GoogleWakeState.VOICE_INTERACTION_ACTIVE ->
                    "检测到系统默认语音交互当前指向 Google（${st.activeVoiceService?.substringAfterLast('/') ?: "GsaVoiceInteractionService"}）。" +
                        if (st.hasRoot) "检测到 Root，可直接点击下方按钮一键禁用。" else "无 Root，建议点击下方按钮关闭 Google「麦克风」权限。"
                GoogleWakeState.INSTALLED_INACTIVE ->
                    "已安装 Google 搜索；" + if (st.hasRoot) {
                        "当前系统默认语音交互未指向它（Google 录音仍为允许，如不需要可一键禁用）。"
                    } else {
                        "无 Root 时本应用无法读取 Google 的热词状态，如曾开过 Hey Google 唤醒请去权限页确认已拒绝麦克风。"
                    }
            }
        }
        Text(
            gSummary,
            color = p.textTertiary,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.inset)
                .border(1.dp, p.insetBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))

        val canToggleGoogleViaRoot = googleStatus?.hasRoot == true &&
            googleStatus?.state != GoogleWakeState.NOT_INSTALLED &&
            googleStatus?.state != GoogleWakeState.APP_DISABLED

        if (canToggleGoogleViaRoot) {
            val alreadyIgnored = googleStatus?.state == GoogleWakeState.MIC_IGNORED
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = if (alreadyIgnored) "已禁用占麦（重新执行）" else "一键禁用 Google 占麦（Root）",
                    onClick = {
                        if (busy) return@PrimaryButton
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { DeviceEnvTools.setGoogleMicIgnoredViaRoot(ignore = true) }
                            googleFeedbackOk = r.success
                            googleFeedback = r.message
                            busy = false
                            refreshAll()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (alreadyIgnored) {
                    SecondaryButton(
                        text = "恢复权限",
                        onClick = {
                            if (busy) return@SecondaryButton
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { DeviceEnvTools.setGoogleMicIgnoredViaRoot(ignore = false) }
                                googleFeedbackOk = r.success
                                googleFeedback = r.message
                                busy = false
                                refreshAll()
                            }
                        },
                        enabled = !busy,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (googleStatus?.state != GoogleWakeState.NOT_INSTALLED &&
            googleStatus?.state != GoogleWakeState.APP_DISABLED
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "关 Google 麦克风权限",
                    onClick = {
                        val opened = DeviceEnvTools.openGoogleAppSettings(context)
                        googleFeedbackOk = opened
                        googleFeedback = if (opened) {
                            "请在打开的页面点「权限 → 麦克风 → 不允许/拒绝」，返回即生效。"
                        } else {
                            "无法直接打开应用详情，请到系统设置 → 应用管理 → Google 关闭麦克风。"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "系统默认语音助手",
                    onClick = {
                        val opened = DeviceEnvTools.openVoiceAssistantSettings(context)
                        googleFeedbackOk = opened
                        googleFeedback = if (opened) {
                            "可将默认数字助理设为「无」，或关闭 Google Voice Match。"
                        } else {
                            "当前系统未暴露默认助手快捷入口，请用左侧按钮关闭 Google 麦克风。"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton(
                text = "复制电脑 ADB 屏蔽命令（无需 Root）",
                onClick = {
                    val copied = DeviceEnvTools.copyText(
                        context,
                        "ADB屏蔽Google占麦",
                        DeviceEnvTools.ADB_DISABLE_GOOGLE_MIC_CMD,
                    )
                    googleFeedbackOk = copied
                    googleFeedback = if (copied) {
                        "已复制：${DeviceEnvTools.ADB_DISABLE_GOOGLE_MIC_CMD}"
                    } else {
                        "复制失败，请手动执行：${DeviceEnvTools.ADB_DISABLE_GOOGLE_MIC_CMD}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        googleFeedback?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                color = if (googleFeedbackOk) p.accent else p.danger,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        CardDivider()
        Spacer(Modifier.height(14.dp))

        // ==================== 2. 网络 ADB（Wi-Fi 调试） ====================
        val a = adbStatus
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CsLabel("网络 ADB（Wi-Fi 无线调试）", modifier = Modifier.weight(1f))
            val adbBadge = when {
                a == null -> Triple("检测中…", p.dim, p.inset)
                a.tcpPort != null -> Triple("端口 ${a.tcpPort} 已开", p.accent, p.accentSoft)
                a.hasRoot -> Triple("未开启", p.dim, p.inset)
                else -> Triple("需开发者/电脑激活", p.muted, p.inset)
            }
            SettingsBadge(adbBadge.first, fg = adbBadge.second, bg = adbBadge.third)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (Build.VERSION.SDK_INT >= 30) {
                "有 Root 的设备可直接在应用内一键开启 5555 端口；无 Root 时受安卓系统限制无法直接重启 adbd，请进「开发者选项」打开「无线调试」，或首次连电脑运行 `adb tcpip 5555`。"
            } else {
                "有 Root 的设备可直接在应用内一键开启 5555 端口；Android 10 及以下无 Root 手机无法由普通应用启动网络 ADB，需首次用 USB 线连电脑执行 `adb tcpip 5555` 激活（重启手机后会失效，需重新激活）。"
            },
            color = p.muted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.inset)
                .border(1.dp, p.insetBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val ipText = a?.lanIp ?: "未连局域网 Wi-Fi"
            val usbDebugText = if (a?.adbEnabled == true) "已开启" else "未开启"
            val portText = when {
                a == null -> "检测中…"
                a.tcpPort != null -> "已监听 ${a.tcpPort} 端口"
                a.hasRoot -> "未监听（当前为 USB 模式）"
                else -> "普通权限不可直读端口（若刚在电脑执行过 adb tcpip 5555 可直接连接）"
            }
            Text("局域网 IP：$ipText   ·   系统 USB 调试：$usbDebugText", color = p.textTertiary, fontSize = 11.sp)
            Text("网络 ADB 状态：$portText", color = p.muted, fontSize = 11.sp)
            Text(
                DeviceEnvTools.adbConnectCommand(a?.lanIp),
                color = p.accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val isPortOpen = a?.tcpPort != null
            PrimaryButton(
                text = when {
                    a?.hasRoot == true && isPortOpen -> "重设 5555 端口"
                    a?.hasRoot == true -> "一键开启网络 ADB"
                    else -> "尝试开启网络 ADB"
                },
                onClick = {
                    if (busy) return@PrimaryButton
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { DeviceEnvTools.setNetworkAdbViaRoot(enable = true) }
                        adbFeedbackOk = r.success
                        adbFeedback = r.message
                        busy = false
                        refreshAll()
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            if (a?.hasRoot == true && isPortOpen) {
                SecondaryButton(
                    text = "关闭网络 ADB",
                    onClick = {
                        if (busy) return@SecondaryButton
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { DeviceEnvTools.setNetworkAdbViaRoot(enable = false) }
                            adbFeedbackOk = r.success
                            adbFeedback = r.message
                            busy = false
                            refreshAll()
                        }
                    },
                    enabled = !busy,
                )
            } else {
                SecondaryButton(
                    text = "打开开发者选项",
                    onClick = {
                        val opened = DeviceEnvTools.openDeveloperSettings(context)
                        adbFeedbackOk = opened
                        adbFeedback = if (opened) {
                            if (Build.VERSION.SDK_INT >= 30) {
                                "请在开发者选项中开启「USB 调试」与「无线调试」。"
                            } else {
                                "请确保开启「USB 调试」，再连电脑执行一次激活指令。"
                            }
                        } else {
                            "未找到开发者选项入口，请在系统设置 → 关于手机连续点击「版本号」开启。"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "复制连接命令",
                onClick = {
                    val cmd = DeviceEnvTools.adbConnectCommand(a?.lanIp)
                    val ok = DeviceEnvTools.copyText(context, "ADB连接命令", cmd)
                    adbFeedbackOk = ok && !a?.lanIp.isNullOrBlank()
                    adbFeedback = if (a?.lanIp.isNullOrBlank()) {
                        "已复制模板命令（设备当前未连 Wi-Fi，连网后重试可带入真实 IP）：$cmd"
                    } else if (ok) {
                        "已复制：$cmd"
                    } else {
                        "复制失败，请手动输入：$cmd"
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "复制首次激活命令",
                onClick = {
                    val cmd = DeviceEnvTools.adbTcpipCommand(a?.lanIp)
                    val ok = DeviceEnvTools.copyText(context, "ADB激活命令", cmd)
                    adbFeedbackOk = ok
                    adbFeedback = if (ok) {
                        "USB 连电脑执行：$cmd"
                    } else {
                        "复制失败，请手动执行：$cmd"
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        adbFeedback?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                color = if (adbFeedbackOk) p.accent else p.danger,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
