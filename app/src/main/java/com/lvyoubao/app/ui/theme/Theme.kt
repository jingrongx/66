package com.lvyoubao.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 品牌 fallback：青碧（旅行/山水）+ 暖琥珀（行动/提示）
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA5F0E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA8),
    onSecondaryContainer = Color(0xFF271900),
    background = Color(0xFFFCF9F4),
    onBackground = Color(0xFF1B1C1B),
    surface = Color(0xFFFCF9F4),
    onSurface = Color(0xFF1B1C1B),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF474B47),
    outline = Color(0xFF777B76),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF74D7C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF92F4E5),
    secondary = Color(0xFFE7C26D),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4300),
    onSecondaryContainer = Color(0xFFFFDFAB),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE2E3E1),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE2E3E1),
    surfaceVariant = Color(0xFF3E4947),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF89938F),
)

@Composable
fun LvyoubaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
