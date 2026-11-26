package com.lvyoubao.app.ui.common

import android.content.Context
import android.os.SystemClock
import com.lvyoubao.app.data.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 共享唤醒（普通/大字模式用；灵动模式继续用自己的完整链——云端词表+空转惩罚那一套磨了很久，不动）。
 *
 * 待机监听走**端侧 KWS**（sherpa-onnx，词表在 assets，零云配额）：ContinuousConversation 只负责
 * 采集/降噪/喂 KWS，切出来的语音段直接删（不送云端转写），所以电视/噪音随便响都不烧配额。
 * 唤醒后的动作（单次语音 / 直接进豆包通话）由调用方按设置分发。
 */
object WakeHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var standby: ContinuousConversation? = null
    private var oneShot: ContinuousConversation? = null
    private var onWake: (() -> Unit)? = null
    private var ttsJob: Job? = null
    private var echoJob: Job? = null
    private var lastWakeAt = 0L

    fun standbyActive(): Boolean = standby != null
    fun oneShotActive(): Boolean = oneShot != null

    /** 待机唤醒监听：KWS 命中「小源小源」回调 onWake（1.5 秒去重） */
    fun startStandby(context: Context, onWake: () -> Unit) {
        stopStandby()
        this.onWake = onWake
        val ctx = context.applicationContext
        LocalKws.start(ctx) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWakeAt < 1_500L) {
                LogStore.log("kws", "本地命中但 1.5 秒内已唤醒过，忽略")
                return@start
            }
            lastWakeAt = now
            scope.launch { this@WakeHub.onWake?.invoke() }
        }
        val s = ContinuousConversation(
            ctx,
            config = ContinuousConversation.Config(
                silenceEndMs = 380L,
                minSpeechMs = 220L,
                bargeInSensitivity = 0, // 待机不需要打断
                idleTimeoutMs = Long.MAX_VALUE / 2, // 常驻
                maxUtteranceMs = 6_000L,
                peakDropDb = 16f,
                onsetMarginDb = 12f,
                minOnsetDb = -42f,
            ),
            listener = object : ContinuousConversation.Listener {
                // KWS-only：语音段不送云端，切出来就删——待机零配额
                override fun onUtterance(wav: File, snrDb: Float) {
                    wav.delete()
                }

                override fun onBargeIn() {}
                override fun onAutoTimeout() {}
                override fun onError(message: String) {
                    LogStore.log("wake", "待机监听停了：$message")
                }
            },
        )
        // 朗读时停喂 KWS 的例外：正在播的句子不含「小源」时照喂，让用户能喊唤醒词打断播报
        ttsJob?.cancel()
        ttsJob = scope.launch {
            TtsPlayer.state.collect { st -> s.setTtsActive(st is TtsPlayer.State.Speaking) }
        }
        echoJob?.cancel()
        echoJob = scope.launch {
            TtsPlayer.echoRisk.collect { s.setTtsEchoRisk(it) }
        }
        s.start()
        standby = s
        LogStore.log("wake", "普通/大字待机监听启动（端侧 KWS，零配额）")
    }

    fun stopStandby() {
        LogStore.log("wake", "stopStandby 请求让麦（standby=${standby != null}）")
        ttsJob?.cancel()
        ttsJob = null
        echoJob?.cancel()
        echoJob = null
        standby?.stop()
        standby = null
        onWake = null
        LogStore.log("wake", "stopStandby 已返回")
    }

    /**
     * 唤醒后的单次聆听（一句说完自动收段转写）。内部先让出待机的麦克风。
     * onText(null) = 一直没说话超时/出错。
     */
    fun startOneShot(context: Context, asrKey: String, dialect: Boolean, onText: (String?) -> Unit) {
        stopStandby() // 一台设备同一时刻只允许一个采集会话
        cancelOneShot()
        val s = ContinuousConversation(
            context.applicationContext,
            config = ContinuousConversation.Config(
                // 与灵动模式单次聆听同参数：宁多等半秒别切半句，语速自适应，最长 15 秒
                silenceEndMs = 1_300L,
                adaptiveSilence = true,
                minSpeechMs = 300L,
                bargeInSensitivity = 0,
                idleTimeoutMs = 15_000L,
                maxUtteranceMs = 15_000L,
                peakDropDb = 22f,
            ),
            listener = object : ContinuousConversation.Listener {
                override fun onUtterance(wav: File, snrDb: Float) {
                    // 只认近讲：电视/旁人的段信噪比低，删掉继续等
                    if (snrDb < 15f) {
                        wav.delete()
                        return
                    }
                    stopOneShotInternal()
                    scope.launch(Dispatchers.IO) {
                        val text = runCatching {
                            SpeechInput.transcribeFile(wav, asrKey, dialect).getOrNull()
                        }.getOrNull()
                        wav.delete()
                        onText(text?.takeIf { it.isNotBlank() })
                    }
                }

                override fun onBargeIn() {}
                override fun onAutoTimeout() {
                    stopOneShotInternal()
                    onText(null)
                }

                override fun onError(message: String) {
                    stopOneShotInternal()
                    onText(null)
                }
            },
        )
        s.start()
        oneShot = s
    }

    fun cancelOneShot() {
        stopOneShotInternal()
    }

    private fun stopOneShotInternal() {
        oneShot?.stop()
        oneShot = null
    }
}
