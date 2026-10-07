package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 哔哩哔哩直连 API —— [PlatformApi] 的第五个实现。
 *
 * ## 与其它平台的根本差异：这是**视频站**，不是音乐站
 * B 站没有「歌曲」实体，只有用户上传的视频。因此：
 * - **搜歌 = 搜含该歌的视频**：标题里混着「【Hi-Res无损】」「官方MV」等字样，
 *   歌手名不可靠。本实现把标题按常见后缀启发式清洗（见 [cleanTitle]），
 *   并把 UP 主放进 `artist`（对 B 站视频，UP 主是最接近「歌手」的字段）。
 * - **单曲 ID 是 `bvid`**（如 `BV1BZbSzZEGT`），播放在 [BiliResolver]。
 * - **无榜单 / 歌单搜索 / 歌词**：`toplists` / `rankSongs` / `searchPlaylists` /
 *   `playlistSongs` 返回空；`lyrics` 返回空文档。收藏夹需登录态，不在本通道。
 *
 * 边界（实测 2026-10）：搜索需 WBI 签名 + buvid Cookie，否则 412 / -403；
 * 音频档仅 AAC（无无损，会员 FLAC 匿名不可得）。
 */
class BiliPlatformApi : PlatformApi {

    override val platform = MusicPlatform.BB

    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val result = BiliApi.searchVideos(keyword, page, limit) ?: return emptyList()
        return result.arrOrNullValue().mapNotNull { searchItemToSong(it) }
            .distinctBy { it.id }
    }

    override suspend fun songDetail(id: String): Song? {
        if (id.isBlank()) return null
        val v = BiliApi.view(id) ?: return null
        return viewToSong(v)
    }

    override suspend fun songsDetail(ids: List<String>): List<Song> =
        ids.distinct().mapNotNull { runCatching { songDetail(it) }.getOrNull() }

    /** B 站无「歌单搜索」；合集（ugc_season）不在此通道展开 */
    override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> =
        emptyList()

    /** 需登录态的收藏夹，本通道不支持匿名解析 → 空 */
    override suspend fun playlistSongs(playlistId: String): List<Song> = emptyList()

    /**
     * B 站音乐区排行（`rid=3`）。
     *
     * B 站**没有**音乐站意义上的「榜单」，但有音乐区排行——它不是「本周金曲榜首」，
     * 而是「近期音乐区口碑 / 播放综合榜」，条目仍是**用户上传的视频**。
     * 对「用视频站当音乐源」的场景，这是最接近「榜」的内容形态，故接进来。
     * 实测 96 条，含 `bvid` + `cid`，可直接播放；其余分区返回 `-400` 故不接。
     */
    override suspend fun toplists(): List<RankSummary> {
        val data = BiliApi.musicRanking() ?: return emptyList()
        val count = data.arrOrNull("list")?.size ?: 0
        if (count == 0) return emptyList()
        return listOf(
            RankSummary(
                id = MUSIC_RANK_ID,
                platform = MusicPlatform.BB,
                name = "音乐区排行",
                coverUrl = data.arrOrNull("list")?.firstOrNull()?.str("pic")
                    ?.let { BiliText.normalizePic(it.replace("http://", "https://")) }.orEmpty(),
                updateFrequency = "动态更新",
                description = "近期音乐区综合榜 · ${data.str("note").orEmpty()}",
            ),
        )
    }

    override suspend fun rankSongs(rankId: String): List<Song> {
        if (rankId != MUSIC_RANK_ID) return emptyList()
        val list = BiliApi.musicRanking()?.arrOrNull("list") ?: return emptyList()
        return list.mapNotNull { rankItemToSong(it) }.distinctBy { it.id }
    }

    /**
     * B 站不提供 LRC 歌词（虽有 CC 字幕接口，但为视频字幕、
     * 与逐行歌词语义不同）→ 返回空文档，UI 自动隐藏歌词区。
     */
    override suspend fun lyrics(song: Song): SongLyrics = SongLyrics.EMPTY

    override suspend fun searchSuggest(keyword: String): List<String> =
        if (keyword.isBlank()) emptyList() else BiliApi.suggest(keyword)

    /** B 站热搜接口未接入 → 空（不展示热搜） */
    override suspend fun hotSearch(): List<String> = emptyList()

    /** B 站评论区接口未接入 → null */
    override suspend fun comments(songId: String, page: Int, limit: Int) = null

    /* ---------------- 账号（可选登录） ---------------- */

    /** 校验 Cookie 并读取账号资料（含会员态）；无效 / 未登录返回 null */
    suspend fun account(cookie: String): com.dpmusic.app.core.model.BiliAccount? = BiliApi.account(cookie)

    /* ---------------- 映射 ---------------- */

    /** 搜索条目 → [Song]；`aid` 一并缓存，播放时省一次 `view` */
    private fun searchItemToSong(item: JsonElement): Song? {
        val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: return null
        val title = cleanTitle(item.str("title").orEmpty()).ifBlank { return null }
        val author = item.str("author").orEmpty().ifBlank { "未知 UP" }
        return Song(
            id = bvid,
            platform = MusicPlatform.BB,
            title = title,
            artist = author,
            durationMs = parseDuration(item.str("duration")),
            coverUrl = normalizePic(item.str("pic")),
            maxQuality = PlayQuality.HIGH.id,
            extra = buildMap {
                item.str("aid")?.takeIf { it.isNotBlank() }?.let { put("bb_aid", it) }
            },
        )
    }

    /**
     * 排行条目 → [Song]。
     *
     * 排行接口的条目**已自带 `bvid` + `cid`**（实测确认），因此与 `viewToSong`
     * 不同：播放时无需再请求一次 `view` —— `aid`/`cid` 一并写进 `extra` 即可。
     * 标题同样要走 [BiliText.cleanTitle]（音乐区标题照样混着「【无损】」等噪声）。
     */
    private fun rankItemToSong(item: JsonElement): Song? {
        val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: return null
        val title = cleanTitle(item.str("title").orEmpty()).ifBlank { return null }
        val owner = item.objOrNull("owner")?.str("name").orEmpty().ifBlank { "未知 UP" }
        // 封面：排行返回 http:// ，播放器在 https 页面下会加载失败 → 升级协议
        val pic = item.str("pic").orEmpty().replace("http://", "https://")
        return Song(
            id = bvid,
            platform = MusicPlatform.BB,
            title = title,
            artist = owner,
            durationMs = (item.long("duration") ?: 0L) * 1000L,
            coverUrl = normalizePic(pic),
            maxQuality = PlayQuality.HIGH.id,
            extra = buildMap {
                item.str("aid")?.takeIf { it.isNotBlank() }?.let { put("bb_aid", it) }
                item.str("cid")?.takeIf { it.isNotBlank() }?.let { put("bb_cid", it) }
            },
        )
    }

    /** 详情响应 `data` → [Song]；`aid` / `cid` 一并缓存，播放时直接用 */
    private fun viewToSong(v: JsonElement): Song? {
        val bvid = v.str("bvid")?.takeIf { it.isNotBlank() } ?: return null
        val title = v.str("title").orEmpty().ifBlank { return null }
        val owner = v.objOrNull("owner")?.str("name").orEmpty().ifBlank { "未知 UP" }
        return Song(
            id = bvid,
            platform = MusicPlatform.BB,
            title = cleanTitle(title),
            artist = owner,
            durationMs = (v.long("duration") ?: 0L) * 1000L,
            coverUrl = normalizePic(v.str("pic")),
            maxQuality = PlayQuality.HIGH.id,
            extra = buildMap {
                v.str("aid")?.takeIf { it.isNotBlank() }?.let { put("bb_aid", it) }
                v.str("cid")?.takeIf { it.isNotBlank() }?.let { put("bb_cid", it) }
            },
        )
    }

    /**
     * 清洗视频标题，尽量还原歌名（实现见 [BiliText]，那里有单测钉住）。
     *
     * 搜索结果的 `title` 带 `<em class="keyword">…</em>` 高亮标签，必须剥离；
     * 另外常见的「无损 / MV / 4K」等前后缀噪声一并去掉，让匹配更接近真实歌名。
     */
    private fun cleanTitle(raw: String): String = BiliText.cleanTitle(raw)

    /** `//i0.hdslb.com/...` → `https://i0.hdslb.com/...`（B 站返回的是协议相对 URL） */
    private fun normalizePic(pic: String?): String = BiliText.normalizePic(pic)

    /** `"4:30"` / `"1:02:03"` → 毫秒；解析失败为 0 */
    private fun parseDuration(text: String?): Long = BiliText.parseDuration(text)

    private companion object {
        /** 音乐区排行的标识（`toplists` 与 `rankSongs` 共用） */
        const val MUSIC_RANK_ID = "music"
    }
}

/** `JsonElement?` 当作数组取元素（B 站搜索结果的 `data.result` 就是裸数组） */
private fun JsonElement?.arrOrNullValue(): List<JsonElement> =
    (this as? JsonArray)?.toList().orEmpty()
