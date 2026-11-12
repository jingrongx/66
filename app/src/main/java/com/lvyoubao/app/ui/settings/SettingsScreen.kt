package com.lvyoubao.app.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.ProviderPresets
import com.lvyoubao.app.ui.SettingsViewModel
import com.lvyoubao.app.ui.common.TtsPlayer
import com.lvyoubao.app.ui.sync.QrCodeImage
import com.lvyoubao.app.ui.sync.QrScanScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
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
    var showKey by remember { mutableStateOf(false) }
    var showAmap by remember { mutableStateOf(false) }
    var showAsr by remember { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(0) }
    var showSyncQr by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            kotlinx.coroutines.delay(2500)
            savedTick = 0
        }
    }
    var themeDialog by remember { mutableStateOf(false) }
    var styleDialog by remember { mutableStateOf(false) }
    var showVoiceFix by remember { mutableStateOf(false) }
    // 纠正记录条数：打开/关闭管理页时刷新（页内删除后回来数字要变）
    val fixCount = remember(showVoiceFix) {
        com.lvyoubao.app.data.HotwordCorrector.learnedFixList(app.applicationContext).size
    }

    val versionLabel = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "v${info.versionName}($code)"
        }.getOrDefault("v?")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        // ---------- AI 服务 ----------
        SectionLabel("AI 服务")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("服务商", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ProviderPresets.forEach { preset ->
                        FilterChip(
                            selected = state.draft.presetId == preset.id,
                            onClick = { vm.selectPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                FieldLabel("接口地址（OpenAI 兼容）")
                OutlinedTextField(
                    value = state.draft.baseUrl,
                    onValueChange = { value -> vm.updateDraft { it.copy(baseUrl = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://api.deepseek.com") },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("API Key（每个供应商各自记住，互不串用）")
                OutlinedTextField(
                    value = state.draft.apiKey,
                    onValueChange = { value -> vm.updateDraft { it.copy(apiKey = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("填当前供应商的 Key，如 sk-…") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("模型")
                OutlinedTextField(
                    value = state.draft.model,
                    onValueChange = { value -> vm.updateDraft { it.copy(model = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (state.testing) CircularProgressIndicator(Modifier.size(20.dp))
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                if (state.fetchedModels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.fetchedModels.take(12).forEach { model ->
                            AssistChip(
                                onClick = { vm.updateDraft { it.copy(model = model) } },
                                label = { Text(model) },
                            )
                        }
                    }
                }
                state.testResult?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.testConnection() },
                        enabled = !state.testing,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("测试连接") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { vm.save(onSaved = { savedTick++ }) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("保存", fontWeight = FontWeight.Bold) }
                }
                if (savedTick > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已保存 ✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---------- 扫码同步 ----------
        SectionLabel("扫码同步配置")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "先在手机上填好 API Key 等内容并保存；在车机上点「生成同步二维码」，再用手机点「扫码推送」扫它，配置就会自动写进车机。两台设备需连同一个 Wi-Fi，或车机连上手机热点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            vm.clearSyncResult()
                            vm.startSyncServer()
                            if (vm.ui.value.syncQrContent != null) showSyncQr = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("生成同步二维码") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            vm.clearSyncResult()
                            showScanner = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("扫码推送", fontWeight = FontWeight.Bold) }
                }
                state.syncPushResult?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.syncPushFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---------- 地图与语音（可选） ----------
        SectionLabel("地图与语音（可选）")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                FieldLabel("高德 Key（启用附近搜索与步行路线，类型须选「Web服务」）")
                OutlinedTextField(
                    value = state.draft.amapKey,
                    onValueChange = { value -> vm.updateDraft { it.copy(amapKey = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("lbs.amap.com 免费申请，服务平台选「Web服务」") },
                    visualTransformation = if (showAmap) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showAmap = !showAmap }) { Text(if (showAmap) "隐藏" else "显示") }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("语音识别 Key（手机没有语音识别时，用大模型转文字）")
                OutlinedTextField(
                    value = state.draft.asrKey,
                    onValueChange = { value -> vm.updateDraft { it.copy(asrKey = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("在 siliconflow.cn 免费申请") },
                    visualTransformation = if (showAsr) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showAsr = !showAsr }) { Text(if (showAsr) "隐藏" else "显示") }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("常用地址（家）——问「回家的路」直接导航回家")
                OutlinedTextField(
                    value = state.draft.homeAddress,
                    onValueChange = { value -> vm.updateDraft { it.copy(homeAddress = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("小区名或详细地址，如：抚州市临川区某路某小区") },
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    "高德 Key 必须是「Web服务」类型（Android平台类型查不了路线）。朗读免费无需 Key；语音识别用硅基流动免费模型，一般不扣余额。常用地址填好后点下方「保存」，问「回家的路」就直接导航回家，不再追问地址。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                // 朗读链路诊断：逐级实测三级引擎，哪级通哪级挂、挂的原因一目了然（截图即可求助）
                val scope = rememberCoroutineScope()
                var diagRunning by remember { mutableStateOf(false) }
                var diagReport by remember { mutableStateOf<String?>(null) }
                Button(
                    onClick = {
                        if (diagRunning) return@Button
                        diagRunning = true
                        diagReport = null
                        scope.launch {
                            diagReport = TtsPlayer.diagnose(context, state.draft.asrKey.ifBlank { null })
                            diagRunning = false
                        }
                    },
                    enabled = !diagRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (diagRunning) "诊断中…（约 10 秒）" else "🔍 朗读链路诊断（读不出声时点这个）")
                }
                diagReport?.let { report ->
                    Text(
                        report,
                        style = MaterialTheme.typography.labelMedium,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("方言口音识别", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "说抚州话、带家乡口音时打开：换用方言口音大模型（免费），识别更准，稍慢一两秒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.draft.dialectMode,
                        onCheckedChange = { checked ->
                            vm.updateDraft { it.copy(dialectMode = checked) }
                            vm.save(onSaved = {})
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.save(onSaved = { savedTick++ }) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
                if (savedTick > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已保存 ✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---------- 通用 ----------
        SectionLabel("通用")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingRow(
                    title = "外观",
                    value = when (state.draft.themeMode) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    },
                    onClick = { themeDialog = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow(
                    title = "回答风格",
                    value = when (state.draft.answerStyle) {
                        "medium" -> "中等"
                        "detailed" -> "详尽"
                        else -> "简洁"
                    },
                    onClick = { styleDialog = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("大字模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "超大字体 · 语音优先 · 回答自动朗读 · 启动默认进大字模式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.draft.largeTextMode,
                        onCheckedChange = { checked ->
                            // 打开时联动打开自动播报（语音优先）；关闭不动自动播报，尊重用户特意关过的选择
                            vm.updateDraft {
                                it.copy(largeTextMode = checked, autoSpeak = if (checked) true else it.autoSpeak)
                            }
                            vm.save(onSaved = {})
                        },
                    )
                }
            }
        }

        // ---------- 语音纠错 ----------
        SectionLabel("语音纠错")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingRow(
                    title = "纠正记录",
                    value = if (fixCount > 0) "$fixCount 条" else "还没有",
                    onClick = { showVoiceFix = true },
                )
            }
        }
        Text(
            "语音识别的稿子你手动改对再发送，就会记下「识别稿 → 发送稿」的差异；AI 智能纠错据此把同类错误越改越准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // ---------- 关于 ----------
        SectionLabel("关于")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingRow(title = "检查更新", value = versionLabel, onClick = onOpenUpdate)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow(
                    title = "项目主页",
                    value = "GitHub",
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
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Key 只保存在本机（DataStore）。深度思考与联网搜索开关在主页输入卡内；联网搜索按次计费（官方约 ¥5.4/千次成功请求）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "内容由 AI 生成，请仔细甄别，并合法使用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(32.dp))
    }

    if (showSyncQr) {
        AlertDialog(
            onDismissRequest = { showSyncQr = false; vm.stopSyncServer() },
            title = { Text("等待手机扫码") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.syncQrContent?.let { content -> QrCodeImage(content) }
                    Spacer(Modifier.height(12.dp))
                    if (state.syncReceived) {
                        Text(
                            "已收到手机推送的配置并自动保存 ✓",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            "用手机上安装的本应用，点「扫码推送」扫此码。手机里已填好的配置会直接传到本机并自动保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSyncQr = false; vm.stopSyncServer() }) { Text("完成") }
            },
        )
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
    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("外观") },
            text = {
                Column {
                    listOf(
                        "system" to "跟随系统",
                        "light" to "浅色",
                        "dark" to "深色",
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.updateDraft { it.copy(themeMode = value) } }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.draft.themeMode == value,
                                onClick = { vm.updateDraft { it.copy(themeMode = value) } },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.save(onSaved = {})
                    themeDialog = false
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { themeDialog = false }) { Text("取消") }
            },
        )
    }

    if (styleDialog) {
        AlertDialog(
            onDismissRequest = { styleDialog = false },
            title = { Text("回答风格") },
            text = {
                Column {
                    listOf(
                        "concise" to "简洁 · 平时问答一两句，问“详细”才展开",
                        "medium" to "中等 · 攻略类给 3-5 条要点",
                        "detailed" to "详尽 · 攻略类 5-8 条要点讲全",
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.updateDraft { it.copy(answerStyle = value) } }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.draft.answerStyle == value,
                                onClick = { vm.updateDraft { it.copy(answerStyle = value) } },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.save(onSaved = {})
                    styleDialog = false
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { styleDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Column {
        Spacer(Modifier.height(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
