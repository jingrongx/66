package com.lvyoubao.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight

/**
 * 设置页视觉令牌：与 .workbuddy/tmp/settings-design.html 逐项对应。
 * 夜间 = 设计稿原色（slate-950/900/800 + cyan-600）；日间 = 同一套版式的浅色反相，
 * 结构、间距、字号、控件形状完全一致，只换配色。
 */
@Immutable
data class SettingsPalette(
    val page: Color,            // 页面底色（设计稿 bg-slate-950）
    val topBar: Color,          // 顶部导航（slate-950/90）
    val card: Color,            // 卡片（slate-900/90）
    val cardBorder: Color,      // 卡片描边（slate-800）
    val inset: Color,           // 内嵌底（slate-950）
    val insetBorder: Color,     // 内嵌描边（slate-800）
    val insetSoft: Color,       // 更浅一层内嵌（slate-950/70）
    val textPrimary: Color,     // slate-100
    val textSecondary: Color,   // slate-200
    val textTertiary: Color,    // slate-300
    val muted: Color,           // slate-400
    val dim: Color,             // slate-500
    val faint: Color,           // slate-600
    val accent: Color,          // cyan-400 文字
    val accentStrong: Color,    // cyan-600 按钮底
    val accentOn: Color,        // 按钮上的字
    val accentSoft: Color,      // cyan-950 底（导出日志按钮）
    val accentSoftBorder: Color,
    val accentSoftText: Color,  // cyan-300
    val danger: Color,          // red-400
    val divider: Color,         // card 内部横线
    val radioOff: Color,        // 未选中的小方块描边
    val menu: Color,            // 下拉浮层底
    val menuBorder: Color,
    val menuSelected: Color,
    val shadow: Color,
) {
    val isDark: Boolean get() = page.luminance() < 0.5f
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

private val DarkPalette = SettingsPalette(
    page = Color(0xFF020617),
    topBar = Color(0xFF020617),
    card = Color(0xFF0F172A),
    cardBorder = Color(0xFF1E293B),
    inset = Color(0xFF020617),
    insetBorder = Color(0xFF1E293B),
    insetSoft = Color(0xFF0B1220),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFFE2E8F0),
    textTertiary = Color(0xFFCBD5E1),
    muted = Color(0xFF94A3B8),
    dim = Color(0xFF64748B),
    faint = Color(0xFF475569),
    accent = Color(0xFF22D3EE),
    accentStrong = Color(0xFF0891B2),
    accentOn = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF083344),
    accentSoftBorder = Color(0xFF155E75),
    accentSoftText = Color(0xFF67E8F9),
    danger = Color(0xFFF87171),
    divider = Color(0xFF1E293B),
    radioOff = Color(0xFF475569),
    menu = Color(0xFF0F172A),
    menuBorder = Color(0xFF1E293B),
    menuSelected = Color(0xFF083344),
    shadow = Color(0x66000000),
)

private val LightPalette = SettingsPalette(
    page = Color(0xFFF1F5F9),
    topBar = Color(0xFFF8FAFC),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    inset = Color(0xFFF1F5F9),
    insetBorder = Color(0xFFE2E8F0),
    insetSoft = Color(0xFFF8FAFC),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF1E293B),
    textTertiary = Color(0xFF334155),
    muted = Color(0xFF64748B),
    dim = Color(0xFF94A3B8),
    faint = Color(0xFFCBD5E1),
    accent = Color(0xFF0E7490),
    accentStrong = Color(0xFF0891B2),
    accentOn = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFECFEFF),
    accentSoftBorder = Color(0xFFA5F3FC),
    accentSoftText = Color(0xFF0E7490),
    danger = Color(0xFFDC2626),
    divider = Color(0xFFE2E8F0),
    radioOff = Color(0xFF94A3B8),
    menu = Color(0xFFFFFFFF),
    menuBorder = Color(0xFFE2E8F0),
    menuSelected = Color(0xFFECFEFF),
    shadow = Color(0x14000000),
)

val LocalSettingsPalette = compositionLocalOf { DarkPalette }

enum class SectionAccent { Purple, Amber, Blue, Teal, Emerald, Indigo, Orange, Fuchsia, Rose }

@Composable
fun sectionBarColor(accent: SectionAccent): Color {
    val p = LocalSettingsPalette.current
    return when (accent) {
        SectionAccent.Purple -> Color(0xFFA855F7)
        SectionAccent.Amber -> if (p.isDark) Color(0xFFF59E0B) else Color(0xFFD97706)
        SectionAccent.Blue -> if (p.isDark) Color(0xFF3B82F6) else Color(0xFF2563EB)
        SectionAccent.Teal -> if (p.isDark) Color(0xFF14B8A6) else Color(0xFF0D9488)
        SectionAccent.Emerald -> if (p.isDark) Color(0xFF10B981) else Color(0xFF059669)
        SectionAccent.Indigo -> if (p.isDark) Color(0xFF6366F1) else Color(0xFF4F46E5)
        SectionAccent.Orange -> if (p.isDark) Color(0xFFF97316) else Color(0xFFEA580C)
        SectionAccent.Fuchsia -> if (p.isDark) Color(0xFFD946EF) else Color(0xFFC026D3)
        SectionAccent.Rose -> if (p.isDark) Color(0xFFF43F5E) else Color(0xFFE11D48)
    }
}

fun settingsPalette(dark: Boolean): SettingsPalette = if (dark) DarkPalette else LightPalette

@Composable
fun ProvideSettingsPalette(dark: Boolean, content: @Composable () -> Unit) {
    val p = settingsPalette(dark)
    // 弹窗、单选、TextButton 走 MaterialTheme，这里按设置页配色重建一套，
    // 否则「浅色主题 App + 深色设置页」会弹出白底白字的对话框
    val scheme = if (dark) {
        androidx.compose.material3.darkColorScheme(
            primary = p.accent,
            onPrimary = Color(0xFF003544),
            surface = p.card,
            onSurface = p.textPrimary,
            surfaceVariant = p.inset,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.cardBorder,
            surfaceContainerLow = p.inset,
            surfaceContainerLowest = p.page,
            background = p.page,
            onBackground = p.textPrimary,
            outline = p.cardBorder,
            error = p.danger,
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = p.accentStrong,
            onPrimary = Color.White,
            surface = p.card,
            onSurface = p.textPrimary,
            surfaceVariant = p.inset,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.cardBorder,
            surfaceContainerLow = p.inset,
            surfaceContainerLowest = p.page,
            background = p.page,
            onBackground = p.textPrimary,
            outline = p.cardBorder,
            error = p.danger,
        )
    }
    CompositionLocalProvider(LocalSettingsPalette provides p) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = scheme,
            typography = androidx.compose.material3.MaterialTheme.typography,
            shapes = androidx.compose.material3.MaterialTheme.shapes,
            content = content,
        )
    }
}

// ---------------------------------------------------------------- 文字档位

/** 卡片内主标签：text-xs font-medium slate-200 */
@Composable
fun CsLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalSettingsPalette.current.textSecondary) {
    Text(text, modifier = modifier, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
}

/** 说明小字：text-[10px] slate-500 */
@Composable
fun CsHint(text: String, modifier: Modifier = Modifier, color: Color = LocalSettingsPalette.current.dim) {
    Text(text, modifier = modifier, color = color, fontSize = 10.sp, lineHeight = 15.sp)
}

/** 段落说明：text-[11px] slate-400 */
@Composable
fun CsNote(text: String, modifier: Modifier = Modifier, color: Color = LocalSettingsPalette.current.muted) {
    Text(text, modifier = modifier, color = color, fontSize = 11.sp, lineHeight = 18.sp)
}

/** 表单字段标题：text-[11px] medium slate-400 */
@Composable
fun CsFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = LocalSettingsPalette.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 15.sp,
    )
}

// ---------------------------------------------------------------- 结构

/**
 * 分区标题：左侧 6×14 彩色竖条 + 12sp slate-400。右侧可挂徽标。
 * 设计稿里「关于」没有竖条，此组件 bar = false 时只出文字。
 */
@Composable
fun SettingsSectionTitle(
    title: String,
    accent: SectionAccent? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    val p = LocalSettingsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(sectionBarColor(accent)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = p.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            badge()
        }
    }
}

/** 徽标：text-[10px] + 圆角胶囊 */
@Composable
fun SettingsBadge(
    text: String,
    fg: Color,
    bg: Color,
    border: Color? = null,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = fg, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 卡片：rounded-2xl + 1px 描边 + shadow-sm */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val p = LocalSettingsPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = if (p.isDark) 0.dp else 1.dp, shape = shape, clip = false)
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.cardBorder, shape)
            .padding(if (padding >= 0) padding.dp else 0.dp),
        content = content,
    )
}

@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    val p = LocalSettingsPalette.current
    Box(modifier.fillMaxWidth().height(1.dp).background(p.divider))
}

/** 卡片内一行：标题 + 副标题 + 右侧开关，整行可点 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val p = LocalSettingsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            CsLabel(title)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                CsHint(subtitle)
            }
        }
        Spacer(Modifier.width(12.dp))
        CsCheckbox(checked)
    }
}

/** 卡片内一行：标题 + 副标题 + 右侧开关，竖向留白更大（设计稿 py-2.5） */
@Composable
fun ToggleRowWide(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        ToggleRow(title, subtitle, checked, onCheckedChange)
    }
}

/** 可点行：左标题、右值（带 ❯） */
@Composable
fun NavRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    valueColor: Color? = null,
    chevron: Boolean = true,
    arrowUp: Boolean = false,
) {
    val p = LocalSettingsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                CsHint(subtitle)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (arrowUp) "$value ↗" else value,
            color = valueColor ?: p.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chevron) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = valueColor ?: p.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 16dp 小方框（设计稿 accent-cyan 复选框）；整行可点，框本身不单独接收点击 */
@Composable
fun CsCheckbox(checked: Boolean, size: Int = 16) {
    val p = LocalSettingsPalette.current
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) p.accentStrong else Color.Transparent)
            .border(
                1.dp,
                if (checked) p.accentStrong else p.radioOff,
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                "✓",
                color = p.accentOn,
                fontSize = (size * 0.62f).sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (size * 0.7f).sp,
            )
        }
    }
}

// ---------------------------------------------------------------- 输入

/** 输入框：bg-slate-950 + 1px 描边 + rounded-xl + px-3 py-2 + 12sp */
@Composable
fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
    mono: Boolean = false,
    password: Boolean = false,
    showPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Int = 38,
    fill: Color? = null,
    stroke: Color? = null,
    centered: Boolean = false,
    textColor: Color? = null,
    bold: Boolean = false,
) {
    val p = LocalSettingsPalette.current
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill ?: p.inset)
            .border(1.dp, stroke ?: p.insetBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    color = p.faint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = textColor ?: p.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    lineHeight = 17.sp,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center
                    else androidx.compose.ui.text.style.TextAlign.Start,
                ),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 输入框内的「显示 / 隐藏」小字按钮 */
@Composable
fun FieldTrailingText(text: String, onClick: () -> Unit) {
    val p = LocalSettingsPalette.current
    Text(
        text,
        color = p.muted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- 下拉

/**
 * 下拉选择：外形与输入框一致（bg-slate-950 / rounded-xl），右侧小箭头。
 * 选项浮层用卡片色 + 描边，选中项高亮。
 */
@Composable
fun <T> SettingsDropdown(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    mono: Boolean = false,
    enabled: Boolean = true,
    itemHint: ((T) -> String?)? = null,
    fill: Color? = null,
    stroke: Color? = null,
    optionEnabled: ((T) -> Boolean)? = null,
) {
    val p = LocalSettingsPalette.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 38.dp)
                .onGloballyPositioned { coords ->
                    val px = coords.size.width
                    if (px > 0) anchorWidth = with(density) { px.toDp() }
                }
                .clip(RoundedCornerShape(12.dp))
                .background(fill ?: p.inset)
                .border(1.dp, stroke ?: p.insetBorder, RoundedCornerShape(12.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let(label) ?: placeholder,
                color = if (selected == null) p.faint else if (enabled) p.textSecondary else p.faint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = p.dim,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .width(if (anchorWidth > 0.dp) anchorWidth else 260.dp)
                .heightIn(max = 360.dp),
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.menu)
                    .border(1.dp, p.menuBorder, RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp),
            ) {
                options.forEach { opt ->
                    val isSelected = opt == selected
                    val optOn = optionEnabled?.invoke(opt) ?: true
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) p.menuSelected else Color.Transparent)
                            .clickable(
                                enabled = optOn,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = p.accent),
                            ) {
                                open = false
                                onSelect(opt)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            label(opt),
                            color = if (!optOn) p.faint else if (isSelected) p.accent else p.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        val hint = itemHint?.invoke(opt)
                        if (!hint.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(hint, color = p.dim, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 药丸按钮组

/**
 * 一排小药丸（延时秒数 / 思考强度 / 打断灵敏度）。
 * 选中：cyan-600 底 + 白字 + cyan-500 描边；未选：slate-700 描边 + slate-400 字。
 */
@Composable
fun <T> PillGroup(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemPaddingH: Int = 10,
    itemPaddingV: Int = 4,
) {
    val p = LocalSettingsPalette.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(if (itemPaddingV >= 6) 8.dp else 6.dp))
                    .background(if (active) p.accentStrong else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) Color(0xFF06B6D4) else if (p.isDark) Color(0xFF334155) else p.cardBorder,
                        RoundedCornerShape(if (itemPaddingV >= 6) 8.dp else 6.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(opt) }
                    .padding(horizontal = itemPaddingH.dp, vertical = itemPaddingV.dp),
            ) {
                Text(
                    label(opt),
                    color = if (active) p.accentOn else p.muted,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 按钮

/** 主按钮：cyan-600 底、白字、rounded-xl、py-2.5 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontBold: Boolean = true,
) {
    val p = LocalSettingsPalette.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) p.accentStrong else p.accentStrong.copy(alpha = 0.5f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = p.accentOn,
            fontSize = 12.sp,
            fontWeight = if (fontBold) FontWeight.SemiBold else FontWeight.Medium,
            lineHeight = 16.sp,
        )
    }
}

/** 次按钮：内嵌底 + slate-700 描边 + slate-200 字 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderColor: Color? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val p = LocalSettingsPalette.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.inset)
            .border(1.dp, borderColor ?: if (p.isDark) Color(0xFF334155) else p.cardBorder, RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Text(
            text,
            color = if (enabled) p.textSecondary else p.faint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        )
    }
}

/** 虚线框「+ 添加」按钮 */
@Composable
fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalSettingsPalette.current
    val dashColor = if (p.isDark) Color(0xFF334155) else p.cardBorder
    val dash = remember(dashColor) {
        androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.inset)
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = dash,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    }
}

/** 六边形分隔（设计稿 space-y-4 的 16dp） */
@Composable
fun SectionGap() = Spacer(Modifier.height(16.dp))
