package com.dpmusic.app.core.net

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 汽水 CENC（AES-CTR）音频解密。
 *
 * ## 输入
 * - [data]：relay 直连 CDN 取回的**加密 MP4**（`stsd=enca` + `senc`）
 * - [key]：由 [SodaCencKey.extractKey] 从 `play_auth` 推导出的 AES key
 *
 * ## 做什么（对应协议 §3.4）
 * 1. 解析 `moov → trak → mdia → minf → stbl`，取 `stsz`（每 sample 字节数）与 `senc`（每 sample IV）
 * 2. 对 `mdat` 里每个 sample 用 **AES-CTR** 解密（IV 取 `senc` 的 per-sample IV）
 * 3. 把 `stsd` 里的 `enca` 改回 `frma` 声明的真实编码（`mp4a` 或 `fLaC`），否则播放器不认
 *
 * ## 为什么保持纯 JVM
 * 只用 `javax.crypto` + 自写 box 解析，不碰 Android API，便于单测与真实文件对照。
 *
 * ## 边界
 * 本实现按**完整文件**解密（播放走「解密到临时文件」路线）；边下边解需要 `DataSource` 层支持，
 * 见 [SodaCencKey] 所在模块的接入层。
 */
object SodaCenc {

    /** 可继续下钻的容器 box */
    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta",
        "moof", "traf", "mvex", "wave",
        // 关键：CENC 把 sinf/frma 放进 sample entry（enca）内部，必须下钻才能拿到真实编码
        "enca", "mp4a", "drms", "alac", "fLaC",
    )

    /** 解密统计（供日志 / 排查） */
    data class Stats(
        val samples: Int,
        val decryptedSamples: Int,
        val decryptedBytes: Long,
        val mdats: Int,
        val relabeled: Int,
        val realCodec: String,
    )

    /**
     * 解密整份文件（**原地修改传入的 [data]**）。
     *
     * 传入的 [ByteArray] 会被就地改写 —— 这个 API 故意不复制：整曲 10~28MB，再复制一份
     * 会让堆峰值翻倍（实测 lossless 档曾因此把 256MB 堆打爆）。调用方需自行决定是否复用
     * 原缓冲区（[SodaCencFetcher] 是先落盘再原地解密）。
     *
     * @return 统计信息；结构不合法（无 senc / senc 与 stsz 数量不符）时抛
     *         [IllegalArgumentException]（调用方应视为解析失败，不要交给播放器）
     */
    fun decryptInPlace(data: ByteArray, key: ByteArray): Stats {
        require(key.size in setOf(16, 24, 32)) { "不是合法 AES 密钥长度：${key.size}" }

        val ivSize = run {
            val tenc = findAll(data, "tenc")
            val v = if (tenc.isNotEmpty() && tenc[0].payloadSize >= 8) {
                data[tenc[0].payloadStart + 7].toInt() and 0xFF
            } else 8
            if (v == 8 || v == 16) v else 8
        }

        val sizes = sampleSizes(data)
        val entries = sencEntries(data, ivSize, sizes.size)
            ?: throw IllegalArgumentException("没有 senc box：这不是加密文件？")
        if (entries.size < sizes.size) {
            throw IllegalArgumentException("senc 有 ${entries.size} 项，但 stsz 有 ${sizes.size} 个 sample")
        }

        val mdats = mdatRanges(data)
        var idx = 0
        var decryptedBytes = 0L

        for ((mStart, mEnd) in mdats) {
            var pos = mStart
            while (pos < mEnd && idx < sizes.size) {
                val n = sizes[idx]
                if (n <= 0 || pos + n > mEnd) break
                val (iv, subs) = entries[idx]
                val cipher = newCtrCipher(key, iv, ivSize)
                if (subs.isEmpty()) {
                    // 原地解密：只借用长度为 n 的小缓冲区，不复制整份文件
                    cipher.update(data, pos, n, data, pos)
                } else {
                    // CENC subsample：clear 段原样，encrypted 段连续喂给同一个 Cipher（keystream 跨段延续）
                    val writable = data
                    var written = 0
                    for ((clear, enc) in subs) {
                        if (clear > 0 && written < n) {
                            written += minOf(clear, n - written) // clear 段本就不动
                        }
                        if (enc > 0 && written < n) {
                            val e = minOf(enc, n - written)
                            cipher.update(writable, pos + written, e, writable, pos + written)
                            written += e
                        }
                    }
                }
                pos += n
                decryptedBytes += n
                idx++
            }
        }

        // 真实编码取自 frma（lossless 档是 fLaC，不是 mp4a）
        val realCodec = sampleEntryCodec(data) ?: "mp4a"
        var relabeled = 0
        for (box in findAll(data, "enca")) {
            val codec = realCodec.padEnd(4, ' ').take(4).toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(codec, 0, data, box.start + 4, 4)
            relabeled++
        }

        return Stats(
            samples = sizes.size,
            decryptedSamples = idx,
            decryptedBytes = decryptedBytes,
            mdats = mdats.size,
            relabeled = relabeled,
            realCodec = realCodec,
        )
    }

    private fun newCtrCipher(key: ByteArray, iv: ByteArray, ivSize: Int): Cipher {
        // CENC：8 字节 nonce（16 字节 IV 时，后 8 字节是起始 block counter）
        val nonce: ByteArray
        val counter: Long
        if (ivSize == 16 && iv.size >= 16) {
            nonce = iv.copyOfRange(0, 8)
            counter = readLongBe(iv, 8)
        } else {
            nonce = iv.copyOfRange(0, minOf(8, iv.size)).let {
                if (it.size == 8) it else it + ByteArray(8 - it.size)
            }
            counter = 0L
        }
        val fullIv = ByteArray(16)
        System.arraycopy(nonce, 0, fullIv, 0, 8)
        for (i in 0 until 8) fullIv[8 + i] = ((counter shr (8 * (7 - i))) and 0xFF).toByte()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fullIv))
        return cipher
    }

    private fun readLongBe(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /* ---------------- MP4 box 解析 ---------------- */

    private data class Box(val type: String, val start: Int, val payloadStart: Int, val end: Int) {
        val payloadSize: Int get() = end - payloadStart
    }

    /** 平铺解析某范围内的同级 box（不递归） */
    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = readIntBe(data, off)
            var hdr = 8
            if (size == 1) {
                if (off + 16 > end) break
                size = readIntBe(data, off + 8) // 实际项目里几乎不会 >2GB；按 32 位足够
                hdr = 16
            } else if (size == 0) {
                size = end - off
            }
            if (size < hdr || off + size > end) break
            val type = String(data, off + 4, 4, Charsets.ISO_8859_1)
            out.add(Box(type, off, off + hdr, off + size))
            off += size
        }
        return out
    }

    private fun readIntBe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** 递归查找指定类型的所有 box（含 stsd 的 sample entry 内部） */
    private fun findAll(data: ByteArray, want: String, start: Int = 0, end: Int = data.size, depth: Int = 0): List<Box> {
        val out = ArrayList<Box>()
        for (b in boxes(data, start, end)) {
            if (b.type == want) out.add(b)
            if (depth >= 14) continue
            when {
                b.type == "stsd" -> out.addAll(findAll(data, want, b.payloadStart + 8, b.end, depth + 1))
                b.type in CONTAINERS -> out.addAll(findAll(data, want, b.payloadStart, b.end, depth + 1))
            }
        }
        return out
    }

    /** `stsz`（或 `stz2`）→ 每个 sample 的字节数 */
    private fun sampleSizes(data: ByteArray): IntArray {
        for (b in findAll(data, "stsz")) {
            val p = b.payloadStart
            val sampleSize = readIntBe(data, p + 4)
            val count = readIntBe(data, p + 8)
            if (count <= 0) throw IllegalArgumentException("stsz count=0")
            val arr = IntArray(count)
            if (sampleSize != 0) {
                arr.fill(sampleSize)
            } else {
                for (i in 0 until count) arr[i] = readIntBe(data, p + 12 + i * 4)
            }
            return arr
        }
        for (b in findAll(data, "stz2")) {
            val p = b.payloadStart
            val fieldSize = data[p + 7].toInt() and 0xFF
            val count = readIntBe(data, p + 8)
            val arr = IntArray(count)
            when (fieldSize) {
                16 -> for (i in 0 until count) arr[i] = ((data[p + 12 + i * 2].toInt() and 0xFF) shl 8) or (data[p + 13 + i * 2].toInt() and 0xFF)
                8 -> for (i in 0 until count) arr[i] = data[p + 12 + i].toInt() and 0xFF
                else -> for (i in 0 until count) {
                    val byte = data[p + 12 + i / 2].toInt() and 0xFF
                    arr[i] = if (i % 2 == 0) (byte shr 4) and 0x0F else byte and 0x0F
                }
            }
            return arr
        }
        throw IllegalArgumentException("既无 stsz 也无 stz2")
    }

    private data class SencEntry(val iv: ByteArray, val subsamples: List<Pair<Int, Int>>)

    /** `senc` → 每 sample 的 (IV, subsamples)。无 senc 时返回 null。 */
    private fun sencEntries(data: ByteArray, ivSize: Int, count: Int): List<SencEntry>? {
        val hits = findAll(data, "senc")
        if (hits.isEmpty()) return null
        val b = hits[0]
        var p = b.payloadStart
        val flags = ((data[p + 1].toInt() and 0xFF) shl 16) or ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
        val n = readIntBe(data, p + 4)
        p += 8
        val hasSub = (flags and 0x2) != 0
        val list = ArrayList<SencEntry>(n)
        for (i in 0 until n) {
            if (p + ivSize > b.end) break
            val iv = data.copyOfRange(p, p + ivSize)
            p += ivSize
            val subs = ArrayList<Pair<Int, Int>>()
            if (hasSub) {
                if (p + 2 > b.end) break
                val k = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                p += 2
                for (j in 0 until k) {
                    if (p + 6 > b.end) break
                    val clear = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    val enc = readIntBe(data, p + 2)
                    p += 6
                    subs.add(clear to enc)
                }
            }
            list.add(SencEntry(iv, subs))
        }
        return list
    }

    private fun mdatRanges(data: ByteArray): List<Pair<Int, Int>> =
        boxes(data, 0, data.size).filter { it.type == "mdat" }.map { it.payloadStart to it.end }

    /**
     * 真实编码：扫 `frma` 签名（**不要按结构走**）。
     *
     * `enca` 是 sample entry，其音频头长度是 **28 字节**（QuickTime SoundDescription v0），
     * 不是 ISO 的 20 字节；差 8 字节就永远找不到 `frma`，于是所有档位都被错标成 `mp4a`，
     * lossless 的 FLAC 流因此被 ffmpeg 报几千个错误。按签名扫描最稳妥。
     */
    private fun sampleEntryCodec(data: ByteArray): String? {
        var i = indexOf(data, "frma", 0)
        while (i >= 0) {
            if (i + 8 <= data.size) {
                val codec = String(data, i + 4, 4, Charsets.ISO_8859_1)
                if (codec.all { it.code in 33..126 }) return codec
            }
            i = indexOf(data, "frma", i + 1)
        }
        return null
    }

    private fun indexOf(data: ByteArray, needle: String, from: Int): Int {
        val n = needle.toByteArray(Charsets.ISO_8859_1)
        var i = from
        outer@ while (i + n.size <= data.size) {
            for (j in n.indices) if (data[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}