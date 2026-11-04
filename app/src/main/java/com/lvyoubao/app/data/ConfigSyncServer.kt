package com.lvyoubao.app.data

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * 扫码同步用的极简一次性 HTTP 服务（车机端接收配置）。
 *
 * 生命周期：显示二维码期间开启，关闭二维码即停止。
 * 只接受一个路径：POST /sync?t=<token>，body 为 SyncableConfig 的 JSON。
 * token 为每次开启随机生成的一次性口令，防止局域网内无关请求写入配置。
 */
class ConfigSyncServer(private val onConfigReceived: (SyncableConfig) -> Unit) {

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
                name = "config-sync-server"
                start()
            }
            Endpoint(ip, ss.localPort, token, ConfigSyncCodec.encodeLanQr(ip, ss.localPort, token))
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

    /** 取本机局域网 IPv4（优先 192.168 网段） */
    private fun lanIpv4(): String? {
        return try {
            val candidates = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(host)
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
    }

    private fun handleClient(client: Socket, token: String) {
        try {
            client.soTimeout = 8000
            client.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val query = requestLine.substringAfter('?', "").substringBefore(' ')
                val givenToken = query.split('&')
                    .firstOrNull { it.startsWith("t=") }
                    ?.substring(2) ?: ""

                val body = if (contentLength > 0 && contentLength < 64 * 1024) {
                    val buf = ByteArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = input.read(buf, off, contentLength - off)
                        if (n < 0) break
                        off += n
                    }
                    String(buf, 0, off, Charsets.UTF_8)
                } else ""

                val valid = givenToken == token
                val config = if (valid && body.isNotEmpty()) ConfigSyncCodec.decodeJson(body) else null
                val respBody = when {
                    !valid -> """{"ok":false,"error":"token"}"""
                    config == null -> """{"ok":false,"error":"payload"}"""
                    else -> """{"ok":true}"""
                }
                val bytes = respBody.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
                if (config != null) onConfigReceived(config)
            }
        } catch (_: Exception) {
            // 单个连接失败不影响服务继续
        }
    }

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
