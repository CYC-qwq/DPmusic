package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.SourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 内置 LX 音源 Key 默认值。
 *
 * 已按交付要求**清空**：不内置任何 Key，由用户在「设置 → 音源 Key」中自行填写
 * （留空则无法解析在线播放地址）。
 */
private const val DefaultLxApiKey = ""

/** 应用设置快照 */
data class AppSettings(
    val defaultPlatform: MusicPlatform = MusicPlatform.WY,
    val quality: PlayQuality = PlayQuality.HIGH,
    /**
     * LX 音源服务 Key。
     *
     * ⚠️ 当前内置的是**测试用 Key**（仅为联调/体验方便）：
     * 正式发布前请改为空字符串，或让用户自行在「设置 → 音源 Key」中配置。
     */
    val lxApiKey: String = DefaultLxApiKey,
    /** 图片缓存最大占用（MB；0 = 不限制） */
    val maxStorageMb: Int = 1024,
    /** 音乐下载目录（空 = 默认公共音乐目录 DPmusic） */
    val downloadDir: String = "",
    /** 歌词字号缩放：竖屏（0.75 - 1.6） */
    val lyricScalePortrait: Float = 1f,
    /** 歌词字号缩放：横屏（0.75 - 1.6） */
    val lyricScaleLandscape: Float = 1f,
    /** 歌词行距缩放：竖屏（0.5 - 2.0） */
    val lyricSpacingPortrait: Float = 1f,
    /** 歌词行距缩放：横屏（0.5 - 2.0） */
    val lyricSpacingLandscape: Float = 1f,
    /** 逐字歌词（卡拉OK式字级高亮；无逐字数据时自动回退行级） */
    val verbatimLyric: Boolean = true,
    /** 无字级数据时：按行时长匀速模拟逐字（需配合 verbatimLyric 使用） */
    val simulatedVerbatim: Boolean = true,
    val dynamicColor: Boolean = true,
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val darkMode: Int = 0,
    /** 剪切板自动读取（从其他应用切回时识别剪贴板中的歌曲 / 歌单 / 一起听链接并询问） */
    val clipboardAutoRead: Boolean = false,
    /** 内部：上次已处理的剪贴板文本（去重，避免重复弹窗） */
    val lastClipboardHandled: String = "",
    /** 主题色板 id（见 ThemePalettes.kt；default = 品牌回退配色） */
    val themeColor: String = "default",
    /** 玻璃风格模式（半透明磨砂面板 + 全局流光底；Android 12+ 支持真实模糊） */
    val glassMode: Boolean = false,
    /** 音源解析优先级（自定义脚本 vs 远端代理 Key）；默认 Key 优先 */
    val sourcePriority: SourcePriority = SourcePriority.KEY_FIRST,
    /** 播放速度倍率（0.5 - 2.0；变速不变调） */
    val playbackSpeed: Float = 1f,
    /** 定时退出：勾选「播完当前歌曲后停止」偏好（下次打开面板保持） */
    val sleepTimerWaitSongEnd: Boolean = true,
    /** 歌词繁体化（中文歌词转换为繁体显示） */
    val lyricS2T: Boolean = false,
    /** 列表显示：专辑名 */
    val listShowAlbumName: Boolean = true,
    /** 列表显示：歌曲时长 */
    val listShowDuration: Boolean = true,
    /** 列表显示：封面 */
    val listShowCover: Boolean = true,
    /** 列表显示：来源徽标 */
    val listShowSource: Boolean = true,
    /** 下载完成后：写入歌曲信息标签（MP3 / FLAC） */
    val downloadWriteTags: Boolean = false,
    /** 下载完成后：写入封面 */
    val downloadWriteCover: Boolean = false,
    /** 下载完成后：嵌入歌词（MP3） */
    val downloadEmbedLyric: Boolean = false,
    /** WebDAV 数据同步：启用 */
    val webdavEnabled: Boolean = false,
    /** WebDAV 服务器地址（如 https://dav.example.com/） */
    val webdavUrl: String = "",
    /** WebDAV 用户名 */
    val webdavUsername: String = "",
    /** WebDAV 密码（仅本机保存，不参与同步） */
    val webdavPassword: String = "",
    /** 云端同步目录（默认 /DPmusic/） */
    val webdavPath: String = "/DPmusic/",
    /** 自动同步：收藏 / 歌单 / 屏蔽规则变更后节流上传 */
    val webdavAutoSync: Boolean = false,
    /** 上次同步时间（0 = 从未） */
    val webdavLastSyncTime: Long = 0L,
    /** 桌面歌词：启用（悬浮窗总开关） */
    val desktopLyricEnabled: Boolean = false,
    /** 桌面歌词：样式预设 id（见 core/lyric/DesktopLyricStyle.kt） */
    val desktopLyricPreset: String = "aurora",
    /** 桌面歌词：字号（sp） */
    val desktopLyricFontSize: Float = 22f,
    /** 桌面歌词：字间距（em） */
    val desktopLyricLetterSpacing: Float = 0.02f,
    /** 桌面歌词：整体不透明度（0.3 - 1.0） */
    val desktopLyricOpacity: Float = 1f,
    /** 桌面歌词：背景样式（preset / none / solid / glass） */
    val desktopLyricBackground: String = "preset",
    /** 桌面歌词：背景色（ARGB；0 = 跟随预设） */
    val desktopLyricBackgroundColor: Int = 0,
    /** 桌面歌词：背景浓度（0.1 - 1.0） */
    val desktopLyricBackgroundAlpha: Float = 0.45f,
    /** 桌面歌词：圆角（dp） */
    val desktopLyricCorner: Float = 22f,
    /** 桌面歌词：文字色（ARGB；0 = 跟随预设） */
    val desktopLyricTextColor: Int = 0,
    /** 桌面歌词：高亮色（ARGB；0 = 跟随预设 / 主题色） */
    val desktopLyricHighlightColor: Int = 0,
    /** 桌面歌词：描边宽度（dp；0 = 无描边） */
    val desktopLyricStrokeWidth: Float = 0f,
    /** 桌面歌词：描边色（ARGB） */
    val desktopLyricStrokeColor: Int = 0xFF0B0B10.toInt(),
    /** 桌面歌词：文字阴影 */
    val desktopLyricShadow: Boolean = true,
    /** 桌面歌词：逐字卡拉OK */
    val desktopLyricVerbatim: Boolean = true,
    /** 桌面歌词：显示翻译 */
    val desktopLyricShowTranslation: Boolean = true,
    /** 桌面歌词：显示下一行 */
    val desktopLyricShowNextLine: Boolean = true,
    /** 桌面歌词：迷你控制条（上一首 / 播放暂停 / 下一首 / 关闭） */
    val desktopLyricControls: Boolean = true,
    /** 桌面歌词：锁定位置（锁定后不可拖动） */
    val desktopLyricLocked: Boolean = false,
    /** 桌面歌词：触摸穿透（开启后点击 / 拖动穿透到下层应用） */
    val desktopLyricTouchThrough: Boolean = false,
    /** 桌面歌词：悬浮位置 X 偏移（px；-1 = 未设置，默认底部居中） */
    val desktopLyricOffsetX: Float = -1f,
    /** 桌面歌词：悬浮位置 Y 偏移（px；-1 = 未设置） */
    val desktopLyricOffsetY: Float = -1f,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                defaultPlatform = MusicPlatform.fromId(prefs[KEY_PLATFORM]),
                quality = PlayQuality.fromId(prefs[KEY_QUALITY]),
                lxApiKey = prefs[KEY_LX_API_KEY] ?: DefaultLxApiKey,
                maxStorageMb = prefs[KEY_MAX_STORAGE_MB] ?: 1024,
                downloadDir = prefs[KEY_DOWNLOAD_DIR].orEmpty(),
                lyricScalePortrait = prefs[KEY_LYRIC_SCALE_PORTRAIT] ?: 1f,
                lyricScaleLandscape = prefs[KEY_LYRIC_SCALE_LANDSCAPE] ?: 1f,
                lyricSpacingPortrait = prefs[KEY_LYRIC_SPACING_PORTRAIT] ?: 1f,
                lyricSpacingLandscape = prefs[KEY_LYRIC_SPACING_LANDSCAPE] ?: 1f,
                verbatimLyric = prefs[KEY_VERBATIM_LYRIC] ?: true,
                simulatedVerbatim = prefs[KEY_SIMULATED_VERBATIM] ?: true,
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
                darkMode = prefs[KEY_DARK_MODE] ?: 0,
                clipboardAutoRead = prefs[KEY_CLIPBOARD_AUTO_READ] ?: false,
                lastClipboardHandled = prefs[KEY_LAST_CLIPBOARD_HANDLED].orEmpty(),
                themeColor = prefs[KEY_THEME_COLOR] ?: "default",
                glassMode = prefs[KEY_GLASS_MODE] ?: false,
                sourcePriority = SourcePriority.fromId(prefs[KEY_SOURCE_PRIORITY]),
                playbackSpeed = prefs[KEY_PLAYBACK_SPEED] ?: 1f,
                sleepTimerWaitSongEnd = prefs[KEY_SLEEP_TIMER_WAIT_SONG_END] ?: true,
                lyricS2T = prefs[KEY_LYRIC_S2T] ?: false,
                listShowAlbumName = prefs[KEY_LIST_SHOW_ALBUM_NAME] ?: true,
                listShowDuration = prefs[KEY_LIST_SHOW_DURATION] ?: true,
                listShowCover = prefs[KEY_LIST_SHOW_COVER] ?: true,
                listShowSource = prefs[KEY_LIST_SHOW_SOURCE] ?: true,
                downloadWriteTags = prefs[KEY_DOWNLOAD_WRITE_TAGS] ?: false,
                downloadWriteCover = prefs[KEY_DOWNLOAD_WRITE_COVER] ?: false,
                downloadEmbedLyric = prefs[KEY_DOWNLOAD_EMBED_LYRIC] ?: false,
                webdavEnabled = prefs[KEY_WEBDAV_ENABLED] ?: false,
                webdavUrl = prefs[KEY_WEBDAV_URL].orEmpty(),
                webdavUsername = prefs[KEY_WEBDAV_USERNAME].orEmpty(),
                webdavPassword = prefs[KEY_WEBDAV_PASSWORD].orEmpty(),
                webdavPath = prefs[KEY_WEBDAV_PATH] ?: "/DPmusic/",
                webdavAutoSync = prefs[KEY_WEBDAV_AUTO_SYNC] ?: false,
                webdavLastSyncTime = prefs[KEY_WEBDAV_LAST_SYNC_TIME] ?: 0L,
                desktopLyricEnabled = prefs[KEY_DESKTOP_LYRIC_ENABLED] ?: false,
                desktopLyricPreset = prefs[KEY_DESKTOP_LYRIC_PRESET] ?: "aurora",
                desktopLyricFontSize = prefs[KEY_DESKTOP_LYRIC_FONT_SIZE] ?: 22f,
                desktopLyricLetterSpacing = prefs[KEY_DESKTOP_LYRIC_LETTER_SPACING] ?: 0.02f,
                desktopLyricOpacity = prefs[KEY_DESKTOP_LYRIC_OPACITY] ?: 1f,
                desktopLyricBackground = prefs[KEY_DESKTOP_LYRIC_BACKGROUND] ?: "preset",
                desktopLyricBackgroundColor = prefs[KEY_DESKTOP_LYRIC_BACKGROUND_COLOR] ?: 0,
                desktopLyricBackgroundAlpha = prefs[KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA] ?: 0.45f,
                desktopLyricCorner = prefs[KEY_DESKTOP_LYRIC_CORNER] ?: 22f,
                desktopLyricTextColor = prefs[KEY_DESKTOP_LYRIC_TEXT_COLOR] ?: 0,
                desktopLyricHighlightColor = prefs[KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR] ?: 0,
                desktopLyricStrokeWidth = prefs[KEY_DESKTOP_LYRIC_STROKE_WIDTH] ?: 0f,
                desktopLyricStrokeColor = prefs[KEY_DESKTOP_LYRIC_STROKE_COLOR] ?: 0xFF0B0B10.toInt(),
                desktopLyricShadow = prefs[KEY_DESKTOP_LYRIC_SHADOW] ?: true,
                desktopLyricVerbatim = prefs[KEY_DESKTOP_LYRIC_VERBATIM] ?: true,
                desktopLyricShowTranslation = prefs[KEY_DESKTOP_LYRIC_SHOW_TRANSLATION] ?: true,
                desktopLyricShowNextLine = prefs[KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE] ?: true,
                desktopLyricControls = prefs[KEY_DESKTOP_LYRIC_CONTROLS] ?: true,
                desktopLyricLocked = prefs[KEY_DESKTOP_LYRIC_LOCKED] ?: false,
                desktopLyricTouchThrough = prefs[KEY_DESKTOP_LYRIC_TOUCH_THROUGH] ?: false,
                desktopLyricOffsetX = prefs[KEY_DESKTOP_LYRIC_OFFSET_X] ?: -1f,
                desktopLyricOffsetY = prefs[KEY_DESKTOP_LYRIC_OFFSET_Y] ?: -1f,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setDefaultPlatform(platform: MusicPlatform) =
        dataStore.edit { it[KEY_PLATFORM] = platform.id }

    suspend fun setQuality(quality: PlayQuality) =
        dataStore.edit { it[KEY_QUALITY] = quality.id }

    suspend fun setLxApiKey(key: String) =
        dataStore.edit { it[KEY_LX_API_KEY] = key.trim() }

    suspend fun setMaxStorageMb(mb: Int) =
        dataStore.edit { it[KEY_MAX_STORAGE_MB] = mb }

    suspend fun setDownloadDir(path: String) =
        dataStore.edit { it[KEY_DOWNLOAD_DIR] = path.trim() }

    suspend fun setLyricScalePortrait(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SCALE_PORTRAIT] = scale }

    suspend fun setLyricScaleLandscape(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SCALE_LANDSCAPE] = scale }

    suspend fun setLyricSpacingPortrait(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SPACING_PORTRAIT] = scale }

    suspend fun setLyricSpacingLandscape(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SPACING_LANDSCAPE] = scale }

    suspend fun setVerbatimLyric(enabled: Boolean) =
        dataStore.edit { it[KEY_VERBATIM_LYRIC] = enabled }

    suspend fun setSimulatedVerbatim(enabled: Boolean) =
        dataStore.edit { it[KEY_SIMULATED_VERBATIM] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) =
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setDarkMode(mode: Int) =
        dataStore.edit { it[KEY_DARK_MODE] = mode }

    suspend fun setClipboardAutoRead(enabled: Boolean) =
        dataStore.edit { it[KEY_CLIPBOARD_AUTO_READ] = enabled }

    suspend fun setLastClipboardHandled(text: String) =
        dataStore.edit { it[KEY_LAST_CLIPBOARD_HANDLED] = text }

    suspend fun setThemeColor(id: String) =
        dataStore.edit { it[KEY_THEME_COLOR] = id }

    suspend fun setGlassMode(enabled: Boolean) =
        dataStore.edit { it[KEY_GLASS_MODE] = enabled }
    suspend fun setSourcePriority(priority: SourcePriority) =
        dataStore.edit { it[KEY_SOURCE_PRIORITY] = priority.id }

    suspend fun setPlaybackSpeed(speed: Float) =
        dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed }

    suspend fun setSleepTimerWaitSongEnd(enabled: Boolean) =
        dataStore.edit { it[KEY_SLEEP_TIMER_WAIT_SONG_END] = enabled }

    suspend fun setLyricS2T(enabled: Boolean) =
        dataStore.edit { it[KEY_LYRIC_S2T] = enabled }

    suspend fun setListShowAlbumName(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_ALBUM_NAME] = enabled }

    suspend fun setListShowDuration(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_DURATION] = enabled }

    suspend fun setListShowCover(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_COVER] = enabled }

    suspend fun setListShowSource(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_SOURCE] = enabled }

    suspend fun setDownloadWriteTags(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_WRITE_TAGS] = enabled }

    suspend fun setDownloadWriteCover(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_WRITE_COVER] = enabled }

    suspend fun setDownloadEmbedLyric(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_EMBED_LYRIC] = enabled }

    suspend fun setWebdavEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_WEBDAV_ENABLED] = enabled }

    suspend fun setWebdavUrl(url: String) =
        dataStore.edit { it[KEY_WEBDAV_URL] = url.trim() }

    suspend fun setWebdavUsername(name: String) =
        dataStore.edit { it[KEY_WEBDAV_USERNAME] = name.trim() }

    suspend fun setWebdavPassword(password: String) =
        dataStore.edit { it[KEY_WEBDAV_PASSWORD] = password }

    suspend fun setWebdavPath(path: String) =
        dataStore.edit { it[KEY_WEBDAV_PATH] = path.trim() }

    suspend fun setWebdavAutoSync(enabled: Boolean) =
        dataStore.edit { it[KEY_WEBDAV_AUTO_SYNC] = enabled }

    suspend fun setWebdavLastSyncTime(time: Long) =
        dataStore.edit { it[KEY_WEBDAV_LAST_SYNC_TIME] = time }

    suspend fun setDesktopLyricEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_ENABLED] = enabled }

    suspend fun setDesktopLyricPreset(id: String) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_PRESET] = id }

    suspend fun setDesktopLyricFontSize(size: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_FONT_SIZE] = size }

    suspend fun setDesktopLyricLetterSpacing(spacing: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_LETTER_SPACING] = spacing }

    suspend fun setDesktopLyricOpacity(alpha: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OPACITY] = alpha }

    suspend fun setDesktopLyricBackground(mode: String) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND] = mode }

    suspend fun setDesktopLyricBackgroundColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND_COLOR] = color }

    suspend fun setDesktopLyricBackgroundAlpha(alpha: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA] = alpha }

    suspend fun setDesktopLyricCorner(radius: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_CORNER] = radius }

    suspend fun setDesktopLyricTextColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_TEXT_COLOR] = color }

    suspend fun setDesktopLyricHighlightColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR] = color }

    suspend fun setDesktopLyricStrokeWidth(width: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_STROKE_WIDTH] = width }

    suspend fun setDesktopLyricStrokeColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_STROKE_COLOR] = color }

    suspend fun setDesktopLyricShadow(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHADOW] = enabled }

    suspend fun setDesktopLyricVerbatim(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_VERBATIM] = enabled }

    suspend fun setDesktopLyricShowTranslation(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHOW_TRANSLATION] = enabled }

    suspend fun setDesktopLyricShowNextLine(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE] = enabled }

    suspend fun setDesktopLyricControls(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_CONTROLS] = enabled }

    suspend fun setDesktopLyricLocked(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_LOCKED] = enabled }

    suspend fun setDesktopLyricTouchThrough(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_TOUCH_THROUGH] = enabled }

    suspend fun setDesktopLyricOffsetX(offset: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OFFSET_X] = offset }

    suspend fun setDesktopLyricOffsetY(offset: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OFFSET_Y] = offset }

    private companion object {
        val KEY_PLATFORM = stringPreferencesKey("default_platform")
        val KEY_QUALITY = stringPreferencesKey("preferred_quality")
        val KEY_LX_API_KEY = stringPreferencesKey("lx_api_key")
        val KEY_MAX_STORAGE_MB = intPreferencesKey("max_storage_mb")
        val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val KEY_LYRIC_SCALE_PORTRAIT = floatPreferencesKey("lyric_scale_portrait")
        val KEY_LYRIC_SCALE_LANDSCAPE = floatPreferencesKey("lyric_scale_landscape")
        val KEY_LYRIC_SPACING_PORTRAIT = floatPreferencesKey("lyric_spacing_portrait")
        val KEY_LYRIC_SPACING_LANDSCAPE = floatPreferencesKey("lyric_spacing_landscape")
        val KEY_VERBATIM_LYRIC = booleanPreferencesKey("verbatim_lyric")
        val KEY_SIMULATED_VERBATIM = booleanPreferencesKey("simulated_verbatim")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_DARK_MODE = intPreferencesKey("dark_mode")
        val KEY_CLIPBOARD_AUTO_READ = booleanPreferencesKey("clipboard_auto_read")
        val KEY_LAST_CLIPBOARD_HANDLED = stringPreferencesKey("last_clipboard_handled")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_GLASS_MODE = booleanPreferencesKey("glass_mode")
        val KEY_SOURCE_PRIORITY = stringPreferencesKey("source_priority")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val KEY_SLEEP_TIMER_WAIT_SONG_END = booleanPreferencesKey("sleep_timer_wait_song_end")
        val KEY_LYRIC_S2T = booleanPreferencesKey("lyric_s2t")
        val KEY_LIST_SHOW_ALBUM_NAME = booleanPreferencesKey("list_show_album_name")
        val KEY_LIST_SHOW_DURATION = booleanPreferencesKey("list_show_duration")
        val KEY_LIST_SHOW_COVER = booleanPreferencesKey("list_show_cover")
        val KEY_LIST_SHOW_SOURCE = booleanPreferencesKey("list_show_source")
        val KEY_DOWNLOAD_WRITE_TAGS = booleanPreferencesKey("download_write_tags")
        val KEY_DOWNLOAD_WRITE_COVER = booleanPreferencesKey("download_write_cover")
        val KEY_DOWNLOAD_EMBED_LYRIC = booleanPreferencesKey("download_embed_lyric")
        val KEY_WEBDAV_ENABLED = booleanPreferencesKey("webdav_enabled")
        val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
        val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val KEY_WEBDAV_PATH = stringPreferencesKey("webdav_path")
        val KEY_WEBDAV_AUTO_SYNC = booleanPreferencesKey("webdav_auto_sync")
        val KEY_WEBDAV_LAST_SYNC_TIME = longPreferencesKey("webdav_last_sync_time")
        val KEY_DESKTOP_LYRIC_ENABLED = booleanPreferencesKey("desktop_lyric_enabled")
        val KEY_DESKTOP_LYRIC_PRESET = stringPreferencesKey("desktop_lyric_preset")
        val KEY_DESKTOP_LYRIC_FONT_SIZE = floatPreferencesKey("desktop_lyric_font_size")
        val KEY_DESKTOP_LYRIC_LETTER_SPACING = floatPreferencesKey("desktop_lyric_letter_spacing")
        val KEY_DESKTOP_LYRIC_OPACITY = floatPreferencesKey("desktop_lyric_opacity")
        val KEY_DESKTOP_LYRIC_BACKGROUND = stringPreferencesKey("desktop_lyric_background")
        val KEY_DESKTOP_LYRIC_BACKGROUND_COLOR = intPreferencesKey("desktop_lyric_background_color")
        val KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA = floatPreferencesKey("desktop_lyric_background_alpha")
        val KEY_DESKTOP_LYRIC_CORNER = floatPreferencesKey("desktop_lyric_corner")
        val KEY_DESKTOP_LYRIC_TEXT_COLOR = intPreferencesKey("desktop_lyric_text_color")
        val KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR = intPreferencesKey("desktop_lyric_highlight_color")
        val KEY_DESKTOP_LYRIC_STROKE_WIDTH = floatPreferencesKey("desktop_lyric_stroke_width")
        val KEY_DESKTOP_LYRIC_STROKE_COLOR = intPreferencesKey("desktop_lyric_stroke_color")
        val KEY_DESKTOP_LYRIC_SHADOW = booleanPreferencesKey("desktop_lyric_shadow")
        val KEY_DESKTOP_LYRIC_VERBATIM = booleanPreferencesKey("desktop_lyric_verbatim")
        val KEY_DESKTOP_LYRIC_SHOW_TRANSLATION = booleanPreferencesKey("desktop_lyric_show_translation")
        val KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE = booleanPreferencesKey("desktop_lyric_show_next_line")
        val KEY_DESKTOP_LYRIC_CONTROLS = booleanPreferencesKey("desktop_lyric_controls")
        val KEY_DESKTOP_LYRIC_LOCKED = booleanPreferencesKey("desktop_lyric_locked")
        val KEY_DESKTOP_LYRIC_TOUCH_THROUGH = booleanPreferencesKey("desktop_lyric_touch_through")
        val KEY_DESKTOP_LYRIC_OFFSET_X = floatPreferencesKey("desktop_lyric_offset_x")
        val KEY_DESKTOP_LYRIC_OFFSET_Y = floatPreferencesKey("desktop_lyric_offset_y")
    }
}