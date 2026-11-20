package com.lvyoubao.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvyoubao.app.data.ProviderCredentials
import com.lvyoubao.app.data.presetById
import com.lvyoubao.app.ui.SettingsViewModel

/**
 * 设计稿区块 3：服务商与 API 密钥集中管理（4×2 tab + 纯凭证面板）。
 * 8 家：DeepSeek / 豆包 / 智谱 GLM / Kimi / 通义千问 / 自定义 / 硅基流动 / 高德地图。
 *
 * 每家的凭证各存各的：当前主服务商的写 draft.baseUrl/apiKey，其余写 draft.savedProviders[id]，
 * 切换 tab 绝不把上一家的 Key 显示到下一家头上。
 */
@Composable
internal fun ProviderCredentialsCard(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val p = LocalSettingsPalette.current
    val context = LocalContext.current
    val d = state.draft
    var tab by remember { mutableStateOf("deepseek") }
    var showKey by remember { mutableStateOf(false) }

    val tabs = listOf(
        "deepseek" to "DeepSeek",
        "doubao" to "豆包",
        "zhipu" to "智谱 GLM",
        "moonshot" to "Kimi",
        "qwen" to "通义千问",
        "custom" to "自定义",
        "silicon" to "硅基流动",
        "amap" to "高德地图",
    )

    // 取某家的地址 / Key：当前主服务商读 draft，其余读 savedProviders（没配过就空）
    fun baseOf(id: String): String {
        if (id == d.presetId) return d.baseUrl
        return d.savedProviders[id]?.baseUrl ?: presetById(id).baseUrl
    }

    fun keyOf(id: String): String {
        if (id == d.presetId) return d.apiKey
        return d.savedProviders[id]?.apiKey.orEmpty()
    }

    fun write(id: String, baseUrl: String? = null, apiKey: String? = null) {
        if (id == d.presetId) {
            vm.updateDraft {
                it.copy(
                    baseUrl = baseUrl ?: it.baseUrl,
                    apiKey = apiKey ?: it.apiKey,
                )
            }
        } else {
            val old = d.savedProviders[id] ?: ProviderCredentials(
                baseUrl = presetById(id).baseUrl,
                apiKey = "",
                model = presetById(id).defaultModel,
            )
            vm.updateDraft {
                it.copy(
                    savedProviders = it.savedProviders + (id to old.copy(
                        baseUrl = baseUrl ?: old.baseUrl,
                        apiKey = apiKey ?: old.apiKey,
                    )),
                )
            }
        }
    }

    SettingsCard {
        // 4×2 tab 网格：容器内嵌底 + rounded-xl + 1px 描边；窄屏（车机 240dp 级）落成 2 列
        androidx.compose.foundation.layout.BoxWithConstraints {
            val cols = if (maxWidth < 320.dp) 2 else 4
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.inset)
                    .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
                    .padding(6.dp),
            ) {
                tabs.chunked(cols).forEachIndexed { rowIndex, rowTabs ->
                    if (rowIndex > 0) Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowTabs.forEach { (id, label) ->
                            val active = tab == id
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) p.accentStrong else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { tab = id; showKey = false }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (active) p.accentOn else p.muted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 16.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            "silicon" -> {
                CredentialPanel(
                    label = "",
                    keyLabel = "语音服务 Key (硅基流动)",
                    linkText = "免费申请 ↗",
                    linkUrl = "https://cloud.siliconflow.cn/account/ak",
                    key = d.asrKey,
                    onKeyChange = { v -> vm.updateDraft { it.copy(asrKey = v.trim()) } },
                    showKey = showKey,
                    onToggleShow = { showKey = !showKey },
                    hint = "供经典模式 SenseVoice、TeleSpeech 23种方言及 CosyVoice2 免费调用。",
                )
            }

            "amap" -> {
                CredentialPanel(
                    label = "",
                    keyLabel = "高德 Web 服务 Key",
                    linkText = "高德控制台 ↗",
                    linkUrl = "https://console.amap.com/dev/key/app",
                    key = d.amapKey,
                    onKeyChange = { v -> vm.updateDraft { it.copy(amapKey = v.trim()) } },
                    showKey = showKey,
                    onToggleShow = { showKey = !showKey },
                    hint = "用于周边地点检索 (search_poi) 与路线规划计算。",
                )
            }

            else -> {
                val preset = presetById(tab)
                val isCustom = tab == "custom"
                val urlLabel = when (tab) {
                    "doubao" -> "接口地址 (Base URL / Ark)"
                    "custom" -> "自定义接口地址 (Base URL)"
                    else -> "接口地址 (Base URL)"
                }
                val keyLabel = when (tab) {
                    "doubao" -> "API Key (X-Api-Key)"
                    "qwen" -> "API Key (DashScope)"
                    else -> "API Key"
                }
                val urlPlaceholder = if (isCustom) "https://api.example.com/v1 或内网地址" else preset.baseUrl
                val keyPlaceholder = when (tab) {
                    "doubao" -> "填入火山引擎 API Key"
                    "zhipu" -> "填入智谱 API Key"
                    "moonshot" -> "填入 Moonshot API Key"
                    "qwen" -> "填入阿里云百炼 API-KEY"
                    "custom" -> "填入自定义 API Key（可选）"
                    else -> "填入 ${preset.label} API Key"
                }

                CredentialPanel(
                    label = urlLabel,
                    baseUrl = baseOf(tab),
                    onBaseUrlChange = { v -> write(tab, baseUrl = v.trim()) },
                    baseUrlPlaceholder = urlPlaceholder,
                    keyLabel = keyLabel,
                    linkText = when (tab) {
                        "deepseek" -> "申请 Key ↗"
                        "doubao" -> "火山控制台 ↗"
                        "zhipu" -> "智谱控制台 ↗"
                        "moonshot" -> "Moonshot 控制台 ↗"
                        "qwen" -> "百炼控制台 ↗"
                        else -> ""
                    },
                    linkUrl = preset.apiKeyUrl,
                    key = keyOf(tab),
                    onKeyChange = { v -> write(tab, apiKey = v.trim()) },
                    keyPlaceholder = keyPlaceholder,
                    showKey = showKey,
                    onToggleShow = { showKey = !showKey },
                    hint = when (tab) {
                        "deepseek" -> "仅保存在本机私有目录。供文本/图文对话、ASR与TTS服务调用。"
                        "doubao" -> "供常规文本/图片对话与 Seeduplex 实时全双工语音通话公用（两把 Key 来自不同产品，下方各填各的）。"
                        "zhipu" -> "新用户送免费体验包。原生多模态视觉旗舰与超长上下文。"
                        "moonshot" -> "月之暗面 Kimi 官方接口，支持超长文本与多模态分析。"
                        "qwen" -> "阿里云 DashScope 兼容模式，支持 Qwen3-VL 视觉旗舰。"
                        else -> "兼容 OpenAI Chat Completions 规范的任何服务商或自建服务 (如 Ollama、vLLM、OneAPI)。"
                    },
                    // 豆包：实时语音（Seeduplex）另有一把 Key，面板内直接补齐，通话页无需再填
                    extra = if (tab == "doubao") {
                        {
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                CsFieldLabel("实时语音 Key (Seeduplex 全双工)", Modifier.weight(1f))
                                LinkText(
                                    "语音控制台 ↗",
                                    "https://console.volcengine.com/speech/new/setting/apikeys",
                                    context,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            SettingsField(
                                value = d.duplexApiKey,
                                onValueChange = { v -> vm.updateDraft { it.copy(duplexApiKey = v.trim()) } },
                                placeholder = "填入豆包语音「实时语音」产品的 Key",
                                mono = true,
                                password = true,
                                showPassword = showKey,
                                keyboardType = KeyboardType.Password,
                                trailing = { FieldTrailingText(if (showKey) "隐藏" else "显示") { showKey = !showKey } },
                            )
                        }
                    } else null,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = if (state.testing) "测试中…" else "测试当前服务商连接",
            onClick = {
                when (tab) {
                    "amap" -> vm.testAmapKey(d.amapKey)
                    "silicon" -> vm.testCredential(
                        "silicon", "https://api.siliconflow.cn/v1", d.asrKey, "Qwen/Qwen3-8B",
                    )
                    else -> vm.testCredential(tab, baseOf(tab), keyOf(tab), presetById(tab).defaultModel)
                }
            },
            enabled = !state.testing,
            modifier = Modifier.fillMaxWidth(),
        )
        state.testResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                result,
                color = if (state.testFailed) p.danger else p.accent,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/** 小链接文字：cyan-400 */
@Composable
private fun LinkText(text: String, url: String, context: android.content.Context) {
    val p = LocalSettingsPalette.current
    if (text.isBlank() || url.isBlank()) return
    Text(
        text,
        color = p.accent,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .padding(vertical = 2.dp),
    )
}

/**
 * 单家凭证面板。baseUrl 为空且没有 baseUrlPlaceholder 需求时只显示 Key（硅基 / 高德）。
 * extra 用于豆包面板补实时语音 Key。
 */
@Composable
private fun CredentialPanel(
    label: String,
    baseUrl: String? = null,
    onBaseUrlChange: (String) -> Unit = {},
    baseUrlPlaceholder: String = "",
    keyLabel: String = "API Key",
    linkText: String = "",
    linkUrl: String = "",
    key: String,
    onKeyChange: (String) -> Unit,
    keyPlaceholder: String = "",
    showKey: Boolean,
    onToggleShow: () -> Unit,
    hint: String,
    extra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (baseUrl != null) {
            CsFieldLabel(label)
            Spacer(Modifier.height(4.dp))
            SettingsField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = baseUrlPlaceholder,
                mono = true,
            )
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CsFieldLabel(keyLabel, Modifier.weight(1f))
            LinkText(linkText, linkUrl, context)
        }
        Spacer(Modifier.height(4.dp))
        SettingsField(
            value = key,
            onValueChange = onKeyChange,
            placeholder = keyPlaceholder,
            mono = true,
            password = true,
            showPassword = showKey,
            keyboardType = KeyboardType.Password,
            trailing = { FieldTrailingText(if (showKey) "隐藏" else "显示", onToggleShow) },
        )
        Spacer(Modifier.height(4.dp))
        CsNote(hint)
        extra?.invoke()
    }
}

/** 键盘输入行（供其它卡片复用的小尺寸行） */
@Composable
internal fun InlineInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    minHeight: Int = 38,
) {
    SettingsField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        mono = mono,
        minHeight = minHeight,
    )
}

/** 空占位：保证 defaultMinSize 生效（Compose 里空 Row 也会占位，这里显式留白用） */
@Composable
internal fun MinHeightSpacer(height: Int) = Spacer(Modifier.defaultMinSize(minHeight = height.dp))
