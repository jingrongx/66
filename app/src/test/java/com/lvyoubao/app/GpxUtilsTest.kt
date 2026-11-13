package com.lvyoubao.app

import com.lvyoubao.app.data.GpxUtils
import com.lvyoubao.app.data.TrackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxUtilsTest {

    @Test
    fun testBuildAndParseGpx() {
        val originalPoints = listOf(
            116.397128 to 39.916527,
            116.397500 to 39.917000,
            116.398000 to 39.917500,
        )
        val gpxXml = GpxUtils.buildGpx("北京故宫徒步", originalPoints)
        assertTrue(gpxXml.contains("<gpx version=\"1.1\""))
        assertTrue(gpxXml.contains("<name>北京故宫徒步</name>"))
        assertTrue(gpxXml.contains("""<trkpt lat="39.916527" lon="116.397128"/>"""))

        val parsed = GpxUtils.parseGpx(ByteArrayInputStream(gpxXml.toByteArray(Charsets.UTF_8)))
        assertEquals("北京故宫徒步", parsed.name)
        assertEquals(3, parsed.points.size)
        assertEquals(116.397128, parsed.points[0].first, 0.000001)
        assertEquals(39.916527, parsed.points[0].second, 0.000001)
    }

    @Test
    fun testBuildWithTrackStorePoints() {
        val tracks = listOf(
            TrackStore.TrackPoint(ts = 1700000000000L, lat = 31.2304, lon = 121.4737),
            TrackStore.TrackPoint(ts = 1700000060000L, lat = 31.2310, lon = 121.4745),
        )
        val xml = GpxUtils.buildGpx("上海足迹", emptyList(), tracks)
        assertTrue(xml.contains("<trkpt lat=\"31.2304\" lon=\"121.4737\">"))
        assertTrue(xml.contains("<time>"))

        val parsed = GpxUtils.parseGpx(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(2, parsed.points.size)
        assertEquals(121.4737, parsed.points[0].first, 0.0001)
        assertEquals(31.2304, parsed.points[0].second, 0.0001)
    }
}
