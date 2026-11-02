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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.ProviderPresets
import com.lvyoubao.app.ui.SettingsViewModel

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

    BackHandler { onClose() }

    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            kotlinx.coroutines.delay(2500)
            savedTick = 0
        }
    }
    var themeDialog by remember { mutableStateOf(false) }

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
                FieldLabel("API Key")
                OutlinedTextField(
                    value = state.draft.apiKey,
                    onValueChange = { value -> vm.updateDraft { it.copy(apiKey = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("sk-…") },
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
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("测试连接") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { vm.save(onSaved = { savedTick++ }) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
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

        // ---------- 地图与语音（可选） ----------
        SectionLabel("地图与语音（可选）")
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                FieldLabel("高德 Key（启用附近搜索与步行路线）")
                OutlinedTextField(
                    value = state.draft.amapKey,
                    onValueChange = { value -> vm.updateDraft { it.copy(amapKey = value.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("在 lbs.amap.com 免费申请") },
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
                Text(
                    "朗读已内置云端高质量语音（免费，无需 Key）；这里的 Key 只用于语音识别兜底。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.save(onSaved = { savedTick++ }) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("长辈模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "超大字体 · 语音优先 · 回答自动朗读",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.draft.elderMode,
                        onCheckedChange = { checked ->
                            vm.updateDraft { it.copy(elderMode = checked) }
                            vm.save(onSaved = {})
                        },
                    )
                }
            }
        }

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
