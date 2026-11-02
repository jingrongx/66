package com.lvyoubao.app.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.QuickNote
import com.lvyoubao.app.ui.common.TtsPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 随手记 + 自动游记（个人飞轮的叙事合成，轻量版）：
 * 旅途一句话记录（自动带位置）→ AI 合成第一人称游记 → 可收藏进个人记忆。
 */
@Composable
fun NotesScreen(onClose: () -> Unit, app: TravelApp) {
    val vm: NotesViewModel = viewModel(
        factory = remember { viewModelFactory { initializer { NotesViewModel(app) } } },
    )
    val notes by vm.notes.collectAsState()
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val dayFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
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
            Column {
                Text("随手记", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "一句话记下见闻，攒够了合成一篇游记",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::updateInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("今天遇到什么？一句话就行") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val place = if (LocationContext.hasPermission(context)) {
                        runCatching { LocationContext.current(context)?.description }.getOrNull().orEmpty()
                    } else ""
                    vm.addNote(place)
                },
                enabled = state.input.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("记一笔") }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.generateTravelogue() },
            enabled = notes.isNotEmpty() && !state.generating,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (state.travelogue == null) "把随手记合成一篇游记" else "重新合成游记", fontWeight = FontWeight.Bold)
        }

        if (state.generating) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }

        state.travelogue?.let { travelogue ->
            Spacer(Modifier.height(12.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("我的游记", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        travelogue,
                        fontSize = 18.sp,
                        lineHeight = 27.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { TtsPlayer.speak(context, travelogue) }) { Text("朗读") }
                        TextButton(
                            onClick = { vm.saveTravelogueToMemory() },
                            enabled = !state.travelogueSaved,
                        ) { Text(if (state.travelogueSaved) "已存进我的记忆 ✓" else "存进我的记忆") }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        Spacer(Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (notes.isEmpty()) {
                item {
                    Text(
                        "还没有随手记。看到好吃的、好玩的、累了，随手记一句，回家就有得回忆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(notes.asReversed(), key = { it.ts }) { note ->
                NoteRow(
                    note = note,
                    dayText = dayFormat.format(Date(note.ts)),
                    timeText = timeFormat.format(Date(note.ts)),
                    onDelete = { vm.deleteNote(note.ts) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NoteRow(note: QuickNote, dayText: String, timeText: String, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$dayText $timeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (note.place.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            note.place,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
