package com.lvyoubao.app.ui.common

import java.nio.charset.StandardCharsets

/**
 * Edge TTS v2 DRM 令牌（Sec-MS-GEC）生成——从 edge-tts 项目 drm.py 逐行移植，
 * 已用参考实现的双时间戳向量逐一比对通过（2026-11 验证，协议版本 2-143.0.3650.75）。
 * 全部 u32 运算以 Long 承载（& 0xFFFFFFFFL），乘法先转 Long 避免符号扩展。
 */
object EdgeTtsDrm {

    private val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val WIN_EPOCH = 11644473600L
    private val M = 0xFFFFFFFFL

    private val SHA256_INITIAL = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt(),
    )
    private val SHA256_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49.toInt(), 0x04BD35AB.toInt(), 0xFEB0A7E4.toInt(), 0x03EFFBD2.toInt(),
    )
    private val SHA256_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA.toInt(),
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564,
    )
    private val RC = intArrayOf(
        0x428A2F98.toInt(), 0x71374491.toInt(), 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B.toInt(), 0x59F111F1.toInt(), 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01.toInt(), 0x243185BE.toInt(), 0x550C7DC3.toInt(),
        0x72BE5D74.toInt(), 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6.toInt(), 0x240CA1CC.toInt(),
        0x2DE92C6F.toInt(), 0x4A7484AA.toInt(), 0x5CB0A9DC.toInt(), 0x76F988DA.toInt(),
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351.toInt(), 0x14292967.toInt(),
        0x27B70A85.toInt(), 0x2E1B2138.toInt(), 0x4D2C6DFC.toInt(), 0x53380D13.toInt(),
        0x650A7354.toInt(), 0x766A0ABB.toInt(), 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070.toInt(),
        0x19A4C116.toInt(), 0x1E376C08.toInt(), 0x2748774C.toInt(), 0x34B0BCB5.toInt(),
        0x391C0CB3.toInt(), 0x4ED8AA4A.toInt(), 0x5B9CCA4F.toInt(), 0x682E6FF3.toInt(),
        0x748F82EE.toInt(), 0x78A5636F.toInt(), 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
    private val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val BLOCK: IntArray = run {
        val b = RC.copyOf()
        b[14] = SHA256_ADJUSTMENT
        b
    }
    private val SIGMA0_ADJUSTMENT = 0x059C0B80
    private val SIGMA1_ADJUSTMENT = 0x000B92C0

    private val CS64_COEFF = longArrayOf(
        0xE82E91B1L, 0xB32700D0L, 0x44DE24C6L, 0x88DFF51DL, 0x3BEB65BCL, 0x29514038L,
        0xC89C5933L, 0xB328CBDFL, 0x8D1EC211L, 0xD405B4D5L, 0x314EF5D5L, 0x26E55F3CL,
        0xE92491B1L, 0x607801C6L, 0xF0A5B9D5L, 0x82B2C1FBL, 0x06064CF3L, 0xFA51A3DAL,
        0xDA79FA60L, 0x207CC464L, 0xBED20560L, 0x3C3C74F3L, 0x89F9C9F8L, 0xD2D14B61L,
        0x35FA38C6L, 0xBB04A2C1L, 0xCB49E3C6L, 0xC5626167L,
    )
    private val CS64_FACTOR = 0x82893933L
    private val CS64_ADJ0 = 0xF2F90B1A
    private val CS64_ADJ1 = 0x0B1A0000

    // u32 循环左移 16（long 承载，返回 u32）
    private fun rol16(v: Long): Long {
        val x = v and M
        return ((x shl 16) or (x ushr 16)) and M
    }

    // 消息调度小 sigma0（含左移项，与参考实现一致）
    private fun sigma0(v: Int): Int {
        return (v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)
    }

    // 消息调度小 sigma1
    private fun sigma1(v: Int): Int {
        return (v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)
    }

    // 轮函数大 Sigma1：ROTR6 ^ ROTR11 ^ ROTR25
    private fun bigSigma1(e: Int): Int {
        return ((e ushr 6) or (e shl 26)) xor ((e ushr 11) or (e shl 21)) xor ((e ushr 25) or (e shl 7))
    }

    // 轮函数大 Sigma0：ROTR2 ^ ROTR13 ^ ROTR22
    private fun bigSigma0(a: Int): Int {
        return ((a ushr 2) or (a shl 30)) xor ((a ushr 13) or (a shl 19)) xor ((a ushr 22) or (a shl 10))
    }

    // firstRound=14 时用 BLOCK 常量表；消耗 words[firstRound..63]
    private fun sha256Rounds(state: IntArray, words: IntArray, firstRound: Int): IntArray {
        val K = if (firstRound == 14) BLOCK else RC
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in firstRound until 64) {
            val t1 = h + bigSigma1(e) + (g xor (e and (f xor g))) + K[i] + words[i]
            val t2 = bigSigma0(a) + ((a and b) or (c and (a or b)))
            d += t1
            h = t1 + t2
            val oldA = a
            a = h
            h = g
            g = f
            f = e
            e = d
            d = c
            c = b
            b = oldA
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: IntArray, from: Int, to: Int) {
        for (index in from until to) {
            val x = words[index - 2]
            val y = words[index - 15]
            words[index] = sigma1(x) + words[index - 7] + sigma0(y) + words[index - 16]
        }
    }

    private fun sha256Init(prefix: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((prefix[0].toInt() and 0xFF) shl 16) or
            ((prefix[1].toInt() and 0xFF) shl 8) or (prefix[2].toInt() and 0xFF)
        words[15] = ((prefix[3].toInt() and 0xFF) shl 24) or
            ((prefix[4].toInt() and 0xFF) shl 16) or
            ((prefix[5].toInt() and 0xFF) shl 8) or (prefix[6].toInt() and 0xFF)
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) (sigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT) else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) {
                value += words[index - 7]
            }
            if (index - 15 >= 14) {
                value += if (index == 29) (sigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT) else sigma0(words[index - 15])
            }
            words[index] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA256_ADJUSTMENT - RC[14]
        expandSchedule(words, 31, 64)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteArray((data.size - 7) + 1 + zeros + 8)
        System.arraycopy(data, 7, padded, 0, data.size - 7)
        padded[data.size - 7] = 0x80.toByte()
        val bitLen = length.toLong() * 8
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        }
        var state = sha256Init(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val words = IntArray(64)
            for (i in 0 until 16) {
                val o = off + i * 4
                words[i] = ((padded[o].toInt() and 0xFF) shl 24) or
                    ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
            }
            expandSchedule(words, 16, 64)
            val working = sha256Rounds(state, words, 0)
            for (i in 0 until 8) {
                state[i] += working[i]
            }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (state[i] ushr 24).toByte()
            out[i * 4 + 1] = (state[i] ushr 16).toByte()
            out[i * 4 + 2] = (state[i] ushr 8).toByte()
            out[i * 4 + 3] = state[i].toByte()
        }
        return out
    }

    private fun wordsLe4Even(data: ByteArray): IntArray {
        val pad = Math.floorMod(-data.size, 4)
        val padded = ByteArray(data.size + pad)
        System.arraycopy(data, 0, padded, 0, data.size)
        var count = padded.size / 4
        if (count % 2 != 0) {
            count++
        }
        val words = IntArray(count)
        for (i in 0 until padded.size / 4) {
            val o = i * 4
            words[i] = (padded[o].toInt() and 0xFF) or
                ((padded[o + 1].toInt() and 0xFF) shl 8) or
                ((padded[o + 2].toInt() and 0xFF) shl 16) or
                ((padded[o + 3].toInt() and 0xFF) shl 24)
        }
        return words
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        var keyA = (digest[0].toLong() and 0xFF) or ((digest[1].toLong() and 0xFF) shl 8) or
            ((digest[2].toLong() and 0xFF) shl 16) or ((digest[3].toLong() and 0xFF) shl 24)
        var keyB = (digest[4].toLong() and 0xFF) or ((digest[5].toLong() and 0xFF) shl 8) or
            ((digest[6].toLong() and 0xFF) shl 16) or ((digest[7].toLong() and 0xFF) shl 24)
        keyA = keyA or 1L
        keyB = keyB or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and M, (keyB * CS64_FACTOR) and M)
        val pairCount = CS64_COEFF.size / 2
        val terms = LongArray(pairCount + 64)
        val offsets = LongArray(pairCount + 64)
        for (index in 0 until pairCount) {
            terms[index] = (CS64_COEFF[index * 2] * factors[index % 2]) and M
            offsets[index] = CS64_COEFF[index * 2 + 1]
        }
        val words = wordsLe4Even(concat(byteArrayOf(0), data))
        for (index in words.indices) {
            val word = words[index].toLong() and M
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJ0 * factors[0]
                offset += CS64_ADJ1
            }
            terms[pairCount + index] = term and M
            offsets[pairCount + index] = offset and M
        }
        val n = pairCount + words.size
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var index = 0
        while (index < n) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16((offsets[index] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M
            t = rol16((offsets[index + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M

            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            index += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun leHex(v: Int): String {
        return String.format(
            "%02X%02X%02X%02X",
            v and 0xFF, (v ushr 8) and 0xFF, (v ushr 16) and 0xFF, (v ushr 24) and 0xFF,
        )
    }

    private fun hex(bytes: ByteArray): String {
        return buildString {
            for (b in bytes) {
                append(String.format("%02X", b))
            }
        }
    }

    /** 生成 v2 Sec-MS-GEC 令牌，格式 "<16hex>-<64hex>" */
    fun generateSecMsGecV2(unixSeconds: Long): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 10_000_000L
        val strToHash = (ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(StandardCharsets.US_ASCII)
        val digest = sha256Digest(strToHash)
        val cs64 = cs64Digest(strToHash, digest)
        return leHex(cs64[0].toInt()) + leHex(cs64[1].toInt()) + "-" + hex(digest)
    }
}
