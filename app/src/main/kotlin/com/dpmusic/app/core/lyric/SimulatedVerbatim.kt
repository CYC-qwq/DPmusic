package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics

/**
 * 无字级数据时的「匀速逐字」模拟。
 *
 * 当歌曲只拿到行级歌词（无字级时间轴）时，把每行时长按字符数均分，
 * 为每个字符生成模拟的 LyricWord（匀速填充），用于卡拉OK式渲染兜底：
 * - 行时长 = 下一行开始时间 - 本行开始时间；最后一行 / 时间异常时按字数估算（250ms/字，限制 1.5s - 6s）；
 * - 空白字符并入前一个字符（保持英文单词间距），不单独占时间槽；
 * - 已有字级数据的行、空白行保持不变；hasWordTiming 保持原值（模拟结果不计入真实字级数据）。
 */
fun SongLyrics.withSimulatedVerbatim(): SongLyrics {
    if (lines.isEmpty()) return this
    if (lines.none { it.words.isEmpty() && it.text.isNotBlank() }) return this

    val out = lines.mapIndexed { index, line ->
        if (line.words.isNotEmpty() || line.text.isBlank()) return@mapIndexed line

        // 切分：每个非空白字符一个时间槽；空白并入前一个字符（保持英文间距）
        val tokens = mutableListOf<StringBuilder>()
        for (ch in line.text) {
            if (ch.isWhitespace()) {
                tokens.lastOrNull()?.append(ch)
            } else {
                tokens += StringBuilder().append(ch)
            }
        }
        if (tokens.isEmpty()) return@mapIndexed line

        // 行时长：取后续最近一个更晚开始的行；无有效后续行时按字数估算
        val nextStart = (index + 1 until lines.size).firstOrNull { lines[it].timeMs > line.timeMs }?.let { lines[it].timeMs }
        val span = if (nextStart != null) {
            nextStart - line.timeMs
        } else {
            (tokens.size * 250L).coerceIn(1500L, 6000L)
        }

        // 匀速切分：第 i 个字符占 [start + span*i/n, start + span*(i+1)/n)
        val n = tokens.size
        val words = tokens.mapIndexed { i, token ->
            val start = line.timeMs + span * i / n
            val end = line.timeMs + span * (i + 1) / n
            LyricWord(token.toString(), start, end - start)
        }
        line.copy(words = words)
    }
    return copy(lines = out)
}
