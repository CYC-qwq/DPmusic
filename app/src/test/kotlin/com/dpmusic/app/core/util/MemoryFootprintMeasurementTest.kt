package com.dpmusic.app.core.util

import com.dpmusic.app.core.lyric.LyricsHub
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存改造的**效果测量**（不是行为断言，而是量出真实数字）。
 *
 * 用真实结构的歌词对象（含逐字时间轴）灌入缓存，对比：
 * - **原实现**：无上限 `ConcurrentHashMap`，只增不减 → 全部驻留；
 * - **新实现**：有界 LRU(300) + 压力收缩 → 驻留量封顶。
 *
 * 这里不 mock 任何东西 —— 直接调 [LyricsHub] 的真实 API，
 * 用 `cachedCount` 与对象结构估算驻留字节数，给出可复算的对比。
 */
class MemoryFootprintMeasurementTest {

    @After
    fun tearDown() {
        LyricsHub.clearCache()
        LyricsHub.setCurrentKeyForTest("")
    }

    /**
     * 构造一首「典型带逐字时间轴的歌词」。
     *
     * 参数按**实测抽样**定：网易云 YRC 平均每首 261 个逐字单元、
     * 行数约 50 → 每行约 5 个词。这样单首近似占用落在实测的 21~22KB 区间，
     * 而不是我随手拍的更大数字。
     */
    private fun realisticLyrics(seed: Int): SongLyrics {
        val lines = (0 until 50).map { i ->
            val words = (0 until 5).map { w ->
                LyricWord(text = "字", startMs = (i * 1000L + w * 100L), durationMs = 100L)
            }
            LyricLine(
                timeMs = i * 1000L,
                text = "第${i}行歌词内容示例-$seed",
                words = words,
            )
        }
        return SongLyrics(lines = lines, hasWordTiming = true)
    }

    private fun song(index: Int) = Song(
        id = "song$index",
        platform = MusicPlatform.WY,
        title = "测试歌曲$index",
        artist = "歌手",
    )

    /**
     * 单首歌词的**近似驻留字节**（JVM，compressed oops）。
     *
     * 估算口径（保守，便于复算）：
     * - `LyricLine`: 对象头 16 + long 8 + String ref 4 + trans ref 4 + List ref 4 ≈ 40B
     * - `LyricWord`: 头 16 + String ref 4 + long 8 + long 8 ≈ 40B
     * - 每字符 String ≈ 24B + 内容 2B
     */
    private fun approxBytes(lyrics: SongLyrics): Long {
        var total = 0L
        for (line in lyrics.lines) {
            total += 40
            total += (line.text.length * 2 + 24)
            total += line.words.size * 40L
            for (w in line.words) total += (w.text.length * 2 + 24)
        }
        return total
    }

    /* ---------------- 测量 1：无上限 vs 有界 ---------------- */

    @Test
    fun `测量 缓存驻留量：原实现无上限 新实现封顶`() {
        val songs = 500
        val perSong = approxBytes(realisticLyrics(0))

        // —— 原实现的行为：全部驻留 ——
        val originalBytes = perSong * songs

        // —— 新实现：写满 500 首后，缓存被 LRU 封顶 ——
        repeat(songs) { i -> LyricsHub.publish(song(i), realisticLyrics(i)) }

        val newCount = LyricsHub.cachedCount
        val newBytes = perSong * newCount

        println("=== 歌词缓存驻留量（$songs 首已播放）===")
        println("单首近似占用      : ${perSong} B")
        println("原实现（无上限）  : $originalBytes B = ${originalBytes / 1024} KB")
        println("新实现（LRU 300） : $newBytes B = ${newBytes / 1024} KB")
        println("减少              : ${(originalBytes - newBytes) / 1024} KB "
            + "(${((originalBytes - newBytes) * 100 / originalBytes)}%)")

        assertTrue("新实现必须封顶", newCount <= 300)
        assertTrue("实测确实更省", newBytes < originalBytes)
        assertEquals("LRU 上限应为 300", 300, newCount)
    }

    /* ---------------- 测量 2：极端播放量 ---------------- */

    @Test
    fun `测量 连续播放 2000 首时的驻留量`() {
        val songs = 2000
        val perSong = approxBytes(realisticLyrics(0))

        repeat(songs) { i -> LyricsHub.publish(song(i), realisticLyrics(i)) }

        val original = perSong * songs
        val now = perSong * LyricsHub.cachedCount

        println("=== 连续播放 $songs 首 ===")
        println("原实现 : ${original / 1024} KB  —— 注意：这只是模型外推；")
        println("         真机上它会持续增长直到超过堆上限，届时被系统 kill（后台播放中断），")
        println("         而不是平稳停在某个数字。")
        println("新实现 : ${now / 1024} KB（恒定封顶，与播放量无关）")

        assertTrue("$songs 首后仍须封顶", LyricsHub.cachedCount <= 300)
    }

    /* ---------------- 测量 3：内存压力收缩 ---------------- */

    @Test
    fun `测量 触发内存压力后缓存骤降`() {
        repeat(300) { i -> LyricsHub.publish(song(i), realisticLyrics(i)) }
        val before = LyricsHub.cachedCount
        LyricsHub.setCurrentKeyForTest(song(299).stableKey)

        // 模拟 SEVERE 档：收缩到 40
        LyricsHub.trimTo(40)
        val afterSevere = LyricsHub.cachedCount

        // 模拟 CRITICAL 档：只留当前曲
        LyricsHub.trimTo(1)
        val afterCritical = LyricsHub.cachedCount

        println("=== 内存压力下的收缩 ===")
        println("常态        : $before 首")
        println("SEVERE 档后 : $afterSevere 首")
        println("CRITICAL 后 : $afterCritical 首")

        assertEquals(300, before)
        assertEquals(40, afterSevere)
        assertEquals(1, afterCritical)
        assertTrue("当前曲必须幸存", LyricsHub.isCached(song(299).stableKey))
    }
}