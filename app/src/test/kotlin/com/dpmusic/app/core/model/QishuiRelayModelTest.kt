package com.dpmusic.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QishuiRelayResult] / [QishuiRelayException] 的语义单测。
 *
 * 这些标签与错误分类直接驱动播放决策（是否播放、是否回退、是否重试），
 * 用测试钉住，避免以后改文案时误改语义。
 */
class QishuiRelayModelTest {

    private fun result(quality: String) = QishuiRelayResult(
        url = "https://cdn/x", quality = quality, codec = "aac",
        durationS = 1.0, catalogueS = 1.0, isFullLength = true,
        cache = "hit", expiresHintS = 43200,
    )

    @Test
    fun `档位标签 - 协议五档`() {
        assertEquals("高音质", result("hi_res").qualityLabel)
        assertEquals("空间音频", result("spatial").qualityLabel)
        assertEquals("极高", result("highest").qualityLabel)
        assertEquals("较高", result("higher").qualityLabel)
        assertEquals("标准", result("medium").qualityLabel)
    }

    @Test
    fun `档位标签 - 未知档位回退原文`() {
        assertEquals("weird_tier", result("weird_tier").qualityLabel)
        assertEquals("未知", result("").qualityLabel)
    }

    /** 协议 §0/§5：hi_res 不标「无损」；但 v2 的 lossless 档确实是无损 */
    @Test
    fun `不把 hi_res 标成无损`() {
        assertFalse(result("hi_res").qualityLabel.contains("无损"))
    }

    /** lossless 档标签为「无损」（协议 v2 新增的 FLAC 档） */
    @Test
    fun `lossless 档标为无损`() {
        assertEquals("无损", result("lossless").qualityLabel)
    }

    /* ---------------- 错误分类 ---------------- */

    @Test
    fun `503 或 agent_unavailable 判为家机离线`() {
        assertTrue(QishuiRelayException("agent_unavailable", 503, "x").agentOffline)
        assertTrue(QishuiRelayException("", 503, "x").agentOffline)
        assertFalse(QishuiRelayException("no_tier", 502, "x").agentOffline)
    }

    @Test
    fun `401 判为鉴权失败`() {
        assertTrue(QishuiRelayException("unauthorized", 401, "x").unauthorized)
        assertFalse(QishuiRelayException("no_tier", 502, "x").unauthorized)
    }

    @Test
    fun `错误码常量与协议一致`() {
        assertEquals("no_tier", QishuiRelayException.CODE_NO_TIER)
        assertEquals("agent_unavailable", QishuiRelayException.CODE_AGENT_UNAVAILABLE)
        assertEquals("unauthorized", QishuiRelayException.CODE_UNAUTHORIZED)
        assertEquals("bad_request", QishuiRelayException.CODE_BAD_REQUEST)
    }
}