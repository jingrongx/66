package com.lvyoubao.app

import org.junit.Test

/**
 * 朗读链路真实网络测试（发版前在本机跑）：
 * - EdgeTts：实网合成一段中文，验证 DRM 令牌与协议是否仍被微软接受（无 Key、免费链路）；
 * - CloudTts：假 Key 调硅基流动，验证请求连通与失败静默返回 null 的兜底路径。
 * 这些测试依赖外网，CI 不跑；发版前本地 `gradlew :app:testDebugUnitTest` 验证。
 */
class TtsRealTest {

    private fun tmpDir(): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "tts_test_${System.currentTimeMillis()}")

    @Test
    fun edgeSynthesizeRealNetwork() = kotlinx.coroutines.runBlocking {
        val dir = tmpDir()
        val file = com.lvyoubao.app.ui.common.EdgeTts.synthesize(
            "你好，这是旅游宝的朗读测试，检查合成链路是否正常。",
            dir,
        )
        println("EdgeTts -> ${file?.absolutePath} bytes=${file?.length()}")
        org.junit.Assert.assertNotNull("Edge 实网合成失败（协议/令牌/网络任一环节断了）", file)
        org.junit.Assert.assertTrue("合成产物过小，不是有效音频", file!!.length() > 1_000)
    }

    @Test
    fun cloudSynthesizeRejectsFakeKeyGracefully() = kotlinx.coroutines.runBlocking {
        val dir = tmpDir()
        val file = com.lvyoubao.app.ui.common.CloudTts.synthesize("测试", dir, "sk-invalid-test-key")
        println("CloudTts(fake key) -> $file（应为 null）")
        org.junit.Assert.assertNull("假 Key 不应产出音频文件", file)
    }
}
