package com.dpmusic.app.core.net

import com.dpmusic.app.core.lyric.KrcParser
import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 汽水音乐（抖音）直连 API —— [PlatformApi] 的第四个实现。
 *
 * 全部走**匿名**端点（App UA `Luna/19.1.0 Android`，无签名、无登录）：
 * - 搜索：`POST /luna/search/track?q=` → `result_groups[].data[].entity.track`
 * - 歌单：`POST /luna/playlist/detail` → `media_resources[].entity.(track | track_wrapper.track)`
 * - 联想：`GET  /luna/sug`；热词：`POST /luna/search-block`
 * - 单曲详情 + 歌词：`GET /luna/h5/seo_track`（浏览器 UA）→ `seo_track.track` / `lyric.content`
 *
 * 边界（实测）：内容形态为**场景电台**（无榜单接口，`toplists()` 以场景列表呈现，
 * 见该类实现）；评论接口不可匿名（返回 null）。
 * 取播放地址不在此类，见 [QishuiResolver]。
 */
class QishuiPlatformApi : PlatformApi {

    override val platform = MusicPlatform.QS

    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val cursor = ((page - 1).coerceAtLeast(0)) * limit
        val out = mutableListOf<JsonElement>()
        runCatching { QishuiApi.searchTracksRoot(keyword, cursor = cursor, count = limit) }
            .getOrNull()?.let { collectTracks(it, out) }
        return out.mapNotNull { trackToSong(it) }.distinctBy { it.id }
    }

    override suspend fun songDetail(id: String): Song? {
        if (id.isBlank()) return null
        val track = runCatching { QishuiApi.seoTrack(id) }.getOrNull()
            ?.objOrNull("seo_track")?.objOrNull("track")
            ?: return null
        return trackToSong(track)
    }

    override suspend fun songsDetail(ids: List<String>): List<Song> =
        ids.distinct().mapNotNull { runCatching { songDetail(it) }.getOrNull() }

    override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> {
        if (keyword.isBlank()) return emptyList()
        val sugs = runCatching { QishuiApi.sug(keyword) }.getOrNull()?.arrOrNull("sugs") ?: return emptyList()
        return sugs.mapNotNull { s ->
            val e = s.objOrNull("entity")?.objOrNull("playlist") ?: return@mapNotNull null
            val id = e.str("id") ?: return@mapNotNull null
            PlaylistSummary(
                id = id,
                platform = MusicPlatform.QS,
                name = s.str("suggestion").orEmpty(),
                trackCount = 0,
            )
        }.distinctBy { it.id }.take(limit)
    }

    override suspend fun playlistSongs(playlistId: String): List<Song> {
        if (playlistId.isBlank()) return emptyList()
        val root = runCatching { QishuiApi.playlistDetail(playlistId, count = 50) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<JsonElement>()
        collectTracks(root, out)
        return out.mapNotNull { trackToSong(it) }.distinctBy { it.id }
    }

    override suspend fun playlistMeta(playlistId: String): PlaylistSummary? {
        if (playlistId.isBlank()) return null
        val root = runCatching { QishuiApi.playlistDetail(playlistId, count = 1) }.getOrNull() ?: return null
        val pl = root.objOrNull("playlist") ?: return null
        val name = pl.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistSummary(
            id = playlistId,
            platform = MusicPlatform.QS,
            name = name,
            coverUrl = pl.objOrNull("url_cover")?.let { coverOf(it) }.orEmpty(),
            trackCount = pl.int("count_tracks") ?: pl.int("media_count") ?: 0,
        )
    }

    /**
     * 汽水**个性化推荐歌单**（发现-混排）。
     *
     * 匿名可用：`POST /luna/discover/mix {}` → `inner_block[].resources[].entity.playlist`。
     * 实测返回「吉他伴奏」「港味经典粤语」「失恋」「深夜学习轻音乐」等，点进去即可播放。
     */
    suspend fun recommendedPlaylists(): List<PlaylistSummary> {
        val root = runCatching { QishuiApi.discoverMix() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<PlaylistSummary>()
        root.arrOrNull("inner_block").orEmpty().forEach { block ->
            block.arrOrNull("resources").orEmpty().forEach { res ->
                val pl = res.objOrNull("entity")?.objOrNull("playlist") ?: return@forEach
                val id = pl.str("id") ?: return@forEach
                if (out.any { it.id == id }) return@forEach
                out += PlaylistSummary(
                    id = id,
                    platform = MusicPlatform.QS,
                    name = pl.str("title").orEmpty().ifBlank { "推荐歌单" },
                    coverUrl = pl.objOrNull("url_cover")?.let { coverOf(it) }.orEmpty(),
                    trackCount = pl.int("count_tracks") ?: 0,
                    creator = pl.str("desc").orEmpty().take(40),
                )
            }
        }
        return out
    }

    /**
     * 汽水的「榜单」= **场景电台**（图书馆 / 专注 / 深夜 EMO / DJ 模式 … 实测 45 个）。
     *
     * 汽水**没有**榜单接口，它的内容形态就是「一进就播的推荐流」，即场景电台。
     * 此前 `toplists()` 返回空、而 [rankSongs] 却已委托 [radioSongs] —— 前后自相矛盾，
     * 白白浪费了已实现的通道。现补齐：把场景电台作为该平台的榜单列表，
     * 榜单页即可像其它音源一样浏览汽水内容。
     */
    override suspend fun toplists(): List<RankSummary> =
        sceneRadios().map { scene ->
            RankSummary(
                id = scene.id,
                platform = MusicPlatform.QS,
                name = scene.name,
                updateFrequency = "实时推荐",
                description = "汽水场景电台 · 点开即从该场景起播",
            )
        }

    /**
     * 本人（登录用户）的歌单列表 —— **需登录态**。
     *
     * 走 PC 端 `/luna/pc/me/playlist`，实测返回「我喜欢的音乐」「抖音收藏的音乐」等。
     * 未登录 / cookie 失效 → 空列表。
     *
     * 注：歌单标题字段是 `title`（不是 `name`），封面是 `url_cover`。
     */
    suspend fun myPlaylists(cookie: String): List<PlaylistSummary> {
        if (cookie.isBlank()) return emptyList()
        val root = runCatching { QishuiApi.myPlaylistsRaw(cookie) }.getOrNull() ?: return emptyList()
        // ⚠️ 该接口响应**不含 `status_code`**（只有 `status_info`），不能用 isOk() 判定；
        //    以 `playlists` 数组是否存在为准。
        val arr = root.arrOrNull("playlists") ?: return emptyList()
        return arr.mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            PlaylistSummary(
                id = id,
                platform = MusicPlatform.QS,
                name = p.str("title").orEmpty().ifBlank { "未命名歌单" },
                coverUrl = p.objOrNull("url_cover")?.let { coverOf(it) }.orEmpty(),
                trackCount = p.int("resource_cnt") ?: p.int("count_tracks") ?: 0,
                creator = p.objOrNull("owner")?.str("nickname").orEmpty(),
            )
        }
    }

    /** 一个场景电台（汽水「一进 App 就播」的电台类型） */
    data class QsScene(
        val id: String,
        val name: String,
    )

    /**
     * 场景电台目录：`feed/mode` → `(scene_mode_id, 场景名)`。
     * 实测 45 个（治愈 / 轻音乐 / 深夜EMO / DJ模式 / 粤语 / 失恋必听 …）。
     */
    suspend fun sceneRadios(): List<QsScene> {
        val root = runCatching { QishuiApi.feedModes() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QsScene>()
        root.arrOrNull("feed_mode_block").orEmpty().forEach { block ->
            block.arrOrNull("feed_mode").orEmpty().forEach { m ->
                val sm = m.objOrNull("entity")?.objOrNull("feed_scene_mode") ?: return@forEach
                val id = sm.int("scene_mode_id")?.toString() ?: return@forEach
                val text = m.str("text").orEmpty().ifBlank { "场景 $id" }
                if (out.none { it.id == id }) out += QsScene(id, text)
            }
        }
        return out
    }

    /**
     * 拉取某个场景电台的曲目流（对应汽水「自动播放」的推荐流）。
     *
     * @param radioId 即 [QsScene.id]（`scene_mode_id`）
     *
     * ⚠️ 该流**混有免费全曲与 VIP 试听**（服务端按账号权益逐曲判定），
     * 与 `h5/seo_track` 是同一套门控。
     */
    suspend fun radioSongs(radioId: String, count: Int = 30): List<Song> {
        if (radioId.isBlank()) return emptyList()
        val root = runCatching { QishuiApi.radioTracks(radioId, count) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<JsonElement>()
        collectTracks(root, out)
        return out.mapNotNull { trackToSong(it) }.distinctBy { it.id }
    }

    /**
     * 关联推荐曲（无 cursor 上限，可链式延伸）。
     *
     * `POST /luna/media/related {id}` → 6 首关联曲。
     * 拿"上一批最后一首"继续做种子，每步都能拿到新歌 —— 这是电台「一直播下去」的基础。
     */
    suspend fun relatedSongs(id: String, count: Int = 20): List<Song> {
        if (id.isBlank()) return emptyList()
        val root = runCatching { QishuiApi.relatedMedia(id) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<JsonElement>()
        collectTracks(root, out)
        return out.mapNotNull { trackToSong(it) }.distinctBy { it.id }.take(count)
    }

    /**
     * 每日领免费 VIP（尽力而为）。
     * ⚠️ 见 [QishuiApi.applyFreeVip] 的说明：本通道不改变取址门控。
     */
    suspend fun applyFreeVip(cookie: String): JsonElement {
        if (cookie.isBlank()) return JsonObject(emptyMap())
        return runCatching { QishuiApi.applyFreeVip(cookie) }
            .getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * 场景电台曲目。
     *
     * ⚠️ 该接口**单次恒定只回 6 首**且无 cursor（实测），照直返回会让「榜单」页
     * 只显示 6 首、看着像坏了。故这里多轮抽样再按 id 去重，凑到一屏可观的数量；
     * 场景池子有限（「治愈」连续 40 次只去重出 27 首），故设上限避免无谓空转。
     */
    override suspend fun rankSongs(rankId: String): List<Song> {
        if (rankId.isBlank()) return emptyList()
        val merged = LinkedHashMap<String, Song>()
        repeat(RADIO_SAMPLE_ROUNDS) {
            if (merged.size >= RADIO_TARGET) return@repeat
            runCatching { radioSongs(rankId, 30) }.getOrDefault(emptyList()).forEach { s ->
                merged.putIfAbsent(s.id, s)
            }
        }
        return merged.values.toList()
    }

    /** 逐字歌词：`seo_track` 的 `lyric.content` 为**明文 KRC**，直接交给 [KrcParser] */
    override suspend fun lyrics(song: Song): SongLyrics {
        val raw = runCatching { QishuiApi.seoTrack(song.id) }.getOrNull()
            ?.objOrNull("lyric")?.str("content").orEmpty()
        if (raw.isBlank()) return SongLyrics.EMPTY
        return runCatching { KrcParser.parse(raw) }.getOrElse { SongLyrics.EMPTY }
    }

    override suspend fun searchSuggest(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        val sugs = runCatching { QishuiApi.sug(keyword) }.getOrNull()?.arrOrNull("sugs") ?: return emptyList()
        return sugs.mapNotNull { it.str("suggestion")?.takeIf { s -> s.isNotBlank() } }.distinct().take(10)
    }

    override suspend fun hotSearch(): List<String> {
        val words = runCatching { QishuiApi.searchBlock() }.getOrNull()?.arrOrNull("suggest_words")
            ?: return emptyList()
        return words.mapNotNull { it.str("keyword")?.takeIf { k -> k.isNotBlank() } }.distinct().take(20)
    }

    /** 汽水评论接口不可匿名访问 → 返回 null（UI 不展示评论入口） */
    override suspend fun comments(songId: String, page: Int, limit: Int): CommentsPage? = null

    private companion object {
        /** 场景电台抽样轮数上限（单次仅回 6 首，需多轮凑量） */
        const val RADIO_SAMPLE_ROUNDS = 8

        /** 期望凑到的曲目数；达到即提前停止抽样 */
        const val RADIO_TARGET = 40
    }
}

/* ---------------- 解析助手 ---------------- */

/**
 * 递归收敛出所有「曲目节点」。
 *
 * 汽水响应层级多变：搜索是 `entity.track`，歌单是 `entity.track` / `entity.track_wrapper.track`，
 * 电台是 `items[]`。用「字段特征」判定（有 id + name + duration + artists）比逐路径判断更稳。
 */
private fun collectTracks(el: JsonElement?, out: MutableList<JsonElement>) {
    when (el) {
        is JsonObject -> {
            val looksLikeTrack = el.str("id") != null &&
                el.str("name") != null &&
                el.str("duration") != null &&
                !el.arrOrNull("artists").isNullOrEmpty()
            if (looksLikeTrack) out += el else el.values.forEach { collectTracks(it, out) }
        }
        is JsonArray -> el.forEach { collectTracks(it, out) }
        else -> Unit
    }
}

private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

/** 汽水曲目节点 → 统一 [Song] */
private fun trackToSong(track: JsonElement): Song? {
    val id = track.str("id") ?: return null
    val name = track.str("name")?.takeIf { it.isNotBlank() } ?: return null
    val artist = track.arrOrNull("artists")
        ?.mapNotNull { it.str("name")?.takeIf { n -> n.isNotBlank() } }
        .orEmpty().joinToString("/")
    val album = track.objOrNull("album")
    return Song(
        id = id,
        platform = MusicPlatform.QS,
        title = name,
        artist = artist.ifBlank { "未知歌手" },
        album = album?.str("name").orEmpty(),
        durationMs = track.long("duration") ?: 0L,
        coverUrl = album?.let { coverOf(it) }.orEmpty(),
        maxQuality = maxQualityOf(track),
        extra = buildMap {
            track.str("vid")?.let { put("qs_vid", it) }
            album?.str("id")?.let { put("qs_album_id", it) }
        },
    )
}

/** 专辑封面：`urls[0]` + `uri` + 抖音图床模板（实测 `~<prefix>-image.image` 返回 200） */
private fun coverOf(album: JsonElement): String {
    val uc = album.objOrNull("url_cover") ?: album
    val uri = uc.str("uri") ?: return ""
    val base = uc.arrOrNull("urls")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return ""
    val prefix = uc.str("template_prefix").orEmpty()
    val b = base.trimEnd('/')
    // template_prefix 形如 `tplv-xxxx`，故后缀为 `~tplv-xxxx-image.image`
    return if (prefix.isBlank()) "$b/$uri" else "$b/$uri~$prefix-image.image"
}

/** 从 `bit_rates[].quality` 推断该曲最高可用档位（映射到本项目 [PlayQuality] id） */
private fun maxQualityOf(track: JsonElement): String {
    val labels = track.arrOrNull("bit_rates")?.mapNotNull { it.str("quality") }.orEmpty().toSet()
    return when {
        "hi_res" in labels -> PlayQuality.HIRES.id
        "spatial" in labels -> PlayQuality.ATMOS.id
        "lossless" in labels -> PlayQuality.LOSSLESS.id
        "highest" in labels || "higher" in labels -> PlayQuality.HIGH.id
        "medium" in labels -> PlayQuality.STANDARD.id
        else -> ""
    }
}
