package com.lvyoubao.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.HotwordCorrector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音纠错记忆管理：查看 / 搜索（显示匹配数）/ 单条与多条删除 / 清空全部。
 * 记录来自纠错飞轮——语音识别稿 vs 用户实际发送稿的差异对，AI 智能纠错据此越改越准。
 */
@Composable
fun VoiceFixScreen(app: TravelApp, onClose: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(HotwordCorrector.learnedFixList(app.applicationContext)) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    val filtered = remember(records, query) {
        val q = query.trim()
        if (q.isEmpty()) records
        else records.filter { it.wrong.contains(q, true) || it.right.contains(q, true) }
    }

    fun refresh() {
        records = HotwordCorrector.learnedFixList(app.applicationContext)
        selected.clear()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.padding(start = 4.dp)) {
                    Text("语音纠错记忆", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "语音稿你手动改对再发送，就会记下差异；AI 纠错据此越改越准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索识别原文或改正结果…") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) { Text("✕", fontSize = 15.sp) }
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            // 计数 + 批量操作行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (query.isBlank()) "共 ${records.size} 条"
                    else "共 ${records.size} 条 · 匹配 ${filtered.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selected.clear()
                        selected.addAll(filtered.map { it.ts })
                    },
                    enabled = filtered.isNotEmpty(),
                ) { Text("全选") }
                TextButton(
                    onClick = { confirmDeleteSelected = true },
                    enabled = selected.isNotEmpty(),
                ) { Text("删除(${selected.size})") }
                TextButton(
                    onClick = { confirmClearAll = true },
                    enabled = records.isNotEmpty(),
                ) { Text("清空") }
            }
            if (records.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有纠正记录。\n语音识别的稿子你手动改对后发送，就会自动记下。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 26.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.ts }) { rec ->
                        val checked = rec.ts in selected
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { if (checked) selected.remove(rec.ts) else selected.add(rec.ts) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { if (it) selected.add(rec.ts) else selected.remove(rec.ts) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "识别稿：" + rec.wrong,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "你发送：" + rec.right,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(rec.ts)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("删除已选记录？") },
            text = { Text("将删除选中的 ${selected.size} 条纠正记录，删除后 AI 纠错不再参考它们。") },
            confirmButton = {
                TextButton(onClick = {
                    selected.toList().forEach { HotwordCorrector.removeLearnedFix(app.applicationContext, it) }
                    refresh()
                    confirmDeleteSelected = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("取消") } },
        )
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部纠正记录？") },
            text = { Text("将删除全部 ${records.size} 条纠正记录。语音纠错的「越用越准」会从头积累。") },
            confirmButton = {
                TextButton(onClick = {
                    HotwordCorrector.clearLearnedFixes(app.applicationContext)
                    refresh()
                    confirmClearAll = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } },
        )
    }
}
