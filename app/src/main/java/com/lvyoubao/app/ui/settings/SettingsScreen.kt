package com.lvyoubao.app.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lvyoubao.app.data.presetById
import com.lvyoubao.app.ui.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onClose: () -> Unit, onOpenUpdate: () -> Unit, app: TravelApp) {
    val vm: SettingsViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { SettingsViewModel(app) } } },
    )
    val state by vm.ui.collectAsState()
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
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

        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        SectionLabel("服务商")
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

        SectionLabel("接口地址（OpenAI 兼容）")
        OutlinedTextField(
            value = state.draft.baseUrl,
            onValueChange = { value -> vm.updateDraft { it.copy(baseUrl = value.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.deepseek.com") },
            shape = MaterialTheme.shapes.medium,
        )

        SectionLabel("API Key")
        OutlinedTextField(
            value = state.draft.apiKey,
            onValueChange = { value -> vm.updateDraft { it.copy(apiKey = value.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("sk-…") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "隐藏" else "显示")
                }
            },
            shape = MaterialTheme.shapes.medium,
        )

        SectionLabel("模型")
        OutlinedTextField(
            value = state.draft.model,
            onValueChange = { value -> vm.updateDraft { it.copy(model = value.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (state.testing) CircularProgressIndicator(Modifier.size(20.dp))
            },
            shape = MaterialTheme.shapes.medium,
        )
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
            Spacer(Modifier.height(12.dp))
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(28.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { vm.testConnection() },
                enabled = !state.testing,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("测试连接") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.save(onSaved = onClose) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("保存", fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Key 只保存在本机（DataStore）。默认 DeepSeek，深度思考与联网搜索开关在主页输入框上方。搜索按次计费（官方 $0.75/千次）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenUpdate() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("应用更新", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "检查并下载安装新版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    Spacer(Modifier.height(12.dp))
}
