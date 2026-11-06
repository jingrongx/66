package com.lvyoubao.app.data

import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** 一台设备上某个离线地图的信息（文件名含 .map；date=数据日期，取自文件 lastModified） */
@Serializable
data class MapSyncMapInfo(val file: String, val size: Long, val date: Long)

@Serializable
data class MapSyncInfo(val name: String, val model: String, val maps: List<MapSyncMapInfo>)

/**
 * 离线地图设备间同步：同一 Wi-Fi / 手机热点直连（ServerSocket HTTP + 一次性口令），完全无网可用。
 *
 * 协议（全部要求 ?t=<token> 口令，口令随机生成只在自己出示的二维码里）：
 *   GET  /info            → MapSyncInfo JSON：设备名/型号/已下载地图清单（含数据日期）
 *   GET  /map/<文件名>     → .map 文件字节流（对端数据更新时拉过来）
 *   POST /upload/<文件名>?d=<日期> → body 为 .map 字节流（本机数据更新时推过去）
 *
 * 纯 Java/Android API（ServerSocket + Camera1 扫码 + OkHttp），不依赖任何谷歌服务，
 * 鸿蒙系统（可装 APK 的 HarmonyOS）同样可用；热点场景就是普通局域网，无需额外发现机制。
 */
object MapSync {

    private const val QR_PREFIX = "lvyoubao://mapsync/"
    private val json = Json { ignoreUnknownKeys = true }

    data class Endpoint(val host: String, val port: Int, val token: String, val qrContent: String)

    fun qr(host: String, port: Int, token: String): String = "$QR_PREFIX?h=$host&p=$port&t=$token"

    /** 解析本功能的同步二维码；不是本功能的码返回 null */
    fun parseQr(content: String): Endpoint? {
        if (!content.startsWith(QR_PREFIX)) return null
        val query = content.substringAfter('?', "")
        var host: String? = null
        var port: Int? = null
        var token: String? = null
        query.split('&').forEach { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@forEach
            when (pair.substring(0, i)) {
                "h" -> host = pair.substring(i + 1)
                "p" -> port = pair.substring(i + 1).toIntOrNull()
                "t" -> token = pair.substring(i + 1)
            }
        }
        val h = host ?: return null
        val p = port ?: return null
        val t = token ?: return null
        return Endpoint(h, p, t, content)
    }

    private val ADJECTIVES = listOf("机灵", "慢悠悠", "爱旅行", "欢脱", "好奇", "踏实", "阳光", "爱笑", "省心", "热心")
    private val ANIMALS = listOf("锦鲤", "海豚", "熊猫", "骆驼", "企鹅", "狐狸", "考拉", "白鹭", "小鹿", "仓鼠")

    /** 设备名：中文随机名 + 型号（如 机灵锦鲤·ALN-AL10），首次生成后持久化 */
    fun generateName(): String {
        val model = (android.os.Build.MODEL ?: "").trim().take(20).ifBlank { "安卓设备" }
        return "${ADJECTIVES.random()}${ANIMALS.random()}·$model"
    }

    /** 发起端：出示二维码期间开启；对端扫码后经 /info /map /upload 与本机交换地图 */
    class Server(
        private val mapDir: File,
        private val myName: String,
        private val myModel: String,
        private val onEvent: (String) -> Unit,   // 同步动态（追加到界面日志）
        private val onMapsChanged: () -> Unit,   // 收到新地图后刷新列表
    ) {

        data class Endpoint(val host: String, val port: Int, val token: String, val qrContent: String)

        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null

        @Volatile
        private var running = false

        /** 开启服务；失败（如取不到局域网地址）返回 null */
        fun start(): Endpoint? {
            stop()
            val ip = lanIpv4() ?: return null
            return try {
                val ss = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                running = true
                val token = UUID.randomUUID().toString().replace("-", "").take(10)
                acceptThread = Thread {
                    while (running) {
                        val client = try {
                            ss.accept()
                        } catch (_: Exception) {
                            break
                        }
                        handleClient(client, token)
                    }
                }.apply {
                    isDaemon = true
                    name = "map-sync-server"
                    start()
                }
                Endpoint(ip, ss.localPort, token, qr(ip, ss.localPort, token))
            } catch (_: Exception) {
                stop()
                null
            }
        }

        fun stop() {
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            acceptThread = null
        }

        private fun lanIpv4(): String? = try {
            val candidates = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let(candidates::add)
                    }
                }
            }
            candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }

        private fun handleClient(client: Socket, token: String) {
            try {
                client.soTimeout = 8000
                client.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    val requestLine = readLine(input) ?: return
                    var contentLength = 0L
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            val name = line.substring(0, idx).trim().lowercase()
                            val value = line.substring(idx + 1).trim()
                            if (name == "content-length") contentLength = value.toLongOrNull() ?: 0L
                        }
                    }
                    val method = requestLine.substringBefore(' ')
                    val path = requestLine.substringAfter(' ', "/").substringBefore('?').substringBefore(' ')
                    val query = requestLine.substringAfter('?', "").substringBefore(' ')
                    val givenToken = query.split('&').firstOrNull { it.startsWith("t=") }?.substring(2) ?: ""
                    val dateMs = query.split('&').firstOrNull { it.startsWith("d=") }?.substring(2)?.toLongOrNull() ?: 0L
                    // 头部读完再放大超时：大文件上传/下载不是 8 秒能完的
                    client.soTimeout = 180_000

                    if (givenToken != token) {
                        respond(s, """{"ok":false,"error":"token"}""")
                        return
                    }

                    when {
                        method == "GET" && path == "/info" -> {
                            val maps = mapDir.listFiles { f -> f.name.endsWith(".map") }.orEmpty()
                                .map { MapSyncMapInfo(it.name, it.length(), it.lastModified()) }
                            respond(s, json.encodeToString(MapSyncInfo(myName, myModel, maps)))
                        }

                        method == "GET" && path.startsWith("/map/") -> {
                            val name = java.net.URLDecoder.decode(path.removePrefix("/map/"), "UTF-8")
                            val file = safeMapFile(mapDir, name)
                            if (file == null || !file.exists()) {
                                respond(s, """{"ok":false,"error":"no such map"}""")
                            } else {
                                onEvent("对端拉取了 ${file.name}")
                                s.getOutputStream().apply {
                                    write(
                                        ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                            "Content-Length: ${file.length()}\r\nConnection: close\r\n\r\n")
                                            .toByteArray(Charsets.UTF_8),
                                    )
                                    file.inputStream().use { src -> src.copyTo(this, 128 * 1024) }
                                    flush()
                                }
                            }
                        }

                        method == "POST" && path.startsWith("/upload/") -> {
                            val name = java.net.URLDecoder.decode(path.removePrefix("/upload/"), "UTF-8")
                            val dest = safeMapFile(mapDir, name)
                            if (dest == null || contentLength <= 0 || contentLength > 2L * 1024 * 1024 * 1024) {
                                respond(s, """{"ok":false,"error":"bad request"}""")
                                return
                            }
                            // 对端只在它更新时才推：本机更新就拒收，不覆盖新数据
                            if (dest.exists() && dateMs in 1..(dest.lastModified() - 60_000)) {
                                respond(s, """{"ok":false,"error":"newer local"}""")
                                onEvent("跳过 $name：本机数据更新")
                                return
                            }
                            val part = File(mapDir, "$name.sync.part")
                            part.outputStream().use { out ->
                                val buf = ByteArray(128 * 1024)
                                var remaining = contentLength
                                while (remaining > 0) {
                                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    remaining -= n
                                }
                            }
                            val invalid = runCatching {
                                org.mapsforge.map.reader.MapFile(part).close()
                                false
                            }.getOrDefault(true)
                            if (invalid) {
                                part.delete()
                                respond(s, """{"ok":false,"error":"invalid map"}""")
                                return
                            }
                            dest.delete()
                            if (!part.renameTo(dest)) {
                                part.delete()
                                respond(s, """{"ok":false,"error":"save failed"}""")
                                return
                            }
                            if (dateMs > 0) dest.setLastModified(dateMs)
                            respond(s, """{"ok":true}""")
                            onEvent("收到对端推送 $name（${fmtDate(dateMs)} 数据）")
                            onMapsChanged()
                        }

                        else -> respond(s, """{"ok":false,"error":"unknown"}""")
                    }
                }
            } catch (_: Exception) {
                // 单个连接失败不影响服务继续
            }
        }

        private fun respond(s: Socket, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            s.getOutputStream().apply {
                write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
                )
                write(bytes)
                flush()
            }
        }

        private fun fmtDate(ms: Long): String =
            java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date(ms))

        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8 * 1024) return null
            }
            return sb.toString()
        }
    }

    /** 防路径穿越：只允许本地图目录下的 .map 文件 */
    internal fun safeMapFile(mapDir: File, name: String): File? {
        if (!name.endsWith(".map")) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(mapDir, name)
        return if (f.canonicalPath.startsWith(mapDir.canonicalPath)) f else null
    }

    // ---------- 客户端（连接对端） ----------

    private fun httpClient() = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 拉对端设备信息与地图清单 */
    suspend fun fetchInfo(host: String, port: Int, token: String): MapSyncInfo = withContext(Dispatchers.IO) {
        val body = httpClient().newCall(
            Request.Builder().url("http://$host:$port/info?t=$token").get().build(),
        ).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("连接失败（HTTP ${r.code}）")
            r.body?.string() ?: throw ApiException("对端没有返回信息")
        }
        runCatching { json.decodeFromString<MapSyncInfo>(body) }.getOrElse {
            throw ApiException("这不是旅游宝的地图同步服务")
        }
    }

    /** 从对端下载一个地图文件到 [mapDir]，返回落地的文件 */
    suspend fun downloadMap(
        host: String,
        port: Int,
        token: String,
        fileName: String,
        mapDir: File,
        dateMs: Long,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dest = safeMapFile(mapDir, fileName) ?: throw ApiException("非法的地图文件名")
        val part = File(mapDir, "$fileName.sync.part")
        try {
            httpClient().newCall(
                Request.Builder().url("http://$host:$port/map/${URLEncoder.encode(fileName, "UTF-8")}?t=$token").get().build(),
            ).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("对端取图失败（HTTP ${r.code}）")
                val body = r.body ?: throw ApiException("对端返回空内容")
                val total = body.contentLength()
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done, total)
                    }
                }
            }
            val invalid = runCatching {
                org.mapsforge.map.reader.MapFile(part).close()
                false
            }.getOrDefault(true)
            if (invalid) throw ApiException("传输的地图不完整，请重试")
            dest.delete()
            if (!part.renameTo(dest)) throw ApiException("地图保存失败，请重试")
            if (dateMs > 0) dest.setLastModified(dateMs)
            dest
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** 把本机地图推送给对端（对端数据较旧或没有时） */
    suspend fun uploadMap(host: String, port: Int, token: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            val body = file.asRequestBody("application/octet-stream".toMediaType())
            val url = "http://$host:$port/upload/" + URLEncoder.encode(file.name, "UTF-8") +
                "?t=$token&d=${file.lastModified()}"
            httpClient().newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("推送失败（HTTP ${r.code}）")
                val text = r.body?.string().orEmpty()
                if (text.contains("\"newer local\"")) {
                    false // 对端更新，拒收是正常情况
                } else {
                    text.contains("\"ok\":true")
                }
            }
        }
}
