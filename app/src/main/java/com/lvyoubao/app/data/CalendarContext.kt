package com.lvyoubao.app.data

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 历法上下文：农历/干支生肖/时辰（含起止钟点）/节气（精确到秒）/节日全部本地推算（lunar-java）。
 * 传统历法统一按【北京时间】推算（民俗基准），设备时区不是东八区（人在国外）时额外换算当地时间；
 * 大模型口算农历与节气交节时刻极不可靠——每次提问把精确历法块注入情境，模型照着读。
 * 任何推算异常返回 null 不注入，绝不阻断回答。
 */
object CalendarContext {

    fun block(now: Date = Date()): String? = runCatching {
        val bjCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply { time = now }
        // 农历/节气/时辰按北京时间的钟面字段构造（传统历法基准）
        val solar = Solar(
            bjCal.get(Calendar.YEAR),
            bjCal.get(Calendar.MONTH) + 1,
            bjCal.get(Calendar.DAY_OF_MONTH),
            bjCal.get(Calendar.HOUR_OF_DAY),
            bjCal.get(Calendar.MINUTE),
            bjCal.get(Calendar.SECOND),
        )
        val lunar = solar.lunar
        val localTz = TimeZone.getDefault()
        val timeLine = if (localTz.getOffset(now.time) == TimeZone.getTimeZone("Asia/Shanghai").getOffset(now.time)) {
            "北京时间：${solar.year}年${solar.month}月${solar.day}日 星期${solar.weekInChinese} " +
                String.format(Locale.CHINA, "%02d:%02d", solar.hour, solar.minute) + "（即当地时间）"
        } else {
            val localFmt = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = localTz }
            "北京时间：${solar.year}年${solar.month}月${solar.day}日 星期${solar.weekInChinese} " +
                String.format(Locale.CHINA, "%02d:%02d", solar.hour, solar.minute) +
                "；当地时间（设备时区 ${localTz.id}）：${localFmt.format(now)}"
        }
        buildString {
            appendLine("【历法 · 本地精确推算】农历/节气/节日/时辰类问题必须以下面为准，绝不自行推算：")
            appendLine(timeLine)
            append(
                "农历：${lunar.yearInGanZhi}年（${lunar.yearShengXiao}年）${lunar.monthInChinese}月${lunar.dayInChinese}；",
            )
            append(shiChenLine(lunar) + "；")
            appendLine(jieQiLine(solar, lunar))
            append(festivalLine(solar, lunar))
        }.trim()
    }.getOrNull()

    /**
     * 时辰行：当前时辰 + 起止钟点 + 下一个时辰。
     * 时辰按北京时间两小时一班（子时 23:00–01:00，丑时 01:00–03:00……申时 15:00–17:00）。
     */
    private fun shiChenLine(lunar: Lunar): String = runCatching {
        val zhi = "子丑寅卯辰巳午未申酉戌亥"
        val idx = lunar.timeZhiIndex          // 0=子 … 11=亥
        val startH = (23 + idx * 2) % 24
        val endH = (startH + 2) % 24
        val nextIdx = (idx + 1) % 12
        val nextStartH = (23 + nextIdx * 2) % 24
        "时辰：${zhi[idx]}时（${String.format(Locale.CHINA, "%02d:00", startH)}–${String.format(Locale.CHINA, "%02d:00", endH)}）" +
            "，下一个时辰${zhi[nextIdx]}时 ${String.format(Locale.CHINA, "%02d:00", nextStartH)} 起"
    }.getOrDefault("时辰：推算失败")

    /** lunar-java 节气表个别 key 未本地化（冬至是 DONG_ZHI），统一转中文 */
    private val JQ_EN = mapOf(
        "DONG_ZHI" to "冬至",
        "XIA_ZHI" to "夏至",
        "CHUN_FEN" to "春分",
        "QIU_FEN" to "秋分",
    )

    private fun jqName(key: String): String = JQ_EN[key] ?: key

    /** 节气行：今天交节就报精确交节时刻，否则报下一个节气的日期与倒计时天数 */
    private fun jieQiLine(solar: Solar, lunar: Lunar): String = runCatching {
        val table = lunar.jieQiTable   // Map<节气名, 交节时刻 Solar>
        val today = solar.toYmd()
        val todayJq = table.entries.firstOrNull { it.value.toYmd() == today }
        val nextJq = table.entries
            .filter { it.value.toYmd() > today }
            .minByOrNull { it.value.toYmd() }
        when {
            todayJq != null -> {
                val s = todayJq.value
                "节气：今天就是「${jqName(todayJq.key)}」交节日（${String.format(Locale.CHINA, "%02d:%02d:%02d", s.hour, s.minute, s.second)} 交节）" +
                    (nextJq?.let { "，距下一个节气「${jqName(it.key)}」还有 ${daysBetween(solar, it.value)} 天" } ?: "")
            }
            nextJq != null ->
                "节气：今天不是节气日；下一个节气「${jqName(nextJq.key)}」在 ${nextJq.value.toYmd()}（还有 ${daysBetween(solar, nextJq.value)} 天）"
            else -> "节气：暂无数据"
        }
    }.getOrDefault("节气：暂无数据")

    /** 节日行：农历节日（春节/端午/中秋/除夕…）+ 公历节日（元旦/国庆…），没有就明说 */
    private fun festivalLine(solar: Solar, lunar: Lunar): String {
        val names = (lunar.festivals + solar.festivals).distinct()
        return if (names.isEmpty()) {
            "节日：今天不是主要传统节日或法定节日（民俗讲究可结合准确日期发挥，日期与节气以上面为准）"
        } else {
            "节日：${names.joinToString("、")}"
        }
    }

    private fun daysBetween(from: Solar, to: Solar): Int = runCatching {
        val c1 = Calendar.getInstance().apply { set(from.year, from.month - 1, from.day, 0, 0, 0) }
        val c2 = Calendar.getInstance().apply { set(to.year, to.month - 1, to.day, 0, 0, 0) }
        ((c2.timeInMillis - c1.timeInMillis) / (24 * 3600_000L)).toInt()
    }.getOrDefault(0)
}
