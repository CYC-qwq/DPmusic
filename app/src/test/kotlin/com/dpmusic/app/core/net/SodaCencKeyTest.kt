package com.dpmusic.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * [SodaCencKey] 单测：用**从真实 relay `play_auth` 抓取的向量**钉死密钥推导。
 *
 * 这三条 `play_auth` 取自 `http://122.10.114.177:8080/v1/play` 的真实响应；
 * 期望 key 由参考实现（`dpmusic/cenc_decrypt.py`，Python）算出，且解密后经 ffmpeg
 * 验证为真音频（mean_volume -9.8 dB，零解码错误）。因此这就是端到端锚点。
 */
class SodaCencKeyTest {

    /** base64 解码与 Python 逐字节一致（含 b_hex） */
    @Test
    fun `base64 解码逐字节一致`() {
        val pa = "kbwf92W+KfZ4uzbAfIowwEu5M8RIiDbASbk3xU25NsRIigC7uw=="
        val expectedHex =
            "91bc1ff765be29f678bb36c07c8a30c04bb933c4488836c049b937c54db936c4488a00bbbb"
        assertEquals(expectedHex, SodaCencKey.base64Decode(pa)!!.toHex())
    }

    /** 真实向量 1：终冕/洛天依 highest（relay 实测） */
    @Test
    fun `提取密钥 - 终冕 highest`() {
        val key = SodaCencKey.extractKey("kbwf92W+KfZ4uzbAfIowwEu5M8RIiDbASbk3xU25NsRIigC7uw==")
        assertEquals("45c351977d2342bcafc5f0fbfdadbdd8", key!!.toHex())
        assertEquals(16, key.size)
    }

    /** 真实向量 2：琴始琴终/GOuo medium */
    @Test
    fun `提取密钥 - 琴始琴终 medium`() {
        val key = SodaCencKey.extractKey("obwv9GONKcdngRrKS4QH1X2bBctPjB/wT6Af3FGNAcNMuASNjQ==")
        assertEquals("d25c3360f49749a8a9208d798d59753e", key!!.toHex())
    }

    /** 真实向量 3：琴始琴终/纯音空间 higher */
    @Test
    fun `提取密钥 - 纯音空间 higher`() {
        val key = SodaCencKey.extractKey("kbwf+2eDKc15gT7KdLE44UanP+13kDvZaokj91+mE/dBvwmHhw==")
        assertEquals("41ab7786042d48e0b30f41891fc93882", key!!.toHex())
    }

    /** 真实向量 4：lossless（FLAC 档）——协议 v2 新增，单曲 ~28MB */
    @Test
    fun `提取密钥 - lossless FLAC 档`() {
        val key = SodaCencKey.extractKey("obwvwH+OBfVMvBzFVoka8lWIBfN+ujXDf4oF9Hi/NPBnuC+mpg==")
        assertEquals("df98cd139b254c6d9dc23a12bfd33692", key!!.toHex())
        assertEquals(16, key.size)
    }

    /** 三条向量的 paddingLen 均为 2、skip 均为 1（与协议 §3.4 实测一致） */
    @Test
    fun `paddingLen 与 skip 结构一致`() {
        val list = listOf(
            "kbwf92W+KfZ4uzbAfIowwEu5M8RIiDbASbk3xU25NsRIigC7uw==",
            "obwv9GONKcdngRrKS4QH1X2bBctPjB/wT6Af3FGNAcNMuASNjQ==",
            "kbwf+2eDKc15gT7KdLE44UanP+13kDvZaokj91+mE/dBvwmHhw==",
        )
        for (pa in list) {
            val b = SodaCencKey.base64Decode(pa)!!
            assertEquals(37, b.size)
            val pad = ((b[0].toInt() and 0xFF) xor (b[1].toInt() and 0xFF) xor (b[2].toInt() and 0xFF)) - 48
            assertEquals(2, pad)
        }
    }

    @Test
    fun `非法输入返回 null 而不是抛异常`() {
        assertNull(SodaCencKey.extractKey(""))
        assertNull(SodaCencKey.extractKey("不是base64!!!"))
        assertNull(SodaCencKey.extractKey("YQ=="))          // 太短
        assertNull(SodaCencKey.base64Decode("@@@@"))
    }

    /** 不同 play_auth 必须得到不同 key（防止实现退化成常量） */
    @Test
    fun `不同输入得到不同密钥`() {
        val k1 = SodaCencKey.extractKey("kbwf92W+KfZ4uzbAfIowwEu5M8RIiDbASbk3xU25NsRIigC7uw==")!!
        val k2 = SodaCencKey.extractKey("obwv9GONKcdngRrKS4QH1X2bBctPjB/wT6Af3FGNAcNMuASNjQ==")!!
        assertEquals(false, k1.contentEquals(k2))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}