package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.MusicPlatform

/** 歌单链接解析结果：平台 + 歌单 ID */
data class ParsedPlaylistLink(
    val platform: MusicPlatform,
    val playlistId: String,
)

/**
 * 歌单分享链接解析器（网易云 / QQ音乐 / 酷狗）：
 * - 支持各平台常见分享链接格式（含分享文案中夹带的链接）；
 * - 纯数字 ID 时返回三平台候选，由调用方依次尝试解析（自动匹配）；
 * - 无法识别时返回空列表。
 */
object PlaylistLinkParser {

    private val WY_PATTERNS = listOf(
        Regex("""music\.163\.com/\S*?[?&]id=(\d+)"""),
        Regex("""music\.163\.com/\S*?playlist/(\d+)"""),
    )

    private val QQ_PATTERNS = listOf(
        Regex("""y\.qq\.com/\S*?playlist/([0-9A-Za-z]+)"""),
        Regex("""y\.qq\.com/\S*?taoge\.html\S*?[?&]id=([0-9A-Za-z]+)"""),
        Regex("""y\.qq\.com/\S*?playlist\S*?[?&]id=([0-9A-Za-z]+)"""),
    )

    private val KG_PATTERNS = listOf(
        Regex("""kugou\.com/\S*?special/single/(\d+)"""),
        Regex("""kugou\.com/\S*?plist/list/(\d+)"""),
        Regex("""kugou\.com/\S*?[?&]specialid=(\d+)"""),
    )

    private val PURE_ID = Regex("""^\d{3,}$""")

    /** 解析候选列表：链接 → 单一明确平台；纯数字 → 三平台候选；否则空 */
    fun parseCandidates(raw: String): List<ParsedPlaylistLink> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        WY_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            return listOf(ParsedPlaylistLink(MusicPlatform.WY, it.groupValues[1]))
        }
        QQ_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            return listOf(ParsedPlaylistLink(MusicPlatform.QQ, it.groupValues[1]))
        }
        KG_PATTERNS.firstNotNullOfOrNull { it.find(text) }?.let {
            return listOf(ParsedPlaylistLink(MusicPlatform.KG, it.groupValues[1]))
        }

        // 纯数字：无法从格式识别平台 → 依次尝试三平台（自动匹配）
        PURE_ID.find(text)?.let { match ->
            return MusicPlatform.entries.map { platform -> ParsedPlaylistLink(platform, match.value) }
        }
        return emptyList()
    }
}
