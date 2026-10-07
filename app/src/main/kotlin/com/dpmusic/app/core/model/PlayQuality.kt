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
    HIRES("hires", "Hi-Res", "Hi-Res"),
    /** 全景声（Wy/QQ/KG 均有，部分账号/曲目不可用 → 走降档链） */
    ATMOS("atmos", "全景声 Atmos", "Atmos"),
    /** 全景声 Plus（仅 QQ 提供） */
    ATMOS_PLUS("atmos_plus", "全景声 Plus", "Atmos+"),
    /** 母带（Wy/KG/QQ 少数曲目提供，最高规格） */
    MASTER("master", "母带 Master", "Master");

    companion object {
        fun fromId(id: String?): PlayQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}

/**
 * 平台档位从高到低的完整排序（索引越小越高）。
 * 用于降档链计算，以及判断「某档位是否超出歌曲可用上限」。
 *
 * @param vip 账号会员态（仅 B 站有意义）：大会员可解锁无损 / 全景声。
 */
fun qualityOrder(platform: MusicPlatform, vip: Boolean = false): List<String> = when (platform) {
    MusicPlatform.WY -> listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
    MusicPlatform.QQ -> listOf("atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
    MusicPlatform.KG -> listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")
    // 汽水 seo_track 匿名档位仅 medium/higher/highest（≈128/192/256 kbps），无无损
    MusicPlatform.QS -> listOf("hires", "flac", "320k", "128k")
    // B 站：匿名/非会员只到 AAC（30280≈192k）；**大会员**可解锁 flac（无损）/ dolby（全景声）
    // 注意：具体能否取到还取决于视频本身是否提供该档（服务端不下发时自动沿链下降）
    MusicPlatform.BB -> if (vip) listOf("flac", "atmos", "320k", "128k") else listOf("320k", "128k")
}

/**
 * 返回该音质在当前平台下的「降档链」：
 * 从首选档位开始逐级向下（如 flac -> 320k -> 128k），用于解析失败时自动降级。
 *
 * @param vip 账号会员态（仅 B 站有意义）
 */
fun PlayQuality.chainFor(platform: MusicPlatform, vip: Boolean = false): List<String> {
    val full = qualityOrder(platform, vip)
    val start = full.indexOf(id).takeIf { it >= 0 } ?: full.indexOf("320k")
    return full.subList(start, full.size)
}

/**
 * 列表接口返回的「最高可用档位」标识 → [PlayQuality]。
 *
 * 各平台来源（均为**列表接口直接返回**，无需额外请求、无额外延迟，实测 2026-10-01）：
 * - 网易云 `/api/cloudsearch/pc`：`privilege.maxBrLevel` ∈
 *   `standard`(128k) / `higher`(192k) / `exhigh`(320k) / `lossless`(flac) / `hires`
 * - QQ `client_search_cp` / musicu 歌单：`file.size_hires` / `size_flac` / `size_ape` /
 *   `size_320` 是否 > 0
 * - 酷狗 `/api/v3/search/song`、`/api/v3/rank/song`：`filesize` / `320filesize` /
 *   `sqfilesize` / `filesize_high` / `filesize_super` 是否 > 0
 *
 * ⚠️ 覆盖面是「至少能到这一档」的下界，不是绝对天花板：
 * QQ 榜单接口不返回 `file` 对象、网易云部分接口无 `privilege` → 这些来源返回空串不展示徽标；
 * Atmos / 母带等在列表接口没有稳定字段，此类曲目的上限会被低估。
 * 因此徽标只用于「参考与加速决策」，不做可用性硬门槛（超出上限的档位仍可点击）。
 */
fun qualityFromMaxLevel(level: String?): PlayQuality? = when (level?.trim()?.lowercase()) {
    "standard" -> PlayQuality.STANDARD
    "higher" -> PlayQuality.HIGH
    "exhigh" -> PlayQuality.HIGH
    "lossless" -> PlayQuality.LOSSLESS
    "hires" -> PlayQuality.HIRES
    else -> null
}

/**
 * 某档位是否**超出**该歌曲在列表接口中标注的可用上限。
 * 上限未知（null）时一律返回 false —— 宁可放行让用户试，也不误判为不可用。
 */
fun PlayQuality.exceedsCeiling(song: Song?): Boolean {
    val ceiling = song?.ceilingQuality ?: return false
    val order = qualityOrder(song.platform)
    val mine = order.indexOf(id).takeIf { it >= 0 } ?: return false
    val top = order.indexOf(ceiling.id).takeIf { it >= 0 } ?: return false
    return mine < top
}

/**
 * 为单曲选出**解析起点档位**（「默认以标定最高音质播放」的实现）。
 *
 * - [autoHighest] = false → 直接用 [configured]（原有行为，逐曲不变）；
 * - 开启且该曲有列表接口标定的上限 → 取标定档与 [configured] 中**更高**的一个
 *   （标定只是下界，用户手动选了更高档时不应被反降）；
 * - 标定缺失（列表接口未提供元数据）→ 回退 [configured]。
 *
 * 无论选哪档，后续仍走 [chainFor] 降档链，因此不会因标定偏大而解析失败。
 */
fun startQualityFor(song: Song, configured: PlayQuality, autoHighest: Boolean): PlayQuality {
    if (!autoHighest) return configured
    val ceiling = song.ceilingQuality ?: return configured
    val order = qualityOrder(song.platform)
    val configuredIdx = order.indexOf(configured.id).takeIf { it >= 0 } ?: return configured
    val ceilingIdx = order.indexOf(ceiling.id).takeIf { it >= 0 } ?: return configured
    // 索引越小档位越高
    return if (ceilingIdx < configuredIdx) ceiling else configured
}