package com.lvyoubao.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.ApiException
import com.lvyoubao.app.data.ReleaseInfo
import com.lvyoubao.app.data.UpdateChecker
import com.lvyoubao.app.data.Versions
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateViewModel(app: TravelApp) : ViewModel() {

    private val checker = UpdateChecker()
    private val appContext = app

    data class UiState(
        val currentVersion: String = "",
        val checking: Boolean = false,
        val release: ReleaseInfo? = null,
        val upToDate: Boolean = false,
        val error: String? = null,
        val downloading: Boolean = false,
        val progress: Int = 0,
        val downloadedApk: File? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        val versionName = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()
        _ui.update { it.copy(currentVersion = versionName ?: "?") }
    }

    /** 启动时静默检查：发现新版本才出提示，失败不打扰 */
    fun autoCheck() {
        if (_ui.value.checking || _ui.value.release != null || _ui.value.downloading) return
        check(silent = true)
    }

    /** 更新页里的手动检查：结果与错误都展示 */
    fun manualCheck() = check(silent = false)

    private fun check(silent: Boolean) {
        if (_ui.value.checking || _ui.value.downloading) return
        checkJob = viewModelScope.launch {
            _ui.update { it.copy(checking = true, error = null, upToDate = false) }
            checker.latestRelease().fold(
                onSuccess = { release ->
                    val newer = Versions.isNewer(release.tag_name, _ui.value.currentVersion)
                    _ui.update {
                        it.copy(checking = false, release = if (newer) release else null, upToDate = !newer)
                    }
                },
                onFailure = { e ->
                    _ui.update {
                        if (silent) it.copy(checking = false)
                        else it.copy(checking = false, error = e.message ?: "检查更新失败")
                    }
                },
            )
        }
    }

    fun download() {
        val asset = _ui.value.release?.apkAsset ?: return
        if (_ui.value.downloading) return
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloading = true, progress = 0, error = null, downloadedApk = null) }
            val dir = File(appContext.cacheDir, "updates")
            checker.downloadApk(asset, dir, onProgress = { pct ->
                _ui.update { it.copy(progress = pct) }
            }).fold(
                onSuccess = { file ->
                    _ui.update { it.copy(downloading = false, downloadedApk = file) }
                },
                onFailure = { e ->
                    _ui.update { it.copy(downloading = false, error = e.message ?: "下载失败") }
                },
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _ui.update { it.copy(downloading = false) }
    }

    fun install(context: Context) {
        val file = _ui.value.downloadedApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            _ui.update { it.copy(error = "请先允许「安装未知应用」权限，再点安装") }
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
            _ui.update { state -> state.copy(error = "无法启动安装：${e.message ?: "未知原因"}") }
        }
    }
}
