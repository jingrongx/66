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
    /** 记住最后一次的启动参数：录音/识别失败后能主动恢复待机（否则 LaunchedEffect 依赖没变不会再启） */
    private var lastContext: android.content.Context? = null
    private var lastOnWake: (() -> Unit)? = null
    private var ttsJob: Job? = null
    private var echoJob: Job? = null
    private var lastWakeAt = 0L

    fun standbyActive(): Boolean = standby != null
    fun oneShotActive(): Boolean = oneShot != null

    /** 待机唤醒监听：KWS 命中「小源小源」回调 onWake（1.5 秒去重） */
    fun startStandby(context: Context, onWake: () -> Unit) {
        stopStandby()
        this.onWake = onWake
        lastContext = context.applicationContext
        lastOnWake = onWake
        val ctx = context.applicationContext
        // v0.9.68：命中回调必须捕获稳定引用，不能再延迟读 this@WakeHub.onWake 字段。
        // 旧写法下 stopStandby() 把 onWake 清空后，KWS 线程仍在跑（stopStandby 不停
        // LocalKws），命中时 onWake 已为 null → 空转，用户喊醒没反应但 KWS 照常打
        // 「触发唤醒」日志。捕获 [wakeCb] 后，即使待机被停这一瞬间的命中也能正确分发。
        val wakeCb = onWake
        LocalKws.start(ctx) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWakeAt < 1_500L) {
                LogStore.log("kws", "本地命中但 1.5 秒内已唤醒过，忽略")
                return@start
            }
            lastWakeAt = now
            LogStore.log("kws", "命中回调分发（捕获稳定引用）")
            scope.launch { wakeCb() }
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
     * 录音/识别启动失败后恢复待机唤醒（v0.9.68）。
     * 背景：按住说话前会 stopStandby() 让麦，成功后靠 recording 状态变化触发
     * LaunchedEffect 重新 startStandby；但失败时 recording 没变，LaunchedEffect 不重跑，
     * 待机监听就永久停了——KWS 线程还在跑、喊唤醒词也命中，但 onWake 已是 null，
     * 用户看到「喊了没反应」。失败路径显式调本方法即可恢复。
     */
    fun resumeStandby() {
        val ctx = lastContext
        val cb = lastOnWake
        if (ctx == null || cb == null) {
            LogStore.log("wake", "resumeStandby 跳过：无上次启动参数")
            return
        }
        if (standby != null) return // 已经在待机，别重复启
        LogStore.log("wake", "resumeStandby：录音失败后恢复待机监听")
        startStandby(ctx, cb)
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
