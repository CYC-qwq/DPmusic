package com.dpmusic.app.core.lyric

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ 音乐 QRC 歌词解密器（自定义 DES 三重 EDE + zlib）。
 *
 * 注意：**不是标准 DES**——位序经过特殊处理，必须逐位照搬官方实现（S 盒 / 密钥编排 /
 * 初始置换全部为变体）。流程：
 *
 * ```
 * hex → bytes → DES 解密(K1) → DES 加密(K2) → DES 解密(K3) → zlib 解压 → 明文
 * ```
 *
 * 已与 Python / C# 参考实现产物做过逐字节比对（IDENTICAL）。
 */
object QrcDecoder {

    private val S1 = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    )
    private val S2 = intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    )
    private val S3 = intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    )
    private val S4 = intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    )
    private val S5 = intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    )
    private val S6 = intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    )
    private val S7 = intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    )
    private val S8 = intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )
    private val SB = arrayOf(S1, S2, S3, S4, S5, S6, S7, S8)

    private val KRS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KPC = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val KPD = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val KC = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    // 三个密钥串（实际只取前 8 字节参与位运算）
    private val KEY1 = "!@#)(NHLiuy*$%^&".toByteArray(Charsets.US_ASCII)
    private val KEY2 = "123ZXC!@#)(*$%^&".toByteArray(Charsets.US_ASCII)
    private val KEY3 = "!@#)(*$%^&abcDEF".toByteArray(Charsets.US_ASCII)

    /** 解密 hex 密文为明文；失败返回 null */
    fun decrypt(hex: String): String? {
        if (hex.isBlank()) return null
        val data = hexToBytes(hex) ?: return null
        return try {
            val a = funcDes(data, KEY1, data.size, false)
            val b = funcDes(a, KEY2, a.size, true)
            val c = funcDes(b, KEY3, b.size, false)
            inflate(c)
        } catch (_: Exception) {
            null
        }
    }

    private fun inflate(data: ByteArray): String? {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
            }
        }
        inf.end()
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes.toString(Charsets.UTF_8)
    }

    private fun hexToBytes(s: String): ByteArray? {
        val clean = s.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /* ---------- 位运算核心（逐位照搬参考实现，勿改动） ---------- */

    private fun bitNum(a: ByteArray, b: Int, c: Int): Int {
        val byteIndex = (b / 32) * 4 + 3 - (b % 32) / 8
        val bitPosition = 7 - (b % 8)
        val extractedBit = ((a[byteIndex].toInt() and 0xFF) ushr bitPosition) and 1
        return extractedBit shl c
    }

    private fun bitNumIntR(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitNumIntL(a: Int, b: Int, c: Int): Int = (((a shl b) and Int.MIN_VALUE) ushr c)

    private fun sBoxBit(a: Int): Int = (a and 0x20) or ((a and 0x1F) ushr 1) or ((a and 1) shl 4)

    private fun ip(st: IntArray, input: ByteArray) {
        st[0] = bitNum(input, 57, 31) or bitNum(input, 49, 30) or bitNum(input, 41, 29) or bitNum(input, 33, 28) or
            bitNum(input, 25, 27) or bitNum(input, 17, 26) or bitNum(input, 9, 25) or bitNum(input, 1, 24) or
            bitNum(input, 59, 23) or bitNum(input, 51, 22) or bitNum(input, 43, 21) or bitNum(input, 35, 20) or
            bitNum(input, 27, 19) or bitNum(input, 19, 18) or bitNum(input, 11, 17) or bitNum(input, 3, 16) or
            bitNum(input, 61, 15) or bitNum(input, 53, 14) or bitNum(input, 45, 13) or bitNum(input, 37, 12) or
            bitNum(input, 29, 11) or bitNum(input, 21, 10) or bitNum(input, 13, 9) or bitNum(input, 5, 8) or
            bitNum(input, 63, 7) or bitNum(input, 55, 6) or bitNum(input, 47, 5) or bitNum(input, 39, 4) or
            bitNum(input, 31, 3) or bitNum(input, 23, 2) or bitNum(input, 15, 1) or bitNum(input, 7, 0)
        st[1] = bitNum(input, 56, 31) or bitNum(input, 48, 30) or bitNum(input, 40, 29) or bitNum(input, 32, 28) or
            bitNum(input, 24, 27) or bitNum(input, 16, 26) or bitNum(input, 8, 25) or bitNum(input, 0, 24) or
            bitNum(input, 58, 23) or bitNum(input, 50, 22) or bitNum(input, 42, 21) or bitNum(input, 34, 20) or
            bitNum(input, 26, 19) or bitNum(input, 18, 18) or bitNum(input, 10, 17) or bitNum(input, 2, 16) or
            bitNum(input, 60, 15) or bitNum(input, 52, 14) or bitNum(input, 44, 13) or bitNum(input, 36, 12) or
            bitNum(input, 28, 11) or bitNum(input, 20, 10) or bitNum(input, 12, 9) or bitNum(input, 4, 8) or
            bitNum(input, 62, 7) or bitNum(input, 54, 6) or bitNum(input, 46, 5) or bitNum(input, 38, 4) or
            bitNum(input, 30, 3) or bitNum(input, 22, 2) or bitNum(input, 14, 1) or bitNum(input, 6, 0)
    }

    private fun invIp(st: IntArray, input: ByteArray) {
        input[3] = (bitNumIntR(st[1], 7, 7) or bitNumIntR(st[0], 7, 6) or bitNumIntR(st[1], 15, 5) or
            bitNumIntR(st[0], 15, 4) or bitNumIntR(st[1], 23, 3) or bitNumIntR(st[0], 23, 2) or
            bitNumIntR(st[1], 31, 1) or bitNumIntR(st[0], 31, 0)).toByte()
        input[2] = (bitNumIntR(st[1], 6, 7) or bitNumIntR(st[0], 6, 6) or bitNumIntR(st[1], 14, 5) or
            bitNumIntR(st[0], 14, 4) or bitNumIntR(st[1], 22, 3) or bitNumIntR(st[0], 22, 2) or
            bitNumIntR(st[1], 30, 1) or bitNumIntR(st[0], 30, 0)).toByte()
        input[1] = (bitNumIntR(st[1], 5, 7) or bitNumIntR(st[0], 5, 6) or bitNumIntR(st[1], 13, 5) or
            bitNumIntR(st[0], 13, 4) or bitNumIntR(st[1], 21, 3) or bitNumIntR(st[0], 21, 2) or
            bitNumIntR(st[1], 29, 1) or bitNumIntR(st[0], 29, 0)).toByte()
        input[0] = (bitNumIntR(st[1], 4, 7) or bitNumIntR(st[0], 4, 6) or bitNumIntR(st[1], 12, 5) or
            bitNumIntR(st[0], 12, 4) or bitNumIntR(st[1], 20, 3) or bitNumIntR(st[0], 20, 2) or
            bitNumIntR(st[1], 28, 1) or bitNumIntR(st[0], 28, 0)).toByte()
        input[7] = (bitNumIntR(st[1], 3, 7) or bitNumIntR(st[0], 3, 6) or bitNumIntR(st[1], 11, 5) or
            bitNumIntR(st[0], 11, 4) or bitNumIntR(st[1], 19, 3) or bitNumIntR(st[0], 19, 2) or
            bitNumIntR(st[1], 27, 1) or bitNumIntR(st[0], 27, 0)).toByte()
        input[6] = (bitNumIntR(st[1], 2, 7) or bitNumIntR(st[0], 2, 6) or bitNumIntR(st[1], 10, 5) or
            bitNumIntR(st[0], 10, 4) or bitNumIntR(st[1], 18, 3) or bitNumIntR(st[0], 18, 2) or
            bitNumIntR(st[1], 26, 1) or bitNumIntR(st[0], 26, 0)).toByte()
        input[5] = (bitNumIntR(st[1], 1, 7) or bitNumIntR(st[0], 1, 6) or bitNumIntR(st[1], 9, 5) or
            bitNumIntR(st[0], 9, 4) or bitNumIntR(st[1], 17, 3) or bitNumIntR(st[0], 17, 2) or
            bitNumIntR(st[1], 25, 1) or bitNumIntR(st[0], 25, 0)).toByte()
        input[4] = (bitNumIntR(st[1], 0, 7) or bitNumIntR(st[0], 0, 6) or bitNumIntR(st[1], 8, 5) or
            bitNumIntR(st[0], 8, 4) or bitNumIntR(st[1], 16, 3) or bitNumIntR(st[0], 16, 2) or
            bitNumIntR(st[1], 24, 1) or bitNumIntR(st[0], 24, 0)).toByte()
    }

    private fun f(state: Int, key: IntArray): Int {
        val ls = IntArray(6)
        val t1 = bitNumIntL(state, 31, 0) or ((state and 0xF0000000.toInt()) ushr 1) or bitNumIntL(state, 4, 5) or
            bitNumIntL(state, 3, 6) or ((state and 0x0F000000) ushr 3) or bitNumIntL(state, 8, 11) or
            bitNumIntL(state, 7, 12) or ((state and 0x00F00000) ushr 5) or bitNumIntL(state, 12, 17) or
            bitNumIntL(state, 11, 18) or ((state and 0x000F0000) ushr 7) or bitNumIntL(state, 16, 23)
        val t2 = bitNumIntL(state, 15, 0) or ((state and 0x0000F000) shl 15) or bitNumIntL(state, 20, 5) or
            bitNumIntL(state, 19, 6) or ((state and 0x00000F00) shl 13) or bitNumIntL(state, 24, 11) or
            bitNumIntL(state, 23, 12) or ((state and 0x000000F0) shl 11) or bitNumIntL(state, 28, 17) or
            bitNumIntL(state, 27, 18) or ((state and 0x0000000F) shl 9) or bitNumIntL(state, 0, 23)
        ls[0] = (t1 ushr 24) and 0xFF
        ls[1] = (t1 ushr 16) and 0xFF
        ls[2] = (t1 ushr 8) and 0xFF
        ls[3] = (t2 ushr 24) and 0xFF
        ls[4] = (t2 ushr 16) and 0xFF
        ls[5] = (t2 ushr 8) and 0xFF
        for (i in 0 until 6) ls[i] = ls[i] xor key[i]
        var s = (SB[0][sBoxBit(ls[0] ushr 2)] shl 28) or
            (SB[1][sBoxBit(((ls[0] and 0x03) shl 4) or (ls[1] ushr 4))] shl 24) or
            (SB[2][sBoxBit(((ls[1] and 0x0F) shl 2) or (ls[2] ushr 6))] shl 20) or
            (SB[3][sBoxBit(ls[2] and 0x3F)] shl 16) or
            (SB[4][sBoxBit(ls[3] ushr 2)] shl 12) or
            (SB[5][sBoxBit(((ls[3] and 0x03) shl 4) or (ls[4] ushr 4))] shl 8) or
            (SB[6][sBoxBit(((ls[4] and 0x0F) shl 2) or (ls[5] ushr 6))] shl 4) or
            SB[7][sBoxBit(ls[5] and 0x3F)]
        s = bitNumIntL(s, 15, 0) or bitNumIntL(s, 6, 1) or bitNumIntL(s, 19, 2) or bitNumIntL(s, 20, 3) or
            bitNumIntL(s, 28, 4) or bitNumIntL(s, 11, 5) or bitNumIntL(s, 27, 6) or bitNumIntL(s, 16, 7) or
            bitNumIntL(s, 0, 8) or bitNumIntL(s, 14, 9) or bitNumIntL(s, 22, 10) or bitNumIntL(s, 25, 11) or
            bitNumIntL(s, 4, 12) or bitNumIntL(s, 17, 13) or bitNumIntL(s, 30, 14) or bitNumIntL(s, 9, 15) or
            bitNumIntL(s, 1, 16) or bitNumIntL(s, 7, 17) or bitNumIntL(s, 23, 18) or bitNumIntL(s, 13, 19) or
            bitNumIntL(s, 31, 20) or bitNumIntL(s, 26, 21) or bitNumIntL(s, 2, 22) or bitNumIntL(s, 8, 23) or
            bitNumIntL(s, 18, 24) or bitNumIntL(s, 12, 25) or bitNumIntL(s, 29, 26) or bitNumIntL(s, 5, 27) or
            bitNumIntL(s, 21, 28) or bitNumIntL(s, 10, 29) or bitNumIntL(s, 3, 30) or bitNumIntL(s, 24, 31)
        return s
    }

    private fun desKeySetup(key: ByteArray, schedule: Array<IntArray>, decrypt: Boolean) {
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitNum(key, KPC[i], 31 - i)
            d = d or bitNum(key, KPD[i], 31 - i)
        }
        for (i in 0 until 16) {
            c = ((c shl KRS[i]) or (c ushr (28 - KRS[i]))) and 0xFFFFFFF0.toInt()
            d = ((d shl KRS[i]) or (d ushr (28 - KRS[i]))) and 0xFFFFFFF0.toInt()
            val toGen = if (decrypt) 15 - i else i
            schedule[toGen] = IntArray(6)
            for (j in 0 until 24) {
                schedule[toGen][j / 8] = schedule[toGen][j / 8] or bitNumIntR(c, KC[j], 7 - (j % 8))
            }
            for (j in 24 until 48) {
                schedule[toGen][j / 8] = schedule[toGen][j / 8] or bitNumIntR(d, KC[j] - 27, 7 - (j % 8))
            }
        }
    }

    private fun desCrypt(input: ByteArray, schedule: Array<IntArray>): ByteArray {
        val st = intArrayOf(0, 0)
        ip(st, input)
        for (idx in 0 until 15) {
            val t = st[1]
            val i = f(st[1], schedule[idx])
            st[1] = i xor st[0]
            st[0] = t
        }
        st[0] = f(st[1], schedule[15]) xor st[0]
        invIp(st, input)
        return input
    }

    private fun funcDes(buff: ByteArray, key: ByteArray, length: Int, encrypt: Boolean): ByteArray {
        val schedule = Array(16) { IntArray(6) }
        desKeySetup(key, schedule, !encrypt)
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < length) {
            val block = ByteArray(8)
            val n = minOf(8, length - i)
            System.arraycopy(buff, i, block, 0, n)
            out.writeBytes(desCrypt(block, schedule))
            i += 8
        }
        return out.toByteArray()
    }
}