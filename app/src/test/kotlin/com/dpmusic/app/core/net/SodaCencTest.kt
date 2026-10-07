package com.dpmusic.app.core.net

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [SodaCenc] 单测。
 *
 * 1. **合成 CENC 文件**：自造一个符合 CENC 结构的最小 MP4（`enca`+`senc`+`stsz`+`mdat`），
 *    用已知 key/IV 加密，再用 [SodaCenc.decryptInPlace] 解回来 —— 逐字节比对。这在不依赖外部
 *    文件的前提下钉死「box 解析 + AES-CTR + subsample + relabel」全部逻辑。
 * 2. **真实文件**（可选）：若 `/tmp/enc.m4a` + `/tmp/pa.txt` 存在，则对真实 relay 密文解密，
 *    验证 mdat 前若干字节确实被改写、且 relabel 正确。
 */
class SodaCencTest {

    /* ---------------- 合成文件 ---------------- */

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** 造一个 CENC 加密 MP4：N 个 sample，每 sample 用与其索引绑定的 IV 加密 */
    private fun buildCencFile(
        key: ByteArray,
        samples: List<ByteArray>,
        ivs: List<ByteArray>,
        frmaCodec: String = "mp4a",
    ): ByteArray {
        // mdat = 逐 sample AES-CTR 密文
        val mdatPayload = ByteArrayOutputStream()
        for (i in samples.indices) {
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(pad16(ivs[i])))
            mdatPayload.write(c.update(samples[i]))
        }
        val mdat = box("mdat", mdatPayload.toByteArray())

        // stsz: sample_size=0 + count + 各 sample 大小
        val stszBody = ByteArrayOutputStream()
        stszBody.write(byteArrayOf(0, 0, 0, 0))          // version/flags
        stszBody.write(be32(0))                          // sample_size = 0（变长）
        stszBody.write(be32(samples.size))
        samples.forEach { stszBody.write(be32(it.size)) }
        val stsz = box("stsz", stszBody.toByteArray())

        // senc: version/flags(flags=0 无 subsample) + count + 各 IV
        val sencBody = ByteArrayOutputStream()
        sencBody.write(byteArrayOf(0, 0, 0, 0))
        sencBody.write(be32(samples.size))
        ivs.forEach { sencBody.write(it) }               // 8 字节 IV
        val senc = box("senc", sencBody.toByteArray())

        // stsd > enca（内部带 sinf>frma='mp4a'），使 relabel 有据可依
        val frma = box("frma", frmaCodec.toByteArray(Charsets.ISO_8859_1))
        val sinf = box("sinf", box("schm", ByteArray(8)) + box("schi", box("tenc", ByteArray(8) + byteArrayOf(0, 0, 1, 8))))
        val enca = box("enca", ByteArray(28) + box("sinf", frma + sinf.drop(8).toByteArray()))
        val stsd = box("stsd", ByteArray(4) + be32(1) + enca)

        val stbl = box("stbl", stsd + stsz + senc)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        return box("ftyp", "M4A ".toByteArray() + ByteArray(8)) + moov + mdat
    }

    private fun pad16(iv: ByteArray): ByteArray =
        if (iv.size == 16) iv else iv + ByteArray(16 - iv.size)

    @Test
    fun `合成 CENC 文件逐字节解回`() {
        val key = byteArrayOf(0x45, 0x51.toByte(), 0x97.toByte(), 0x7d, 0x23, 0x42, 0xbc.toByte(), 0xaf.toByte(),
            0xc5.toByte(), 0xf0.toByte(), 0xfb.toByte(), 0xda.toByte(), 0xbd.toByte(), 0xdd.toByte(), 0x81.toByte(), 0x11)
        val samples = listOf(
            ByteArray(160) { (it * 7).toByte() },
            ByteArray(200) { (it * 3 + 1).toByte() },
            ByteArray(100) { (it * 11 + 5).toByte() },
        )
        val ivs = listOf(ByteArray(8) { (it + 1).toByte() }, ByteArray(8) { (it + 31).toByte() }, ByteArray(8) { (it + 61).toByte() })

        val file = buildCencFile(key, samples, ivs)
        val stats = SodaCenc.decryptInPlace(file, key)
        val plain = file // 原地解密：file 已被改写

        assertEquals(3, stats.samples)
        assertEquals(3, stats.decryptedSamples)
        assertEquals("mp4a", stats.realCodec)
        assertEquals(1, stats.relabeled)

        // mdat 负载必须等于原始明文（逐字节）
        val mdatStart = indexOfBytes(plain, "mdat".toByteArray()) + 4
        var off = mdatStart
        for (s in samples) {
            assertArrayEquals(s, plain.copyOfRange(off, off + s.size))
            off += s.size
        }

        // sample entry 已从 enca 改回 mp4a
        val out = String(plain, Charsets.ISO_8859_1)
        assertFalse("不应再有 enca", out.contains("enca"))
        assertTrue("应出现 mp4a", out.contains("mp4a"))
    }

    /** lossless 档的 frma 是 `fLaC`：必须按 frma 改写，不能写死 mp4a（协议 §3.4） */
    @Test
    fun `FLAC 档 relabel 为 fLaC 而不是 mp4a`() {
        val key = ByteArray(16) { 5 }
        val samples = listOf(ByteArray(120) { (it * 3).toByte() })
        val flag = buildCencFile(key, samples, listOf(ByteArray(8) { 2 }), frmaCodec = "fLaC")
        val stats = SodaCenc.decryptInPlace(flag, key)
        val plain = flag
        assertEquals("fLaC", stats.realCodec)
        val out = String(plain, Charsets.ISO_8859_1)
        assertTrue("应出现 fLaC", out.contains("fLaC"))
        assertFalse("不应残留 enca", out.contains("enca"))
        // mdat 明文仍须正确
        val mdatStart = indexOfBytes(plain, "mdat".toByteArray()) + 4
        assertArrayEquals(samples[0], plain.copyOfRange(mdatStart, mdatStart + samples[0].size))
    }

    @Test
    fun `未解密时 mdat 是密文（对照）`() {
        val key = ByteArray(16) { 9 }
        val samples = listOf(ByteArray(128) { 0x42 })
        val file = buildCencFile(key, samples, listOf(ByteArray(8) { 1 }))
        val mdatStart = indexOfBytes(file, "mdat".toByteArray()) + 4
        val cipherByte = file[mdatStart]
        assertFalse("密文首字节不应等于明文 0x42", cipherByte == 0x42.toByte())
    }

    @Test
    fun `错误密钥得到的是噪声（不会恰好等于明文）`() {
        val key = ByteArray(16) { 7 }
        val wrong = ByteArray(16) { 8 }
        val samples = listOf(ByteArray(256) { (it * 5).toByte() })
        val file = buildCencFile(key, samples, listOf(ByteArray(8) { 3 }))
        SodaCenc.decryptInPlace(file, wrong)
        val plain = file
        val mdatStart = indexOfBytes(plain, "mdat".toByteArray()) + 4
        assertFalse(samples[0].contentEquals(plain.copyOfRange(mdatStart, mdatStart + 256)))
    }

    @Test
    fun `没有 senc 的文件被明确拒绝而不是静默返回原文`() {
        // stsz 合法（count=1），但缺 senc → 必须报「没有 senc」而不是把密文当明文返回
        val stszBody = ByteArrayOutputStream()
        stszBody.write(ByteArray(4))       // version/flags
        stszBody.write(be32(0))            // sample_size = 0
        stszBody.write(be32(1))            // count = 1
        stszBody.write(be32(10))           // 单个 sample 大小
        val fake = box("ftyp", "M4A ".toByteArray()) +
            box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stsz", stszBody.toByteArray()))))))
        try {
            SodaCenc.decryptInPlace(fake, ByteArray(16))
            throw AssertionError("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应指明缺 senc：${e.message}", e.message!!.contains("senc"))
        }
    }

    /* ---------------- 真实文件（可选） ---------------- */

    @Test
    fun `真实 relay 密文可解密（若样本存在）`() {
        val encFile = java.io.File("/tmp/enc.m4a")
        val paFile = java.io.File("/tmp/pa.txt")
        assumeTrue("无真实样本，跳过", encFile.exists() && paFile.exists())

        val data = encFile.readBytes()
        val key = SodaCencKey.extractKey(paFile.readText().trim())
        assumeTrue("play_auth 无法解出 key", key != null)

        // 留一份原始密文用于对照（原地解密会改写 data）
        val original = data.copyOf()
        val stats = SodaCenc.decryptInPlace(data, key!!)
        val plain = data
        assertTrue("应解出大量 sample", stats.decryptedSamples > 1000)
        assertEquals("mp4a", stats.realCodec)
        assertEquals(1, stats.relabeled)
        // 密文与明文必须不同（证明真的解密了）
        val mdatStart = indexOfBytes(plain, "mdat".toByteArray()) + 4
        assertFalse(original.contentEquals(plain))
        assertTrue(plain.size == original.size)
        // 前若干字节应被改写
        assertFalse(
            original.copyOfRange(mdatStart, mdatStart + 64).contentEquals(plain.copyOfRange(mdatStart, mdatStart + 64)),
        )
    }

    private fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}