package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 三大音源平台统一枚举。
 * [lxSource] 为 LX 音源代理协议中的平台代号（wy / tx / kg）。
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
    KG("kg", "酷狗音乐", "酷狗", "kg", 0xFF2CA2F9);

    companion object {
        fun fromId(id: String?): MusicPlatform = entries.firstOrNull { it.id == id } ?: WY
    }
}