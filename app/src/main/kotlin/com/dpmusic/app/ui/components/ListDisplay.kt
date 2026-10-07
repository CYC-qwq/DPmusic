package com.dpmusic.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 列表显示开关快照（歌曲行等列表元素按此渲染；由 DPmusicShell 注入）。
 */
data class ListDisplayOptions(
    /** 在歌手后追加专辑名 */
    val showAlbumName: Boolean = true,
    /** 显示歌曲时长 */
    val showDuration: Boolean = true,
    /** 显示封面缩略图 */
    val showCover: Boolean = true,
    /** 显示平台来源徽标 */
    val showSource: Boolean = true,
    /** 显示最高可用音质徽标（数据来自列表接口元数据，不发额外请求） */
    val showQuality: Boolean = true,
)

/** 列表显示开关（默认全开；设置页可关） */
val LocalListDisplayOptions = staticCompositionLocalOf { ListDisplayOptions() }
