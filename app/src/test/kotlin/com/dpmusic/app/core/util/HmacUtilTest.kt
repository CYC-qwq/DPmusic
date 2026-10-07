package com.dpmusic.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HmacUtil] 单测：用 **RFC 4231 官方测试向量** 钉住算法实现。
 *
 * 这是整个 relay 通道能被服务端接受的前提 —— 只要算法一致、密钥/时间戳/路径一致，
 * 签名就恒定且唯一，任何人（含服务端）都能独立复算。
 */
class HmacUtilTest {

    /** RFC 4231 Test Case 1：key = 20 × 0x0b，data = "Hi There" */
    @Test
    fun `RFC 4231 Test Case 1`() {
        val key = String(CharArray(20) { 0x0b.toChar() })
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacUtil.hmacSha256Hex(key, "Hi There"),
        )
    }

    /** RFC 4231 Test Case 2：key = "Jefe"，data = "what do ya want for nothing?" */
    @Test
    fun `RFC 4231 Test Case 2`() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacUtil.hmacSha256Hex("Jefe", "what do ya want for nothing?"),
        )
    }

    /** 输出恒为 64 位十六进制小写 */
    @Test
    fun `输出为 64 位十六进制小写`() {
        val sig = HmacUtil.deviceSignature("test-secret", 1700000000L, "/v1/play")
        assertEquals(64, sig.length)
        assertTrue("必须全为小写十六进制", sig.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** 与 `device.<ts>.<path>` 拼接规则严格一致（改一处即失配） */
    @Test
    fun `设备签名拼接规则 device ts path`() {
        assertEquals(
            "97925cd8e038e140c2e4acb96e9c69da45dff463137bdbbcb201be3487bbdd5c",
            HmacUtil.deviceSignature("test-secret", 1700000000L, "/v1/play"),
        )
    }

    /** 时间戳变化 → 签名必须变化（这正是服务端 300s 窗口的依据） */
    @Test
    fun `时间戳变化导致签名变化`() {
        val a = HmacUtil.deviceSignature("s", 1700000000L, "/v1/play")
        val b = HmacUtil.deviceSignature("s", 1700000001L, "/v1/play")
        assertTrue(a != b)
    }

    /** 路径变化 → 签名必须变化（`path` 不含查询串，不可用同一签名打不同接口） */
    @Test
    fun `路径变化导致签名变化`() {
        val a = HmacUtil.deviceSignature("s", 1700000000L, "/v1/play")
        val b = HmacUtil.deviceSignature("s", 1700000000L, "/v1/audio")
        assertTrue(a != b)
    }

    /** 密钥变化 → 签名必须变化 */
    @Test
    fun `密钥变化导致签名变化`() {
        val a = HmacUtil.deviceSignature("secret-a", 1700000000L, "/v1/play")
        val b = HmacUtil.deviceSignature("secret-b", 1700000000L, "/v1/play")
        assertTrue(a != b)
    }

    /** 同一输入两次调用必须完全一致（无随机盐，可复现） */
    @Test
    fun `同一输入结果可复现`() {
        assertEquals(
            HmacUtil.deviceSignature("k", 1L, "/p"),
            HmacUtil.deviceSignature("k", 1L, "/p"),
        )
    }
}