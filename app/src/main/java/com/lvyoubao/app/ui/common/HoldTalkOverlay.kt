package com.lvyoubao.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvyoubao.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 全应用统一的「按住说话 + 上滑取消」手势修饰器。
 *
 * - 按下瞬间触发震动回馈并回调 [onBegin]；
 * - 持续追踪同一根手指的垂直位移，向上滑出 [cancelThreshold]（默认 88dp）即切换取消态并震动提醒，
 *   滑回热区可无缝恢复正常发送态；
 * - 手指抬起按当前是否取消分流执行 [onRelease] 或 [onCancel]；异常打断手势同样走 [onCancel] 绝不吊死录音。
 */
@Composable
fun rememberHoldTalkGestureModifier(
    key: Any = Unit,
    cancelThreshold: Dp = 88.dp,
    onBegin: () -> Unit,
    onCancelStateChange: (Boolean) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnBegin by rememberUpdatedState(onBegin)
    val currentOnCancelState by rememberUpdatedState(onCancelStateChange)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnCancel by rememberUpdatedState(onCancel)

    return Modifier.pointerInput(key, cancelThreshold) {
        val thresholdPx = with(density) { cancelThreshold.toPx() }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var inCancelZone = false
            var handled = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnBegin()
            val startY = down.position.y
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                    if (change == null || !change.pressed) {
                        break
                    }
                    change.consume()
                    val dragUpPx = startY - change.position.y
                    val shouldCancel = dragUpPx >= thresholdPx
                    if (shouldCancel != inCancelZone) {
                        inCancelZone = shouldCancel
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnCancelState(shouldCancel)
                    }
                }
                handled = true
                if (inCancelZone) {
                    currentOnCancel()
                } else {
                    currentOnRelease()
                }
            } finally {
                // 系统来电/弹窗强制打断触摸事件时，确保安全清理麦克风与全屏动效
                if (!handled) {
                    currentOnCancel()
                }
            }
        }
    }
}

/**
 * 全应用统一的「按住说话」全屏动态声波与上滑取消浮层（普通 / 大字 / 经典通话共用）。
 *
 * 视觉层级：
 * - 不消费触摸事件，让原本按钮上的手势流水线零中断追踪至松手；
 * - 中上区：实时真实声压（`state.amplitude`）驱动的 15 柱声纹波动气泡 + 大号时长计时；
 * - 中下区：上滑取消大圆目标区（滑入即放大亮红提示「松手取消」）；
 * - 最底部：品牌青发光半圆拱形发送区（「松手 记下来 / 直接发送」）。
 */
@Composable
fun HoldTalkFullScreenOverlay(
    state: HoldTalkController.State,
    releaseLabel: String = "记下来",
) {
    AnimatedVisibility(
        visible = state.pressing,
        enter = fadeIn(animationSpec = tween(140)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        val cancelling = state.cancelling
        val smoothAmp by animateFloatAsState(
            targetValue = state.amplitude.coerceIn(0.05f, 1f),
            animationSpec = tween(durationMillis = 80),
            label = "voiceAmp",
        )
        val bubbleBg by animateColorAsState(
            targetValue = if (cancelling) Color(0xFFD63232) else Color(0xFF0B7285),
            animationSpec = tween(160),
            label = "bubbleBg",
        )
        val bubbleBgEnd by animateColorAsState(
            targetValue = if (cancelling) Color(0xFFB71C1C) else Color(0xFF0E96AD),
            animationSpec = tween(160),
            label = "bubbleBgEnd",
        )
        val cancelScale by animateFloatAsState(
            targetValue = if (cancelling) 1.24f else 1f,
            animationSpec = tween(160),
            label = "cancelScale",
        )
        val infiniteTransition = rememberInfiniteTransition(label = "wavePhase")
        val wavePhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(950, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wavePhaseAngle",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xD80A141A),
                            Color(0xEC071015),
                            Color(0xF6040A0D),
                        ),
                    ),
                ),
        ) {
            // 中央核心区：声压呼吸圈 + 动态声纹气泡 + 清楚的状态与计时文字
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 128.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 外围呼应真实麦克风音量的软呼吸光圈
                    val haloRadiusBoost = if (cancelling) 0f else smoothAmp * 26f
                    Canvas(modifier = Modifier.size(210.dp)) {
                        val baseR = size.minDimension * 0.36f + haloRadiusBoost
                        drawCircle(
                            color = (if (cancelling) Color(0xFFFF5252) else Color(0xFF40C4E0))
                                .copy(alpha = if (cancelling) 0.16f else 0.14f + smoothAmp * 0.18f),
                            radius = baseR,
                        )
                        drawCircle(
                            color = (if (cancelling) Color(0xFFFF5252) else Color(0xFF5AD0E8))
                                .copy(alpha = if (cancelling) 0.25f else 0.22f + smoothAmp * 0.2f),
                            radius = baseR * 0.84f,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }

                    // 中心声纹胶囊卡片
                    Box(
                        modifier = Modifier
                            .width(240.dp)
                            .height(112.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(Brush.linearGradient(listOf(bubbleBg, bubbleBgEnd))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 24.dp),
                        ) {
                            val barCount = 15
                            val step = size.width / barCount
                            val barWidth = (step * 0.52f).coerceAtLeast(3.dp.toPx())
                            val midY = size.height / 2f
                            val maxHalf = size.height * 0.45f
                            val minHalf = 4.dp.toPx()

                            for (i in 0 until barCount) {
                                // 中央高、向边缘渐低的对称山峰包络
                                val normPos = abs(i - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
                                val envelope = 1f - normPos * normPos * 0.72f
                                val motion = 0.55f + 0.45f * sin(wavePhase + i * 0.75f)
                                val energy = if (cancelling) 0.18f else smoothAmp
                                val halfH = (minHalf + (maxHalf - minHalf) * energy * envelope * motion)
                                    .coerceIn(minHalf, maxHalf)
                                val x = i * step + (step - barWidth) / 2f
                                drawRoundRect(
                                    color = Color.White.copy(alpha = if (cancelling) 0.72f else 0.96f),
                                    topLeft = Offset(x, midY - halfH),
                                    size = Size(barWidth, halfH * 2f),
                                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = when {
                        cancelling -> "松开手指，取消说话"
                        state.recordTick > 0 -> "正在听您说 · ${state.recordTick}秒"
                        else -> "正在听您说…"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (cancelling) Color(0xFFFF8A80) else Color.White,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = if (cancelling) "本次声音不会被发送或记录" else "松手立即$releaseLabel · 向上滑可取消",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }

            // 底部视觉指引台：上方取消圆钮 + 最底下发光发射圆弧
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 上滑取消圆形目标区
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(cancelScale),
                    shape = CircleShape,
                    color = if (cancelling) Color(0xFFE53935) else Color(0xFF2B3942),
                    border = BorderStroke(
                        width = if (cancelling) 2.5.dp else 1.5.dp,
                        color = if (cancelling) Color(0xFFFFCDD2) else Color.White.copy(alpha = 0.28f),
                    ),
                    shadowElevation = if (cancelling) 10.dp else 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = if (cancelling) "松手取消" else "上滑取消",
                    fontSize = 16.sp,
                    fontWeight = if (cancelling) FontWeight.Bold else FontWeight.Medium,
                    color = if (cancelling) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.72f),
                )

                Spacer(Modifier.height(18.dp))

                // 最底部发光弧形说话区（微信级向上凸圆拱美学）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val arcFillTop by animateColorAsState(
                        targetValue = if (cancelling) Color(0xFF1A252C) else Color(0xFF0E6E80),
                        animationSpec = tween(160),
                        label = "arcTop",
                    )
                    val arcFillBottom by animateColorAsState(
                        targetValue = if (cancelling) Color(0xFF10181D) else Color(0xFF094B57),
                        animationSpec = tween(160),
                        label = "arcBottom",
                    )
                    val arcStroke by animateColorAsState(
                        targetValue = if (cancelling) Color(0xFF394B56) else Color(0xFF5AD0E8),
                        animationSpec = tween(160),
                        label = "arcStroke",
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val archDrop = 28.dp.toPx()
                        val path = Path().apply {
                            moveTo(-10f, archDrop)
                            quadraticTo(w / 2f, -archDrop * 0.75f, w + 10f, archDrop)
                            lineTo(w + 10f, h + 10f)
                            lineTo(-10f, h + 10f)
                            close()
                        }
                        drawPath(
                            path = path,
                            brush = Brush.verticalGradient(listOf(arcFillTop, arcFillBottom)),
                        )
                        val strokePath = Path().apply {
                            moveTo(-10f, archDrop)
                            quadraticTo(w / 2f, -archDrop * 0.75f, w + 10f, archDrop)
                        }
                        drawPath(
                            path = strokePath,
                            color = arcStroke,
                            style = Stroke(width = if (cancelling) 1.5.dp.toPx() else 2.5.dp.toPx()),
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spark_mic),
                            contentDescription = null,
                            tint = if (cancelling) Color.White.copy(alpha = 0.45f) else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (cancelling) "取消本次输入" else "松手 $releaseLabel",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (cancelling) Color.White.copy(alpha = 0.45f) else Color.White,
                        )
                    }
                }
            }
        }
    }
}
