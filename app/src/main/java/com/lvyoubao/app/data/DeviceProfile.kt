package com.lvyoubao.app.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 机型与运行容器特征。同一套 APK 既要跑原生手机，也要跑车机/机器人与鸿蒙 NEXT 卓易通安卓容器。
 */
object DeviceProfile {

    private val ZHUOYI_CONTAINER_PACKAGES = listOf(
        "com.zhuoyi.appstore.lite",
        "com.zhuoyi.appstore",
        "com.droi.tong",
    )

    @Volatile
    private var cachedZhuoyiContainer: Boolean? = null

    /** 横屏机器人（优必选 Visbot 这类）：面板本来就是 308×240 的横向屏 */
    val isLandscapeRobot: Boolean by lazy {
        val s = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.BRAND).lowercase()
        s.contains("visbot") || s.contains("ubtech") || s.contains("优必选")
    }

    /** 屏幕方向的出厂默认值：机器人锁横屏，手机跟随系统 */
    val defaultOrientation: String
        get() = if (isLandscapeRobot) "landscape" else "auto"

    private val DROI_WORD_REGEX = Regex("""(?:^|[^a-z])droi(?:[^a-z]|$)""")

    /**
     * 纯逻辑判定：根据安装来源包名、进程 cgroup 与容器宿主包存在性识别是否处于卓易通 / 鸿蒙 ANCO 容器。
     * 注意不能用裸字串 contains("droi")，因为 "android" 自身即包含 "droi" 子串会误伤全部原生安卓设备。
     */
    internal fun isZhuoyiEnvironment(
        installer: String?,
        cgroupText: String?,
        hasContainerPkg: Boolean,
    ): Boolean {
        if (hasContainerPkg) return true
        val inst = installer?.lowercase().orEmpty()
        if (inst.contains("zhuoyi") || DROI_WORD_REGEX.containsMatchIn(inst)) return true
        val cg = cgroupText?.lowercase().orEmpty()
        return cg.contains("isulad") ||
            cg.contains("zhuoyi") ||
            cg.contains("anco") ||
            DROI_WORD_REGEX.containsMatchIn(cg)
    }

    /**
     * 是否运行在鸿蒙 NEXT 的卓易通（ANCO）安卓容器里。
     * 容器会伪装 Build.*，因此通过安装来源、容器内置宿主包与 /proc/self/cgroup 综合判定并缓存结果。
     */
    fun isZhuoyiContainer(context: Context): Boolean {
        cachedZhuoyiContainer?.let { return it }
        val detected = runCatching {
            val pm = context.applicationContext.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { pm.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                runCatching { pm.getInstallerPackageName(context.packageName) }.getOrNull()
            }
            val hasPkg = ZHUOYI_CONTAINER_PACKAGES.any { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            }
            val cgroup = runCatching {
                val f = File("/proc/self/cgroup")
                if (f.canRead()) f.readText() else null
            }.getOrNull()
            isZhuoyiEnvironment(installer, cgroup, hasPkg)
        }.getOrDefault(false)
        cachedZhuoyiContainer = detected
        return detected
    }
}
