package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.PlayQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QishuiResolver] 的档位逻辑单测。
 *
 * 背景（真实 bug）：resolver 曾把 `qualityId` 写死为 `PlayQuality.HIGH`（`320k`），
 * 导致不论 relay 实际给的是 `hi_res`/`lossless`，UI 永远显示「高品 320K」——
 * 看起来像音质被降级。这两组映射用测试钉死。
 */
class QishuiResolverQualityTest {

    private fun resolver(lossless: Boolean = false) = QishuiResolver(
        enabledProvider = { true },
        relay = null,
        cencFetcher = null,
        losslessPreferredProvider = { lossless },
    )

    /* ---------------- relay 实际档位 → 本地 PlayQuality（决定 UI 显示） ---------------- */

    @Test
    fun `relay 档位映射 - hi_res 显示 Hi-Res 而不是 320K`() {
        val r = resolver()
        assertEquals(PlayQuality.HIRES, r.playQualityOfForTest("hi_res"))
        assertEquals(PlayQuality.HIRES, r.playQualityOfForTest("spatial"))
        assertEquals(PlayQuality.HIRES, r.playQualityOfForTest("highest"))
    }

    @Test
    fun `relay 档位映射 - lossless 显示无损`() {
        assertEquals(PlayQuality.LOSSLESS, resolver().playQualityOfForTest("lossless"))
    }

    @Test
    fun `relay 档位映射 - higher 与 medium`() {
        val r = resolver()
        assertEquals(PlayQuality.HIGH, r.playQualityOfForTest("higher"))
        assertEquals(PlayQuality.STANDARD, r.playQualityOfForTest("medium"))
    }

    @Test
    fun `relay 档位映射 - 大小写不敏感且未知值不崩`() {
        val r = resolver()
        assertEquals(PlayQuality.HIRES, r.playQualityOfForTest("HI_RES"))
        assertEquals(PlayQuality.HIGH, r.playQualityOfForTest("weird"))
    }

    /** 核心回归：hi_res 不再被错标成 320k */
    @Test
    fun `回归 - hi_res 不等于 320k`() {
        assertFalse(resolver().playQualityOfForTest("hi_res") == PlayQuality.HIGH)
    }

    /* ---------------- 请求档位 → relay 尝试顺序 ---------------- */

    @Test
    fun `请求无损时优先试 lossless`() {
        val order = resolver().relayOrderForTest(PlayQuality.LOSSLESS)
        assertEquals("lossless", order.first())
        assertTrue(order.contains("hi_res"))
    }

    @Test
    fun `无损优先开关打开时优先试 lossless`() {
        assertEquals("lossless", resolver(lossless = true).relayOrderForTest(PlayQuality.HIGH).first())
    }

    @Test
    fun `请求 320k 时从 highest 起降`() {
        assertEquals(listOf("highest", "higher", "medium"), resolver().relayOrderForTest(PlayQuality.HIGH))
    }

    @Test
    fun `请求 128k 时只试 medium（不浪费体积）`() {
        assertEquals(listOf("medium"), resolver().relayOrderForTest(PlayQuality.STANDARD))
    }

    @Test
    fun `默认（未开无损）不从 lossless 起`() {
        assertFalse(resolver().relayOrderForTest(PlayQuality.HIGH).contains("lossless"))
        assertEquals("hi_res", resolver().relayOrderForTest(PlayQuality.HIRES).first())
    }
}