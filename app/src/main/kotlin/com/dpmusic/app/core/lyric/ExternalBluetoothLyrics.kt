package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.SongLyrics

/**
 * 车载 / 蓝牙外发歌词的文本派生（纯函数：无状态、无 IO、无协程）。
 *
 * **为什么必须有这一层**：蓝牙（AVRCP）与绝大多数车机、锁屏只读系统媒体元数据里的
 * 标准字段（title / artist / album），**不读** `MediaMetadata.extras`。
 * 所以「歌词上蓝牙」唯一可行的通道是把**当前歌词行写进 `title`**。
 * 参考实现两家做法一致：
 * - NeriPlayer `core/player/metadata/ExternalBluetoothLyrics.kt` →
 *   `title = 当前歌词行`，`artist` 降级为「歌名 - 歌手」；
 * - Melodia `core/player/external/ExternalLyricCoordinator.kt` →
 *   `updateBluetoothTitleForIndex()` 把 title 换成当前行（带翻译时拼「原文 (翻译)」）。
 *
 * 与之相对，`lyricInfo` / MIUI `LYRIC` 那类**整首 LRC** 的 extras 只对明确读取
 * extras 的组件（ColorOS 锁屏岛、HyperOS 歌词组件）有意义 —— 见 [CarLyricInfo]，
 * 且由用户显式开启（它到不了蓝牙）。
 *
 * 本对象被播放 ticker 以亚秒级频率调用，因此必须廉价：只有一次二分与少量字符串处理。
 */
object ExternalBluetoothLyrics {

    /**
     * 末行结束后的保留时长（ms）。
     *
     * 末行之后没有「下一行起点」可用于界定行尾；若立刻丢弃，歌曲尾奏时标题会闪回歌名。
     */
    private const val LAST_LINE_HOLD_MS = 4_000L

    /** 末行保留期之上再给的宽限，容忍播放位置的采样抖动 */
    private const val STALE_GRACE_MS = 1_500L

    /**
     * 单个元数据文本字段的 UTF-8 字节预算。
     *
     * AVRCP 元数据属性长度上限很小，超长会被系统截断，极端情况下整条元数据被丢弃。
     * 240 字节约合 80 个汉字，足以容纳任何一句歌词。
     */
    const val MAX_UTF8_BYTES = 240

    /**
     * 取 [positionMs] 对应的歌词行；尚未到首行、或已过末行保留期时返回 null。
     *
     * 假定 `lyrics.lines` 已按 [LyricLine.timeMs] 升序 —— 该前提与
     * [com.dpmusic.app.core.miisland.MiIslandController] 的二分查找完全一致。
     *
     * 行尾定义为**下一行起点**：行间空隙内仍归属当前行，不会出现「标题在间隙闪回歌名」。
     */
    fun currentLine(lyrics: SongLyrics, positionMs: Long): LyricLine? {
        val lines = lyrics.lines
        if (lines.isEmpty()) return null
        val t = positionMs.coerceAtLeast(0L)
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= t) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null
        val line = lines[found]
        val holdUntil = lines.getOrNull(found + 1)?.timeMs
            ?: (line.timeMs + LAST_LINE_HOLD_MS)
        return line.takeIf { t <= holdUntil + STALE_GRACE_MS }
    }

    /**
     * 派生写进 `METADATA_KEY_TITLE` 的文本；无有效歌词时返回 null（调用方回退为歌名）。
     *
     * 有翻译时拼成「原文 (翻译)」，与 Melodia 的蓝牙标题行为一致。
     * 两者相同、或拼接后超出字节预算时退化为只发原文 —— 宁可丢掉翻译，
     * 也不能让截断吃掉歌词本身。
     */
    fun titleText(line: LyricLine?): String? {
        val text = line?.text?.sanitizeMetadataText()?.takeIf { it.isNotEmpty() } ?: return null
        val translation = line.translation?.sanitizeMetadataText()?.takeIf { it.isNotEmpty() }
        if (translation == null || translation == text) return text
        val joined = "$text ($translation)"
        return if (joined.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES) joined else text
    }

    /**
     * 归一化 + 截断，使其可安全写入 AVRCP 元数据：
     * - 换行 / 制表符 / 控制字符会让部分车机把整行显示成空白，统一压成单个空格；
     * - 按 UTF-8 **字节**截断（而非字符数），避免超出 AVRCP 预算；
     * - 截断时保证不切断码点（emoji / 生僻字不会变成乱码）。
     */
    private fun String.sanitizeMetadataText(): String {
        val normalized = buildString(length) {
            var pendingSpace = false
            this@sanitizeMetadataText.forEach { ch ->
                if (ch.isWhitespace() || ch.isISOControl()) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) {
                        append(' ')
                        pendingSpace = false
                    }
                    append(ch)
                }
            }
        }.trim()
        if (normalized.isEmpty()) return ""
        return normalized.truncateUtf8(MAX_UTF8_BYTES)
    }

    /** 按 UTF-8 字节预算截断，保留末尾省略号且不切断码点。 */
    private fun String.truncateUtf8(limitBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= limitBytes) return this
        val suffix = "…"
        val budget = limitBytes - suffix.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder(budget)
        var used = 0
        var offset = 0
        while (offset < length) {
            val codePoint = Character.codePointAt(this, offset)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            sb.append(text)
            used += bytes
            offset += Character.charCount(codePoint)
        }
        return sb.toString().trimEnd() + suffix
    }
}
