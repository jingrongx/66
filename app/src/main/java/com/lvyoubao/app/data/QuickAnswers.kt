package com.lvyoubao.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 本地快答：时间 / 日期 / 电量这类**不需要模型**的问题，几十毫秒内直接答。
 *
 * 为什么单独抽出来：灵动模式一直有这条快路（`handleAssistantIntent`），
 * 所以「现在几点了」说完就有声；而普通模式和大字模式每句话都要带近 2800 字的
 * system prompt 走一趟模型，同一个问题要等一两秒——用户体感就是「这两个模式反应慢」。
 *
 * 判定原则：**宁可漏判，不可误判**。只要句子里出现「明天/昨天/后天/农历」这种
 * 会改变答案语义的词，一律返回 null 交给模型，别抢它的活。
 *
 * 时间源必须与 [CalendarContext] 一致：[TimeSync.nowDate]（网络校时）+ 北京时间。
 * 以前这里直接读裸 [Date]，机器人断电后时钟一漂就报错时间，而且校准偏移根本没用上。
 */
object QuickAnswers {

    /** 太长的句子一定不是纯问时间，直接放行给模型 */
    private const val MAX_LEN = 14

    private const val PATTERN_FULL = "yyyy-MM-dd HH:mm:ss"
    private const val NOT_ANSWERED = "（未命中）"

    private fun fmt(pattern: String, at: Date): String =
        SimpleDateFormat(pattern, Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(at)

    private fun localFmt(pattern: String, at: Date): String =
        SimpleDateFormat(pattern, Locale.CHINA)
            .apply { timeZone = TimeZone.getDefault() }.format(at)

    private val TIME_WORDS = listOf("几点", "什么时间", "现在时间", "报时", "什么时候了")
    private val DATE_WORDS = listOf("几号", "多少号", "什么日期", "今天日期", "星期几", "周几", "礼拜几")
    private val BATTERY_WORDS = listOf("电量", "多少电", "剩多少电", "电池还有", "还有电吗")

    /** 含这些词就不接管：明天几号、昨天几点、农历几号…… 语义都变了 */
    private val REJECT_WORDS = listOf(
        "明天", "昨天", "后天", "前天", "大后天", "农历", "阴历", "阳历",
        "上个月", "下个月", "去年", "明年", "闹钟", "提醒", "定个", "设个",
    )

    /**
     * 命中返回一句现成的回答（可直接落库、可直接朗读）；不命中返回 null。
     * 纯函数式：不写库、不播报，由调用方决定怎么用。
     */
    fun answer(context: Context, question: String): String? {
        val text = question.trim()
        if (text.isEmpty() || text.length > MAX_LEN) return null
        if (REJECT_WORDS.any { text.contains(it) }) return null

        if (BATTERY_WORDS.any { text.contains(it) }) {
            return batteryAnswer(context).also { answer ->
                LogStore.log("quick", "问「$text」→ ${answer ?: NOT_ANSWERED}")
            }
        }

        // 日期优先于时间：「今天几号」里没有「几点」，但「今天星期几现在几点」两个都占，先答日期
        // 具体说法统一由 TimeAnswer 决定（时区口径读设置），这里只做意图识别
        val mode = TimeSync.zoneMode()
        val answer = when {
            DATE_WORDS.any { text.contains(it) } -> {
                TimeSync.ensureFresh() // 距上次校时超过设定间隔就异步补，不阻塞本次回答
                TimeAnswer.date(mode)
            }
            TIME_WORDS.any { text.contains(it) } -> {
                TimeSync.ensureFresh()
                TimeAnswer.time(mode)
            }
            else -> null
        }
        if (answer != null) logTimeHit(text, answer)
        return answer
    }

    /**
     * 现场排查「时间不对」一眼可见：同时记系统钟读数、校准后读数、偏差秒数和设备时区。
     * 偏差不为 0 说明校准生效；偏差为 0 但仍不准说明 NTP 没同步上或系统钟已被 root 修正。
     */
    private fun logTimeHit(question: String, answer: String) {
        val raw = Date()
        val synced = TimeSync.nowDate()
        LogStore.log(
            "quick",
            "问「$question」→ $answer；系统钟=${fmt(PATTERN_FULL, raw)} " +
                "校准后=${fmt(PATTERN_FULL, synced)} " +
                "偏差=${(synced.time - raw.time) / 1000}s（${TimeSync.status()}）" +
                "设备时区=${TimeZone.getDefault().id} 当地=${localFmt(PATTERN_FULL, synced)}",
        )
    }

    private fun batteryAnswer(context: Context): String? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct < 0) return null
        val status = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (charging) "电量 $pct%，正在充电。" else "电量 $pct%，没在充电。"
    }.getOrNull()
}
