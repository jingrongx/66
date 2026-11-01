package com.lvyoubao.app.ui.observation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.observation.FatigueBand
import com.lvyoubao.app.data.observation.ObservationEvent
import com.lvyoubao.app.sensing.FatigueSensingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObservationScreen(onClose: () -> Unit, app: TravelApp) {
    val vm: ObservationViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { ObservationViewModel(app) } } },
    )
    val state by vm.samples.collectAsState()
    val events by vm.events.collectAsState()
    val running by vm.running.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val message by vm.message.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            vm.startRecording(context)
        } else {
            vm.label(
                ObservationEvent.SYSTEM,
                "定位/通知权限被拒绝：仍可计步，距离与爬升维度缺失",
            )
        }
    }

    val latest = state.lastOrNull()

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
            Text("感知日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "观测版",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        // 记录状态卡
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (running) "记录中" else "未开启",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (running) {
                        Button(onClick = { FatigueSensingService.stop(context) }) { Text("停止") }
                    } else {
                        val permissions = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        Button(onClick = { permissionLauncher.launch(permissions.toTypedArray()) }) {
                            Text("开启记录")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    startedAt?.let {
                        "开始于 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: "开启后，手机放在口袋里正常玩就行，不用管它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (latest != null) {
            Spacer(Modifier.height(16.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "疲劳积分",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        BandDot(FatigueBand.fromScore(latest.score))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${latest.score} 分",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("步数", "${latest.steps}")
                        Metric("连续步行", "${latest.walkingMinutes} 分")
                        Metric("距离", String.format(Locale.getDefault(), "%.1f km", latest.distanceMeters / 1000))
                        Metric("爬升", "${latest.climbMeters.toInt()} m")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("我现在的真实感受（回测的地面真值）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { vm.label(ObservationEvent.LABEL_TIRED, "自报：现在累了") },
                label = { Text("我现在累了") },
            )
            AssistChip(
                onClick = { vm.label(ObservationEvent.LABEL_REST, "自报：坐下休息了") },
                label = { Text("坐下休息了") },
            )
            AssistChip(
                onClick = { if (state.isNotEmpty()) vm.shareCsv(context) },
                label = { Text("导出 CSV") },
            )
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))
        Text("事件时间线", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text(
                "还没有事件。越过黄/橙/红线和你的标记都会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.takeLast(30).reversed().forEach { event ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.ts)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (event.type) {
                                ObservationEvent.THRESHOLD -> "⚠ " + event.detail
                                ObservationEvent.LABEL_TIRED -> "🙋 我累了（积分 ${event.score}）"
                                ObservationEvent.LABEL_REST -> "🪑 休息了（积分 ${event.score}）"
                                else -> event.detail
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "观测版只记录不干预：所有数据只存在本机，可随时导出 CSV 用来回测“积分越线时刻 vs 真实感受”的吻合度。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------- 回测分析：越线时刻 vs 自报“我累了”的提前量 ----------
        val backtest = remember(events) { computeBacktest(events) }
        if (backtest.labels > 0) {
            Spacer(Modifier.height(20.dp))
            Text("回测分析", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "你自报累了 ${backtest.labels} 次，其中 ${backtest.warned} 次在越线后 2 小时内收到过预警（覆盖率 ${backtest.coveragePct}%）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (backtest.warned > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "提前量：平均 ${backtest.avgLeadMin} 分钟 · 最短 ${backtest.minLeadMin} 分钟 · 最长 ${backtest.maxLeadMin} 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "目标：平均提前 15–30 分钟。长期偏低就把 FatigueEngine 的阈值调低或提高权重；频繁误报（覆盖率低）则收紧。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 回测：每次自报“我累了”之前，最近一次越线事件领先了多少分钟（2 小时窗口内算有效预警） */
private fun computeBacktest(events: List<ObservationEvent>): BacktestResult {
    val thresholds = events.filter { it.type == ObservationEvent.THRESHOLD }.sortedBy { it.ts }
    val tiredLabels = events.filter { it.type == ObservationEvent.LABEL_TIRED }
    val leads = tiredLabels.mapNotNull { label ->
        thresholds.lastOrNull { it.ts <= label.ts && label.ts - it.ts <= WARN_WINDOW_MS }
            ?.let { ((label.ts - it.ts) / 60000L).toInt() }
    }
    return BacktestResult(
        labels = tiredLabels.size,
        warned = leads.size,
        coveragePct = if (tiredLabels.isEmpty()) 0 else leads.size * 100 / tiredLabels.size,
        avgLeadMin = if (leads.isEmpty()) 0 else leads.sum() / leads.size,
        minLeadMin = leads.minOrNull() ?: 0,
        maxLeadMin = leads.maxOrNull() ?: 0,
    )
}

private data class BacktestResult(
    val labels: Int,
    val warned: Int,
    val coveragePct: Int,
    val avgLeadMin: Int,
    val minLeadMin: Int,
    val maxLeadMin: Int,
)

private const val WARN_WINDOW_MS = 2 * 60 * 60 * 1000L

@Composable
private fun BandDot(band: FatigueBand) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Color(band.argb)),
    )
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
