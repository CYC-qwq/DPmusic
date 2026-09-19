package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics
import kotlin.math.abs

/**
 * 网易云 YRC 逐字歌词解析器。
 *
 * 格式：
 * - 行：`[行开始ms,行时长ms]`
 * - 字：`(绝对开始ms,时长ms,0)文字`（文字在时间标签之后）
 * - 头部 / 尾部的 JSON 元数据行（`{"t":0,"c":[...]}`）自动跳过
 *
 * 无逐字数据时由调用方回退到行级 LRC。
 */
object YrcParser {

    private val LINE_TAG = Regex("^\\[(\\d+),(\\d+)]")
    private val WORD_TAG = Regex("\\((\\d+),(\\d+),\\d+\\)")

    fun parse(yrc: String, translation: String = ""): SongLyrics {
        if (yrc.isBlank()) return SongLyrics.EMPTY
        val lines = mutableListOf<LyricLine>()
        yrc.split('\n').forEach { raw ->
            val line = raw.trim('\r', '\uFEFF').trim()
            val m = LINE_TAG.find(line) ?: return@forEach
            val body = line.substring(m.range.last + 1)
            val words = parseWords(body)
            val text = words.joinToString("") { it.text }.ifBlank { body.trim() }
            if (text.isEmpty()) return@forEach
            lines += LyricLine(
                timeMs = m.groupValues[1].toLongOrNull() ?: return@forEach,
                text = text,
                words = words,
            )
        }
        if (lines.isEmpty()) return SongLyrics.EMPTY
        val sorted = lines.sortedBy { it.timeMs }

        // 翻译合并：与行级 LRC 相同的 ±400ms 时间戳匹配规则
        val trans = if (translation.isNotBlank()) LrcParser.parseLines(translation) else emptyList()
        val hasTrans = trans.isNotEmpty()
        val merged = if (!hasTrans) {
            sorted
        } else {
            sorted.map { l ->
                val t = trans.firstOrNull { abs(it.timeMs - l.timeMs) <= 400L }?.text
                if (t.isNullOrBlank()) l else l.copy(translation = t)
            }
        }
        return SongLyrics(
            lines = merged,
            hasTranslation = hasTrans,
            hasWordTiming = merged.any { it.words.isNotEmpty() },
        )
    }

    /** 字块：`(开始,时长,0)文字` —— 文字在两个时间标签之间 */
    private fun parseWords(content: String): List<LyricWord> {
        val matches = WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val out = ArrayList<LyricWord>(matches.size)
        matches.forEachIndexed { i, m ->
            val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val text = content.substring(m.range.last + 1, nextStart)
            if (text.isEmpty()) return@forEachIndexed
            val start = m.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            val dur = m.groupValues[2].toLongOrNull() ?: return@forEachIndexed
            out += LyricWord(text, start, dur)
        }
        return out
    }
}