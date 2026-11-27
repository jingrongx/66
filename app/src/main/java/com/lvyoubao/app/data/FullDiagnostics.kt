package com.lvyoubao.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lvyoubao.app.data.duplex.DuplexProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 全链路综合诊断（设计稿「一键体检：API / 语音 / 地图 / 通话 / 权限」）。
 * 结果是一段可直接截图发给开发者的中文报告；每项独立 try，单项失败不影响其它项。
 */
object FullDiagnostics {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun run(context: Context, s: AiSettings): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        lines += "===== 旅游宝全链路诊断 ====="
        lines += "版本：${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("?")}"
        lines += "时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}"
        lines += ""

        // 1. 网络基础
        val netOk = runCatching {
            probeClient.newCall(Request.Builder().url("https://www.baidu.com").head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        lines += "【网络】${if (netOk) "✓ 外网连通" else "✗ 外网不通（检查 Wi-Fi/流量/代理）"}"

        // 2. 麦克风权限与 Google 唤醒抢麦风险
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        lines += "【麦克风权限】${if (micOk) "✓ 已授予" else "✗ 未授予（设置→应用→权限）"}"
        val googleWake = DeviceEnvTools.inspectGoogleWakeStatus(context)
        val wakeConflictText = when (googleWake.state) {
            DeviceEnvTools.GoogleWakeState.NOT_INSTALLED,
            DeviceEnvTools.GoogleWakeState.APP_DISABLED -> "✓ 无 Google 唤醒冲突"
            DeviceEnvTools.GoogleWakeState.MIC_IGNORED -> "✓ Google 录音已禁止"
            DeviceEnvTools.GoogleWakeState.VOICE_INTERACTION_ACTIVE ->
                "△ Google 语音唤醒已激活，可能后台抢占麦克风（设置页→设备兼容与网络 ADB 可禁用）"
            DeviceEnvTools.GoogleWakeState.INSTALLED_INACTIVE ->
                "△ 已安装 Google（设置页→设备兼容与网络 ADB 可检查麦克风权限）"
        }
        lines += "【语音抢麦风险】$wakeConflictText"

        val adbInfo = DeviceEnvTools.inspectNetworkAdbStatus(context)
        val adbPortDesc = when {
            adbInfo.tcpPort != null -> "端口 ${adbInfo.tcpPort} 已开"
            adbInfo.hasRoot -> "未开（Root 可在设置页开启）"
            else -> "无 Root，端口状态受限"
        }
        lines += "【调试网络】Wi-Fi ${adbInfo.lanIp ?: "未连"} · USB调试=${if (adbInfo.adbEnabled) "开" else "关"} · 网络ADB=$adbPortDesc"

        // 3. 文本大模型 API（preset/baseUrl/key/model 齐全 + 探测 /models）
        val preset = presetById(s.presetId)
        val apiOk = when {
            s.apiKey.isBlank() -> "✗ 未配置 API Key"
            s.baseUrl.isBlank() -> "✗ 未配置接口地址"
            else -> {
                val ok = AiClient(s).listModels().isSuccess
                if (ok) "✓ ${preset.label} 连通，模型 ${s.model}" else "✗ ${preset.label} 连接失败（Key 错/余额不足/网络）"
            }
        }
        lines += "【文本 API】$apiOk"

        // 4. ASR（硅基流动 Key）
        val asrOk = when {
            s.asrKey.isBlank() -> "△ 未配语音识别 Key（用系统识别时无妨）"
            else -> {
                val ok = runCatching {
                    probeClient.newCall(Request.Builder().url("https://api.siliconflow.cn/v1/models").header("Authorization", "Bearer ${s.asrKey}").build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) "✓ 硅基流动 Key 有效" else "✗ 硅基流动 Key 无效或网络失败"
            }
        }
        lines += "【语音识别】$asrOk"

        // 5. 高德地图 Key
        val amapOk = when {
            s.amapKey.isBlank() -> "△ 未配高德 Key（附近搜索/路线不可用）"
            else -> {
                val ok = runCatching {
                    val url = "https://restapi.amap.com/v3/place/text?key=${s.amapKey}&keywords=公园&city=北京&output=json"
                    probeClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        body.contains("\"status\":\"1\"")
                    }
                }.getOrDefault(false)
                if (ok) "✓ 高德 Web 服务 Key 有效" else "✗ 高德 Key 无效（须选「Web服务」类型）"
            }
        }
        lines += "【地图】$amapOk"

        // 6. 豆包全双工 Key（只校验非空与格式；真正握手在通话时）
        val duplexOk = when {
            s.duplexApiKey.isBlank() -> "△ 未配豆包实时语音 Key（全双工通话不可用）"
            s.duplexApiKey.length < 10 -> "✗ Key 太短，疑似填错"
            else -> "✓ 已配置（${s.duplexApiKey.take(3)}…），音色 ${s.duplexVoice.take(12)}"
        }
        lines += "【全双工通话】$duplexOk · 端点 ${DuplexProtocol.WS_URL.substringAfterLast('/')}"

        // 7. TTS 引擎（复用现有三级诊断，只取首行结论避免报告过长）
        val ttsReport = com.lvyoubao.app.ui.common.TtsPlayer.diagnose(context, s.asrKey.ifBlank { null })
        val ttsFirst = ttsReport.lineSequence().firstOrNull { it.isNotBlank() } ?: "△ 无结果"
        lines += "【朗读 TTS】$ttsFirst"

        lines += ""
        lines += "===== 诊断结束 ====="
        lines.joinToString("\n")
    }
}
