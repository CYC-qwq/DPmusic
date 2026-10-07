package com.dpmusic.app.core.net

/**
 * B 站视频标题 → 歌名的启发式清洗。
 *
 * 抽成独立对象是因为：这是「用视频站当音乐源」时**最易出错、又最难从界面察觉**的一环
 * —— 标题洗错了，搜索结果看着正常，但歌名/匹配全错。因此单独抽出来用单测钉住。
 */
internal object BiliText {

    private val EM_TAG = Regex("</?em[^>]*>")
    private val HTML_TAG = Regex("<[^>]+>")

    /** 前缀噪声：【Hi-Res无损】/【4K】/（官方）等方括号或圆括号包裹的修饰 */
    private val NOISE_PREFIX = Regex("^[\\s\\[【（(]+[^】\\])）]{0,24}[】\\])）]+\\s*")

    /** 后缀噪声：(Official MV) /【无损音质】/（官方MV）等 */
    private val NOISE_SUFFIX = Regex(
        "(?i)\\s*[\\(（\\[【][^)\\]】）]{0,12}" +
            "(official|mv|lyric|live|audio|hq|hd|4k|无损|高音质|完整版|纯音乐)" +
            "[^)\\]】）]{0,12}[\\)）\\]】]\\s*$",
    )

    /**
     * 清洗标题：
     * ① 剥离 `<em class="keyword">` 高亮标签与其它 HTML；
     * ② 解码常见实体；
     * ③ 去掉首尾常见修饰（【无损】/ (Official MV) 等）。
     */
    fun cleanTitle(raw: String): String {
        val noTags = HTML_TAG.replace(EM_TAG.replace(raw, ""), "")
        val decoded = decodeEntities(noTags)
        return NOISE_PREFIX.replace(decoded, "")
            .replace(NOISE_SUFFIX, "")
            .trim()
    }

    /** `"4:30"` / `"1:02:03"` → 毫秒；解析失败为 0 */
    fun parseDuration(text: String?): Long {
        val parts = text.orEmpty().trim().split(':').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts.any { it < 0 }) return 0L
        var seconds = 0L
        parts.forEach { seconds = seconds * 60 + it }
        return seconds * 1000L
    }

    /** `//i0.hdslb.com/x.jpg` → `https://i0.hdslb.com/x.jpg`（B 站返回协议相对 URL） */
    fun normalizePic(pic: String?): String {
        val p = pic.orEmpty().trim()
        return when {
            p.isEmpty() -> ""
            p.startsWith("//") -> "https:$p"
            else -> p
        }
    }

    private fun decodeEntities(s: String): String {
        val quote = 0x22.toChar()
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            // 实体名分开拼写：避免源码里出现连续三个引号引起解析歧义
            .replace("&" + "quot;", quote.toString())
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}