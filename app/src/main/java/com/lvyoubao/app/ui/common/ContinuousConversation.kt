package com.lvyoubao.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.lvyoubao.app.data.LogStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 连续对话引擎（方案 B：半双工自动轮转 + 可选真·语音打断 barge-in）：
 * AudioRecord(VOICE_COMMUNICATION + 系统 AEC/NS) → 帧级 RMS 能量 VAD → 静音断句切段 WAV → 回调给调用方。
 *
 * 回声处理（打断效果好坏的关键）：
 * - 音源 VOICE_COMMUNICATION + AcousticEchoCanceler/NoiseSuppressor：免原生库，多数设备能压掉自己喇叭的回声；
 * - TTS 播放期间阈值抬高（灵敏度分档：0=半双工不监听 / 1=标准 / 2=灵敏），且 TTS 开播后 300ms 起振静默窗
 *   内不判打断（喇叭起振爆音免疫）；
 * - 连续 3 帧（240ms）高于打断阈值才算真人开口，杜绝单帧噪声误打断。
 *
 * 断句节奏：说话中静音 800ms 判说完（老人语速慢，停顿不抢断）；最短语音 300ms（低于当噪声丢弃）；
 * 单段上限 30 秒强制切（防忘词录满内存）。320ms 前滚缓冲保证首字不被掐。
 *
 * 线程模型：专用采集线程阻塞读；对外只有回调（在音频线程触发，调用方自行切协程）。
 */
class ContinuousConversation(
    private val context: Context,
    private val listener: Listener,
    private val config: Config = Config(),
) {

    interface Listener {
        /** 刚检测到有人开口（开口后约 160~320ms）：给界面即时反馈，不用等整句识别完 */
        fun onSpeechStart() {}

        /** 一段完整语音（WAV 16k 单声道，调用方负责上传 ASR 并删除文件） */
        /**
         * 一段话录完。snrDb = 本段峰值 - 开口前环境底噪：
         * 单麦没有波束成形，这是「说话人离得多近/多响」最靠谱的代理——
         * 近讲 25~40dB，隔一个房间或电视串音通常 < 12dB。
         */
        fun onUtterance(wav: File, snrDb: Float)

        /** 播报中被真人开口打断（调用方应立刻停 TTS/取消在途回答） */
        fun onBargeIn()

        /** 空闲超时（一直没说话），调用方决定退出连续模式 */
        fun onAutoTimeout()

        /** 麦克风打不开等硬错误，调用方应停止引擎 */
        fun onError(message: String)
    }

    data class Config(
        val silenceEndMs: Long = 800L,      // 静音多久算说完
        val minSpeechMs: Long = 300L,       // 短于此的当噪声丢弃
        val maxUtteranceMs: Long = 30_000L, // 单段强制切断
        /**
         * 段内「还在说吗」的相对判定（dB）：低于本段峰值这么多就算这句说完了；0 = 关闭。
         * 嘈杂环境里噪声底一抬高，绝对门槛就再也压不下去——实测一句 2 秒的话能被拖成 18 秒，
         * 转写跟着慢一个量级。人声通常比环境高 15~25dB，掉到峰值以下 16dB 就是收段时机。
         */
        val peakDropDb: Float = 0f,
        val bargeInSensitivity: Int = 1,    // 0=关（半双工）1=标准 2=灵敏
        val echoWindowMs: Long = 300L,      // TTS 起振静默窗
        val idleTimeoutMs: Long = 60_000L,  // 空闲多久回调超时
        // 待机唤醒用的起振灵敏度：相对底噪的余量越小、绝对阈值越低，越容易开口就触发。
        // 默认值沿用原行为（12dB / -45dBFS）；唤醒监听按设置里的灵敏度档位覆盖。
        val onsetMarginDb: Float = 12f,
        val minOnsetDb: Float = -45f,
    )

    companion object {
        private const val SAMPLE_RATE = 16_000

        // 100Hz 一阶高通系数：R/(R+dt)，R=1/(2π·100)，dt=1/16000
        private const val HP_ALPHA = 0.9622f
        private const val FRAME_SAMPLES = 1_280          // 80ms @ 16kHz
        private const val FRAME_MS = 80L
        private const val PREROLL_FRAMES = 4             // 320ms 前滚，保首字
        private const val ONSET_RUN_NORMAL = 2           // 平时连续 2 帧算开口（160ms）
        private const val ONSET_RUN_TTS = 3              // 播报中连续 3 帧才算真人（240ms，防回声误触发）
        private const val MIN_ABS_ONSET_DB = -45f        // 安静房间的绝对起点
        private const val BARGE_ABS_DB_STD = -35f        // 打断绝对阈值（标准档）
        private const val BARGE_ABS_DB_HOT = -42f        // 打断绝对阈值（灵敏档）
        private const val MAX_READ_ERRORS = 60           // 连续读失败 ~5 秒判麦克风被占
    }

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var ttsActive = false
    @Volatile private var ttsStartedAt = 0L
    @Volatile private var busy = false
    @Volatile private var bargeInFired = false

    /** 一组采集配置：采样率 x 采集源 */
    private data class CaptureCfg(val rate: Int, val source: Int, val label: String)

    /**
     * 采集配置阶梯（v0.9.20）：这台设备的 **16k 通道是废的**——
     * 直采出来全是满量程削顶方波（实测峰值 0dBFS），ASR 只会瞎猜，表现就是「喊破嗓子也唤不醒」。
     * 全双工通话早就改成「48k 采 + 软件降采样」才好；唤醒监听一直是 16k 直采，等于一直喂废数据。
     * 顺序按实测可用性排：48k/voip 最稳，往下逐级降级。
     */
    private val captureLadder = listOf(
        // MIC 源优先：按住说话走的就是 MIC（MediaRecorder 44.1k），实测波形干净、识别准；
        // voip 源在没切 MODE_IN_COMMUNICATION 时增益会被拉满，实测峰值 0dBFS 削顶。
        CaptureCfg(48_000, MediaRecorder.AudioSource.MIC, "48k/mic"),
        CaptureCfg(48_000, MediaRecorder.AudioSource.VOICE_RECOGNITION, "48k/asr"),
        CaptureCfg(16_000, MediaRecorder.AudioSource.MIC, "16k/mic"),
        CaptureCfg(16_000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "16k/voip"),
    )

    @Volatile private var activeRate = SAMPLE_RATE

    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioThread: Thread? = null

    // VAD 状态（仅音频线程访问）
    private var noiseFloorDb = -60f

    /** 当前环境噪声底（dBFS）：只给上层写日志/调参看，别拿它做判活决策 */
    fun debugNoiseFloorDb(): Float = noiseFloorDb
    private var voiceRun = 0
    private var inSpeech = false
    private var speechStartAt = 0L
    private var speechPeakDb = -120f
    private var segmentNoiseDb = -60f

    // 100Hz 一阶高通的状态（去空调/桌面震动/手持低频轰鸣）
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lastVoiceAt = 0L
    private var readErrors = 0
    private val preroll = ArrayDeque<ShortArray>(PREROLL_FRAMES)
    private var segment = ByteArrayOutputStream()
    private var segmentSamples = 0
    private var lastActivityAt = 0L

    /**
     * 启动采集（后台重试，不阻塞调用方）。
     * 音频策略在旧录音刚 release 的交接期，新 AudioRecord 的 startRecording 会不生效
     * （构造成功但停在 STOPPED）——退出应用/停唤醒后马上点连聊最容易撞上。后台重试 5 次
     * （每次重建 AudioRecord，约 1.5 秒窗口）；最终失败才回调一次 onError。
     */
    fun start() {
        if (running) return
        stopRequested = false
        thread(name = "conv-start") {
            var lastReason = "麦克风启动失败"
            repeat(5) { attempt ->
                if (stopRequested) return@thread // 重试期间被 stop：放弃启动
                val reason = tryStartOnce()
                if (reason == null) return@thread // 成功：tryStartOnce 内已进入采集态并起采集线程
                lastReason = reason
                if (attempt < 4) Thread.sleep(300)
            }
            if (stopRequested) return@thread
            listener.onError(lastReason)
        }
    }

    /** 尝试创建 AudioRecord 并进入采集态；成功返回 null（已起采集线程），失败返回原因（已自清理） */
    @SuppressLint("MissingPermission")
    private fun tryStartOnce(): String? {
        var lastError = "这台设备的麦克风参数读不出来，连续对话用不了"
        var opened: AudioRecord? = null
        var openedCfg: CaptureCfg? = null
        for (cfg in captureLadder) {
            val minBuf = AudioRecord.getMinBufferSize(
                cfg.rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                lastError = "麦克风参数读不出来（${cfg.label}）"
                continue
            }
            val rec = try {
                AudioRecord(
                    cfg.source,
                    cfg.rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, cfg.rate / 1000 * 80 * 2 * 4),
                )
            } catch (e: Exception) {
                lastError = "麦克风启动失败：${e.message ?: "被占用"}"
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                lastError = "麦克风被占用或无权限，连续对话开不了"
                continue
            }
            // 必须显式 startRecording：v0.8.90 漏了这一步，AudioRecord 不进入采集态，read 全部失败，
            // 5 秒后误报「麦克风读不到数据」退出（长按说话走 MediaRecorder 是另一条路所以没事）
            runCatching { rec.startRecording() }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                // 上一路会话刚释放时 start() 常回 -38（ENOSYS），等一下重试同一路大概率就成。
                // 不重试的话会白白降级到 16k/voip 那条削顶通道（实测 48k/mic 是唯一干净的一路）。
                Thread.sleep(80)
                runCatching { rec.startRecording() }
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                lastError = "麦克风没能开始采集（${cfg.label} 被占用）"
                LogStore.log("conv", "采集配置 ${cfg.label} 启动失败，降级下一路")
                continue
            }
            opened = rec
            openedCfg = cfg
            break
        }
        val cfg = openedCfg ?: return lastError
        val rec = opened ?: return lastError

        record = rec
        activeRate = cfg.rate
        LogStore.log("conv", "采集配置：${cfg.label}（VAD 在 16k 上跑，${cfg.rate / SAMPLE_RATE} 倍降采样）")
        // 重试期间被 stop：立刻清理放弃（快速开关连聊的竞态）
        if (stopRequested) {
            rec.release()
            return "已取消启动"
        }
        // 系统 AEC/NS：压掉自己喇叭的回声与稳态噪声，打断体验的底线（拿不到就靠动态阈值兜底）
        // 系统 AEC/NS：以前只 create 没 setEnabled，效果器一直是关的（等于白建）。
        // 现在真正打开并回读状态——这台 BSP 支不支持，日志里一眼能看到。
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
        // 回声消除只在「允许打断」的会话里开：单麦设备的 AEC 没有干净的参考信号，
        // 常把近讲人声也当回声压掉；待机唤醒用不到打断，宁可不冒这个险。
        if (config.bargeInSensitivity > 0) runCatching { echoCanceler?.enabled = true }
        runCatching { noiseSuppressor?.enabled = true }
        LogStore.log(
            "conv",
            "系统音效：降噪 ${if (noiseSuppressor?.enabled == true) "开" else "不可用"}，" +
                "回声消除 ${if (echoCanceler?.enabled == true) "开" else "不可用"}",
        )
        noiseFloorDb = -60f
        voiceRun = 0
        inSpeech = false
        readErrors = 0
        bargeInFired = false
        lastActivityAt = SystemClock.elapsedRealtime()
        running = true
        audioThread = thread(name = "conv-audio") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop()
        }
        return null
    }

    fun stop() {
        stopRequested = true
        if (!running) return
        running = false
        audioThread?.let { t ->
            runCatching { t.join(800) }
        }
        audioThread = null
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    /** TTS 开始/结束播报（播报中：半双工档丢弃收音，其余档用打断阈值监听） */
    fun setTtsActive(active: Boolean) {
        if (active) {
            ttsStartedAt = SystemClock.elapsedRealtime()
            bargeInFired = false
        }
        ttsActive = active
    }

    /** 调用方正忙着处理（转写/AI/编排）：暂停空闲计时 */
    fun setBusy(value: Boolean) {
        busy = value
        if (value) lastActivityAt = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val factor = (activeRate / SAMPLE_RATE).coerceAtLeast(1)
        val raw = ShortArray(FRAME_SAMPLES * factor)
        while (running) {
            // AudioRecord 常短读：补齐一整帧（80ms）再判活，帧长稳定 VAD 才准
            var filled = 0
            while (filled < raw.size && running) {
                val rec = record ?: return
                val n = try {
                    rec.read(raw, filled, raw.size - filled)
                } catch (e: Exception) {
                    return
                }
                if (n <= 0) break
                filled += n
            }
            if (filled < raw.size) {
                if (++readErrors > MAX_READ_ERRORS) {
                    listener.onError("麦克风连续读不到数据（可能被其他应用占用）")
                    running = false
                    break
                }
                continue
            }
            readErrors = 0
            processFrame(if (factor == 1) raw else downsampleTo16k(raw, factor), FRAME_SAMPLES)
        }
    }

    /** 48k 采集降采样到 16k（factor 点均值）：VAD 与 WAV 都还按 16k 走，上游不用改 */
    private fun downsampleTo16k(src: ShortArray, factor: Int): ShortArray {
        val outSamples = src.size / factor
        val out = ShortArray(outSamples)
        var i = 0
        var o = 0
        while (i + factor <= src.size) {
            var sum = 0
            var k = 0
            while (k < factor) {
                sum += src[i + k]
                k++
            }
            out[o] = (sum / factor).coerceIn(-32768, 32767).toShort()
            o++
            i += factor
        }
        return out
    }

    /**
     * 100Hz 一阶高通：低音炮/空调/桌面共振占了噪声能量的一大半，却一点人声信息都没有。
     * 滤掉之后底噪能降几 dB，VAD 的相对判据才有东西可比。
     */
    private fun highPass(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val x = buf[i].toFloat()
            val y = HP_ALPHA * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = y
            buf[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun processFrame(buf: ShortArray, n: Int) {
        val now = SystemClock.elapsedRealtime()
        highPass(buf, n)
        val frame = buf.copyOf(n)
        val db = (20.0 * log10(rmsOf(frame) / 32768.0 + 1e-6)).toFloat()

        // 空闲帧缓慢跟踪噪声底（只在非语音帧更新，说话时不许把底噪拉高）
        if (!inSpeech && db > -90f) {
            noiseFloorDb = noiseFloorDb * 0.95f + db * 0.05f
        }

        // 半双工档：播报期间整帧丢弃（无语音打断，靠点按/按住打断）
        if (ttsActive && config.bargeInSensitivity == 0) {
            pushPreroll(frame)
            return
        }

        val onsetDb = when {
            ttsActive -> if (config.bargeInSensitivity >= 2) {
                maxOf(noiseFloorDb + 12f, BARGE_ABS_DB_HOT)
            } else {
                maxOf(noiseFloorDb + 20f, BARGE_ABS_DB_STD)
            }
            else -> maxOf(noiseFloorDb + config.onsetMarginDb, config.minOnsetDb)
        }
        // 峰值回落：段内电平掉到峰值以下 peakDropDb，即便还高于绝对门槛也认为这句说完了。
        // 不加这条，屋里有人聊天/开着电视时，一段话永远等不到「静音」，只能靠 maxUtteranceMs 硬切。
        val peakSilent = inSpeech && config.peakDropDb > 0f && db < speechPeakDb - config.peakDropDb
        val isVoice = db >= onsetDb && !peakSilent
        val requiredRun = if (ttsActive) ONSET_RUN_TTS else ONSET_RUN_NORMAL

        if (isVoice) {
            lastVoiceAt = now
            lastActivityAt = now
            if (!inSpeech) {
                val echoWindowOpen = ttsActive && now - ttsStartedAt < config.echoWindowMs
                if (!echoWindowOpen) {
                    voiceRun++
                    if (voiceRun >= requiredRun) startSpeech(now, frame)
                }
                // 起振窗内的帧先进前滚缓冲，窗一过若继续说话能带上开头
                if (!inSpeech) pushPreroll(frame)
            } else {
                appendSegment(frame)
            }
            if (db > speechPeakDb) speechPeakDb = db
        } else {
            voiceRun = 0
            if (inSpeech) {
                appendSegment(frame) // 静音帧也录，保留自然尾音
                if (now - lastVoiceAt > config.silenceEndMs) endSpeech(now)
            }
        }

        // 单段上限：说个没完也强制切，防内存涨满
        if (inSpeech && now - speechStartAt > config.maxUtteranceMs) endSpeech(now)

        // 空闲超时：既没在说话也没在放 TTS、调用方也不忙，安静太久交还控制权
        if (!inSpeech && !ttsActive && !busy &&
            now - maxOf(lastActivityAt, lastVoiceAt) > config.idleTimeoutMs
        ) {
            lastActivityAt = now // 只报一次，等调用方 stop()
            listener.onAutoTimeout()
        }
    }

    private fun startSpeech(now: Long, currentFrame: ShortArray) {
        inSpeech = true
        runCatching { listener.onSpeechStart() } // 音频线程直接回调，调用方只做轻量状态更新
        speechStartAt = now
        speechPeakDb = -120f
        segmentNoiseDb = noiseFloorDb
        segment = ByteArrayOutputStream()
        segmentSamples = 0
        preroll.forEach { appendSegmentInternal(it) } // 前滚带上首字
        preroll.clear()
        appendSegmentInternal(currentFrame)
    }

    private fun pushPreroll(frame: ShortArray) {
        preroll.addLast(frame)
        while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
    }

    private fun appendSegment(frame: ShortArray) {
        if (!inSpeech) return
        appendSegmentInternal(frame)
    }

    private fun appendSegmentInternal(frame: ShortArray) {
        val bytes = ByteArray(frame.size * 2)
        frame.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        segment.write(bytes)
        segmentSamples += frame.size
    }

    private fun endSpeech(now: Long) {
        inSpeech = false
        val speechMs = now - speechStartAt
        val data = segment.toByteArray()
        segment = ByteArrayOutputStream()
        segmentSamples = 0
        if (speechMs < config.minSpeechMs) return // 太短当噪声丢弃
        val wav = pcmToWav(data)
        val file = File(context.cacheDir, "conv_${System.currentTimeMillis()}.wav")
        val snrDb = speechPeakDb - segmentNoiseDb
        runCatching {
            FileOutputStream(file).use { it.write(wav) }
            listener.onUtterance(file, snrDb)
        }.onFailure {
            listener.onError("录音保存失败，请重试")
        }
    }

    private fun rmsOf(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size)
    }

    /** 16kHz 单声道 PCM16 → WAV（44 字节头），硅基流动直接收 */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val total = 36 + pcm.size
        val header = ByteArray(44)
        fun le32(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
            header[offset + 2] = (value shr 16 and 0xFF).toByte()
            header[offset + 3] = (value shr 24 and 0xFF).toByte()
        }
        fun le16(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        le32(4, total)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        le32(16, 16)          // fmt 块长度
        le16(20, 1)           // PCM
        le16(22, 1)           // 单声道
        le32(24, sampleRate)
        le32(28, sampleRate * 2) // byteRate
        le16(32, 2)           // blockAlign
        le16(34, 16)          // bitsPerSample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        le32(40, pcm.size)
        return header + pcm
    }
}
