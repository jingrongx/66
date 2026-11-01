package com.lvyoubao.app.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lvyoubao.app.data.AmapClient
import com.lvyoubao.app.data.ApiException
import com.lvyoubao.app.data.AppTools
import com.lvyoubao.app.data.BRouterClient
import com.lvyoubao.app.data.LocationContext
import com.lvyoubao.app.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(private val appContext: Context, private val repo: SettingsRepository) : ViewModel() {

    data class UiState(
        val maps: List<File> = emptyList(),          // 已下载的离线地图
        val remoteList: List<String> = emptyList(),  // 可下载的省区（mapsforge 服务器）
        val loadingRemote: Boolean = false,
        val downloading: String? = null,             // 正在下载的省区名
        val progress: Int = 0,
        val message: String? = null,
        val routeSummary: String? = null,
        val routing: Boolean = false,
        val routePoints: List<Pair<Double, Double>> = emptyList(),
    )

    private val mapDir = File(appContext.filesDir, "maps").apply { mkdirs() }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    init {
        refreshMaps()
        refreshRemoteList()
    }

    fun refreshMaps() {
        _ui.update { it.copy(maps = mapDir.listFiles { f -> f.name.endsWith(".map") }?.sortedBy { it.name }.orEmpty()) }
    }

    fun refreshRemoteList() {
        if (_ui.value.loadingRemote || _ui.value.remoteList.isNotEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingRemote = true) }
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://download.mapsforge.org/maps/v5/asia/china/"
                    val html = okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(url).header("User-Agent", "lvyoubao-app").get().build(),
                    ).execute().use { it.body?.string().orEmpty() }
                    Regex("href=\"([^\"]+\\.map)\"").findAll(html)
                        .map { it.groupValues[1] }
                        .map { it.removeSuffix(".map") }
                        .sorted()
                        .toList()
                }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(loadingRemote = false, remoteList = list, message = if (list.isEmpty()) "无法获取地图列表（网络受限），可用下方自定义链接下载" else null) }
        }
    }

    fun download(region: String) {
        if (_ui.value.downloading != null) return
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloading = region, progress = 0, message = null) }
            val url = if (region.startsWith("http")) region
            else "https://download.mapsforge.org/maps/v5/asia/china/$region.map"
            val dest = File(mapDir, "${region.removePrefix("http").substringAfterLast('/')}.map")
            withContext(Dispatchers.IO) {
                runCatching {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "lvyoubao-app").get().build())
                        .execute().use { response ->
                            if (!response.isSuccessful) throw ApiException("下载失败（HTTP ${response.code}）")
                            val body = response.body ?: throw ApiException("下载内容为空")
                            val total = body.contentLength()
                            var done = 0L
                            var lastPct = -1
                            body.byteStream().use { input ->
                                dest.outputStream().use { output ->
                                    val buf = ByteArray(128 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n == -1) break
                                        output.write(buf, 0, n)
                                        done += n
                                        if (total > 0) {
                                            val pct = (done * 100 / total).toInt()
                                            if (pct != lastPct) {
                                                lastPct = pct
                                                _ui.update { it.copy(progress = pct) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }.onFailure { e ->
                    dest.delete()
                    _ui.update { it.copy(downloading = null, message = "下载失败：${e.message}") }
                    return@withContext
                }
            }
            _ui.update { it.copy(downloading = null, progress = 100) }
            refreshMaps()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _ui.update { it.copy(downloading = null, message = "已取消下载") }
    }

    fun deleteMap(file: File) {
        file.delete()
        refreshMaps()
    }

    /** 步行路线：高德（有 Key）优先，BRouter（已安装且失败时）兜底 */
    fun routeTo(context: Context, destination: String) {
        if (_ui.value.routing || destination.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(routing = true, message = null, routeSummary = null, routePoints = emptyList()) }
            val settings = repo.settings.firstOrNull() ?: return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val place = LocationContext.current(context) ?: throw ApiException("没有位置信息，请授权定位")
                    if (settings.amapKey.isBlank() && !BRouterClient.isInstalled(context)) {
                        throw ApiException("查路线需要高德 Key（设置里填写）或安装 BRouter 应用")
                    }
                    var source = "高德步行路线"
                    val amap = AmapClient(settings.amapKey)
                    val to = if (settings.amapKey.isBlank()) {
                        throw ApiException("目的地解析需要高德 Key；也可直接输入「纬度,经度」")
                    } else {
                        amap.geocode(destination) ?: throw ApiException("无法解析目的地「$destination」")
                    }
                    val r = runCatching { amap.walkingRoute(place.lon, place.lat, to.first, to.second) }
                        .getOrElse { e ->
                            if (BRouterClient.isInstalled(context)) {
                                source = "BRouter 离线路线"
                                BRouterClient.walkingRoute(context, place.lon, place.lat, to.first, to.second)
                            } else throw e
                        }
                    AppTools.formatRoute(r, destination, source) to r.points
                }
            }
            result.fold(
                onSuccess = { (summary, points) ->
                    _ui.update { it.copy(routing = false, routeSummary = summary, routePoints = points) }
                },
                onFailure = { e ->
                    _ui.update { it.copy(routing = false, message = e.message ?: "路线查询失败") }
                },
            )
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
}
