package com.lvyoubao.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.AutoUpdater
import kotlinx.coroutines.flow.StateFlow

/**
 * 更新页的 VM：全部逻辑都在应用级单例 [AutoUpdater]（启动即查+30 分钟轮询+自动后台下载），
 * 这里只做薄代理——手动检查/下载/取消/安装与后台自动流程共用一条流水线，绝不双下载。
 */
class UpdateViewModel(app: TravelApp) : ViewModel() {

    init {
        AutoUpdater.start(app)
    }

    val ui: StateFlow<AutoUpdater.State> = AutoUpdater.state

    /** 启动时的静默检查：流水线本身已经在轮询，这里只是兜底触发（幂等） */
    fun autoCheck() = AutoUpdater.checkNow(manual = false)

    /** 更新页里的手动检查：结果与错误都展示 */
    fun manualCheck() = AutoUpdater.checkNow(manual = true)

    fun download() = AutoUpdater.downloadNow()

    fun cancelDownload() = AutoUpdater.cancelDownload()

    fun install(context: Context) = AutoUpdater.install(context)
}
