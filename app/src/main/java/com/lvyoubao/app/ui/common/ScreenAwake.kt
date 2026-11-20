package com.lvyoubao.app.ui.common

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.lvyoubao.app.ScreenAdminReceiver
import com.lvyoubao.app.data.LogStore
import java.lang.ref.WeakReference

/**
 * 空闲息屏 / 语音唤醒亮屏。
 *
 * 灵动模式常年当桌面挂在机器人头上，屏幕整夜亮着是这个设备最耗电的一项。
 * 这里有两条独立的控制线，缺一条都不生效：
 *
 *  1. **应用侧**：说话、录音、触摸期间给窗口加 `FLAG_KEEP_SCREEN_ON`；待机 N 秒后撤掉。
 *  2. **系统侧**：屏幕超时必须是个有限值。机器人/车机这类设备出厂常被设成「永不熄屏」，
 *     不少还开过开发者选项里的「充电时保持唤醒」——那样光撤 flag 屏幕也不会灭。
 *     开启本功能时把系统超时对齐到 N 秒、并把保持唤醒关掉（需要「修改系统设置」权限）。
 *
 * 唤醒亮屏用带 `ACQUIRE_CAUSES_WAKEUP` 的唤醒锁，超时 8 秒自动释放，
 * 不会像常驻 wakelock 那样把电池整夜占着。
 */
object ScreenAwake {

    private const val TAG = "screen"

    /** 空闲多久息屏（毫秒）；0 = 常亮不息屏 */
    @Volatile
    var idleTimeoutMs: Long = 30_000L
        private set

    /** 是否接管屏幕（只有灵动模式接管；普通/大字模式维持系统原本行为） */
    @Volatile
    private var enabled = false

    private val main = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    /** 当前窗口是否挂着「保持亮屏」 */
    private var keepOnApplied = false

    /** 秒表：最近一次「有人在场」的时刻，用于诊断息屏时机 */
    @Volatile
    private var lastBusyAt = System.currentTimeMillis()

    private val sleepRunnable = Runnable {
        applyKeepOn(false)
        LogStore.log(TAG, "空闲息屏：待机 ${idleTimeoutMs / 1000} 秒，请求熄屏")
        sleepNow()
    }

    /** 绑定宿主 Activity（MainActivity.onCreate 调用）；重复绑定以最后一次为准 */
    fun bind(activity: Activity) {
        activityRef = WeakReference(activity)
        appContext = activity.applicationContext
        if (enabled) keepOn()
    }

    /**
     * 立刻熄屏。
     *
     * 两条路，能走哪条走哪条：
     * 1. `PowerManager.goToSleep()`——要 DEVICE_POWER（系统签名/特权应用才有），
     *    系统签名的版本或预置到 priv-app 的版本会自动走这条；
     * 2. `DevicePolicyManager.lockNow()`——要用户激活一次设备管理器（只申请 force-lock），
     *    这是普通应用在「固件把睡眠决策交给应用」的机器上唯一能真熄屏的正规方式。
     *
     * 两条都不通时只撤掉「保持亮屏」，屏幕会不会灭取决于固件——本机固件是不会灭的。
     */
    fun sleepNow(): Boolean {
        val context = appContext ?: return false
        // 路线 1：系统接口（没有权限会抛 SecurityException，静默跳过）
        val viaSystem = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val m = PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m.invoke(pm, SystemClock.uptimeMillis())
            true
        }.getOrDefault(false)
        if (viaSystem) {
            LogStore.log(TAG, "已熄屏（系统 goToSleep）")
            return true
        }
        // 路线 2：设备管理器
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin: ComponentName = ScreenAdminReceiver.component(context)
        if (dpm != null && dpm.isAdminActive(admin)) {
            val ok = runCatching { dpm.lockNow() }.isSuccess
            LogStore.log(TAG, if (ok) "已熄屏（设备管理器 lockNow）" else "熄屏失败：lockNow 抛异常")
            return ok
        }
        LogStore.log(TAG, "熄屏受限：本机固件把睡眠决策交给应用，需激活「熄屏权限」（设置 → 空闲息屏）")
        return false
    }

    /**
     * 开关接管：灵动模式打开、其它模式关掉。
     * 关掉时立刻撤掉保持亮屏，屏幕行为交还给系统设置——普通模式/大字模式不受影响。
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        main.removeCallbacks(sleepRunnable)
        if (on) {
            keepOn()
        } else {
            applyKeepOn(false)
        }
    }

    fun unbind(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            keepOnApplied = false
            main.removeCallbacks(sleepRunnable)
        }
    }

    /**
     * 息屏时长变更（设置页改动 / 启动读配置时调用）。
     * 0 表示常亮：立即取消倒计时并保持亮屏。
     */
    fun setIdleTimeout(ms: Long) {
        idleTimeoutMs = ms.coerceAtLeast(0)
        if (idleTimeoutMs <= 0) {
            keepOn()
        } else if (keepOnApplied) {
            // 正在亮屏中：按新时长重新倒计时
            idle()
        }
    }

    /**
     * 有人在场：保持亮屏，取消息屏倒计时。
     * 录音、播报、触摸、按键都该调这个——只要还在交互，屏幕就不该灭。
     */
    fun keepOn() {
        if (!enabled) return
        lastBusyAt = System.currentTimeMillis()
        main.removeCallbacks(sleepRunnable)
        applyKeepOn(true)
    }

    /**
     * 进入待机：屏幕再亮 [idleTimeoutMs] 后交给系统熄灭。
     * 交互结束时（播报完毕、回到萌眼、松开按键）调用；0 时常亮，等同于 [keepOn]。
     */
    fun idle() {
        if (!enabled) return
        if (idleTimeoutMs <= 0) {
            keepOn()
            return
        }
        lastBusyAt = System.currentTimeMillis()
        applyKeepOn(true)
        main.removeCallbacks(sleepRunnable)
        main.postDelayed(sleepRunnable, idleTimeoutMs)
    }

    /**
     * 声音唤醒：把屏幕点亮。
     * 机器人息屏后 CPU 靠录音链路维持，人喊唤醒词时屏幕必须立刻回来，
     * 否则「回应了但看不见」——用户会以为设备死了。
     */
    fun wakeUp(context: Context) {
        if (!enabled) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            val wl = wakeLock ?: runCatching {
                pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "lvyoubao:voice-wake",
                ).apply { setReferenceCounted(false) }
            }.getOrNull()?.also { wakeLock = it }
            if (wl != null && !wl.isHeld) {
                val ok = runCatching { wl.acquire(8_000L) }.isSuccess
                LogStore.log(TAG, if (ok) "唤醒亮屏：点亮屏幕" else "唤醒亮屏：唤醒锁申请失败")
            }
        }
        keepOn()
    }

    /**
     * 把系统屏幕超时对齐到 [seconds] 秒，并关掉「充电时保持唤醒」。
     * 返回 false 表示没有「修改系统设置」权限（应用侧的保持亮屏仍然生效，
     * 但如果系统超时是「永不」，撤掉 flag 后屏幕也不会灭——需要授权一次）。
     */
    fun applySystemTimeout(context: Context, seconds: Int): Boolean {
        if (seconds <= 0) return false
        val resolver = context.contentResolver
        if (!Settings.System.canWrite(context)) {
            LogStore.log(TAG, "系统超时未调整：缺少「修改系统设置」权限（应用内保持亮屏仍生效）")
            return false
        }
        val target = seconds * 1000
        val cur = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        }.getOrDefault(-1)
        var ok = true
        if (cur != target) {
            ok = runCatching {
                Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, target)
            }.isSuccess
            LogStore.log(TAG, "系统屏幕超时：${cur / 1000}秒 → ${target / 1000}秒（${if (ok) "成功" else "失败"}）")
        }
        // 开发者选项里的「充电时保持唤醒」会让屏幕永不休眠，绕过所有超时设置
        runCatching {
            val stay = Settings.Global.getInt(resolver, STAY_ON_WHILE_PLUGGED_IN, 0)
            if (stay != 0) {
                Settings.Global.putInt(resolver, STAY_ON_WHILE_PLUGGED_IN, 0)
                LogStore.log(TAG, "已关闭「充电时保持唤醒」（原值 $stay）")
            }
        }.onFailure {
            // 写 Global 需要 WRITE_SECURE_SETTINGS；没有也不影响主流程
        }
        return ok
    }

    /** 当前屏幕状态快照，排查「为什么没息屏/没亮屏」时打一行 */
    fun describe(context: Context): String {
        val resolver = context.contentResolver
        val timeout = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        }.getOrDefault(-1)
        val stay = runCatching {
            Settings.Global.getInt(resolver, STAY_ON_WHILE_PLUGGED_IN, 0)
        }.getOrDefault(-1)
        val canWrite = Settings.System.canWrite(context)
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isInteractive ?: true
        return "系统超时=${if (timeout < 0) "永不" else "${timeout / 1000}秒"} " +
            "保持唤醒=$stay 可改系统设置=$canWrite 屏幕=$screenOn " +
            "应用保持亮屏=$keepOnApplied 设定=${if (idleTimeoutMs <= 0) "常亮" else "${idleTimeoutMs / 1000}秒"} " +
            "距上次交互=${(System.currentTimeMillis() - lastBusyAt) / 1000}秒"
    }

    private fun applyKeepOn(on: Boolean) {
        val act = activityRef?.get()
        if (act == null) {
            keepOnApplied = on
            return
        }
        keepOnApplied = on
        act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            val w = act.window ?: return@runOnUiThread
            if (on) {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private const val STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in"
}
