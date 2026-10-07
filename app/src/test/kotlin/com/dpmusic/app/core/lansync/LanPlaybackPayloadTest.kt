package com.dpmusic.app.core.lansync

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放流转载荷的编解码与进度补偿契约。
 *
 * 这里钉住的是**跨设备语义**，不是本地逻辑：进度补偿错了，用户会看到
 * 「两端进度条差一个网络往返」；夹取规则错了，用户会看到「起播即跳下一首」。
 * 两类问题在单机自测里都发现不了。
 */
class LanPlaybackPayloadTest {

    private val songs = listOf(
        song("a", "第一首", 180_000L),
        song("b", "第二首", 240_000L),
        song("c", "第三首", 300_000L),
    )

    private fun song(id: String, title: String, durationMs: Long) = Song(
        id = id,
        platform = MusicPlatform.WY,
        title = title,
        artist = "测试歌手",
        durationMs = durationMs,
    )

    @Test
    fun `round trip keeps the whole queue and the exact position`() {
        val payload = LanPlaybackPayload(
            songs = songs,
            currentIndex = 1,
            positionMs = 133_000L,
            sentAtMs = 1_000_000L,
            isPlaying = true,
            fromAlias = "手机 A",
        )
        val json = LanJson.encodeToString(LanPlaybackPayload.serializer(), payload)
        val parsed = LanJson.decodeFromString(LanPlaybackPayload.serializer(), json)

        // 逐字段相等：任何一个字段丢了，接收端都会「接着放错的地方」
        assertEquals(payload, parsed)
        assertEquals(3, parsed.songs.size)
        assertEquals(133_000L, parsed.positionMs)
    }

    @Test
    fun `position is compensated by the elapsed transfer time`() {
        val payload = LanPlaybackPayload(
            songs = songs,
            currentIndex = 0,
            positionMs = 60_000L,
            sentAtMs = 1_000_000L,
            isPlaying = true,
        )
        // 传输 + 解析花了 800ms：接收端应从 60.8s 起播，而不是 60s
        assertEquals(60_800L, payload.compensatedPositionMs(nowMs = 1_000_800L))
    }

    @Test
    fun `paused playback is not compensated`() {
        val payload = LanPlaybackPayload(
            songs = songs,
            currentIndex = 0,
            positionMs = 60_000L,
            sentAtMs = 1_000_000L,
            isPlaying = false,
        )
        // 暂停态进度是静止的：加补偿会跳到一个「未来」的位置
        assertEquals(60_000L, payload.compensatedPositionMs(nowMs = 1_030_000L))
    }

    @Test
    fun `clock skew never produces a negative or shrinking position`() {
        val payload = LanPlaybackPayload(
            songs = songs,
            currentIndex = 0,
            positionMs = 5_000L,
            // 对端时钟比本机快 10 秒（发送时刻在未来）→ elapsed 为负
            sentAtMs = 1_010_000L,
            isPlaying = true,
        )
        assertEquals(5_000L, payload.compensatedPositionMs(nowMs = 1_000_000L))
    }

    @Test
    fun `negative stored position is clamped to zero`() {
        val payload = LanPlaybackPayload(
            songs = songs,
            currentIndex = 0,
            positionMs = -5_000L,
            sentAtMs = 1_000_000L,
            isPlaying = false,
        )
        assertEquals(0L, payload.compensatedPositionMs(nowMs = 1_000_000L))
    }

    @Test
    fun `payload decodes with defaults when the peer omits fields`() {
        // 对端可能是更早 / 更简单的实现：只给队列与索引也必须能解出来
        val minimal = """{"songs":[],"currentIndex":0}"""
        val parsed = LanJson.decodeFromString(LanPlaybackPayload.serializer(), minimal)
        assertEquals(0, parsed.currentIndex)
        assertEquals(0L, parsed.positionMs)
        assertNotNull(parsed.songs)
    }

    @Test
    fun `playback file name is on the accepted list`() {
        // 接收端的白名单与协议常量必须一致：漏了它，流转会被当成「非 DPmusic 数据」拒绝
        assertTrue(LanProtocol.FILE_PLAYBACK.endsWith(".json"))
        assertTrue(LanProtocol.FILE_PLAYBACK.startsWith("dpmusic-"))
    }
}