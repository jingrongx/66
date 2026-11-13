package com.lvyoubao.app.data

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * 标准 GPX 1.1（GPS Exchange Format）解析与导出。
 * 户外徒步、自行车骑行与旅行路书通用标准，打通旅游宝与两步路、佳明 Garmin、Google Earth 等专业户外系统。
 */
object GpxUtils {

    data class GpxTrack(
        val name: String,
        val points: List<Pair<Double, Double>>, // (经度, 纬度) WGS-84
    )

    /** 把坐标点序列导出为标准 GPX 1.1 字符串 */
    fun buildGpx(name: String, points: List<Pair<Double, Double>>, trackPoints: List<TrackStore.TrackPoint> = emptyList()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowIso = sdf.format(Date())
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="旅游宝 - 零决策 AI 助手" xmlns="http://www.topografix.com/GPX/1/1">""")
            appendLine("""  <metadata>""")
            appendLine("""    <name>${escapeXml(name)}</name>""")
            appendLine("""    <time>$nowIso</time>""")
            appendLine("""  </metadata>""")
            appendLine("""  <trk>""")
            appendLine("""    <name>${escapeXml(name)}</name>""")
            appendLine("""    <trkseg>""")
            if (trackPoints.isNotEmpty()) {
                trackPoints.forEach { pt ->
                    val t = sdf.format(Date(pt.ts))
                    appendLine("""      <trkpt lat="${pt.lat}" lon="${pt.lon}"><time>$t</time></trkpt>""")
                }
            } else {
                points.forEach { (lon, lat) ->
                    appendLine("""      <trkpt lat="$lat" lon="$lon"/>""")
                }
            }
            appendLine("""    </trkseg>""")
            appendLine("""  </trk>""")
            appendLine("""</gpx>""")
        }
    }

    /** 从输入流解析 GPX 格式中的轨迹坐标（WGS-84），兼容纯 JVM 与 Android SDK 环境 */
    fun parseGpx(stream: InputStream): GpxTrack {
        val points = mutableListOf<Pair<Double, Double>>()
        var trackName = "导入的轨迹"

        runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            val names = doc.getElementsByTagName("name")
            if (names.length > 0) {
                val n = names.item(0).textContent?.trim().orEmpty()
                if (n.isNotBlank()) trackName = n
            }

            fun extractPoints(tagName: String) {
                val nodeList = doc.getElementsByTagName(tagName)
                for (i in 0 until nodeList.length) {
                    val node = nodeList.item(i)
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        val element = node as org.w3c.dom.Element
                        val lat = element.getAttribute("lat")?.toDoubleOrNull()
                        val lon = element.getAttribute("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            points.add(lon to lat)
                        }
                    }
                }
            }

            extractPoints("trkpt")
            if (points.isEmpty()) extractPoints("wpt")
            if (points.isEmpty()) extractPoints("rtept")
        }

        return GpxTrack(trackName, points)
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
