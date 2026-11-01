package com.lvyoubao.app.data.observation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

enum class FatigueBand(val label: String, val argb: Long) {
    GREEN("状态良好", 0xFF4CAF50),
    YELLOW("有点累了", 0xFFFFC107),
    AMBER("建议休息", 0xFFFF9800),
    RED("明显透支", 0xFFF44336);

    companion object {
        fun fromScore(score: Int): FatigueBand = when {
            score >= 80 -> RED
            score >= 60 -> AMBER
            score >= 40 -> YELLOW
            else -> GREEN
        }
    }
}

/** 每 5 分钟（或状态变化时）落一条样本 */
@Serializable
data class FatigueSample(
    val ts: Long,
    val steps: Int,
    val walkingMinutes: Int,
    val distanceMeters: Float,
    val climbMeters: Float,
    val score: Int,
    val band: String,
)

/** 事件：阈值越过 / 用户标记真实感受 / 系统异常 */
@Serializable
data class ObservationEvent(
    val ts: Long,
    val type: String,
    val detail: String = "",
    val score: Int = -1,
) {
    companion object {
        const val THRESHOLD = "THRESHOLD"
        const val LABEL_TIRED = "LABEL_TIRED"
        const val LABEL_REST = "LABEL_REST"
        const val SYSTEM = "SYSTEM"
    }
}

object SensingBus {
    val running = MutableStateFlow(false)
    val band = MutableStateFlow(FatigueBand.GREEN)
    val startedAt = MutableStateFlow<Long?>(null)
}
