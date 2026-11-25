package com.lvyoubao.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 统一报时口径——普通/大字（QuickAnswers）、灵动（模型上下文）、以及未来任何模式
 * 都从这里取「现在几点 / 今天几号」的说法，避免各写一套导致同 App 里答案不一致。
 *
 * 时区由设置项 `timeZoneMode` 控制：
 *  - auto   ：设备时区与北京同钟面只报一个；不同则北京时间 + 当地时间都报；
 *  - beijing：只报北京时间；
 *  - local  ：只报设备当地时间；
 *  - both   ：无论是否同钟面都报两个。
 *
 * 时间一律用 [TimeSync.nowDate]（已校时），不在别处直接 new Date()。
 */
object TimeAnswer {

    private val BEIJING = TimeZone.getTimeZone("Asia/Shanghai")
    private const val PATTERN_DATE = "yyyy 年 M 月 d 日"
    private const val PATTERN_WEEK = "EEEE"
    private const val PATTERN_HM = "HH:mm"

    private fun fmt(pattern: String, at: Date, tz: TimeZone): String =
        SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = tz }.format(at)

    private fun localTz(): TimeZone = TimeZone.getDefault()

    private fun sameClock(at: Date): Boolean =
        localTz().getOffset(at.time) == BEIJING.getOffset(at.time)

    private enum class ZoneChoice { BEIJING_ONLY, LOCAL_ONLY, BOTH }

    private fun choice(at: Date, mode: String): ZoneChoice = when (mode) {
        "beijing" -> ZoneChoice.BEIJING_ONLY
        "local" -> ZoneChoice.LOCAL_ONLY
        "both" -> ZoneChoice.BOTH
        else -> if (sameClock(at)) ZoneChoice.BEIJING_ONLY else ZoneChoice.BOTH // auto
    }

    /**
     * 「现在几点」的回答。校准时间、时区口径全在这里定，调用方不要二次加工。
     */
    fun time(mode: String): String {
        val now = TimeSync.nowDate()
        return when (choice(now, mode)) {
            ZoneChoice.BEIJING_ONLY -> "现在是 ${fmt(PATTERN_HM, now, BEIJING)}。"
            ZoneChoice.LOCAL_ONLY -> "现在是当地时间 ${fmt(PATTERN_HM, now, localTz())}。"
            ZoneChoice.BOTH ->
                "北京时间 ${fmt(PATTERN_HM, now, BEIJING)}，当地时间 ${fmt(PATTERN_HM, now, localTz())}。"
        }
    }

    /**
     * 「今天几号 / 星期几」的回答。双时区时各自带星期，应对境外跨日。
     */
    fun date(mode: String): String {
        val now = TimeSync.nowDate()
        return when (choice(now, mode)) {
            ZoneChoice.BEIJING_ONLY ->
                "今天是 ${fmt(PATTERN_DATE, now, BEIJING)}，${fmt(PATTERN_WEEK, now, BEIJING)}。"
            ZoneChoice.LOCAL_ONLY ->
                "今天是当地时间 ${fmt(PATTERN_DATE, now, localTz())}，${fmt(PATTERN_WEEK, now, localTz())}。"
            ZoneChoice.BOTH ->
                "北京时间 ${fmt(PATTERN_DATE, now, BEIJING)} ${fmt(PATTERN_WEEK, now, BEIJING)}，" +
                    "当地时间 ${fmt(PATTERN_DATE, now, localTz())} ${fmt(PATTERN_WEEK, now, localTz())}。"
        }
    }

    /**
     * 给模型上下文用的精确时间行（含农历/节气等，由 CalendarContext 拼接）。
     * 这里只负责「北京时间 / 当地时间」两行怎么摆，模型照读即可。
     */
    fun contextTimeBlock(mode: String, calendarBody: String): String {
        val now = TimeSync.nowDate()
        val bj = "北京时间：${fmt("yyyy-MM-dd HH:mm", now, BEIJING)}"
        return when (choice(now, mode)) {
            ZoneChoice.BEIJING_ONLY -> "$bj（即当地时间）\n$calendarBody"
            ZoneChoice.LOCAL_ONLY ->
                "当地时间（设备时区 ${localTz().id}）：${fmt("yyyy-MM-dd HH:mm", now, localTz())}\n$calendarBody"
            ZoneChoice.BOTH ->
                "$bj；当地时间（设备时区 ${localTz().id}）：${fmt("yyyy-MM-dd HH:mm", now, localTz())}\n$calendarBody"
        }
    }
}
