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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvyoubao.app.data.VoiceModelOptions
import com.lvyoubao.app.ui.SettingsViewModel

private data class EngineOption(val id: String, val label: String, val enabled: Boolean = true)

private data class VoiceOption(val id: String, val label: String)

/**
 * 设计稿区块 4-2：2. 语音消息和实时通话配置（两块并存 · 各自配置）。
 *
 * A. 语音消息（按住说话）：ASR / LLM / TTS 三段完全独立平铺；松手自动发送 + 延时秒数。
 * B. 实时通话（全屏）：通话引擎二选一——豆包全双工（端到端 S2S，不走三段）或经典串行（三段独立选）。
 * 两块互不影响：改通话引擎不会动语音消息的三段模型。
 */
@Composable
internal fun VoiceConfigCard(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val p = LocalSettingsPalette.current
    val d = state.draft
    val fieldBg = if (p.isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF)

    val engines = listOf(
        EngineOption("doubao", "豆包全双工 ｜端到端直出语音·免按键插话·可打断（推荐）"),
        EngineOption("classic", "经典串行 ｜ASR → LLM → TTS 三段各自独立选模型"),
        EngineOption(
            "realtime",
            "音视频通话 ｜Doubao-Seed-Realtime-1.0（暂未开放 · 视频token成本高）",
            enabled = false,
        ),
    )
    val voices = listOf(
        VoiceOption("zh_female_vv_jupiter_bigtts", "Vivi 2.0 (女声 · 方言/歌唱)"),
        VoiceOption("zh_male_xiaohe_jupiter_bigtts", "小何 2.0 (男声 · 方言/歌唱)"),
        VoiceOption("zh_male_yunzhou_jupiter_bigtts", "云舟 2.0 (沉稳男声)"),
        VoiceOption("zh_female_xiaotian_jupiter_bigtts", "小天 2.0 (甜美女声)"),
    )
    // 官方 explicit_dialect 取值；仅 4 个精品音色支持，选了才有方言效果
    val dialects = listOf(
        VoiceOption("", "普通话（默认）"),
        VoiceOption("dongbei", "东北话"),
        VoiceOption("sichuan", "四川话"),
        VoiceOption("shaanxi", "陕西话"),
        VoiceOption("yue", "粤语"),
        VoiceOption("beijing", "北京话"),
        VoiceOption("henan", "河南话"),
        VoiceOption("tianjin", "天津话"),
        VoiceOption("shanghai", "上海话"),
    )
    val musicOptions = listOf(
        VoiceOption("0", "关闭（默认）"),
        VoiceOption("1", "开启（说「唱一首…」会开唱）"),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.insetSoft)
            .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "2. 语音消息和实时通话配置",
                color = p.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("两块并存 · 各自配置", color = p.muted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(14.dp))

        // ==================== A. 语音消息（按住说话） ====================
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(p.card)
                .border(1.dp, p.cardBorder, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "A. 语音消息（按住说话）",
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("主界面输入区", color = p.accent, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            Spacer(Modifier.height(8.dp))
            CsNote("松手转文字进草稿，可连说几段、可编辑，点发送或延时自动发出。")

            StageBlock(
                title = "① 语音识别模型 (ASR)",
                hint = "把说的话转成文字 · 独立选择",
                options = VoiceModelOptions.ASR,
                selectedId = d.voiceAsrModel.ifBlank { "sensevoice" },
                onSelect = { id ->
                    vm.updateDraft {
                        it.copy(
                            voiceAsrModel = id,
                            // 选 TeleSpeech 即方言口音增强，选别的即关掉——不再需要额外的方言开关
                            dialectMode = id == "telespeech",
                        )
                    }
                },
                fieldBg = fieldBg,
            )

            StageBlock(
                title = "② 对话大模型 (LLM)",
                hint = "理解并生成回答 · 独立选择",
                options = VoiceModelOptions.LLM,
                selectedId = d.voiceLlmModel.ifBlank { "deepseek-flash" },
                onSelect = { id -> vm.updateDraft { it.copy(voiceLlmModel = id) } },
                fieldBg = fieldBg,
            )

            StageBlock(
                title = "③ 语音合成模型 (TTS)",
                hint = "把回答读出来 · 独立选择",
                options = VoiceModelOptions.TTS,
                selectedId = d.voiceTtsModel.ifBlank { "edge" },
                onSelect = { id -> vm.updateDraft { it.copy(voiceTtsModel = id) } },
                fieldBg = fieldBg,
            )

            // 松手后自动发送 + 延时秒数
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(p.divider))
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "松手后自动发送",
                subtitle = "松手后倒计时自动提交，编辑输入框即取消",
                checked = d.asrAutoSendSeconds >= 0,
                onCheckedChange = { checked ->
                    vm.updateDraft {
                        it.copy(
                            asrAutoSendEnabled = checked,
                            asrAutoSendSeconds = if (checked) {
                                if (it.asrAutoSendSeconds in 3..9) it.asrAutoSendSeconds else 5
                            } else -1,
                        )
                    }
                },
            )
            if (d.asrAutoSendSeconds >= 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.inset)
                        .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "延时秒数",
                        color = p.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    PillGroup(
                        options = listOf(3, 5, 7, 9),
                        // 旧配置可能是 0/1 秒（立即/1秒），不在四档里：显示上按最近档 3 秒高亮，
                        // 点一下即落到新档位，不留「一个都没选中」的空态
                        selected = if (d.asrAutoSendSeconds in 3..9) d.asrAutoSendSeconds else 3,
                        label = { "${it}秒" },
                        onSelect = { sec -> vm.updateDraft { it.copy(asrAutoSendSeconds = sec) } },
                        itemPaddingH = 10,
                        itemPaddingV = 4,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ==================== B. 实时通话（全屏通话界面） ====================
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(p.card)
                .border(1.dp, p.cardBorder, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "B. 实时通话",
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("点导航「通话」进全屏", color = p.accent, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            Spacer(Modifier.height(8.dp))
            CsNote("松手即问、回答自动朗读、朗读中可插话。通话引擎二选一，一次通话只跑一条链路。")

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CsLabel("通话引擎", Modifier.weight(1f))
                CsHint("二选一 · 互斥")
            }
            Spacer(Modifier.height(4.dp))
            SettingsDropdown(
                options = engines,
                selected = engines.firstOrNull { it.id == d.callEngine } ?: engines.first(),
                label = { it.label },
                onSelect = { engine -> vm.updateDraft { it.copy(callEngine = engine.id) } },
                optionEnabled = { it.enabled },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "音视频版基于 Seeduplex 扩展，新增画面理解与搜图；当前只做语音，配置项预留不启用。",
                color = p.faint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.inset)
                    .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    CsLabel("默认声音输出")
                    Text(
                        "两种通话引擎均适用 · 通话界面可随时切换",
                        color = p.muted,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PillGroup(
                    options = listOf(true, false),
                    selected = d.callSpeakerphone,
                    label = { if (it) "免提外放" else "听筒" },
                    onSelect = { useSpeaker -> vm.updateDraft { it.copy(callSpeakerphone = useSpeaker) } },
                    itemPaddingH = 10,
                    itemPaddingV = 5,
                )
            }

            if (d.callEngine == "classic") {
                Spacer(Modifier.height(10.dp))
                Text(
                    "经典串行链路：松手说话 → 语音识别 → 大模型回答 → 朗读",
                    color = p.accent,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                StageBlock(
                    title = "① 语音识别模型 (ASR)",
                    hint = "把说的话转成文字 · 独立选择",
                    options = VoiceModelOptions.ASR,
                    selectedId = d.callAsrModel.ifBlank { "sensevoice" },
                    onSelect = { id -> vm.updateDraft { it.copy(callAsrModel = id) } },
                    fieldBg = fieldBg,
                )
                StageBlock(
                    title = "② 对话大模型 (LLM)",
                    hint = "理解并生成回答 · 独立选择",
                    options = VoiceModelOptions.LLM,
                    selectedId = d.callLlmModel.ifBlank { "deepseek-flash" },
                    onSelect = { id -> vm.updateDraft { it.copy(callLlmModel = id) } },
                    fieldBg = fieldBg,
                )
                StageBlock(
                    title = "③ 语音合成模型 (TTS)",
                    hint = "把回答读出来 · 独立选择",
                    options = VoiceModelOptions.TTS,
                    selectedId = d.callTtsModel.ifBlank { "edge" },
                    onSelect = { id -> vm.updateDraft { it.copy(callTtsModel = id) } },
                    fieldBg = fieldBg,
                )
            } else {
                // 豆包全双工：端到端 S2S，不经过 ASR / LLM / TTS
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.insetSoft)
                        .border(1.dp, p.insetBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CsLabel("通话音色")
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            SettingsDropdown(
                                options = voices,
                                selected = voices.firstOrNull { it.id == d.duplexVoice } ?: voices.first(),
                                label = { it.label },
                                onSelect = { v -> vm.updateDraft { it.copy(duplexVoice = v.id) } },
                                fill = fieldBg,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CsLabel("通话方言")
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            SettingsDropdown(
                                options = dialects,
                                selected = dialects.firstOrNull { it.id == d.duplexDialect } ?: dialects.first(),
                                label = { it.label },
                                onSelect = { v -> vm.updateDraft { it.copy(duplexDialect = v.id) } },
                                fill = fieldBg,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CsLabel("唱歌能力")
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            SettingsDropdown(
                                options = musicOptions,
                                selected = musicOptions.firstOrNull { it.id == (if (d.duplexMusic) "1" else "0") } ?: musicOptions.first(),
                                label = { it.label },
                                onSelect = { v -> vm.updateDraft { it.copy(duplexMusic = v.id == "1") } },
                                fill = fieldBg,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.inset)
                            .border(1.dp, p.insetBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            "✦ 端到端 S2S 特性说明：",
                            color = p.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "由豆包 Seeduplex 3.0 大模型直出 24kHz 高清语音，完全免去前端 ASR 识别与后端 TTS 朗读模块，" +
                                "支持自然呼吸打断与免按键自由交谈。",
                            color = p.muted,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 三段链路里的一段：标题 + 右侧用途说明 + 全宽下拉 */
@Composable
private fun StageBlock(
    title: String,
    hint: String,
    options: List<VoiceModelOptions.Option>,
    selectedId: String,
    onSelect: (String) -> Unit,
    fieldBg: Color,
) {
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CsLabel(title, Modifier.weight(1f))
        CsHint(hint)
    }
    Spacer(Modifier.height(4.dp))
    SettingsDropdown(
        options = options,
        selected = options.firstOrNull { it.id == selectedId } ?: options.first(),
        label = { it.label },
        onSelect = { onSelect(it.id) },
        fill = fieldBg,
    )
}
