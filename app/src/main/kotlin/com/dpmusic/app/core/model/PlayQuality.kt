package com.dpmusic.app.core.model

/**
 * 播放音质档位。
 * 实测 LX 代理可用档位：
 * - wy：128k / 320k / flac / flac24bit / hires / atmos / master
 * - tx：128k / 320k / flac / flac24bit / hires / atmos / atmos_plus
 * - kg：128k / 320k / flac / flac24bit / hires / atmos / master
 */
enum class PlayQuality(val id: String, val label: String, val shortLabel: String) {
    STANDARD("128k", "标准 128K", "128K"),
    HIGH("320k", "高品 320K", "320K"),
    LOSSLESS("flac", "无损 FLAC", "FLAC"),
    FLAC24("flac24bit", "无损 24bit", "24bit"),
    HIRES("hires", "Hi-Res", "Hi-Res");

    companion object {
        fun fromId(id: String?): PlayQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}

/**
 * 返回该音质在当前平台下的「降档链」：
 * 从首选档位开始逐级向下（如 flac -> 320k -> 128k），用于解析失败时自动降级。
 */
fun PlayQuality.chainFor(platform: MusicPlatform): List<String> {
    val full = when (platform) {
        MusicPlatform.WY -> listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
        MusicPlatform.QQ -> listOf("atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
        MusicPlatform.KG -> listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
    }
    val start = full.indexOf(id).takeIf { it >= 0 } ?: full.indexOf("320k")
    return full.subList(start, full.size)
}