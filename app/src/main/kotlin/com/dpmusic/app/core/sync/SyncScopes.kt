package com.dpmusic.app.core.sync

/**
 * 数据同步范围（白名单）：
 * - 只同步这里显式列出的键；账号 Cookie / API Key / WebDAV 配置（含密码）
 *   等敏感键永远不参与同步（新增设置键需评估后手动加入）。
 */
object SyncScopes {

    /** 「设置与音源」：应用设置 + 均衡器 + 自定义音源脚本（元信息与激活项） */
    val SETTINGS_KEYS: Set<String> = setOf(
        "default_platform", "preferred_quality", "max_storage_mb", "download_dir",
        "lyric_scale_portrait", "lyric_scale_landscape",
        "lyric_spacing_portrait", "lyric_spacing_landscape",
        "verbatim_lyric", "simulated_verbatim", "dynamic_color", "dark_mode",
        "clipboard_auto_read", "theme_color", "glass_mode", "source_priority",
        "playback_speed", "sleep_timer_wait_song_end", "lyric_s2t",
        "list_show_album_name", "list_show_duration", "list_show_cover", "list_show_source",
        "download_write_tags", "download_write_cover", "download_embed_lyric",
        "equalizer_settings_json", "user_script_list_json", "user_script_active_id",
    )

    /** 「设置与音源」前缀匹配（自定义音源脚本内容：user_script_content_{id}） */
    val SETTINGS_PREFIXES: List<String> = listOf("user_script_content_")

    /** 「歌单与数据」：收藏 / 歌单 / 历史 / 搜索历史 / 屏蔽规则 / 听歌统计 */
    val LISTS_KEYS: Set<String> = setOf(
        "favorites_json", "user_playlists_json", "recent_json",
        "search_history_json", "dislike_rules", "listening_daily_seconds",
    )

    fun matchesSettings(key: String): Boolean =
        key in SETTINGS_KEYS || SETTINGS_PREFIXES.any { key.startsWith(it) }

    fun matchesLists(key: String): Boolean = key in LISTS_KEYS
}
