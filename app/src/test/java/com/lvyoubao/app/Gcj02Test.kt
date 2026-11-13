package com.lvyoubao.app

import com.lvyoubao.app.data.Gcj02
import com.lvyoubao.app.data.observation.FatigueEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gcj02Test {

    @Test
    fun testOutOfChina() {
        // 海外坐标无偏移
        assertTrue(Gcj02.outOfChina(48.8566, 2.3522)) // 巴黎
        val (pLat, pLon) = Gcj02.fromWgs(48.8566, 2.3522)
        assertEquals(48.8566, pLat, 1e-6)
        assertEquals(2.3522, pLon, 1e-6)
    }

    @Test
    fun testRoundtripPrecision() {
        // 北京天安门
        val wgsLat = 39.908722
        val wgsLon = 116.397496
        val (gcjLat, gcjLon) = Gcj02.fromWgs(wgsLat, wgsLon)
        // 验证 GCJ-02 存在数百米偏移
        val diffM = FatigueEngine.haversine(wgsLat, wgsLon, gcjLat, gcjLon)
        assertTrue(diffM in 100.0..800.0)

        // 验证反转回 WGS-84 的残差在 0.5 米以内
        val (revLat, revLon) = Gcj02.toWgs(gcjLat, gcjLon)
        val errorM = FatigueEngine.haversine(wgsLat, wgsLon, revLat, revLon)
        assertTrue("反转误差需小于 0.5 米，实际 $errorM 米", errorM < 0.5)
    }
}
