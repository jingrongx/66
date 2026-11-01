package com.lvyoubao.app.data.observation

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 感知数据导出单条（CSV 用） */
@Serializable
data class ExportRow(val columns: List<String>)

/**
 * 观测数据本地存储（JSONL 文件，只存本机）。
 * 样本量小（5 分钟一条，一天约 100 条），先不上 Room，Phase 2 再迁移。
 */
class ObservationStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "observation").apply { mkdirs() }
    private val samplesFile = File(dir, "samples.jsonl")
    private val eventsFile = File(dir, "events.jsonl")
    private val mutex = Mutex()

    private val _samples = MutableStateFlow<List<FatigueSample>>(emptyList())
    val samples: StateFlow<List<FatigueSample>> = _samples.asStateFlow()

    private val _events = MutableStateFlow<List<ObservationEvent>>(emptyList())
    val events: StateFlow<List<ObservationEvent>> = _events.asStateFlow()

    init {
        _samples.value = load(samplesFile, MAX_SAMPLES) { json.decodeFromString(it) }
        _events.value = load(eventsFile, MAX_EVENTS) { json.decodeFromString(it) }
    }

    private inline fun <T> load(file: File, limit: Int, parse: (String) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { parse(line) }.getOrNull()
            }
        }.getOrDefault(emptyList()).takeLast(limit)
    }

    suspend fun appendSample(sample: FatigueSample) = mutex.withLock {
        _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
        samplesFile.appendText(json.encodeToString(sample) + "\n")
    }

    suspend fun appendEvent(event: ObservationEvent) = mutex.withLock {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        eventsFile.appendText(json.encodeToString(event) + "\n")
    }

    /** 导出 CSV 到 cache 目录，返回文件列表（样本 + 事件） */
    fun exportCsv(): List<File> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(contextDir(), "exports").apply { mkdirs() }
        val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val samplesCsv = File(outDir, "lvyoubao_samples_$stamp.csv")
        samplesCsv.writeText("time,steps,walking_min,distance_m,climb_m,score,band\n")
        _samples.value.forEach { s ->
            samplesCsv.appendText(
                listOf(
                    tsFormat.format(Date(s.ts)), s.steps, s.walkingMinutes,
                    fmt(s.distanceMeters), fmt(s.climbMeters), s.score, s.band,
                ).joinToString(",") + "\n"
            )
        }

        val eventsCsv = File(outDir, "lvyoubao_events_$stamp.csv")
        eventsCsv.writeText("time,type,detail,score\n")
        _events.value.forEach { e ->
            eventsCsv.appendText(
                listOf(
                    tsFormat.format(Date(e.ts)), e.type,
                    "\"${e.detail.replace("\"", "\"\"")}\"", e.score.toString(),
                ).joinToString(",") + "\n"
            )
        }
        return listOf(samplesCsv, eventsCsv)
    }

    private fun contextDir(): File = samplesFile.parentFile?.parentFile ?: File(".")

    private fun fmt(v: Float): String = String.format(Locale.US, "%.0f", v)

    private companion object {
        const val MAX_SAMPLES = 3000
        const val MAX_EVENTS = 800
    }
}
