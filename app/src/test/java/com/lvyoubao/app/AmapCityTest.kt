package com.lvyoubao.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AmapCityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testParseMunicipalityCity() {
        // 直辖市：city 为空数组 "[]"，province 为 "北京市"，adcode 为 "110108"
        val sampleJson = """
            {
                "status": "1",
                "info": "OK",
                "regeocode": {
                    "addressComponent": {
                        "city": [],
                        "province": "北京市",
                        "adcode": "110108",
                        "district": "海淀区"
                    }
                }
            }
        """.trimIndent()

        val root = json.parseToJsonElement(sampleJson).jsonObject
        val regeo = root["regeocode"]?.jsonObject
        val ac = regeo?.get("addressComponent")?.jsonObject
        val cityVal = runCatching { ac?.get("city")?.jsonPrimitive?.content }.getOrNull()
        val provVal = runCatching { ac?.get("province")?.jsonPrimitive?.content }.getOrNull()
        val adcodeVal = runCatching { ac?.get("adcode")?.jsonPrimitive?.content }.getOrNull()
        val city = cityVal?.takeIf { it.isNotBlank() && it != "[]" }
        val province = provVal?.takeIf { it.isNotBlank() && it != "[]" }
        val adcode = adcodeVal?.takeIf { it.isNotBlank() && it != "[]" }

        val resolved = city ?: province ?: adcode
        assertEquals("北京市", resolved)
    }

    @Test
    fun testParseStandardCity() {
        // 普通地级市：city 为 "杭州市"
        val sampleJson = """
            {
                "status": "1",
                "info": "OK",
                "regeocode": {
                    "addressComponent": {
                        "city": "杭州市",
                        "province": "浙江省",
                        "adcode": "330106",
                        "district": "西湖区"
                    }
                }
            }
        """.trimIndent()

        val root = json.parseToJsonElement(sampleJson).jsonObject
        val regeo = root["regeocode"]?.jsonObject
        val ac = regeo?.get("addressComponent")?.jsonObject
        val cityVal = runCatching { ac?.get("city")?.jsonPrimitive?.content }.getOrNull()
        val provVal = runCatching { ac?.get("province")?.jsonPrimitive?.content }.getOrNull()
        val city = cityVal?.takeIf { it.isNotBlank() && it != "[]" }
        val province = provVal?.takeIf { it.isNotBlank() && it != "[]" }

        val resolved = city ?: province
        assertEquals("杭州市", resolved)
    }
}
