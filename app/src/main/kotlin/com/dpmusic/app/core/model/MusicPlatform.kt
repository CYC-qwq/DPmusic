package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 音源平台统一枚举。
 * [lxSource] 为 LX 音源代理协议中的平台代号（wy / tx / kg / bb），汽水不在协议内故留空。
 */
@Serializable
enum class MusicPlatform(
    val id: String,
    val label: String,
    val shortLabel: String,
    val lxSource: String,
    val brandColor: Long,
) {
    WY("wy", "网易云音乐", "网易云", "wy", 0xFFC20C0C),
    QQ("qq", "QQ音乐", "QQ音乐", "tx", 0xFF31C27C),
    KG("kg", "酷狗音乐", "酷狗", "kg", 0xFF2CA2F9),
    /**
     * 汽水音乐（抖音）。直连 **匿名** API，播放地址走 `h5/seo_track`（无需签名）。
     * [lxSource] 留空——汽水不在 LX 代理解析协议内，其播放链路见 `QishuiResolver`。
     */
    QS("qs", "汽水音乐", "汽水", "", 0xFFFE2C55),

    /**
     * 哔哩哔哩。直连 **匿名** API（WBI 签名 + buvid 指纹），播放地址走 DASH 音频流。
     * [lxSource] 为 `bb` —— B 站在 LX 代理解析协议内（脚本 / Key 均可解析），
     * 但本应用另有自己的直连通道，见 `BiliResolver`。
     */
    BB("bb", "哔哩哔哩", "B站", "bb", 0xFF00AEEC);

    /**
     * 是否提供「榜单」内容（用于榜单页的平台选择过滤）。
     *
     * 五个音源**都有**各自的榜单内容，但形态不同：
     * - 网易云 / QQ / 酷狗：常规音乐榜单；
     * - 汽水：**场景电台**（图书馆 / 专注 / 深夜 EMO … 实测 45 个）；
     * - B 站：**音乐区排行**（近期音乐区综合榜，实测 96 条）。
     *
     * 后两者的「榜」不是榜单接口的产物，而是各自平台的内容形态 —— 由对应
     * `PlatformApi.toplists()` 负责换算成统一的 [RankSummary]。
     */
    val hasToplist: Boolean get() = true

    companion object {
        fun fromId(id: String?): MusicPlatform = entries.firstOrNull { it.id == id } ?: WY
    }
}