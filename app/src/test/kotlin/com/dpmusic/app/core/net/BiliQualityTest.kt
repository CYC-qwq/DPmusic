package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.BiliAccount
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.model.qualityOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按账号定音质」相关测试：
 * 1. B 站档位表随会员态变化（匿名 / 非会员只到 AAC；大会员解锁无损 / 全景声）；
 * 2. 降档链在会员态下正确；
 * 3. [BiliAccount] 的登录 / 会员判定语义。
 *
 * 这些是「登录 → 音质」这条链路的**逻辑底座**：UI 过滤、起始档、降档都依赖它们。
 */
class BiliQualityTest {

    /* ---------------- 档位表随会员态变化 ---------------- */

    @Test
    fun `B站匿名只到 AAC 顶档 320k`() {
        assertEquals(listOf("320k", "128k"), qualityOrder(MusicPlatform.BB, vip = false))
    }

    @Test
    fun `B站大会员解锁无损与全景声`() {
        assertEquals(listOf("flac", "atmos", "320k", "128k"), qualityOrder(MusicPlatform.BB, vip = true))
    }

    @Test
    fun `其它平台不受会员态影响`() {
        // vip 只对 B 站有意义，网易云档位表不因 vip 改变
        assertEquals(
            qualityOrder(MusicPlatform.WY, vip = false),
            qualityOrder(MusicPlatform.WY, vip = true),
        )
    }

    /* ---------------- 降档链 ---------------- */

    @Test
    fun `匿名请求无损时降档链回落到 AAC`() {
        // 非会员没有无损档 → chainFor 找不到 flac，退到 320k 起点
        val chain = PlayQuality.LOSSLESS.chainFor(MusicPlatform.BB, vip = false)
        assertEquals(listOf("320k", "128k"), chain)
    }

    @Test
    fun `大会员请求无损时降档链含无损与全景声`() {
        val chain = PlayQuality.LOSSLESS.chainFor(MusicPlatform.BB, vip = true)
        assertEquals(listOf("flac", "atmos", "320k", "128k"), chain)
    }

    @Test
    fun `大会员请求全景声时降档链跳过无损`() {
        val chain = PlayQuality.ATMOS.chainFor(MusicPlatform.BB, vip = true)
        assertEquals(listOf("atmos", "320k", "128k"), chain)
    }

    @Test
    fun `匿名请求 320k 时降档链只到 128k`() {
        assertEquals(listOf("320k", "128k"), PlayQuality.HIGH.chainFor(MusicPlatform.BB, vip = false))
    }

    /* ---------------- 账号语义 ---------------- */

    @Test
    fun `空 mid 视为未登录`() {
        val acc = BiliAccount()
        assertFalse(acc.isLogin)
        assertFalse(acc.isVip)
        assertTrue(acc.summary.contains("匿名"))
    }

    @Test
    fun `vipStatus 为 1 视为大会员`() {
        val acc = BiliAccount(mid = "123", nickname = "听歌的人", vipStatus = 1, vipLabel = "年度大会员")
        assertTrue(acc.isLogin)
        assertTrue(acc.isVip)
        assertTrue("大会员态应提示可无损", acc.summary.contains("无损"))
    }

    @Test
    fun `已登录但非会员时提示仅 AAC`() {
        val acc = BiliAccount(mid = "123", nickname = "路人", vipStatus = 0)
        assertTrue(acc.isLogin)
        assertFalse(acc.isVip)
        assertTrue("非会员态应提示仅 AAC", acc.summary.contains("AAC"))
    }

    @Test
    fun `vipStatus 非 1 不视为大会员`() {
        // 过期 / 异常值都不解锁无损，避免误判放大可播档位
        assertFalse(BiliAccount(mid = "1", vipStatus = 0).isVip)
        assertFalse(BiliAccount(mid = "1", vipStatus = 2).isVip)
    }
}