package com.lvyoubao.app.data

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Log

/**
 * 通话音频路由与焦点公共助手（全双工 [com.lvyoubao.app.data.duplex.DuplexAudioEngine] 与经典通话 [com.lvyoubao.app.ui.common.TtsPlayer] 共用）。
 *
 * 设计要点：
 * 1. API 31+ 优先使用 setCommunicationDevice；仅当目标设备缺失或 setCommunicationDevice 返回 false 时才降级调用 deprecated 的 isSpeakerphoneOn，
 *    避免同时调用导致 Android 12+ 内部 clearCommunicationDevice 互相覆盖。
 * 2. 进入通话通道时主动请求并维护 AudioFocus，保证鸿蒙卓易通等严格音频策略允许路由切换。
 * 3. 在鸿蒙卓易通容器下，anco_audio 会将 USAGE_VOICE_COMMUNICATION / audio_voip.sock 固定路由到听筒且忽略通信切免提请求；
 *    因此卓易通免提外放需切至 MEDIA_SPEAKER_BYPASS（USAGE_MEDIA + MODE_NORMAL），听筒再切回 VOICE_COMMUNICATION。
 */
internal object CallAudioRouter {

    private const val TAG = "CallAudioRouter"

    /** 全双工通话实际输出模式 */
    enum class DuplexOutputMode {
        /** 通话流 + 通信模式（保留硬件 AEC，原生安卓设备免提/听筒及卓易通听筒走此模式） */
        VOICE_COMMUNICATION,

        /** 媒体音乐流 + 正常模式（卓易通容器免提专用：绕开容器 voip 听筒锁死，稳定走扬声器外放） */
        MEDIA_SPEAKER_BYPASS,
    }

    /** 决定全双工通话实际采用的输出通道模式 */
    internal fun resolveDuplexOutputMode(
        isZhuoyi: Boolean,
        targetSpeaker: Boolean,
    ): DuplexOutputMode = if (isZhuoyi && targetSpeaker) {
        DuplexOutputMode.MEDIA_SPEAKER_BYPASS
    } else {
        DuplexOutputMode.VOICE_COMMUNICATION
    }

    /**
     * API 31+ 下判断是否还需要降级调用 deprecated isSpeakerphoneOn：
     * 仅在低版本，或 API 31+ 找不到目标设备 / setCommunicationDevice 明确失败时才降级，防止状态覆盖。
     */
    internal fun shouldUseLegacySpeakerphoneFallback(
        sdkInt: Int,
        targetDeviceFound: Boolean,
        setCommSucceeded: Boolean,
    ): Boolean = sdkInt < Build.VERSION_CODES.S || !targetDeviceFound || !setCommSucceeded

    /** 构造播放 AudioAttributes（通话听筒/通信流 vs 媒体外放流） */
    fun buildPlaybackAttributes(useVoiceCall: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (useVoiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_MEDIA,
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /**
     * 创建流式 PCM16 AudioTrack（兼容 API 21+，统一通过 AudioAttributes 控制流用途）。
     *
     * [sessionId] 用于与上行 AudioRecord 共享音频会话：系统 AEC（AcousticEchoCanceler）附加在
     * 采集会话上，只有播放轨也在同一会话时才能拿到本地播放的参考信号、把扬声器里自己说的话从
     * 上行里抵消掉。默认 AUDIO_SESSION_ID_GENERATE 保持普通一次性播报（如 TTS）的既有行为。
     */
    fun createStreamingAudioTrack(
        useVoiceCall: Boolean,
        sampleRate: Int,
        channelMask: Int,
        bufferSizeInBytes: Int,
        sessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE,
    ): AudioTrack {
        val attrs = buildPlaybackAttributes(useVoiceCall)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(sessionId)
                .build()
        } else {
            AudioTrack(
                attrs,
                format,
                bufferSizeInBytes,
                AudioTrack.MODE_STREAM,
                sessionId,
            )
        }
    }

    /** 查询指定类型列表中的首个音频输出设备（API 23+） */
    fun findOutputDevice(am: AudioManager, vararg targetTypes: Int): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        for (type in targetTypes) {
            outputs.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    /** 查询指定类型列表中的首个通信设备（API 31+） */
    fun findCommunicationDevice(am: AudioManager, vararg targetTypes: Int): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val devices = runCatching { am.availableCommunicationDevices }.getOrNull() ?: return null
        for (type in targetTypes) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    /** 判断当前是否接有外部输出设备（有线耳机 / 蓝牙耳机 / USB 音频等） */
    fun hasExternalAudioOutput(am: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty().any { dev ->
                when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
                    else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                }
            }
        }
        @Suppress("DEPRECATION")
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }

    /** 当前系统是否处于免提扬声器路由 */
    fun isSpeakerOutputActive(am: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commType = runCatching { am.communicationDevice?.type }.getOrNull()
            if (commType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true
            if (commType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return false
        }
        @Suppress("DEPRECATION")
        return runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
    }

    /**
     * 在 MODE_IN_COMMUNICATION 模式下统一切换扬声器 / 听筒 / 外接耳机路由，返回描述字符串便于日志追踪。
     */
    fun applyCommunicationRoute(
        am: AudioManager,
        enableSpeaker: Boolean,
        hasExternal: Boolean,
        userOverrode: Boolean,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 插着耳机且用户未强行切免提时：清掉强制通信设备，让系统优先输出到耳机
            if (!enableSpeaker && hasExternal && !userOverrode) {
                runCatching { am.clearCommunicationDevice() }
                @Suppress("DEPRECATION")
                runCatching { am.isSpeakerphoneOn = false }
                return "clearComm(让位外接耳机)"
            }
            val targetType = if (enableSpeaker) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val targetDev = findCommunicationDevice(am, targetType)
            val setCommOk = if (targetDev != null) {
                runCatching { am.setCommunicationDevice(targetDev) }.getOrDefault(false)
            } else {
                false
            }
            val fallback = shouldUseLegacySpeakerphoneFallback(
                sdkInt = Build.VERSION.SDK_INT,
                targetDeviceFound = targetDev != null,
                setCommSucceeded = setCommOk,
            )
            if (fallback) {
                if (!enableSpeaker && targetDev == null) {
                    runCatching { am.clearCommunicationDevice() }
                }
                @Suppress("DEPRECATION")
                runCatching { am.isSpeakerphoneOn = enableSpeaker }
            }
            return "setComm(dev=${targetDev?.type}, ok=$setCommOk, fallback=$fallback)"
        }
        @Suppress("DEPRECATION")
        runCatching { am.isSpeakerphoneOn = enableSpeaker }
        return "legacySpeaker=$enableSpeaker"
    }

    /** 清理通信路由到默认态（退出通话或切出通信模式时调用） */
    fun clearCommunicationRoute(am: AudioManager, restoreSpeaker: Boolean? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        }
        @Suppress("DEPRECATION")
        runCatching { am.isSpeakerphoneOn = restoreSpeaker ?: false }
    }

    /** 为 AudioTrack 绑定首选物理输出设备（API 23+），切免提/听筒时直达 AudioFlinger */
    fun applyPreferredDevice(
        track: AudioTrack?,
        am: AudioManager?,
        targetSpeaker: Boolean,
        hasExternal: Boolean,
        userOverrode: Boolean,
    ) {
        if (track == null || am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val dev = resolvePreferredOutputDevice(am, targetSpeaker, hasExternal, userOverrode)
        runCatching { track.preferredDevice = dev }
    }

    /** 为 MediaPlayer 绑定首选物理输出设备（API 28+） */
    fun applyPreferredDevice(
        mp: MediaPlayer?,
        am: AudioManager?,
        targetSpeaker: Boolean,
        hasExternal: Boolean,
        userOverrode: Boolean,
    ) {
        if (mp == null || am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dev = resolvePreferredOutputDevice(am, targetSpeaker, hasExternal, userOverrode)
        runCatching { mp.preferredDevice = dev }
    }

    private fun resolvePreferredOutputDevice(
        am: AudioManager,
        targetSpeaker: Boolean,
        hasExternal: Boolean,
        userOverrode: Boolean,
    ): AudioDeviceInfo? = when {
        hasExternal && !userOverrode -> null
        targetSpeaker -> if (hasExternal) findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) else null
        else -> findOutputDevice(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    /** 音频焦点句柄：持有当前通话 / 媒体会话焦点，切换通道或退出时可靠释放 */
    class AudioFocusSession {
        private var currentAm: AudioManager? = null
        private var focusRequest: Any? = null
        private var currentUseVoiceCall: Boolean? = null
        private val listener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.d(TAG, "音频焦点变化：$change")
        }

        @Synchronized
        fun acquire(am: AudioManager, useVoiceCall: Boolean) {
            if (currentAm === am && currentUseVoiceCall == useVoiceCall) return
            abandon()
            currentAm = am
            currentUseVoiceCall = useVoiceCall
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(buildPlaybackAttributes(useVoiceCall))
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequest = req
                val res = runCatching { am.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                Log.d(TAG, "申请音频焦点(O+): voice=$useVoiceCall res=$res")
            } else {
                val stream = if (useVoiceCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
                @Suppress("DEPRECATION")
                val res = runCatching {
                    am.requestAudioFocus(listener, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                Log.d(TAG, "申请音频焦点(legacy): stream=$stream res=$res")
            }
        }

        @Synchronized
        fun abandon() {
            val am = currentAm ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? AudioFocusRequest)?.let { req ->
                    runCatching { am.abandonAudioFocusRequest(req) }
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { am.abandonAudioFocus(listener) }
            }
            focusRequest = null
            currentAm = null
            currentUseVoiceCall = null
        }
    }
}
