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
        val remoteDates: Map<String, Long> = emptyMap(), // 各省区远端数据日期（文件名→时间戳）
        val updatesAvailable: Set<String> = emptySet(),  // 本地已有且远端数据更新的省区
        val loadingRemote: Boolean = false,
        val downloading: String? = null,             // 正在下载的省区名
        val progress: Int = 0,
        val progressBytes: Long = 0,                 // 已下载字节（进度卡显示速度/剩余时间用）
        val totalBytes: Long = 0,
        val speedKbs: Long = 0,                      // 实测速度（KB/s，指数滑动平均）
        val message: String? = null,
        val routeSummary: String? = null,
        val routing: Boolean = false,
        val routePoints: List<Pair<Double, Double>> = emptyList(),
        val trackPoints: List<Pair<Double, Double>> = emptyList(),  // 感知轨迹（足迹叠加）
    )

    /** 下载速度表：指数滑动平均，避免瞬时抖动跳来跳去 */
    private class SpeedMeter {
        private var lastTs = 0L
        private var lastBytes = 0L
        private var ema = 0f

        fun tick(done: Long): Float = synchronized(this) {
            val now = System.currentTimeMillis()
            if (lastTs == 0L) {
                lastTs = now
                lastBytes = done
                return 0f
            }
            val dt = now - lastTs
            if (dt >= 300) {
                val instant = (done - lastBytes).coerceAtLeast(0) * 1000f / dt
                ema = if (ema == 0f) instant else ema * 0.7f + instant * 0.3f
                lastTs = now
                lastBytes = done
            }
            ema
        }
    }

    /** 只存「上次检查更新时间」，用于每天进页自动检查一次（OSM 数据定期更新） */
    @kotlinx.serialization.Serializable
    data class MapRegistry(val lastCheck: Long = 0)

    private val mapDir = File(appContext.filesDir, "maps").apply { mkdirs() }
    private val registryFile get() = File(mapDir, "registry.json")

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    init {
        cleanLegacyPartials()
        refreshMaps()
        // 地图数据有时效性：距上次检查超过 24 小时（或从没查过）就在进页时自动检查一次
        refreshRemoteList(force = lastCheckStale())
        validateMaps()
        refreshTrack()
    }

    private fun lastCheckStale(): Boolean = runCatching {
        val reg = kotlinx.serialization.json.Json.decodeFromString<MapRegistry>(registryFile.readText())
        System.currentTimeMillis() - reg.lastCheck > 24 * 60 * 60 * 1000L
    }.getOrDefault(true)

    /** 历史版本可能残留下载中断的 .part 文件：带 .meta 断点的是可续传的，留着；其余清掉 */
    private fun cleanLegacyPartials() {
        viewModelScope.launch(Dispatchers.IO) {
            mapDir.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".part.meta") -> if (!File(f.parentFile, f.name.removeSuffix(".meta")).exists()) f.delete()
                    f.name.endsWith(".part") -> if (!File(f.parentFile, f.name + ".meta").exists()) f.delete()
                }
            }
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

    /** 拉取远端省区列表 + 数据日期（Apache 目录页每行带日期）；force=true 时忽略缓存强制刷新 */
    fun refreshRemoteList(force: Boolean = false) {
        if (_ui.value.loadingRemote) return
        if (!force && _ui.value.remoteList.isNotEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingRemote = true) }
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://download.mapsforge.org/maps/v5/asia/china/"
                    val html = okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(url).header("User-Agent", "lvyoubao-app").get().build(),
                    ).execute().use { it.body?.string().orEmpty() }
                    val list = Regex("href=\"([^\"]+\\.map)\"").findAll(html)
                        .map { it.groupValues[1].removeSuffix(".map") }
                        .sorted()
                        .toList()
                    // 每个条目 href 之后、下一个条目之前的尾巴里带日期（ISO 或 Apache 两种格式）
                    val dates = HashMap<String, Long>()
                    html.split("href=\"").drop(1).forEach { chunk ->
                        val name = chunk.substringBefore('"').removeSuffix(".map")
                        val tail = chunk.substringAfter('"').substringBefore("href=\"")
                        parseIndexDate(tail)?.let { dates[name] = it }
                    }
                    list to dates
                }.getOrDefault(emptyList<String>() to emptyMap<String, Long>())
            }
            val (list, dates) = fetched
            if (list.isNotEmpty()) {
                runCatching {
                    registryFile.writeText(
                        kotlinx.serialization.json.Json.encodeToString(
                            MapRegistry.serializer(),
                            MapRegistry(lastCheck = System.currentTimeMillis()),
                        ),
                    )
                }
            }
            _ui.update { state ->
                state.copy(
                    loadingRemote = false,
                    remoteList = list,
                    remoteDates = dates,
                    updatesAvailable = computeUpdates(state.maps, dates),
                    message = if (list.isEmpty()) "无法获取地图列表（网络受限），可用下方自定义链接下载" else null,
                )
            }
        }
    }

    /** 目录页日期解析：ISO（2026-05-12 03:14）或 Apache（12-May-2026 03:14），解析不了返回 null */
    private fun parseIndexDate(text: String): Long? {
        val iso = Regex("(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})").find(text)?.groupValues
        if (iso != null) {
            return runCatching {
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    set(iso[1].toInt(), iso[2].toInt() - 1, iso[3].toInt(), iso[4].toInt(), iso[5].toInt(), 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.getOrNull()
        }
        val apache = Regex("(\\d{2})-([A-Za-z]{3})-(\\d{4})\\s+(\\d{2}):(\\d{2})").find(text)?.groupValues
        if (apache != null) {
            val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            val month = months.indexOf(apache[2].lowercase())
            if (month >= 0) {
                return runCatching {
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                        set(apache[3].toInt(), month, apache[1].toInt(), apache[4].toInt(), apache[5].toInt(), 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }.getOrNull()
            }
        }
        return null
    }

    /** 本地已有、且远端数据日期比本地文件新的省区（本地文件的下载数据日期存在 lastModified 里） */
    private fun computeUpdates(maps: List<File>, dates: Map<String, Long>): Set<String> {
        val margin = 10 * 60 * 1000L // 容 10 分钟时钟差
        return maps.mapNotNull { f ->
            val name = f.name.removeSuffix(".map")
            val remote = dates[name]
            if (remote != null && remote > f.lastModified() + margin) name else null
        }.toSet()
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
            val resumed = part.exists() && File(part.parentFile, part.name + ".meta").exists()
            _ui.update {
                it.copy(
                    downloading = region,
                    progress = 0,
                    progressBytes = 0,
                    totalBytes = 0,
                    speedKbs = 0,
                    message = null,
                )
            }
            val meter = SpeedMeter()
            try {
                // 分段并行 + 断点续传：取消/失败留下的 .part/.meta 会接着传，不从头来
                MapDownloader.download(url, part) { done, total ->
                    val kbs = (meter.tick(done) / 1024).toLong().coerceAtLeast(0)
                    _ui.update {
                        it.copy(
                            progress = (done * 100 / total).toInt(),
                            progressBytes = done,
                            totalBytes = total,
                            speedKbs = kbs,
                        )
                    }
                }
                // 完整下完才验证+改名：半截文件进地图页会必崩
                val invalid = withContext(Dispatchers.IO) {
                    runCatching {
                        org.mapsforge.map.reader.MapFile(part).close()
                        false
                    }.getOrDefault(true)
                }
                if (invalid) {
                    File(part.parentFile, part.name + ".meta").delete()
                    part.delete()
                    throw ApiException("下载的地图文件不完整，请换个网络重试")
                }
                dest.delete()
                if (!part.renameTo(dest)) throw ApiException("地图文件保存失败，请重试")
                // 把远端数据日期写进文件时间戳，时效检测全靠它比对
                val remoteDate = _ui.value.remoteDates[fileName]
                dest.setLastModified(remoteDate ?: System.currentTimeMillis())
                _ui.update { state ->
                    val dateText = remoteDate?.let {
                        "（" + java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date(it)) + " 数据）"
                    } ?: ""
                    state.copy(
                        downloading = null,
                        progress = 100,
                        updatesAvailable = computeUpdates(state.maps + dest, state.remoteDates) - setOf(fileName),
                        message = "已下载「${MapRegions.displayName(fileName)}」$dateText",
                    )
                }
                refreshMaps()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 断点保留：.part + .meta 都在，再点「下载」从断点接着传
                _ui.update {
                    it.copy(
                        downloading = null,
                        message = "已暂停下载" + if (resumed || part.exists()) "，进度已记住（再点「下载」接着传）" else "",
                    )
                }
            } catch (e: ApiException) {
                // 失败也留断点，只有服务器不支持续传时才清
                _ui.update { it.copy(downloading = null, message = e.message ?: "下载失败（重试会断点续传）") }
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
                        AmapClient.resolveDestination(amap, destination, place.lon, place.lat)
                            ?: throw ApiException("无法解析目的地「$destination」")
                    }
                    val r = runCatching { amap.walkingRoute(place.lon, place.lat, to.lon, to.lat) }
                        .getOrElse { e ->
                            if (BRouterClient.isInstalled(context)) {
                                source = "BRouter 离线路线"
                                BRouterClient.walkingRoute(context, place.lon, place.lat, to.lon, to.lat)
                            } else throw e
                        }
                    AppTools.formatRoute(r, to.name, source) to r.points
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
