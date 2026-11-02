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
import com.lvyoubao.app.data.TrackStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(
    private val appContext: Context,
    private val repo: SettingsRepository,
    private val trackStore: TrackStore,
) : ViewModel() {

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
        val trackPoints: List<Pair<Double, Double>> = emptyList(),  // 感知轨迹（足迹叠加）
    )

    private val mapDir = File(appContext.filesDir, "maps").apply { mkdirs() }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    init {
        cleanLegacyPartials()
        refreshMaps()
        refreshRemoteList()
        validateMaps()
        refreshTrack()
    }

    /** 历史版本可能残留下载中断的 .part 文件，启动时清掉 */
    private fun cleanLegacyPartials() {
        viewModelScope.launch(Dispatchers.IO) {
            mapDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    /** 校验已下载地图：损坏文件（下载中断遗留）直接清理并提示，避免进地图页必崩 */
    private fun validateMaps() {
        viewModelScope.launch(Dispatchers.IO) {
            val broken = mapDir.listFiles { f -> f.name.endsWith(".map") }?.filter { file ->
                runCatching {
                    org.mapsforge.map.reader.MapFile(file).close()
                    false
                }.getOrDefault(true)
            }.orEmpty()
            if (broken.isEmpty()) return@launch
            broken.forEach { it.delete() }
            _ui.update {
                it.copy(
                    maps = it.maps.filter { m -> m !in broken },
                    message = "清理了 ${broken.size} 个损坏的地图文件（多半是之前下载中断造成的），重新下载即可",
                )
            }
        }
    }

    /** 感知轨迹（足迹）加载，叠加到离线地图 */
    fun refreshTrack() {
        viewModelScope.launch(Dispatchers.IO) {
            val points = trackStore.points.value
                .map { Pair(it.lon, it.lat) }
            _ui.update { it.copy(trackPoints = points) }
        }
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
        val fileName = (if (region.startsWith("http")) region.substringAfterLast('/') else region)
            .removeSuffix(".map")
        val dest = File(mapDir, "$fileName.map")
        val part = File(mapDir, "$fileName.map.part")
        val url = if (region.startsWith("http")) region
        else "https://download.mapsforge.org/maps/v5/asia/china/$fileName.map"
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloading = region, progress = 0, message = null) }
            try {
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
                                    part.outputStream().use { output ->
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
                        throw if (e is ApiException) e else ApiException("下载失败：${e.message ?: "网络异常"}")
                    }
                }
                // 完整下完才验证+改名：中断/取消不会残留半截 .map（此前半截文件会让地图页必崩）
                val invalid = withContext(Dispatchers.IO) {
                    runCatching {
                        org.mapsforge.map.reader.MapFile(part).close()
                        false
                    }.getOrDefault(true)
                }
                if (invalid) {
                    part.delete()
                    throw ApiException("下载的地图文件不完整，请换个网络重试")
                }
                dest.delete()
                if (!part.renameTo(dest)) throw ApiException("地图文件保存失败，请重试")
                _ui.update { it.copy(downloading = null, progress = 100) }
                refreshMaps()
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                _ui.update { it.copy(downloading = null, message = "已取消下载") }
            } catch (e: ApiException) {
                part.delete()
                _ui.update { it.copy(downloading = null, message = e.message ?: "下载失败") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
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
