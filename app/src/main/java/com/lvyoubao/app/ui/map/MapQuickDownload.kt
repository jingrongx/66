package com.lvyoubao.app.ui.map

/**
 * 会话地图卡里的「缺哪个省补哪个省」：用户站在省界（如三清山服务区，江西侧）只下载了邻省时，
 * 底图一半空白——不用退回离线地图页翻列表，就地下载缺失省份，下完自动回填底图。
 * 镜像优先、官方兜底、断点续传（.part+.meta）、完整性校验，与离线地图页同款规则。
 */
internal object MapQuickDownload {

    /**
     * 下载一个省的离线地图（provinceZh 传中文名，如「江西」），成功返回地图文件。
     * 本地已有完好文件就直接返回不重复下；force=true 跳过这个检查强制重下——
     * 文件存在但关键点探不到数据时（残缺/只盖到邻省一角），只有重下才能治好。
     * 失败抛异常，由调用方展示；断点保留可重试。
     */
    suspend fun downloadProvince(
        context: android.content.Context,
        provinceZh: String,
        mirrorUrl: String,
        force: Boolean = false,
        onProgress: (done: Long, total: Long) -> Unit,
    ): java.io.File {
        val slug = MapRegions.slugOf(provinceZh)
            ?: throw com.lvyoubao.app.data.ApiException("没找到「$provinceZh」对应的地图下载项")
        val dir = java.io.File(context.filesDir, "maps").apply { mkdirs() }
        val dest = java.io.File(dir, "$slug.map")
        if (force) dest.delete()
        if (dest.exists() && dest.length() > 0) {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { org.mapsforge.map.reader.MapFile(dest).close(); true }.getOrDefault(false)
            }
            if (ok) return dest
            dest.delete()   // 存在但打不开：当坏文件清掉重下
        }
        val part = java.io.File(dir, "$slug.map.part")
        val official = "https://download.mapsforge.org/maps/v5/asia/china/$slug.map"
        val mirror = mirrorUrl.trim().trimEnd('/')
        val urls = buildList {
            if (mirror.isNotEmpty()) add("$mirror/$slug.map")
            add(official)
        }
        var lastError: Exception? = null
        for (candidate in urls) {
            try {
                MapDownloader.download(candidate, part, onProgress)
                lastError = null
                break
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        // 完整下完才验证+改名：半截文件进渲染层会必崩
        val invalid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { org.mapsforge.map.reader.MapFile(part).close(); false }.getOrDefault(true)
        }
        if (invalid) {
            java.io.File(dir, part.name + ".meta").delete()
            part.delete()
            throw com.lvyoubao.app.data.ApiException("下载的地图文件不完整，请换个网络重试")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw com.lvyoubao.app.data.ApiException("地图文件保存失败，请重试")
        return dest
    }
}
