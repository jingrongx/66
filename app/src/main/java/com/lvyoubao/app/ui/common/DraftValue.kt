package com.lvyoubao.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 草稿输入框的值托管：文字从外部变进来时（语音转写追加、点建议、清空），
 * 把光标挪到末尾；自己打字、挪光标时原样保留。
 * 之前直接绑 String，外部追加后光标还停在原地，识别出来的字看起来「跑到最前面」。
 */
@Composable
fun rememberDraftValue(text: String): MutableState<TextFieldValue> {
    val value = remember { mutableStateOf(TextFieldValue(text)) }
    LaunchedEffect(text) {
        if (text != value.value.text) {
            value.value = TextFieldValue(text, TextRange(text.length))
        }
    }
    return value
}
