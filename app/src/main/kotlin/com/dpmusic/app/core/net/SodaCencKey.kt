package com.dpmusic.app.core.net

/**
 * 汽水 CENC 密钥推导：`play_auth` / `spade_a` → AES key。
 *
 * ## 这是什么
 *
 * 汽水的音频是 **CENC AES-CTR** 加密（`stsd=enca` + `senc`），密钥 blob 由 relay 以
 * `play_auth` 字段下发（协议 §3.4）。本类把该 blob 解出 16 字节 AES key。
 *
 * ## 为什么自己写 base64
 *
 * `java.util.Base64` 要 API 26，而本项目 `minSdk 23`；`android.util.Base64` 又无法在
 * JVM 单测里跑。密钥推导必须**可单测钉死**（错一位就是满屏解码错误），所以自带一个
 * 标准 base64 解码器，让整条路径保持纯 JVM。
 */
object SodaCencKey {

    /**
     * 从 `play_auth` 推导 AES key。
     *
     * @return 16/24/32 字节 AES key；blob 非法时返回 null（调用方应视为解析失败）
     */
    fun extractKey(playAuth: String): ByteArray? {
        val b = base64Decode(playAuth) ?: return null
        if (b.size < 3) return null

        val paddingLen = ((b[0].toInt() and 0xFF) xor
            (b[1].toInt() and 0xFF) xor
            (b[2].toInt() and 0xFF)) - 48
        if (paddingLen < 0 || paddingLen > b.size - 2) return null

        val inner = b.copyOfRange(1, b.size - paddingLen)
        if (inner.isEmpty()) return null

        // buff = [0xFA, 0x55] + inner
        val out = ByteArray(inner.size)
        for (i in inner.indices) {
            val buffByte = when (i) {
                0 -> 0xFA
                1 -> 0x55
                else -> inner[i - 2].toInt() and 0xFF
            }
            var v = ((inner[i].toInt() and 0xFF) xor buffByte) - Integer.bitCount(i) - 21
            while (v < 0) v += 255
            out[i] = (v and 0xFF).toByte()
        }

        val outStr = String(out, Charsets.ISO_8859_1)
        val skip = base36(outStr[0])
        val end = 1 + (b.size - paddingLen - 2) - skip
        if (end < 1 || end > outStr.length) return null

        val hexKey = outStr.substring(1, end)
        if (hexKey.isEmpty() || hexKey.length % 2 != 0) return null
        return hexDecode(hexKey)
    }

    /* ---------------- 工具 ---------------- */

    /** base36：`0-9`→0-9，`a-z`→10-35，`A-Z`→10-35，其它→0（与参考实现一致） */
    private fun base36(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> 0
    }

    private fun hexDecode(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in 0 until s.length step 2) {
            val hi = hexVal(s[i]) ?: return null
            val lo = hexVal(s[i + 1]) ?: return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /** 标准 base64 解码（容忍缺失的 `=` 填充）；非法字符返回 null */
    fun base64Decode(input: String): ByteArray? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in s) {
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}