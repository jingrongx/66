package com.lvyoubao.app.ui.observation

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.TravelApp
import com.lvyoubao.app.data.observation.FatigueSample
import com.lvyoubao.app.data.observation.ObservationEvent
import com.lvyoubao.app.data.observation.ObservationStore
import com.lvyoubao.app.data.observation.SensingBus
import com.lvyoubao.app.sensing.FatigueSensingService
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ObservationViewModel(app: TravelApp) : ViewModel() {

    private val store: ObservationStore = app.observationStore

    val samples: StateFlow<List<FatigueSample>> = store.samples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events: StateFlow<List<ObservationEvent>> = store.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val running = SensingBus.running.asStateFlow()
    val startedAt = SensingBus.startedAt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun startRecording(context: Context) {
        FatigueSensingService.start(context)
    }

    fun stopRecording(context: Context) {
        FatigueSensingService.stop(context)
    }

    /** 观测版的关键动作：用户自报真实感受，作为回测的地面真值 */
    fun label(type: String, detail: String) {
        viewModelScope.launch {
            store.appendEvent(
                ObservationEvent(
                    ts = System.currentTimeMillis(),
                    type = type,
                    detail = detail,
                    score = samples.value.lastOrNull()?.score ?: -1,
                )
            )
            _message.value = "已记录：$detail"
        }
    }

    fun shareCsv(context: Context) {
        val files: List<File> = store.exportCsv()
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出感知数据")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
