package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.MusicPlatform

/** 歌曲链接解析结果：平台 + 平台内歌曲 ID */
data class ParsedSongLink(
    val platform: MusicPlatform,
    val songId: String,
)

/**
 * 歌曲官方链接解析器（网易云 / QQ音乐 / 酷狗）：
 * - 支持各平台常见官方分享链接（含分享文案中夹带的链接）；
 * - 无法识别时返回 null。
 */
object SongLinkParser {

    private val WY_PATTERNS = listOf(
        // https://music.163.com/#/song?id=186016 / https://music.163.com/song?id=186016 / https://y.music.163.com/m/song?id=186016
        Regex("""music\.163\.com/\S*?song\S*?[?&]id=(\d+)"""),
        // https://music.163.com/song/186016 / https://music.163.com/#/song/186016
        Regex("""music\.163\.com/\S*?song/(\d+)"""),
    )

    private val QQ_PATTERNS = listOf(
        // https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV
        Regex("""y\.qq\.com/\S*?songDetail/([0-9A-Za-z]+)"""),
        // https://y.qq.com/n/yqq/song/0039MnYb0qxYhV.html
        Regex("""y\.qq\.com/\S*?song/([0-9A-Za-z]+)"""),
        // https://i.y.qq.com/v8/playsong.html?songmid=0039MnYb0qxYhV
        Regex("""y\.qq\.com/\S*?songmid=([0-9A-Za-z]+)"""),
    )

    private val KG_PATTERNS = listOf(
        // https://www.kugou.com/song/#hash=b3a52a7a958bf0aed0ebfba2e9a818b7
        Regex("""kugou\.com/\S*?[?&#]hash=([0-9A-Fa-f]{16,})"""),
    )

    /** 解析链接（或含链接的分享文案）：识别成功返回平台 + 歌曲 ID；无法识别返回 null */
    fun parse(raw: String): ParsedSongLink? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        WY_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            return ParsedSongLink(MusicPlatform.WY, it.groupValues[1])
        }
        QQ_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            return ParsedSongLink(MusicPlatform.QQ, it.groupValues[1])
        }
        KG_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            // 酷狗 hash 统一小写，保证与搜索结果 / 播放解析的 stableKey 一致
            return ParsedSongLink(MusicPlatform.KG, it.groupValues[1].lowercase())
        }
        return null
    }
}
