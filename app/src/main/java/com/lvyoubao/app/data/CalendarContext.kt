package com.lvyoubao.app.data

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历法上下文：农历/干支生肖/时辰/节气（精确到秒）/节日全部本地推算（lunar-java），不靠模型记忆。
 * 大模型口算农历与节气交节时刻极不可靠（实测把立冬交节日答成「不是节气日」，靠用户纠正才改对）——
 * 每次提问把精确历法块注入情境，模型照着读；民俗讲究各地不同，由模型基于准确日期自行发挥或搜索。
 * 任何推算异常返回 null 不注入，绝不阻断回答。
 */
object CalendarContext {

    fun block(now: Date = Date()): String? = runCatching {
        val solar = Solar.fromDate(now)
        val lunar = solar.lunar
        buildString {
            appendLine("【历法 · 本地精确推算】农历/节气/节日类问题必须以下面为准，绝不自行推算：")
            append(
                "公历：${solar.year}年${solar.month}月${solar.day}日 星期${solar.weekInChinese} " +
                    String.format(Locale.CHINA, "%02d:%02d", solar.hour, solar.minute) + "；",
            )
            append(
                "农历：${lunar.yearInGanZhi}年（${lunar.yearShengXiao}年）${lunar.monthInChinese}月${lunar.dayInChinese}；",
            )
            append("时辰：${lunar.timeZhi.takeLast(1)}时；")
            appendLine(jieQiLine(solar, lunar))
            append(festivalLine(solar, lunar))
        }.trim()
    }.getOrNull()

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
