package com.lvyoubao.app.ui.spark

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * 灵动模式「墨玉」配色（v0.9.15 改版）：
 * 青色只出现在状态点、眼睛、主操作三处，其余交给中性与留白；
 * 层次靠 5% 白卡 + 8.5% 描边，不再用大面积发光。
 */
object SparkColors {
    val accent = Color(0xFF2FD8EE)
    val accentDeep = Color(0xFF0B8FA3)
    val accentInk = Color(0xFF04222A)
    val danger = Color(0xFFE5484D)
    val dangerSoft = Color(0xFFFF7A7A)

    val bgTop = Color(0xFF141C22)
    val bgBottom = Color(0xFF070A0C)

    val card = Color(0x0DFFFFFF)       // 5% 白
    val cardStrong = Color(0x14FFFFFF) // 8% 白
    val line = Color(0x16FFFFFF)       // 8.5% 白

    val text = Color(0xFFE6EFF2)
    val sub = Color(0xFF748E99)
    val userBubble = Color(0x292FD8EE) // 青色 16%
}

/** 顶栏图标钮：24×24、7 圆角、5% 白底 + 8.5% 描边（统一三颗，不再用文字按钮） */
@Composable
internal fun SparkIconButton(
    iconRes: Int,
    contentDescription: String,
    scale: Float,
    tint: Color = SparkColors.sub,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size((24 * scale).dp)
            .clip(RoundedCornerShape((7 * scale).dp))
            .background(SparkColors.card)
            .border(1.dp, SparkColors.line, RoundedCornerShape((7 * scale).dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((13 * scale).dp),
        )
    }
}

/** 底部两侧圆钮（相机 / 相册）：29×29 圆、可置灰（通话期间不可点） */
@Composable
internal fun SparkRoundButton(
    iconRes: Int,
    contentDescription: String,
    scale: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size((29 * scale).dp)
            .clip(CircleShape)
            .background(SparkColors.card)
            .border(1.dp, SparkColors.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) SparkColors.sub else SparkColors.sub.copy(alpha = 0.32f),
            modifier = Modifier.size((14 * scale).dp),
        )
    }
}
