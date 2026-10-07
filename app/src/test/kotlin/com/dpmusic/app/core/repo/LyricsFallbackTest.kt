package com.dpmusic.app.core.repo

import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.net.LxResolver
import com.dpmusic.app.core.net.PlatformApi
import com.dpmusic.app.core.script.ScriptEnginePool
import com.dpmusic.app.core.script.ScriptMusicResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词跨平台兜底的规则测试。
 *
 * 这些规则决定「这首歌到底有没有歌词、有的话是谁家的」，但全在 UI 后面，
 * 肉眼看不出来，所以用单测钉住 —— 尤其是两条安全边界：
 * 1. **绝不使用用户已关闭的平台**（关掉的音源不该「以兜底之名」被偷偷使用）；
 * 2. **必须同名才采用**（宁可没有歌词，也不要张冠李戴的歌词）。
 */
class LyricsFallbackTest {

    /* ---------------- 假平台：只关心歌词与搜索 ---------------- */

    /** open：个别用例要继承它来伪造「接口抛异常」 */
    private open class FakeApi(
        override val platform: MusicPlatform,
        /** 搜索返回的同名曲（null = 搜不到） */
        private val searchHit: Song? = null,
        /** 这个平台的歌词（空 = 该平台也没歌词） */
        private val lyricLines: List<LyricLine> = emptyList(),
    ) : PlatformApi {
        var lyricCalls = 0
            private set
        var searchCalls = 0
            private set

        override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
            searchCalls++
            return listOfNotNull(searchHit)
        }

        override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> =
            emptyList()

        override suspend fun playlistSongs(playlistId: String): List<Song> = emptyList()
        override suspend fun toplists(): List<RankSummary> = emptyList()
        override suspend fun rankSongs(rankId: String): List<Song> = emptyList()

        override suspend fun lyrics(song: Song): SongLyrics {
            lyricCalls++
            return if (lyricLines.isEmpty()) SongLyrics.EMPTY else SongLyrics(lyricLines)
        }

        override suspend fun comments(songId: String, page: Int, limit: Int): CommentsPage? = null
    }

    private fun song(platform: MusicPlatform, id: String, title: String, artist: String) =
        Song(id = id, platform = platform, title = title, artist = artist)

    /** 造一个只注入歌词相关依赖的仓库；resolver 在歌词路径上不会被调用 */
    private fun repo(
        apis: Map<MusicPlatform, PlatformApi>,
        enabled: List<MusicPlatform> = emptyList(),
    ) = MusicRepository(
        apis = apis,
        resolver = LxResolver(apiKeyProvider = { "" }),
        scriptResolver = ScriptMusicResolver(ScriptEnginePool { throw UnsupportedOperationException() }) { emptyList() },
        enabledPlatformsProvider = { enabled },
    )

    private val oneLine = listOf(LyricLine(1_000, "line"))

    /* ---------------- 主链路：本平台有就用本平台 ---------------- */

    @Test
    fun `本平台有歌词时不触发兜底`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY, lyricLines = oneLine)
        val qq = FakeApi(MusicPlatform.QQ, searchHit = song(MusicPlatform.QQ, "m1", "t", "a"), lyricLines = oneLine)
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertEquals(oneLine, lyrics.lines)
        assertNull("本平台命中时不该标注来源", lyrics.sourcePlatform)
        assertEquals("不该去别的平台搜", 0, qq.searchCalls)
    }

    /* ---------------- 兜底命中：拿到歌词 + 标注来源 ---------------- */

    @Test
    fun `本平台无歌词时从 QQ 兜底并标注来源`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)   // 空歌词（复现网易云 uncollected 场景）
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m1", "FX戦士くるみちゃん", "鈴木愛奈"),
            lyricLines = oneLine,
        )
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "3443432381", "FX戦士くるみちゃん", "鈴木愛奈"))

        assertEquals(oneLine, lyrics.lines)
        assertEquals("必须标注实际来源，不冒充原平台", MusicPlatform.QQ, lyrics.sourcePlatform)
        assertTrue(lyrics.isCrossPlatform)
    }

    /* ---------------- 安全边界 1：不用已关闭的平台 ---------------- */

    @Test
    fun `已关闭的平台不参与歌词兜底`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m1", "t", "a"),
            lyricLines = oneLine,
        )
        // 用户关掉了 QQ，只留网易云
        val lyrics = repo(
            mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq),
            enabled = listOf(MusicPlatform.WY),
        ).lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertTrue("关掉的音源不该被偷偷使用", lyrics.isEmpty)
        assertEquals("根本不该去问 QQ", 0, qq.searchCalls)
    }

    /* ---------------- 安全边界 2：不同名不采用 ---------------- */

    @Test
    fun `兜底平台搜到不同名的歌时宁可无歌词`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)
        // 搜出来的是一首完全无关的歌
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m9", "完全无关的另一首歌", "别的歌手"),
            lyricLines = oneLine,
        )
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "目标歌曲", "目标歌手"))

        assertTrue("错配的歌词比没有更糟", lyrics.isEmpty)
    }

    @Test
    fun `歌名相同但歌手不同时仍采用（译者版本等合理差异）`() = runBlocking {
        // `similar` 的既定语义：歌名匹配时歌手允许近似 —— 这里钉住该行为不被误改
        val wy = FakeApi(MusicPlatform.WY)
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m1", "目标歌曲", "目标歌手/另一人"),
            lyricLines = oneLine,
        )
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "目标歌曲", "目标歌手"))

        assertEquals(oneLine, lyrics.lines)
        assertEquals(MusicPlatform.QQ, lyrics.sourcePlatform)
    }

    /* ---------------- 全都没有 → 空 ---------------- */

    @Test
    fun `所有平台都没有歌词时返回空`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)
        val qq = FakeApi(MusicPlatform.QQ, searchHit = song(MusicPlatform.QQ, "m1", "t", "a"))
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertTrue(lyrics.isEmpty)
        assertNull(lyrics.sourcePlatform)
    }

    @Test
    fun `兜底平台搜不到候选时返回空`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)
        val qq = FakeApi(MusicPlatform.QQ, searchHit = null)
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertTrue(lyrics.isEmpty)
        assertEquals(1, qq.searchCalls)
    }

    /* ---------------- 自身就是 QQ 的歌：不自我兜底 ---------------- */

    @Test
    fun `QQ 曲目无歌词时不会拿自己的搜索结果再查一遍`() = runBlocking {
        val qq = FakeApi(MusicPlatform.QQ, searchHit = song(MusicPlatform.QQ, "m1", "t", "a"))
        val lyrics = repo(mapOf(MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.QQ, "1", "t", "a"))

        assertTrue(lyrics.isEmpty)
        assertEquals("不该把自己排进兜底链", 0, qq.searchCalls)
        assertEquals(1, qq.lyricCalls)   // 只查了本平台这一次
    }

    /* ---------------- 异常安全：本平台抛错也不崩 ---------------- */

    @Test
    fun `本平台歌词接口抛异常时仍尝试兜底`() = runBlocking {
        val wy = object : FakeApi(MusicPlatform.WY) {
            override suspend fun lyrics(song: Song): SongLyrics = throw IllegalStateException("boom")
        }
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m1", "t", "a"),
            lyricLines = oneLine,
        )
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertEquals(oneLine, lyrics.lines)
        assertEquals(MusicPlatform.QQ, lyrics.sourcePlatform)
    }

    @Test
    fun `兜底平台抛异常时静默返回空（不把异常抛给 UI）`() = runBlocking {
        val wy = FakeApi(MusicPlatform.WY)
        val qq = object : FakeApi(MusicPlatform.QQ) {
            override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> =
                throw IllegalStateException("network down")
        }
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "1", "t", "a"))

        assertTrue(lyrics.isEmpty)
    }

    /* ---------------- 数据模型默认值 ---------------- */

    @Test
    fun `普通歌词默认不带来源标注`() {
        val lyrics = SongLyrics(oneLine)
        assertNull(lyrics.sourcePlatform)
        assertFalse(lyrics.isCrossPlatform)
        assertTrue(SongLyrics.EMPTY.isEmpty)
        assertNull(SongLyrics.EMPTY.sourcePlatform)
    }

    /* ---------------- 纯符号歌名的匹配（本次真实踩到的坑） ---------------- */

    @Test
    fun `纯符号歌名归一化后为空时按原名精确匹配`() {
        // `$・¥・€` 里全是符号 / 货币符，normalize 后是空串。
        // 修复前 similar 直接判否 → 这首歌永远兜不到歌词。
        val a = song(MusicPlatform.WY, "1", "\$・¥・€", "鈴木愛奈")
        val b = song(MusicPlatform.QQ, "m1", "\$・¥・€", "鈴木愛奈")
        assertTrue("同一首歌必须能匹配上", MusicRepository.similar(a, b))
    }

    @Test
    fun `纯符号歌名但原名不同时仍不匹配`() {
        // 修复不能把「两个不同的符号歌名」误判成同一首
        val a = song(MusicPlatform.WY, "1", "\$・¥・€", "鈴木愛奈")
        val b = song(MusicPlatform.QQ, "m1", "!!!", "鈴木愛奈")
        assertFalse("不同歌名不该匹配", MusicRepository.similar(a, b))
    }

    @Test
    fun `一侧歌名为符号一侧为正常时视为不同`() {
        val a = song(MusicPlatform.WY, "1", "\$・¥・€", "鈴木愛奈")
        val b = song(MusicPlatform.QQ, "m1", "正常歌名", "鈴木愛奈")
        assertFalse(MusicRepository.similar(a, b))
    }

    @Test
    fun `兜底链能命中纯符号歌名的曲目`() = runBlocking {
        // 端到端：本平台空 → QQ 有同名符号歌名 + 歌词 → 应拿到并标注来源
        val wy = FakeApi(MusicPlatform.WY)
        val qq = FakeApi(
            MusicPlatform.QQ,
            searchHit = song(MusicPlatform.QQ, "m1", "\$・¥・€", "鈴木愛奈"),
            lyricLines = oneLine,
        )
        val lyrics = repo(mapOf(MusicPlatform.WY to wy, MusicPlatform.QQ to qq))
            .lyrics(song(MusicPlatform.WY, "3443432382", "\$・¥・€", "鈴木愛奈"))

        assertEquals(oneLine, lyrics.lines)
        assertEquals(MusicPlatform.QQ, lyrics.sourcePlatform)
    }
}
