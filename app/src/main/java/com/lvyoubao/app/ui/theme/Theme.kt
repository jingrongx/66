package com.lvyoubao.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
        content = content,
    )
}
