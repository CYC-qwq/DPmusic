package com.dpmusic.app.core.audio

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.int
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objList
import com.dpmusic.app.core.net.objOrNull
import com.dpmusic.app.core.net.parseJsonPayload
import com.dpmusic.app.core.net.str
import com.dpmusic.app.core.net.toHttps
import com.dpmusic.app.core.net.urlEnc

/**
 * 网易云「听歌识曲」匹配接口封装。
 *
 * 流程：指纹引擎（afp.wasm）产出 Base64 指纹 → 本类提交匹配接口 → 返回候选歌曲列表。
 * 接口无加密：固定 sessionId + algorithmCode=shazam_v2，直接表单提交即可。
 */
object AudioRecognizer {

    private const val MATCH_URL = "https://interface.music.163.com/api/music/audio/match"

    /**
     * 单条识别候选：歌曲 + 音频中的匹配起始秒（供 UI 标注）。
     * @param originCoverType 网易云版本标记：1=原唱、2=翻唱、3=Cover 标注、0/其他=未知
     */
    data class Candidate(
        val song: Song,
        val startTimeSec: Double,
        val originCoverType: Int? = null,
    ) {
        /** 版本标签（结果行展示）：原唱 / 翻唱 / Cover / 现场版；无标记返回 null */
        val versionLabel: String?
            get() {
                val isCover = originCoverType == 2 || originCoverType == 3
                val isLive = song.title.contains("live", ignoreCase = true) ||
                    song.album.contains("现场")
                return when {
                    isCover -> "翻唱"
                    isLive -> "现场版"
                    originCoverType == 1 -> "原唱"
                    else -> null
                }
            }
    }

    /**
     * 提交指纹（Base64），返回候选列表（接口原序，通常 1-3 条）。
     * 无匹配时返回空列表。
     */
    suspend fun recognize(fingerprint: String): List<Candidate> {
        val raw = Http.postForm(
            url = MATCH_URL,
            form = mapOf(
                "sessionId" to "441df692-afea-4a54-8aff-f5f20fd34f12",
                "algorithmCode" to "shazam_v2",
                "duration" to "6",
                "rawdata" to fingerprint,
                "times" to "2",
                "decrypt" to "1",
            ),
            referer = "https://music.163.com/",
        )
        val results = parseJsonPayload(raw)
            .objOrNull("data")
            ?.arrOrNull("result")
            ?.objList()
            .orEmpty()
        return results.mapNotNull { item ->
            val songObj = item.objOrNull("song") ?: return@mapNotNull null
            val id = songObj.long("id")?.toString() ?: return@mapNotNull null
            val title = songObj.str("name") ?: return@mapNotNull null
            val artists = songObj.arrOrNull("artists")?.objList()
                ?.mapNotNull { it.str("name") }
                .orEmpty()
            val albumObj = songObj.objOrNull("album")
            Candidate(
                song = Song(
                    id = id,
                    platform = MusicPlatform.WY,
                    title = title,
                    artist = artists.joinToString("/").ifBlank { "未知歌手" },
                    album = albumObj?.str("name").orEmpty(),
                    durationMs = songObj.long("duration") ?: 0L,
                    coverUrl = albumObj?.str("picUrl").orEmpty(),
                ),
                startTimeSec = item.str("startTime")?.toDoubleOrNull() ?: 0.0,
                originCoverType = songObj.int("originCoverType"),
            )
        }
    }

    /**
     * 按关键词搜索更多版本（「找原唱」用）。
     * 搜索接口返回 originCoverType：1=原唱、2=翻唱、3=Cover 标注、0/其他=未知。
     */
    suspend fun searchVersions(keyword: String, limit: Int = 30): List<VersionSong> {
        val raw = Http.get(
            "https://music.163.com/api/cloudsearch/pc?s=${urlEnc(keyword)}&type=1&offset=0&limit=$limit",
            referer = "https://music.163.com/",
        )
        val songs = parseJsonPayload(raw)
            .objOrNull("result")
            ?.arrOrNull("songs")
            ?.objList()
            .orEmpty()
        return songs.mapNotNull { item ->
            val id = item.long("id")?.toString() ?: return@mapNotNull null
            val title = item.str("name") ?: return@mapNotNull null
            val artists = item.arrOrNull("ar")?.objList()
                ?.mapNotNull { it.str("name") }
                .orEmpty()
            val albumObj = item.objOrNull("al")
            VersionSong(
                song = Song(
                    id = id,
                    platform = MusicPlatform.WY,
                    title = title,
                    artist = artists.joinToString("/").ifBlank { "未知歌手" },
                    album = albumObj?.str("name").orEmpty(),
                    durationMs = item.long("dt") ?: 0L,
                    coverUrl = albumObj?.str("picUrl").orEmpty().toHttps(),
                ),
                originCoverType = item.int("originCoverType"),
            )
        }
    }

    /** 搜索结果条目：歌曲 + 原唱/翻唱标记 */
    data class VersionSong(val song: Song, val originCoverType: Int?) {
        /** 版本标签：原唱 / 翻唱 / Cover；未知返回 null */
        val label: String?
            get() = when (originCoverType) {
                1 -> "原唱"
                2 -> "翻唱"
                3 -> "Cover"
                else -> null
            }
    }
}
