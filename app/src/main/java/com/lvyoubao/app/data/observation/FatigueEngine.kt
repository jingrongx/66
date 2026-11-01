package com.lvyoubao.app.data.observation

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 疲劳积分引擎（观测版）：被动信号 → 0–100 积分。
 *
 * 构成（满负荷权重）：
 *   步数   40：本次记录累计步数 / 个人基线（默认 10000 步）
 *   连续步行 25：连续步行时长（停步超过 5 分钟重置；间隔≥10 分钟视为中断）
 *   累计爬升 20：GPS 高程正向累计（>2m 计入，滞回防漂移）
 *   持续时长 15：本次记录总时长（6 小时满）
 *
 * 观测版目标：验证“积分越过橙线（60）的时刻”是否比用户自报“我累了”早 15–30 分钟。
 */
class FatigueEngine(private val baselineSteps: Int = 10_000) {

    data class Snapshot(
        val ts: Long,
        val steps: Int,
        val continuousWalkingMinutes: Int,
        val distanceMeters: Float,
        val climbMeters: Float,
        val elapsedMinutes: Int,
        val score: Int,
        val band: FatigueBand,
        val stepLoad: Int,
        val walkLoad: Int,
        val climbLoad: Int,
        val timeLoad: Int,
    )

    private var startTs = 0L
    private var lastTickTs = 0L

    private var stepBase: Int? = null
    private var steps = 0
    private var stepsAtLastTick = 0

    private var totalWalkingMin = 0f
    private var continuousWalkingMin = 0f
    private var restGrace = REST_GRACE_MIN

    private var distanceM = 0f
    private var climbM = 0f
    private var lastAlt: Double? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasLastPoint = false

    fun start(now: Long) {
        startTs = now
        lastTickTs = now
        stepsAtLastTick = steps
    }

    fun onSteps(cumulativeSteps: Int) {
        val base = stepBase ?: cumulativeSteps.also { stepBase = it }
        steps = (cumulativeSteps - base).coerceAtLeast(0)
    }

    fun onLocation(ts: Long, lat: Double, lon: Double, altitude: Double?, accuracyM: Float) {
        if (accuracyM > ACCURACY_LIMIT_M) return
        if (hasLastPoint) {
            val d = haversine(lastLat, lastLon, lat, lon)
            if (d in 5f..500f) distanceM += d
        }
        altitude?.let { alt ->
            val last = lastAlt
            if (last == null) {
                lastAlt = alt
            } else {
                val delta = alt - last
                when {
                    // 只累计明显上升；小幅波动保持参考点不动，防 GPS 高程漂移
                    delta > 2f -> {
                        climbM += delta.toFloat()
                        lastAlt = alt
                    }
                    // 明显下降时重置参考点，避免漂移持续累计
                    delta < -5f -> lastAlt = alt
                }
            }
        }
        lastLat = lat
        lastLon = lon
        hasLastPoint = true
    }

    fun onTick(now: Long): Snapshot {
        val elapsedMs = (now - lastTickTs).coerceAtLeast(0)
        lastTickTs = now
        val dtMin = (elapsedMs / 60000f).coerceAtMost(MAX_TICK_MIN)

        val deltaSteps = (steps - stepsAtLastTick).coerceAtLeast(0)
        stepsAtLastTick = steps

        if (dtMin >= 10f) {
            // 进程休眠过久，视为中断休息
            continuousWalkingMin = 0f
            restGrace = REST_GRACE_MIN
        } else {
            val pace = if (dtMin > 0f) deltaSteps / dtMin else 0f
            if (pace >= WALKING_PACE_STEPS_PER_MIN) {
                totalWalkingMin += dtMin
                continuousWalkingMin += dtMin
                restGrace = REST_GRACE_MIN
            } else {
                restGrace -= dtMin
                if (restGrace <= 0f) continuousWalkingMin = 0f
            }
        }

        val elapsedMin = ((now - startTs) / 60000f).roundToInt()
        val stepLoad = (40f * min(1f, steps / baselineSteps.toFloat())).roundToInt()
        val walkLoad = (25f * min(1f, continuousWalkingMin / 60f)).roundToInt()
        val climbLoad = (20f * min(1f, climbM / 300f)).roundToInt()
        val timeLoad = (15f * min(1f, elapsedMin / 360f)).roundToInt()
        val score = (stepLoad + walkLoad + climbLoad + timeLoad).coerceIn(0, 100)

        return Snapshot(
            ts = now,
            steps = steps,
            continuousWalkingMinutes = continuousWalkingMin.roundToInt(),
            distanceMeters = distanceM,
            climbMeters = climbM,
            elapsedMinutes = elapsedMin,
            score = score,
            band = FatigueBand.fromScore(score),
            stepLoad = stepLoad,
            walkLoad = walkLoad,
            climbLoad = climbLoad,
            timeLoad = timeLoad,
        )
    }

    companion object {
        private const val WALKING_PACE_STEPS_PER_MIN = 10
        private const val REST_GRACE_MIN = 5f
        private const val MAX_TICK_MIN = 30f
        private const val ACCURACY_LIMIT_M = 50f

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return (2 * r * asin(min(1.0, sqrt(a)))).toFloat()
        }
    }
}
