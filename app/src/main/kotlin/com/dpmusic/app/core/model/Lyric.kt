package com.dpmusic.app.core.model

/** 单个字 / 词（逐字歌词的最小单元，时间均为毫秒） */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/** 单行歌词（含可选翻译与逐字时间轴） */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    /** 逐字时间轴（空 = 该行无逐字数据，按整行渲染） */
    val words: List<LyricWord> = emptyList(),
)

/** 解析完成的歌词文档 */
data class SongLyrics(
    val lines: List<LyricLine>,
    val hasTranslation: Boolean = false,
    /** 是否含逐字（字级）时间轴 */
    val hasWordTiming: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val EMPTY = SongLyrics(emptyList())
    }
}
