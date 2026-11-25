package com.lvyoubao.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.lang.String.format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 网络时间校准（SNTP）——手机和机器人共用一套。
 *
 * 两类设备的坑不一样：
 *  - 机器人：老固件无 RTC 电池，断电回到出厂日期，一漂几小时；有 root 可直接修系统钟。
 *  - 手机：系统钟通常准，但可能被手动改、或人在境外连不上国内 NTP；没 root 只能补偿。
 *
 * 策略：
 *  - offset 持久化到 SharedPreferences：进程被杀重启后立刻恢复，不用等异步校时——
 *    这是「重启后第一句问时间就准」的关键；
 *  - NTP 源国内 + 国际混合，IP 直连优先（DNS 解析不受 soTimeout 保护，境外能卡几分钟），
 *    每个源整体套 withTimeout，卡住也能跳下一个；
 *  - 偏差 > 60 秒且有 root 就 `date -s` 修系统钟（修完 offset 清零并持久化），
 *    否则缓存偏移，报时间时用 [nowDate] 补偿；
 *  - 报时间时若距上次成功校时超过 10 分钟，异步补一次（走时速率漂移的设备也不会越积越偏）；
 *  - 每次结果写 LogStore，排查「时间不对」一眼看到偏差、用的哪个源、是否修成功。
 */
object TimeSync {

    private const val TAG = "timesync"
    private const val PREF = "time_sync"
    private const val K_OFFSET = "offset_ms"
    private const val K_HAS = "has_sync"
    private const val K_NET_AT = "synced_net_at"
    private const val FRESH_MS = 10 * 60_000L

    /** 国内 IP 优先（机器人实测 DNS 卡死，IP 直连稳），再国内域名，最后国际源兜境外 */
    private val NTP_HOSTS = listOf(
        "203.107.6.88",          // 阿里云 NTP 任播 IP
        "ntp.aliyun.com",
        "cn.ntp.org.cn",
        "162.159.200.1",         // Cloudflare NTP IP
        "time.cloudflare.com",
        "pool.ntp.org",
    )

    /** 服务器时间 - 系统时间（ms）。null = 还没同步成功过，用裸系统时间 */
    @Volatile
    private var offsetMs: Long? = null

    /** 上次发起同步的时刻（wall clock ms），用于节流。发起时刻而非成功时刻，失败后也能按周期重试 */
    @Volatile
    private var lastAttemptAt = 0L

    /** 上次成功校时的单调时刻，用于判断 offset 是否该刷新（elapsedRealtime 不受改系统钟影响） */
    @Volatile
    private var lastSuccessRealtime = 0L

    /** 是否拿到过可信网络时间（含从磁盘恢复的） */
    @Volatile
    private var syncedOnce = false

    /** offset 是否来自磁盘恢复（日志区分「启动即准」和「校时才准」） */
    @Volatile
    private var restored = false

    @Volatile
    private var prefs: SharedPreferences? = null

    private val syncing = AtomicBoolean(false)

    // ===== 报时口径（三种模式统一读这里）=====
    @Volatile
    private var autoSync = true

    @Volatile
    private var zoneMode = "auto"

    @Volatile
    private var intervalMin = 10

    /** 设置保存后调用：校时开关/报时区制/间隔即时生效 */
    fun applySettings(s: AiSettings) {
        autoSync = s.timeAutoSync
        zoneMode = s.timeZoneMode
        intervalMin = s.timeSyncIntervalMin.coerceIn(1, 120)
    }

    /** 当前报时区制，供 TimeAnswer / CalendarContext 统一读取 */
    fun zoneMode(): String = zoneMode

    /**
     * Application.onCreate 同步调用：立刻从磁盘恢复上次校时的 offset。
     * 必须同步——异步校时要 1~5 秒，期间用户问时间会读到裸系统钟（机器人可能差几小时）。
     */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        if (p.getBoolean(K_HAS, false)) {
            offsetMs = p.getLong(K_OFFSET, 0L)
            syncedOnce = true
            restored = true
            LogStore.log("timesync", "恢复上次校时：${status()}，报时=${fmt(System.currentTimeMillis() + (offsetMs ?: 0L))}")
        }
    }

    /** 校准后的当前时间：给 AI 报时间/记会话时间戳用。每次实时读系统钟 + 缓存偏移 */
    fun nowDate(): Date = Date(System.currentTimeMillis() + (offsetMs ?: 0L))

    /** 日志用的校时状态：现场一眼判断校准是否生效 */
    fun status(): String = when (val o = offsetMs) {
        null -> if (syncedOnce) "系统钟已修正" else "未校时"
        else -> "补偿 ${o / 1000}s${if (restored) "（磁盘恢复）" else ""}"
    }

    /**
     * 问时间时顺手检查：距上次成功校时超过 10 分钟就异步补一次。
     * 不阻塞当前回答（当前用现有 offset，走时速率漂移 10 分钟也就几秒误差），
     * 但能保证下一次问时间是新的。
     */
    fun ensureFresh() {
        if (!autoSync) return
        val interval = intervalMin * 60_000L
        if (SystemClock.elapsedRealtime() - lastSuccessRealtime < interval) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sync(minIntervalMs = 60_000L) }
        }
    }

    /**
     * 同步一次（IO 线程）。
     * 节流按「发起时刻」算，失败后也能按周期重试；并发调用只放一个进去。
     * 回前台场景传较短的 minIntervalMs，避免长时间后台后仍吃 10 分钟旧窗口。
     */
    suspend fun sync(minIntervalMs: Long = 10 * 60_000L): Boolean = withContext(Dispatchers.IO) {
        if (!syncing.compareAndSet(false, true)) return@withContext false
        try {
            val now = System.currentTimeMillis()
            if (now - lastAttemptAt < minIntervalMs) return@withContext true
            lastAttemptAt = now
            val (net, host) = firstReachable() ?: run {
                Log.w(TAG, "SNTP 全部不可达，沿用系统时间")
                LogStore.log("timesync", "SNTP 全部不可达（${status()}），沿用系统时间")
                return@withContext false
            }
            val offset = net - now
            if (kotlin.math.abs(offset) > 60_000L) {
                if (fixSystemClock(net)) {
                    offsetMs = null // 系统钟已修对，无需再补偿
                    persist(null, net)
                    Log.i(TAG, "系统时间偏差 ${offset / 1000}s，已用 root 修正系统时钟")
                    LogStore.log("timesync", "源=$host 偏差 ${offset / 1000}s，已用 root 修正系统时钟")
                } else {
                    offsetMs = offset
                    persist(offset, net)
                    Log.w(TAG, "系统时间偏差 ${offset / 1000}s，无 root，按偏移补偿")
                    LogStore.log("timesync", "源=$host 偏差 ${offset / 1000}s，无 root，按偏移补偿")
                }
            } else {
                offsetMs = offset
                persist(offset, net)
                Log.i(TAG, "时间校准：偏差 ${offset}ms（正常范围）")
                LogStore.log("timesync", "源=$host 偏差 ${offset}ms，报时=${fmt(net)}")
            }
            syncedOnce = true
            restored = false
            lastSuccessRealtime = SystemClock.elapsedRealtime()
            true
        } finally {
            syncing.set(false)
        }
    }

    /** 开机校时：尊重「自动网络校时」开关；用户关掉时不碰网络，只用磁盘恢复的 offset */
    suspend fun syncIfAuto() {
        if (autoSync) sync()
    }

    /**
     * 应用从后台切回前台时调用。进程被杀后重启走 onCreate，但进程没死、只是
     * Activity 重建时 onCreate 不会再跑——挂在前台生命周期上补一次校时兜住。
     */
    suspend fun syncOnForeground() {
        if (!autoSync) return
        val was = status()
        sync(minIntervalMs = 60_000L)
        LogStore.log("timesync", "回前台校时：此前=$was，当前=${status()}，报时=${fmt(nowDate().time)}")
    }

    /** 把 offset 落盘。offset=null 表示系统钟已被 root 修准，存 0 即可 */
    private fun persist(offset: Long?, netAt: Long) {
        runCatching {
            prefs?.edit()
                ?.putBoolean(K_HAS, true)
                ?.putLong(K_OFFSET, offset ?: 0L)
                ?.putLong(K_NET_AT, netAt)
                ?.apply()
        }
    }

    /**
     * 依次尝试 NTP 源，返回 (服务器时间, 源地址)。
     * 每个源整体套 withTimeout——InetAddress.getByName 不受 soTimeout 保护，
     * 境外解析可能卡几分钟，超时就跳下一个。
     */
    private suspend fun firstReachable(): Pair<Long, String>? {
        for (h in NTP_HOSTS) {
            val t = withTimeoutOrNull(2_000L) { sntpQuery(h, 1_200) }
            if (t != null) {
                LogStore.log("timesync", "NTP 源 $h 应答，偏差 ${t - System.currentTimeMillis()}ms")
                return t to h
            }
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

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(ms))
}
