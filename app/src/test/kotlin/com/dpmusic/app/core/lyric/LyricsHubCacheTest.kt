package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词缓存的有界性与收缩规则测试。
 *
 * 背景：改动前 `LyricsHub.cache` 是无上限的 `ConcurrentHashMap`，只增不减，
 * 实测每首逐字歌词约 22KB，长期播放会累积到 20MB+。这里钉住「它有界、
 * 且收缩时不会把正在看的那首清掉」。
 *
 * 注：`LyricsHub` 是单例，用例之间用 [LyricsHub.clearCache] 隔离。
 */
class LyricsHubCacheTest {

    @After
    fun tearDown() {
        LyricsHub.clearCache()
    }

    private fun song(id: String) = Song(
        id = id,
        platform = MusicPlatform.WY,
        title = "t$id",
        artist = "a",
    )

    private fun lyrics(text: String) = SongLyrics(listOf(LyricLine(1_000, text)))

    /* ---------------- 上限 ---------------- */

    @Test
    fun `缓存条目数不超过上限`() {
        // 灌入远超 CACHE_MAX(300) 的条数
        repeat(500) { i ->
            LyricsHub.publish(song("s$i"), lyrics("line$i"))
        }
        assertTrue(
            "缓存必须有界（当前 ${LyricsHub.cachedCount}）",
            LyricsHub.cachedCount <= 300,
        )
    }

    @Test
    fun `空歌词不写入缓存`() {
        LyricsHub.publish(song("empty"), SongLyrics.EMPTY)
        assertEquals("空结果不入缓存（保留切回重试的机会）", 0, LyricsHub.cachedCount)
    }

    /* ---------------- 收缩 ---------------- */

    @Test
    fun `trimTo 收缩到指定条数`() {
        repeat(100) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        assertEquals(100, LyricsHub.cachedCount)

        LyricsHub.trimTo(20)

        assertEquals(20, LyricsHub.cachedCount)
    }

    @Test
    fun `trimTo 不会把条数放大`() {
        repeat(10) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }

        LyricsHub.trimTo(100)

        assertEquals("目标大于现有量时不该凭空增加", 10, LyricsHub.cachedCount)
    }

    @Test
    fun `trimTo 后仍可继续写入（缓存未被破坏）`() {
        repeat(50) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        LyricsHub.trimTo(10)

        LyricsHub.publish(song("new"), lyrics("new"))

        assertEquals(11, LyricsHub.cachedCount)
    }

    @Test
    fun `clearCache 清空全部`() {
        repeat(30) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        LyricsHub.clearCache()
        assertEquals(0, LyricsHub.cachedCount)
    }

    /* ---------------- 当前曲保护 ---------------- */

    @Test
    fun `trimTo 保留当前正在播放曲目的歌词`() {
        repeat(60) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        // s0 是最早写入的 → 收缩时最先被逐出；把它设为当前曲来验证保护生效
        val current = song("s0")
        LyricsHub.setCurrentKeyForTest(current.stableKey)

        LyricsHub.trimTo(5)

        assertEquals(5, LyricsHub.cachedCount)
        assertTrue(
            "当前正在播放的曲目不该被逐出（否则正在显示的歌词会被清掉）",
            LyricsHub.isCached(current.stableKey),
        )
    }

    @Test
    fun `trimTo 到 0 时当前曲仍被保留`() {
        repeat(20) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        val current = song("s0")
        LyricsHub.setCurrentKeyForTest(current.stableKey)

        LyricsHub.trimTo(0)

        assertTrue("只剩当前曲也必须留着", LyricsHub.isCached(current.stableKey))
    }

    @Test
    fun `当前曲不在缓存中时 trimTo 仍收缩到目标`() {
        repeat(60) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        // 指向一首从未缓存过的曲目
        LyricsHub.setCurrentKeyForTest(song("never-cached").stableKey)

        LyricsHub.trimTo(5)

        assertEquals(5, LyricsHub.cachedCount)
    }

    /* ---------------- 极端参数 ---------------- */

    @Test
    fun `trimTo 传 0 时清空`() {
        repeat(20) { i -> LyricsHub.publish(song("s$i"), lyrics("l$i")) }
        LyricsHub.trimTo(0)
        assertEquals(0, LyricsHub.cachedCount)
    }

    @Test
    fun `空缓存上 trimTo 不崩`() {
        LyricsHub.trimTo(10)
        assertEquals(0, LyricsHub.cachedCount)
    }

    /* ---------------- 并发 ---------------- */

    @Test
    fun `并发写入不抛异常且维持上限`() {
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(100) { i -> LyricsHub.publish(song("t$t-$i"), lyrics("l")) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("并发写入后仍须有界", LyricsHub.cachedCount <= 300)
    }
}