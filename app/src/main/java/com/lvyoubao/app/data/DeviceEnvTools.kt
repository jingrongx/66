package com.lvyoubao.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 设备环境排查与兼容辅助工具：
 * 1. 检测与处理 Google 语音唤醒（GsaVoiceInteractionService / :interactor）占麦冲突；
 * 2. 读取设备局域网 IP、ADB 与 5555 端口状态，并支持在已 Root 设备上直接开关或生成无 Root 引导指令；
 * 3. 提供公共局域网 IPv4 提取 [lanIpv4]，避免扫码同步模块各自重复扫描网卡。
 */
object DeviceEnvTools {

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    const val DEFAULT_ADB_PORT = 5555

    const val ADB_DISABLE_GOOGLE_MIC_CMD =
        "adb shell cmd appops set $GOOGLE_APP_PACKAGE RECORD_AUDIO ignore"

    enum class GoogleWakeState {
        /** 未安装 Google 搜索，无唤醒占麦隐患 */
        NOT_INSTALLED,
        /** 已安装但被停用，无唤醒占麦隐患 */
        APP_DISABLED,
        /** 已在 Root 检查中确认 RECORD_AUDIO=ignore */
        MIC_IGNORED,
        /** 系统当前默认语音交互服务正指向 Google */
        VOICE_INTERACTION_ACTIVE,
        /** 已安装 Google，但当前系统默认语音交互未指向它 */
        INSTALLED_INACTIVE,
    }

    data class GoogleWakeStatus(
        val state: GoogleWakeState,
        val activeVoiceService: String?,
        val activeAssistant: String?,
        val hasRoot: Boolean,
        /** 有 Root 时精确读取到的 AppOps 状态；无 Root 时受系统权限限制无法直接读取，返回 null */
        val recordAudioIgnored: Boolean?,
    )

    data class NetworkAdbStatus(
        val lanIp: String?,
        val adbEnabled: Boolean,
        val tcpPort: Int?,
        val hasRoot: Boolean,
        val sdkInt: Int = Build.VERSION.SDK_INT,
    )

    data class ActionResult(
        val success: Boolean,
        val message: String,
    )

    /** 探测当前设备是否可调用 `su` 提权；与 TimeSync 口径一致并覆盖常见路径 */
    fun hasRootAccess(): Boolean {
        if (File("/system/xbin/su").exists() || File("/system/bin/su").exists() || File("/sbin/su").exists()) {
            return true
        }
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    /** 取本机局域网 IPv4（优先 192.168 网段） */
    fun lanIpv4(): String? = runCatching {
        val candidates = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addresses = ni.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let(candidates::add)
                }
            }
        }
        selectBestLanIpv4(candidates)
    }.getOrNull()

    internal fun selectBestLanIpv4(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.startsWith("192.168.") }
            ?: candidates.firstOrNull { it.startsWith("10.") }
            ?: candidates.firstOrNull { it.startsWith("172.") }
            ?: candidates.firstOrNull()
    }

    /** 判断系统设置值是否指向 Google 语音交互/助手 */
    internal fun isGoogleVoiceComponent(component: String?): Boolean {
        if (component.isNullOrBlank()) return false
        return component == GOOGLE_APP_PACKAGE || component.startsWith("$GOOGLE_APP_PACKAGE/")
    }

    /** 解析 `getprop service.adb.tcp.port` 输出；<=0 或非法值返回 null */
    internal fun parseTcpPort(raw: String?): Int? {
        val port = raw?.trim()?.toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /** 解析 `appops get ... RECORD_AUDIO` 输出判断是否为 ignore */
    internal fun parseRecordAudioIgnored(output: String?): Boolean? {
        if (output.isNullOrBlank()) return null
        val line = output.lineSequence()
            .firstOrNull { it.contains("RECORD_AUDIO") }
            ?: return null
        return when {
            line.contains(": ignore") -> true
            line.contains(": allow") || line.contains(": default") || line.contains(": deny") -> false
            else -> null
        }
    }

    fun adbConnectCommand(ip: String?, port: Int = DEFAULT_ADB_PORT): String {
        val host = ip?.takeIf { it.isNotBlank() } ?: "<设备WiFi_IP>"
        return "adb connect $host:$port"
    }

    fun adbTcpipCommand(ip: String?, port: Int = DEFAULT_ADB_PORT): String =
        "adb tcpip $port && ${adbConnectCommand(ip, port)}"

    /** 检测 Google 语音唤醒在本机的当前状态 */
    fun inspectGoogleWakeStatus(context: Context): GoogleWakeStatus {
        val root = hasRootAccess()
        val appInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(GOOGLE_APP_PACKAGE, 0)
        }.getOrNull()

        val activeVoice = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val activeAssist = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (appInfo == null) {
            return GoogleWakeStatus(
                state = GoogleWakeState.NOT_INSTALLED,
                activeVoiceService = activeVoice,
                activeAssistant = activeAssist,
                hasRoot = root,
                recordAudioIgnored = null,
            )
        }
        if (!appInfo.enabled) {
            return GoogleWakeStatus(
                state = GoogleWakeState.APP_DISABLED,
                activeVoiceService = activeVoice,
                activeAssistant = activeAssist,
                hasRoot = root,
                recordAudioIgnored = null,
            )
        }

        val appOpsIgnored = if (root) {
            val res = runSu("appops get $GOOGLE_APP_PACKAGE RECORD_AUDIO")
            if (res.exitCode == 0) parseRecordAudioIgnored(res.output) else null
        } else {
            null
        }

        val state = when {
            appOpsIgnored == true -> GoogleWakeState.MIC_IGNORED
            isGoogleVoiceComponent(activeVoice) || isGoogleVoiceComponent(activeAssist) ->
                GoogleWakeState.VOICE_INTERACTION_ACTIVE
            else -> GoogleWakeState.INSTALLED_INACTIVE
        }
        return GoogleWakeStatus(
            state = state,
            activeVoiceService = activeVoice,
            activeAssistant = activeAssist,
            hasRoot = root,
            recordAudioIgnored = appOpsIgnored,
        )
    }

    /** 检测网络 ADB 当前状态（含局域网 IP、ADB 总开关与端口） */
    fun inspectNetworkAdbStatus(context: Context): NetworkAdbStatus {
        val root = hasRootAccess()
        val ip = lanIpv4()
        val adbEnabled = runCatching {
            @Suppress("DEPRECATION")
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

        val propFromSys = readSystemProperty("service.adb.tcp.port")
        val portFromSys = parseTcpPort(propFromSys)
        val port = portFromSys ?: if (root) {
            val res = runSu("getprop service.adb.tcp.port")
            if (res.exitCode == 0) parseTcpPort(res.output) else null
        } else {
            null
        }

        return NetworkAdbStatus(
            lanIp = ip,
            adbEnabled = adbEnabled,
            tcpPort = port,
            hasRoot = root,
        )
    }

    /**
     * 尝试通过 Root 禁用或恢复 Google 应用录音占用。
     * 无 Root 设备直接返回明确说明，交由 UI 引导跳转系统设置页关闭。
     */
    fun setGoogleMicIgnoredViaRoot(ignore: Boolean): ActionResult {
        if (!hasRootAccess()) {
            return ActionResult(
                success = false,
                message = "当前设备无 Root 权限，Android 系统禁止普通应用直接修改 Google 权限；请点下方按钮去系统页面关闭「麦克风」权限。",
            )
        }
        val mode = if (ignore) "ignore" else "allow"
        val cmd = if (ignore) {
            "cmd appops set $GOOGLE_APP_PACKAGE RECORD_AUDIO $mode && am force-stop $GOOGLE_APP_PACKAGE"
        } else {
            "cmd appops set $GOOGLE_APP_PACKAGE RECORD_AUDIO $mode"
        }
        val res = runSu(cmd)
        if (res.exitCode != 0) {
            return ActionResult(
                success = false,
                message = "Root 执行未成功（退出码 ${res.exitCode}）：${res.output.ifBlank { "可能未授予本应用 Root 授权" }}",
            )
        }
        val check = runSu("appops get $GOOGLE_APP_PACKAGE RECORD_AUDIO")
        val verified = parseRecordAudioIgnored(check.output)
        return if (ignore && verified == true) {
            ActionResult(true, "已通过 Root 禁用 Google 麦克风权限并停止其唤醒进程（不影响文字搜索）。")
        } else if (!ignore && verified == false) {
            ActionResult(true, "已通过 Root 恢复 Google 麦克风权限。")
        } else {
            ActionResult(true, "命令已执行；若状态未刷新请重新检测。")
        }
    }

    /**
     * 尝试通过 Root 开启或关闭网络 ADB（默认 5555 端口）。
     * 无 Root 时按规则返回说明，无法静默修改系统守护进程属性。
     */
    fun setNetworkAdbViaRoot(enable: Boolean, port: Int = DEFAULT_ADB_PORT): ActionResult {
        if (!hasRootAccess()) {
            val msg = if (Build.VERSION.SDK_INT >= 30) {
                "当前设备无 Root，普通应用无法直接重启 adbd。请打开下方「开发者选项 → 无线调试」开启。"
            } else {
                "当前设备无 Root，普通应用无法直接设置 5555 端口。请先用数据线连电脑运行 `adb tcpip $port`，之后即可无线连接（重启后需重设）。"
            }
            return ActionResult(success = false, message = msg)
        }
        val target = if (enable) port.toString() else "-1"
        val cmd = if (enable) {
            "settings put global adb_enabled 1 && setprop service.adb.tcp.port $target && stop adbd && start adbd"
        } else {
            "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
        }
        val res = runSu(cmd)
        if (res.exitCode != 0) {
            return ActionResult(
                success = false,
                message = "Root 开启失败（退出码 ${res.exitCode}）：${res.output.ifBlank { "请确认已授权本应用 Root" }}",
            )
        }
        val verify = parseTcpPort(runSu("getprop service.adb.tcp.port").output)
        return if (enable) {
            if (verify == port) {
                ActionResult(true, "网络 ADB 已在 $port 端口开启。")
            } else {
                ActionResult(true, "已下发开启命令，如未生效请确认系统开发者选项已允许 USB 调试。")
            }
        } else {
            ActionResult(true, "已关闭网络 ADB 监听并切回 USB 模式。")
        }
    }

    /** 跳转到 Google 应用详情页（进「权限 → 麦克风」关闭即可根治占麦） */
    fun openGoogleAppSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$GOOGLE_APP_PACKAGE")),
            Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        return startFirstResolvable(context, candidates)
    }

    /**
     * 优先尝试拉起 Google 语音唤醒设置或系统「默认数字助理/语音输入」页，
     * 方便在系统层把默认助手改为「无」或关闭 Voice Match。
     */
    fun openVoiceAssistantSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("$GOOGLE_APP_PACKAGE.action.HOTWORD_DETECTION_SETTINGS")
                .setPackage(GOOGLE_APP_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$GOOGLE_APP_PACKAGE")),
            Intent(Settings.ACTION_SETTINGS),
        )
        return startFirstResolvable(context, candidates)
    }

    /** 跳转到系统「开发者选项」（未开启开发者模式时降级到「关于手机」或设置首页） */
    fun openDeveloperSettings(context: Context): Boolean {
        val candidates = mutableListOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        return startFirstResolvable(context, candidates)
    }

    fun copyText(context: Context, label: String, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    private fun startFirstResolvable(context: Context, candidates: List<Intent>): Boolean {
        for (raw in candidates) {
            val intent = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = runCatching {
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull()
            if (resolved?.activityInfo == null) continue
            if (resolved.activityInfo.packageName == context.packageName) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, "") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private data class ShellResult(val exitCode: Int, val output: String)

    private fun runSu(command: String): ShellResult = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val code = process.waitFor()
        ShellResult(code, output)
    }.getOrElse {
        ShellResult(-1, it.message.orEmpty())
    }
}
