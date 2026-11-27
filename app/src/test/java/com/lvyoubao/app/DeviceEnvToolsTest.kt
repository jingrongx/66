package com.lvyoubao.app

import com.lvyoubao.app.data.DeviceEnvTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeviceEnvTools] 纯逻辑单测：网卡优先级、ADB 端口解析、AppOps 输出与组件判断。 */
class DeviceEnvToolsTest {

    @Test
    fun selectBestLanIpv4_prefers192OverOthers() {
        assertEquals(
            "192.168.3.112",
            DeviceEnvTools.selectBestLanIpv4(listOf("10.0.2.15", "172.17.0.1", "192.168.3.112")),
        )
        assertEquals(
            "10.1.2.3",
            DeviceEnvTools.selectBestLanIpv4(listOf("172.16.0.5", "10.1.2.3")),
        )
        assertEquals(
            "100.64.0.1",
            DeviceEnvTools.selectBestLanIpv4(listOf("100.64.0.1")),
        )
        assertNull(DeviceEnvTools.selectBestLanIpv4(emptyList()))
    }

    @Test
    fun isGoogleVoiceComponent_matchesOnlyGooglePackageOrComponent() {
        assertTrue(
            DeviceEnvTools.isGoogleVoiceComponent(
                "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            ),
        )
        assertTrue(DeviceEnvTools.isGoogleVoiceComponent("com.google.android.googlequicksearchbox"))
        assertFalse(DeviceEnvTools.isGoogleVoiceComponent("com.anthropic.claude/.ClaudeVoiceInteractionService"))
        assertFalse(DeviceEnvTools.isGoogleVoiceComponent("com.google.android.googlequicksearchbox.fake/.Svc"))
        assertFalse(DeviceEnvTools.isGoogleVoiceComponent(""))
        assertFalse(DeviceEnvTools.isGoogleVoiceComponent(null))
    }

    @Test
    fun parseTcpPort_acceptsValidPortAndRejectsDisabled() {
        assertEquals(5555, DeviceEnvTools.parseTcpPort("5555\n"))
        assertEquals(5556, DeviceEnvTools.parseTcpPort(" 5556 "))
        assertNull(DeviceEnvTools.parseTcpPort("-1"))
        assertNull(DeviceEnvTools.parseTcpPort("0"))
        assertNull(DeviceEnvTools.parseTcpPort("65536"))
        assertNull(DeviceEnvTools.parseTcpPort(""))
        assertNull(DeviceEnvTools.parseTcpPort(null))
    }

    @Test
    fun parseRecordAudioIgnored_parsesAppOpsOutput() {
        assertEquals(
            true,
            DeviceEnvTools.parseRecordAudioIgnored(
                "RECORD_AUDIO: ignore; time=+2m40s ago; rejectTime=+1m0s ago",
            ),
        )
        assertEquals(
            false,
            DeviceEnvTools.parseRecordAudioIgnored(
                "COARSE_LOCATION: allow\nRECORD_AUDIO: allow; time=+39s ago",
            ),
        )
        assertEquals(false, DeviceEnvTools.parseRecordAudioIgnored("RECORD_AUDIO: default"))
        assertNull(DeviceEnvTools.parseRecordAudioIgnored("No operations."))
        assertNull(DeviceEnvTools.parseRecordAudioIgnored(null))
    }

    @Test
    fun adbCommands_formatWithAndWithoutIp() {
        assertEquals(
            "adb connect 192.168.3.112:5555",
            DeviceEnvTools.adbConnectCommand("192.168.3.112"),
        )
        assertEquals(
            "adb tcpip 5555 && adb connect 192.168.3.112:5555",
            DeviceEnvTools.adbTcpipCommand("192.168.3.112"),
        )
        assertEquals(
            "adb connect <设备WiFi_IP>:5555",
            DeviceEnvTools.adbConnectCommand(null),
        )
    }
}
