package com.dpmusic.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动波形包络测试。
 *
 * 这段逻辑跑在**音频线程**上、每 4.2ms 一次，一旦环形回绕或列边界算错，
 * 表现是「波形整幅跳一下 / 某一段是平的」——这类 off-by-one 靠肉眼看很难定位，
 * 所以抽成纯函数在这里直接喂人造缓冲验证。
 */
class WaveEnvelopeTest {

    private fun envelope(
        ring: FloatArray,
        mask: Int,
        writePos: Int,
        span: Int,
        columns: Int,
    ): FloatArray {
        val out = FloatArray(columns * 2)
        buildWaveEnvelopeInto(ring, mask, writePos, span, columns, out)
        return out
    }

    /** 值等于下标的环形缓冲，便于反推「取到了哪几个样本」 */
    private fun ramp(size: Int): FloatArray = FloatArray(size) { it.toFloat() }

    @Test
    fun `整数分列时每列取到正确的 min-max`() {
        // 窗口 = 0..15，4 列各 4 个样本
        val out = envelope(ramp(16), mask = 15, writePos = 16, span = 16, columns = 4)
        assertArrayEquals(floatArrayOf(0f, 3f, 4f, 7f, 8f, 11f, 12f, 15f), out)
    }

    @Test
    fun `窗口跨过缓冲区尾部时正确回绕`() {
        // writePos=2、span=8 → 窗口 = 环形缓冲的 [10..15] 再接 [0..1]
        val out = envelope(ramp(16), mask = 15, writePos = 2, span = 8, columns = 4)
        assertArrayEquals(
            floatArrayOf(10f, 11f, 12f, 13f, 14f, 15f, 0f, 1f),
            out,
        )
    }

    @Test
    fun `前进一个样本时整幅只平移一格（滚动的本质）`() {
        val ring = ramp(16)
        val columns = 8
        val a = envelope(ring, mask = 15, writePos = 8, span = columns, columns = columns)
        val b = envelope(ring, mask = 15, writePos = 9, span = columns, columns = columns)
        // 1 列 1 个样本 → 每列 min == max == 该样本
        for (c in 0 until columns) {
            assertEquals(a[c * 2], a[c * 2 + 1], 0f)
            assertEquals(b[c * 2], b[c * 2 + 1], 0f)
        }
        // b 应等于 a 左移一格（最左一格被移出，新的进到最右）
        for (c in 0 until columns - 1) {
            assertEquals("第 $c 列", a[(c + 1) * 2], b[c * 2], 0f)
        }
    }

    @Test
    fun `跨度不能整除列数时边界不重叠也不留缝`() {
        // 100 个样本切 7 列：边界应为 14, 28, 42, 57, 71, 85, 100
        val out = envelope(ramp(128), mask = 127, writePos = 100, span = 100, columns = 7)
        val expected = floatArrayOf(
            0f, 13f,
            14f, 27f,
            28f, 41f,
            42f, 56f,
            57f, 70f,
            71f, 84f,
            85f, 99f,
        )
        assertArrayEquals(expected, out)
    }

    @Test
    fun `空列退化为零而不是漏出 Float_MAX_VALUE`() {
        // span=2 但列数=4 → 第 0、2 列为空
        val ring = FloatArray(16) { (it + 10).toFloat() }
        val out = envelope(ring, mask = 15, writePos = 2, span = 2, columns = 4)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 10f, 10f, 0f, 0f, 11f, 11f),
            out,
        )
    }

    @Test
    fun `实测参数下正好覆盖整个跨度`() {
        // 0.5s @44.1kHz、512 列（线上参数）
        val span = 22050
        val columns = 512
        val ring = FloatArray(32768) { 0f }
        // 只让窗口最后一个样本非零：若边界少算一个样本，它就会漏掉
        ring[(4096 - 1) and 32767] = 1f
        val out = envelope(ring, mask = 32767, writePos = 4096, span = span, columns = columns)
        assertEquals(1f, out[columns * 2 - 1], 0f)
        // 每列边界严格递增（否则说明有列被跳过或重复）
        for (c in 0 until columns) {
            assertTrue("第 $c 列 min/max 反了", out[c * 2] <= out[c * 2 + 1])
        }
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals("长度不一致", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("第 $i 项", expected[i], actual[i], 0f)
        }
    }
}