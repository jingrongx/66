package com.lvyoubao.app.data

import android.content.Context
import com.lvyoubao.app.ui.common.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 共享倒计时（data 层单例）：豆包通话 FC 的 set_timer 工具用它，任何模式都能定计时。
 * 到点用 TtsPlayer 语音播报（灵动模式自己的计时 UI 更丰富，仍走本地链不经过这里）。
 */
object CallTimer {

    data class State(val remainSec: Int = 0, val label: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var appCtx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 开始倒计时：重复调用覆盖上一个 */
    fun start(totalSec: Int, label: String) {
        job?.cancel()
        _state.value = State(totalSec, label)
        job = scope.launch {
            var remain = totalSec
            while (remain > 0) {
                delay(1_000)
                remain--
                _state.value = State(remain, label)
            }
            val ctx = appCtx
            val text = if (label.isBlank()) "时间到啦" else "时间到啦，记得$label"
            if (ctx != null) TtsPlayer.speak(ctx, text)
            _state.value = State()
        }
    }

    fun cancel(): Boolean {
        val running = _state.value.remainSec > 0
        job?.cancel()
        _state.value = State()
        return running
    }

    /** App 启动时喂一次 applicationContext，到点才能开口说话 */
    fun attach(context: Context) {
        appCtx = context.applicationContext
    }
}
