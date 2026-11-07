package com.lvyoubao.app.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lvyoubao.app.data.AutoUpdater

/**
 * 「新版本已下载好」弹窗：自动更新流水线把包在后台下完后弹出，点「安装」直接升级，
 * 「稍后」本轮进程内不再打扰（更新页里仍可见、仍可安装）。
 */
@Composable
fun UpdateReadyDialog(state: AutoUpdater.State) {
    val release = state.release ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { AutoUpdater.dismissUpdate() },
        title = { Text("新版本已下载好", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("v${release.versionLabel} 已在后台下载完成，点「安装」立即升级。")
                state.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { AutoUpdater.install(context) }) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = { AutoUpdater.dismissUpdate() }) { Text("稍后") }
        },
    )
}
