package com.lvyoubao.app.data

import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络时间校准（SNTP）。
 *
 * 为什么要有它：机器人的老固件断电重启后系统时钟会漂（无 RTC 电池），系统自带的
 * auto_time 又依赖 Google/运营商 NTP——国内网络经常同步不上，一漂就是几小时甚至
 * 回到出厂日期。用户问「现在几点」AI 就一本正经地报错时间。
 *
 * 策略：
 *  - SNTP 依次试国内 NTP（ntp.aliyun.com / cn.ntp.org.cn），带超时，全部失败不打扰任何人；
 *  - 偏差 > 60 秒：有 root（机器人设备有）直接 `date -s` 把系统时钟修对——屏幕、
 *    闹钟、AI 上下文一次全对；没 root 就缓存偏移量，[CalendarContext] 报时间时补上；
 *  - 偏差 ≤ 60 秒：也缓存偏移（AI 报时间准到秒）；
 *  - 结果写一行日志，现场排查「时间不对」一眼能看到差了多少、修没修成。
 */
object TimeSync {

    private const val TAG = "timesync"

    /** 服务器时间 - 系统时间（ms）。null = 还没同步成功过，用裸系统时间 */
    @Volatile
    private var offsetMs: Long? = null

    /** 上次同步成功的时刻（wall clock ms），用于节流 */
    @Volatile
    private var lastSyncAt = 0L

    /** 校准后的当前时间：给 AI 报时间/记会话时间戳用 */
    fun nowDate(): Date = Date(System.currentTimeMillis() + (offsetMs ?: 0L))

    /**
     * 同步一次（IO 线程）。节流：10 分钟内不重复查（每次问答都可能调到，别把 NTP 打爆）。
     * 返回是否拿到了可信的网络时间。
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 10 * 60_000L && offsetMs != null) return@withContext true
        val net = firstReachable() ?: run {
            Log.w(TAG, "SNTP 全部不可达，沿用系统时间")
            return@withContext false
        }
        lastSyncAt = now
        val offset = net - now
        if (kotlin.math.abs(offset) > 60_000L) {
            if (fixSystemClock(net)) {
                offsetMs = null // 系统钟已修对，无需再补偿
                Log.i(TAG, "系统时间偏差 ${offset / 1000}s，已用 root 修正系统时钟")
            } else {
                offsetMs = offset
                Log.w(TAG, "系统时间偏差 ${offset / 1000}s，无 root 修不了系统钟，AI 报时间将按偏移补偿")
            }
        } else {
            offsetMs = offset
            Log.i(TAG, "时间校准：偏差 ${offset}ms（正常范围）")
        }
        true
    }

    /** 依次尝试 NTP 服务器，返回第一个应答的服务器时间（ms）；全挂返回 null。
     *  IP 直连排在域名前：这台机器人实测 DNS 解析 NTP 域名会长时间阻塞
     *  （getByName 不受 soTimeout 保护，一卡几分钟整个校时白等），
     *  203.107.6.88 是阿里云 NTP 的任播 IP，多年稳定；IP 失效会 1.5s 超时落到域名兜底。 */
    private fun firstReachable(): Long? {
        val hosts = listOf("203.107.6.88", "ntp.aliyun.com", "cn.ntp.org.cn")
        for (h in hosts) {
            sntpQuery(h, timeoutMs = 1_500)?.let { return it }
        }
        return null
    }

    /**
     * SNTP 客户端（NTP v4 请求格式，解析应答第 40 字节起的 64 位 transmit 时间戳）。
     * 返回值按「应答往返一半」折算到本机当下，秒级精度足够。
     */
    private fun sntpQuery(host: String, timeoutMs: Int): Long? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            val req = ByteArray(48).also { it[0] = 0x1B } // LI=0 VN=4 Mode=3(client)
            val t1 = System.currentTimeMillis()
            socket.send(DatagramPacket(req, req.size, addr, 123))
            val resp = ByteArray(48)
            socket.receive(DatagramPacket(resp, resp.size))
            val rtt = System.currentTimeMillis() - t1
            fun u32(o: Int) = ((resp[o].toLong() and 0xff) shl 24) or ((resp[o + 1].toLong() and 0xff) shl 16) or
                ((resp[o + 2].toLong() and 0xff) shl 8) or (resp[o + 3].toLong() and 0xff)
            val seconds = u32(40)
            if (seconds == 0L) return@runCatching null // 服务器没填 transmit（如 kiss-o'-death）
            val fraction = u32(44)
            val unixMs = (seconds - 2_208_988_800L) * 1000L + fraction * 1000L / 4_294_967_296L
            unixMs + rtt / 2
        }
    }.getOrNull()

    /** root 用 `date -s` 修系统时钟（toybox 认 YYYYMMDD.HHMMSS，UTC 输入 + -u 免时区歧义） */
    private fun fixSystemClock(targetMs: Long): Boolean = runCatching {
        if (!File("/system/xbin/su").exists() && !File("/system/bin/su").exists()) return false
        val fmt = SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val utc = fmt.format(Date(targetMs))
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "date -u -s $utc"))
        p.waitFor() == 0
    }.getOrDefault(false)
}
