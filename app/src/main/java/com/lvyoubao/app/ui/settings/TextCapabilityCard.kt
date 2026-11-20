package com.lvyoubao.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvyoubao.app.data.ProviderPresets
import com.lvyoubao.app.data.presetById
import com.lvyoubao.app.ui.SettingsViewModel

/**
 * 设计稿区块 4-1：1. 常规文本对话与图片。
 * 主服务商 + 官方最新模型代号双列下拉；深度思考（仅 DeepSeek）带 low/high/max 三档；
 * 联网搜索；内容安全风控（仅 DeepSeek）。
 */
@Composable
internal fun TextCapabilityCard(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val p = LocalSettingsPalette.current
    val d = state.draft
    val isDeepSeek = d.presetId == "deepseek"

    // 主服务商候选（设计稿 6 家：DeepSeek / 豆包 / 智谱 / Kimi / 通义 / 自定义）
    val providerIds = listOf("deepseek", "doubao", "zhipu", "moonshot", "qwen", "custom")
    val providers = remember { providerIds.map { presetById(it) } }
    val currentProvider = providers.firstOrNull { it.id == d.presetId } ?: providers.first()

    // 各家官方模型代号（与设计稿 capabilityModels 一致）
    val modelOptions = when (d.presetId) {
        "deepseek" -> listOf("deepseek-flash", "deepseek-v4-pro")
        "doubao" -> listOf("doubao-1.5-vision-pro", "doubao-1.5-pro-32k", "doubao-1.5-lite-32k")
        "zhipu" -> listOf("glm-5.3-flash", "glm-4-plus")
        "moonshot" -> listOf("kimi-k2.6", "moonshot-v1-auto")
        "qwen" -> listOf("qwen3-vl-plus", "qwen-turbo")
        else -> listOf("custom-model")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.insetSoft)
            .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text("1. 常规文本对话与图片", color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(10.dp))
        // 手机（≥320dp）双列并排；车机/小屏（240dp 级别）自动落成一列，避免两列各挤出半句话
        androidx.compose.foundation.layout.BoxWithConstraints {
            val narrow = maxWidth < 320.dp
            val providerBlock: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    CsFieldLabel("主服务商")
                    Spacer(Modifier.height(4.dp))
                    SettingsDropdown(
                        options = providers,
                        selected = currentProvider,
                        label = { it.label },
                        onSelect = { preset -> vm.selectPreset(preset) },
                        fill = if (p.isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
                    )
                }
            }
            val modelBlock: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    CsFieldLabel("官方最新模型代号")
                    Spacer(Modifier.height(4.dp))
                    SettingsDropdown(
                        options = modelOptions,
                        selected = d.model.takeIf { it.isNotBlank() },
                        label = { it },
                        onSelect = { model -> vm.updateDraft { it.copy(model = model) } },
                        mono = true,
                        fill = if (p.isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
                    )
                }
            }
            if (narrow) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    providerBlock(Modifier.fillMaxWidth())
                    modelBlock(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providerBlock(Modifier.weight(1f))
                    modelBlock(Modifier.weight(1f))
                }
            }
        }
        if (d.model.isNotBlank() && d.model !in modelOptions) {
            Spacer(Modifier.height(4.dp))
            CsHint("当前用的自定义代号：${d.model}（不在官方列表里也能跑）")
        }

        // 深度思考模式：仅 DeepSeek 具备 thinking 控参机制
        if (isDeepSeek) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(p.divider))
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "深度思考模式 (Thinking Mode)",
                subtitle = "通过 extra_body 传递 thinking 开关与推理深度",
                checked = d.thinking,
                onCheckedChange = { checked -> vm.updateDraft { it.copy(thinking = checked) } },
            )
            if (d.thinking) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (p.isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF))
                        .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "思考强度 (reasoning_effort)",
                        color = p.muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    PillGroup(
                        options = listOf("low", "high", "max"),
                        selected = d.reasoningEffort,
                        label = {
                            when (it) {
                                "low" -> "低 (low)"
                                "max" -> "最大 (max)"
                                else -> "高 (high)"
                            }
                        },
                        onSelect = { effort -> vm.updateDraft { it.copy(reasoningEffort = effort) } },
                        itemPaddingH = 8,
                        itemPaddingV = 2,
                    )
                }
            }
        }

        // 联网搜索能力
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(p.divider))
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "联网搜索能力",
            subtitle = if (isDeepSeek) "DeepSeek 原生联网搜索为主，搜索引擎作为补充" else "搜索引擎作为补充",
            checked = d.searchEnabled,
            onCheckedChange = { checked -> vm.updateDraft { it.copy(searchEnabled = checked) } },
        )

        // 内容安全风控：仅 DeepSeek
        if (isDeepSeek) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(p.divider))
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "内容安全风控",
                subtitle = "开启 DeepSeek 官方安全校验与风控审核（默认开启）",
                checked = d.safetyCheckEnabled,
                onCheckedChange = { checked -> vm.updateDraft { it.copy(safetyCheckEnabled = checked) } },
            )
        }
    }
}
