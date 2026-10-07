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

/**
 * 解析完成的歌词文档。
 *
 * @param sourcePlatform 歌词**实际**来自哪个平台。null = 与原曲同平台（常态）。
 *   跨平台兜底命中时填来源平台 —— UI 据此显示「歌词来自 QQ 音乐」之类提示，
 *   不把别家的歌词冒充原平台的，用户有知情权。
 */
data class SongLyrics(
    val lines: List<LyricLine>,
    val hasTranslation: Boolean = false,
    /** 是否含逐字（字级）时间轴 */
    val hasWordTiming: Boolean = false,
    val sourcePlatform: MusicPlatform? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** 是否为跨平台兜底所得（UI 据此显示来源提示） */
    val isCrossPlatform: Boolean get() = sourcePlatform != null

    companion object {
        val EMPTY = SongLyrics(emptyList())
    }
}
