package com.lvyoubao.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 感知轨迹点存储（JSONL，只存本机）：感知服务定位回调按节流规则落点，
 * 供离线地图页叠加"今天的足迹"。样本量小（移动 ≥15m 或间隔 ≥60s 才记一条）。
 */
class TrackStore(context: Context) {

    @Serializable
    data class TrackPoint(val ts: Long, val lat: Double, val lon: Double)

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(File(context.filesDir, "observation"), "track.jsonl")
    private val mutex = Mutex()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    init {
        _points.value = load()
    }

    private fun load(): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString<TrackPoint>(line) }.getOrNull()
            }
        }.getOrDefault(emptyList()).takeLast(MAX_POINTS)
    }

    /** 追加一个轨迹点（内部已加锁；调用方自行节流） */
    suspend fun append(point: TrackPoint) = mutex.withLock {
        _points.value = (_points.value + point).takeLast(MAX_POINTS)
        runCatching {
            if (!file.exists()) file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(point) + "\n")
        }
    }

    /** 清空轨迹（例如重开一天行程） */
    suspend fun clear() = mutex.withLock {
        _points.value = emptyList()
        runCatching { file.delete() }
    }

    companion object {
        const val MAX_POINTS = 3000
    }
}
