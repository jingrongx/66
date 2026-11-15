package com.lvyoubao.app.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.ask.CardSharer
import kotlinx.coroutines.launch

/** 卡片分享状态：open() 弹出选择框；share(true→微信 / false→相册)；弹窗显隐由 [visible] 驱动 */
class CardShareState internal constructor(
    val share: (Boolean) -> Unit,
    private val visibleState: MutableState<Boolean>,
) {
    val visible: Boolean get() = visibleState.value
    fun open() {
        visibleState.value = true
    }

    fun close() {
        visibleState.value = false
    }
}

/**
 * 回答卡「分享」共用逻辑（普通/大字模式都从这拿状态）：
 * 生成卡片图（表格、图表原样收录）、微信/相册两条路、旧系统存储权限、结果提示，全在这里收口。
 */
@Composable
fun rememberCardShareState(message: SessionMessage): CardShareState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val visible = remember { mutableStateOf(false) }
    val sharing = remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val doShare: (Boolean) -> Unit = { toWeChat ->
        if (!sharing.value) {
            sharing.value = true
            scope.launch {
                val tip = runCatching {
                    val bitmap = CardSharer.buildCardBitmap(context, message, accent)
                    if (toWeChat) {
                        // 返回 false = 没直接拉起微信（如鸿蒙卓易通里微信是原生应用，安卓层够不着），
                        // 此时已自动打开系统分享面板，可面板里选微信
                        if (CardSharer.shareToWeChat(context, bitmap)) "" else "已打开系统分享，可在面板里选微信"
                    } else {
                        CardSharer.saveToGallery(context, bitmap)?.let { "已保存到相册：$it" } ?: "保存失败，请重试"
                    }
                }.getOrElse { "生成卡片图失败，请重试" }
                sharing.value = false
                if (tip.isNotEmpty()) toast(tip)
            }
        }
    }

    // Android 9 及以下存相册需要存储权限（10+ 走 MediaStore 免授权）
    val requestStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doShare(false) else toast("需要存储权限才能保存到相册")
    }

    val share: (Boolean) -> Unit = { toWeChat ->
        visible.value = false
        if (toWeChat) {
            doShare(true)
        } else {
            val needLegacy = Build.VERSION.SDK_INT in 23..28 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needLegacy) {
                requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                doShare(false)
            }
        }
    }

    return remember(message.ts) { CardShareState(share, visible) }
}

/** 分享弹窗：配合 [rememberCardShareState] 用，放在卡片 Composable 尾部即可 */
@Composable
fun CardShareDialog(state: CardShareState) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = { state.close() },
        title = { Text("分享卡片") },
        text = { Text("把这条回答生成为一张图片，表格、图表原样收录") },
        confirmButton = {
            Button(onClick = { state.share(true) }) { Text("分享到微信") }
        },
        dismissButton = {
            OutlinedButton(onClick = { state.share(false) }) { Text("保存到相册") }
        },
    )
}
