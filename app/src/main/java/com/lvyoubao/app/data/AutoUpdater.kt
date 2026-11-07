package com.lvyoubao.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用内自动更新流水线（应用级单例，检查页/弹窗共用一条流水线，绝不双下载）：
 * - 启动即查 + 每 30 分钟静默复查一轮（检查在循环内 await，绝不让下一句读到旧状态）；
 * - 发现新版本【自动在后台下载】（国内优先走最快的加速线路，见 UpdateChecker.fastFirst），
 *   手动检查发现的新版同样自动下载；
 * - 下载完成后置 ready，界面弹「点击安装」——用户点一下就装，全程不用盯进度条；
 * - 失败静默，下个周期自动重试；手动检查入口（更新页）走同一条流水线。
 */
object AutoUpdater {

    data class State(
        val currentVersion: String = "",
        val checking: Boolean = false,
        val release: ReleaseInfo? = null,
        val upToDate: Boolean = false,
        val error: String? = null,
        val downloading: Boolean = false,
        val progress: Int = 0,
        val downloadedApk: File? = null,
        // 弹窗点「稍后」的版本：本轮进程内不再自动弹（更新页里仍可见、仍可安装）
        val dismissedTag: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val checker = UpdateChecker()
    private lateinit var appContext: Context
    private var started = false

    /** 静默复查间隔：30 分钟 */
    private const val INTERVAL_MS = 30 * 60_000L

    @Synchronized
    fun start(app: Context) {
        appContext = app.applicationContext
        if (started) return
        started = true
        val versionName = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()
        _state.update { it.copy(currentVersion = versionName ?: "?") }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                // 检查在循环内同步等待：拿到结果立刻决定是否自动下载（v0.8.35 的竞态修复：
                // 旧版 checkNow 发射后不管，循环读到的是检查前的旧状态，自动下载永远不触发）
                val release = doCheck(manual = false)
                if (release != null) downloadNow()
                delay(INTERVAL_MS)
            }
        }
    }

    /** 更新页手动检查：结果与错误都展示；发现新版同样接自动下载 */
    fun checkNow(manual: Boolean) {
        val cur = _state.value
        if (cur.checking || cur.downloading) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (doCheck(manual) != null) downloadNow()
        }
    }

    /**
     * 执行一轮检查（挂起，完成后状态已落地）：
     * 发现新版本返回 ReleaseInfo（顺带触发自动下载），否则返回 null。
     */
    private suspend fun doCheck(manual: Boolean): ReleaseInfo? {
        _state.update { it.copy(checking = true, error = null, upToDate = false) }
        return checker.latestRelease().fold(
            onSuccess = { release ->
                val newer = Versions.isNewer(release.tag_name, _state.value.currentVersion)
                val cleaned = if (newer) release.copy(body = release.body?.let { AiClient.stripMarkdown(it) }) else release
                _state.update {
                    it.copy(checking = false, release = if (newer) cleaned else null, upToDate = !newer)
                }
                if (newer) cleaned else null
            },
            onFailure = { e ->
                _state.update {
                    if (manual) it.copy(checking = false, error = e.message ?: "检查更新失败")
                    else it.copy(checking = false)   // 静默：失败不打扰，下个周期再试
                }
                null
            },
        )
    }

    private var downloadJob: Job? = null

    /** 后台下载（自动触发与手动触发共用；下载走最快线路优先） */
    fun downloadNow() {
        val asset = _state.value.release?.apkAsset ?: return
        if (_state.value.downloading) return
        downloadJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            _state.update { it.copy(downloading = true, progress = 0, error = null, downloadedApk = null) }
            val dir = File(appContext.cacheDir, "updates")
            checker.downloadApk(asset, dir, onProgress = { pct ->
                _state.update { it.copy(progress = pct) }
            }).fold(
                onSuccess = { file ->
                    _state.update { it.copy(downloading = false, downloadedApk = file) }
                },
                onFailure = { e ->
                    _state.update { it.copy(downloading = false, error = e.message ?: "下载失败") }
                },
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(downloading = false) }
    }

    /** 弹窗点「稍后」：本轮进程内不再自动弹这个版本（更新页里仍可见、仍可安装） */
    fun dismissUpdate() {
        _state.update { it.copy(dismissedTag = it.release?.tag_name) }
    }

    fun install(context: Context) {
        val file = _state.value.downloadedApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            _state.update { it.copy(error = "请先允许「安装未知应用」权限，再点安装") }
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { e ->
            _state.update { it.copy(error = "无法启动安装：${e.message ?: "未知原因"}") }
        }
    }
}
