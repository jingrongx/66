package com.lvyoubao.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局尺寸令牌：一流界面的关键是全应用只有一套尺寸。
 * - 主按钮（每屏最想让人按的）：高 48dp、字 15sp Bold；长辈模式 56dp / 20sp Bold
 * - 次要按钮/TextButton：默认 40dp、字 14sp；长辈模式 52dp / 17sp
 * - 小图标钮统一 28dp（图标 16dp），不再 24/28/30/32/44 各写各的
 * - 最小辅助字号 12sp（labelSmall 全局抬到 12，不再出现 11sp 小字）
 */
object AppDim {
    val btnPrimaryH = 48.dp
    val btnPrimaryFont = 15.sp
    val btnElderH = 56.dp
    val btnElderFont = 20.sp
    val btnElderRowH = 52.dp
    val btnElderRowFont = 17.sp
    val iconBtnSmall = 28.dp
    val iconSmall = 16.dp
    val capMin = 12.sp
    val chipElderH = 44.dp
}

/** 字阶：在 M3 默认基础上只动小字档——labelSmall 11→12、labelMedium 12→13、bodySmall 12→13 */
private val AppTypography = Typography().let { t ->
    t.copy(
        labelSmall = t.labelSmall.copy(fontSize = 12.sp),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
    )
}

/** 圆角语言统一：medium=14 / large=18，与各屏硬编码的 RoundedCornerShape(14/18) 一派 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// 品牌配色：深海青（主）+ 日出橙（行动/警示）。
// 不使用动态取色：部分国产 ROM 的动态色实现对比度异常（深底深字），固定品牌方案保证可读性。
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7285),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDE9F7),
    onPrimaryContainer = Color(0xFF002029),
    secondary = Color(0xFFD9480F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF380D00),
    tertiary = Color(0xFF0B7A4B),
    background = Color(0xFFF6FAFC),
    onBackground = Color(0xFF10242C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10242C),
    surfaceVariant = Color(0xFFDFEBF0),
    onSurfaceVariant = Color(0xFF3B545E),
    outline = Color(0xFF6E8791),
    error = Color(0xFFC0392B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5AD0E8),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFB8EBFF),
    secondary = Color(0xFFFFB68F),
    onSecondary = Color(0xFF4F1500),
    secondaryContainer = Color(0xFF6E2F00),
    onSecondaryContainer = Color(0xFFFFDBC8),
    tertiary = Color(0xFF6FDCA5),
    background = Color(0xFF0E1A20),
    onBackground = Color(0xFFE4EEF3),
    surface = Color(0xFF142229),
    onSurface = Color(0xFFE4EEF3),
    surfaceVariant = Color(0xFF1F333C),
    onSurfaceVariant = Color(0xFFB2C9D2),
    outline = Color(0xFF62818D),
    error = Color(0xFFFF8A80),
)

@Composable
fun LvyoubaoTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
