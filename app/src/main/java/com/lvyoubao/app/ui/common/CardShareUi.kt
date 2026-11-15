package com.lvyoubao.app.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.lvyoubao.app.data.SessionMessage
import com.lvyoubao.app.ui.ask.CardSharer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 分享结果的大字提醒内容：big 主文案一行放大，small 可选副行（如保存路径） */
class CardShareTip(val big: String, val small: String? = null)

/** 卡片分享状态：open() 弹出选择框；share(true→微信 / false→相册)；弹窗与大字提醒的显隐各自驱动 */
class CardShareState internal constructor(
    val share: (Boolean) -> Unit,
    private val visibleState: MutableState<Boolean>,
    private val tipState: MutableState<CardShareTip?>,
) {
    val visible: Boolean get() = visibleState.value
    val tip: CardShareTip? get() = tipState.value

    fun open() {
        visibleState.value = true
    }

    fun close() {
        visibleState.value = false
    }

    fun dismissTip() {
        tipState.value = null
    }
}

/**
 * 回答卡「分享」共用逻辑（普通/大字模式都从这拿状态）：
 * 生成卡片图（表格、图表原样收录）、微信/相册两条路、旧系统存储权限、结果提示，全在这里收口。
 * 结果提示：保存成功/失败用大字提醒卡（toast 对老人太小），「已打开系统分享」保持 toast——
 * 系统分享面板会盖在应用上面，应用内的提醒卡看不见。
 */
@Composable
fun rememberCardShareState(message: SessionMessage): CardShareState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val visible = remember { mutableStateOf(false) }
    val tip = remember { mutableStateOf<CardShareTip?>(null) }
    val sharing = remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val doShare: (Boolean) -> Unit = { toWeChat ->
        if (!sharing.value) {
            sharing.value = true
            scope.launch {
                var panelToast = ""
                val result = runCatching {
                    val bitmap = CardSharer.buildCardBitmap(context, message, accent)
                    if (toWeChat) {
                        // 返回 false = 没直接拉起微信（鸿蒙卓易通容器 / 未装微信），
                        // 此时已自动打开系统分享面板，可面板里选微信——用 toast 提示，提醒卡会被面板盖住
                        if (CardSharer.shareToWeChat(context, bitmap)) {
                            null
                        } else {
                            panelToast = "已打开系统分享，可在面板里选微信"
                            null
                        }
                    } else {
                        CardSharer.saveToGallery(context, bitmap)
                            ?.let { CardShareTip("✅ 已保存到相册", "Pictures/旅游宝/$it") }
                            ?: CardShareTip("❌ 保存失败，请重试")
                    }
                }.getOrElse { CardShareTip("❌ 生成卡片图失败，请重试") }
                sharing.value = false
                if (panelToast.isNotEmpty()) toast(panelToast) else tip.value = result
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

    return remember(message.ts) { CardShareState(share, visible, tip) }
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

/** 大字结果提醒：居中大卡片，约 2.6 秒自动消失，点一下立即关（配合 [rememberCardShareState] 挂在卡片尾部） */
@Composable
fun CardShareTipDialog(state: CardShareState) {
    val tip = state.tip ?: return
    Dialog(
        onDismissRequest = { state.dismissTip() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.dismissTip() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tip.big,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    tip.small?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(tip) {
        delay(2600)
        state.dismissTip()
    }
}
