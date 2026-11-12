package com.lvyoubao.app.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行日志（v0.8.52）：朗读链路等关键事件的内存环形缓冲 + 本机文件持久化。
 * 设置页「导出运行日志」一键把日志变成 txt 分享出去——用户不需要 adb，
 * 出问题复现一次、点导出、发文件，每一级的真实行为与失败原因都在里面。
 */
object LogStore {

    private const val MAX_MEMORY_LINES = 800
    private const val MAX_FILE_BYTES = 400_000L

    private val lines = ArrayDeque<String>()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** Application.onCreate 时初始化：日志落在 filesDir/exports（FileProvider 可分享路径） */
    fun init(filesDir: File) {
        val dir = File(filesDir, "exports").apply { mkdirs() }
        logFile = File(dir, "lvyoubao_log.txt")
    }

    fun log(tag: String, msg: String) {
        val line = "${ts.format(Date())} [$tag] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
        }
        println("lvyoubao: $line")
        runCatching { Log.i("lvyoubao.log", "[$tag] $msg") }
        val f = logFile
        if (f != null) synchronized(this) {
            runCatching {
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    f.delete() // 防无限增长：超限从头再记
                }
                f.appendText(line + "\n")
            }
        }
    }

    /** 导出：持久化文件内容（含本次启动前的）+ 内存缓冲，落成带时间戳的 txt 返回 */
    fun export(exportDir: File): File {
        exportDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(exportDir, "旅游宝日志_$stamp.txt")
        val persisted = logFile?.takeIf { it.exists() }?.readText()?.trimEnd().orEmpty()
        val memory = synchronized(lines) { lines.joinToString("\n") }
        val body = buildString {
            appendLine("旅游宝运行日志 导出于 ${ts.format(Date())}")
            appendLine("== 本次启动前的历史（最多 400KB，超出部分已截断重记） ==")
            if (persisted.isNotBlank()) appendLine(persisted) else appendLine("（无）")
            appendLine()
            appendLine("== 最近内存缓冲（最新 ${MAX_MEMORY_LINES} 行） ==")
            appendLine(memory)
        }
        out.writeText(body)
        return out
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { logFile?.delete() }
        log("log", "日志已清空")
    }
}
