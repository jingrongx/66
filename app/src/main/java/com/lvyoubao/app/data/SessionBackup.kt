package com.lvyoubao.app.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 会话导入与导出（支持旅游宝原生格式与 DeepSeek 官方网页版/Demo 互通格式）。
 */
object SessionBackup {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun formatIsoUtc(ts: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ts))
    }

    private fun parseIsoUtc(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val clean = iso.substringBefore('.').substringBefore('Z')
            sdf.parse(clean)?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    /** 导出为 ZIP 文件，兼容 DeepSeek 官方 ZIP 格式（manifest.json + chat_session_index.json + chat_sessions） */
    fun exportToZip(context: Context, sessions: List<ChatSession>, destZipFile: File): File {
        if (destZipFile.exists()) destZipFile.delete()
        destZipFile.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(destZipFile)).use { zos ->
            // 1. manifest.json
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("{\"export_format_version\":3}".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. 原生纯 JSON 完整备份，保证 100% 无损还原（地图卡、画像规则等）
            zos.putNextEntry(ZipEntry("sessions_backup.json"))
            zos.write(json.encodeToString(sessions).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. chat_session_index.json
            val indexArray = buildJsonArray {
                sessions.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("title", s.title)
                        put("pinned", false)
                        put("message_count", s.messages.size)
                        put("inserted_at", formatIsoUtc(s.createdAt))
                        put("updated_at", formatIsoUtc(s.updatedAt))
                    })
                }
            }
            zos.putNextEntry(ZipEntry("chat_session_index.json"))
            zos.write(indexArray.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. 每个会话一个 chat_sessions/{id}.json（DeepSeek 规范）
            sessions.forEach { s ->
                val sessionObj = buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("pinned", false)
                    put("current_message_id", s.messages.size)
                    put("inserted_at", formatIsoUtc(s.createdAt))
                    put("updated_at", formatIsoUtc(s.updatedAt))
                    put("messages", buildJsonArray {
                        s.messages.forEachIndexed { idx, m ->
                            val msgId = idx + 1
                            val role = if (m.role == "user") "USER" else "ASSISTANT"
                            add(buildJsonObject {
                                put("id", msgId)
                                put("parent_id", if (idx == 0) null else idx)
                                put("role", role)
                                put("status", "FINISHED")
                                put("thinking_enabled", false)
                                put("search_enabled", false)
                                put("accumulated_token_usage", 0)
                                put("inserted_at", formatIsoUtc(m.ts))
                                put("fragments", buildJsonArray {
                                    if (role == "USER") {
                                        add(buildJsonObject {
                                            put("type", "REQUEST")
                                            put("content", m.text)
                                        })
                                    } else {
                                        add(buildJsonObject {
                                            put("type", "RESPONSE")
                                            put("content", m.toChatText())
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                zos.putNextEntry(ZipEntry("chat_sessions/${s.id}.json"))
                zos.write(sessionObj.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // 5. 写入引用的落盘图片
            val imageDir = File(context.filesDir, "sent_images")
            val referencedImages = sessions.flatMap { it.messages }.flatMap { it.imagePaths }.toSet()
            referencedImages.forEach { path ->
                val f = File(path)
                if (f.exists() && f.isFile) {
                    runCatching {
                        zos.putNextEntry(ZipEntry("images/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        return destZipFile
    }

    /** 从 InputStream 导入会话（自动识别 ZIP 压缩包或单 JSON 文件） */
    fun importFromStream(context: Context, inputStream: InputStream): List<ChatSession> {
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(1024)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()

        val isZip = read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        return if (isZip) {
            importFromZip(context, buffered)
        } else {
            importFromJson(buffered)
        }
    }

    private fun importFromJson(inputStream: InputStream): List<ChatSession> {
        val text = inputStream.reader(Charsets.UTF_8).readText()
        // 先尝试作为 List<ChatSession>
        runCatching {
            return json.decodeFromString<List<ChatSession>>(text)
        }
        // 再尝试作为单行或多行 JSONL
        val lines = text.lines().filter { it.isNotBlank() }
        val sessions = lines.mapNotNull { line ->
            runCatching { json.decodeFromString<ChatSession>(line) }.getOrNull()
        }
        return sessions
    }

    private fun importFromZip(context: Context, inputStream: InputStream): List<ChatSession> {
        val imageDir = File(context.filesDir, "sent_images").apply { mkdirs() }
        val sessions = mutableListOf<ChatSession>()
        var nativeBackupJson: String? = null
        val dsSessionJsons = mutableListOf<String>()

        ZipInputStream(inputStream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                when {
                    name == "sessions_backup.json" -> {
                        nativeBackupJson = zis.reader(Charsets.UTF_8).readText()
                    }
                    name.startsWith("chat_sessions/") && name.endsWith(".json") -> {
                        dsSessionJsons.add(zis.reader(Charsets.UTF_8).readText())
                    }
                    name.startsWith("images/") || name.startsWith("chat_files/") -> {
                        val fileName = name.substringAfterLast('/')
                        if (fileName.isNotBlank()) {
                            val dest = File(imageDir, fileName)
                            FileOutputStream(dest).use { out -> zis.copyTo(out) }
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        // 优先还原 100% 无损的原生备份
        if (!nativeBackupJson.isNullOrBlank()) {
            runCatching {
                val parsed = json.decodeFromString<List<ChatSession>>(nativeBackupJson!!)
                if (parsed.isNotEmpty()) return parsed
            }
        }

        // 否则解析 DeepSeek 规范的 chat_sessions/*.json
        dsSessionJsons.forEach { sessionText ->
            runCatching {
                val obj = json.parseToJsonElement(sessionText).jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { "导入的对话" } ?: "导入的对话"
                val createdAt = parseIsoUtc(obj["inserted_at"]?.jsonPrimitive?.contentOrNull)
                val updatedAt = parseIsoUtc(obj["updated_at"]?.jsonPrimitive?.contentOrNull)

                val rawMsgs = obj["messages"]?.jsonArray ?: emptyList()
                val msgList = mutableListOf<SessionMessage>()
                rawMsgs.forEach { rawMsgEl ->
                    val mObj = rawMsgEl.jsonObject
                    val roleStr = mObj["role"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "user"
                    val role = if (roleStr == "user") "user" else "assistant"
                    val ts = parseIsoUtc(mObj["inserted_at"]?.jsonPrimitive?.contentOrNull)

                    // 解析 fragments
                    val frags = mObj["fragments"]?.jsonArray ?: emptyList()
                    var contentText = ""
                    val steps = mutableListOf<String>()
                    frags.forEach { fragEl ->
                        val fObj = fragEl.jsonObject
                        val fType = fObj["type"]?.jsonPrimitive?.contentOrNull
                        val fContent = fObj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        when (fType) {
                            "REQUEST", "RESPONSE" -> contentText += fContent
                            "THINK" -> {
                                if (fContent.isNotBlank()) {
                                    steps.add("思考过程：$fContent")
                                }
                            }
                        }
                    }

                    if (contentText.isNotBlank()) {
                        msgList.add(
                            SessionMessage(
                                role = role,
                                text = contentText,
                                steps = steps,
                                ts = ts,
                            )
                        )
                    }
                }

                if (msgList.isNotEmpty()) {
                    sessions.add(
                        ChatSession(
                            id = id,
                            title = title,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                            messages = msgList,
                        )
                    )
                }
            }
        }

        return sessions
    }
}
