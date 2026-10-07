package com.dpmusic.app.core.miisland

import android.content.Context
import android.content.SharedPreferences

/**
 * 小米超级岛偏好存储。
 *
 * 为什么单独用 SharedPreferences 而不并入 [com.dpmusic.app.core.data.SettingsRepository]（DataStore）：
 * 播放**前台服务**在通知回调里需要**同步**读取当前模式（通知是同步构建的，没机会挂起等 DataStore），
 * SharedPreferences 可同步读且有内存缓存，避免为此把通知构建改成异步。
 */
object MiIslandPrefs {

    private const val PREFS = "mi_island_prefs"

    private const val KEY_MODE = "mi_island_mode"
    private const val KEY_AUTO_EXPAND = "mi_island_auto_expand"
    private const val KEY_TITLE_PERSISTENT = "mi_island_title_persistent"
    private const val KEY_LEN_LIMIT = "mi_island_len_limit"
    private const val KEY_MAX_LEN = "mi_island_max_len"
    private const val KEY_CUSTOM_TITLE = "mi_island_custom_title"
    private const val KEY_LAYOUT = "mi_island_layout"
    private const val KEY_PALETTE = "mi_island_palette"
    private const val KEY_CUSTOM_COLOR = "mi_island_custom_color"

    @Volatile
    private var instance: SharedPreferences? = null

    /** 进程启动时调用一次（[com.dpmusic.app.DPmusicApp]）。 */
    fun init(context: Context) {
        if (instance == null) {
            instance = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun prefs(): SharedPreferences? = instance

    // ---------------- 展示形态 ----------------

    fun getMode(): MiIslandMode = MiIslandMode.fromValue(prefs()?.getString(KEY_MODE, null))

    fun setMode(mode: MiIslandMode) {
        prefs()?.edit()?.putString(KEY_MODE, mode.value)?.apply()
    }

    /** 是否启用歌词超级岛（决定是否参与「魔法重发」流程） */
    fun usesLyricIsland(): Boolean = getMode().usesLyricIsland

    // ---------------- 卡片样式（布局 × 配色） ----------------

    /** 展开态大岛 + 折叠态小胶囊的插槽组合（见 [MiIslandLayout]） */
    fun getLayout(): MiIslandLayout = MiIslandLayout.fromId(prefs()?.getString(KEY_LAYOUT, null))

    fun setLayout(layout: MiIslandLayout) {
        prefs()?.edit()?.putString(KEY_LAYOUT, layout.id)?.apply()
    }

    /** 配色方案（见 [MiIslandPalette]）；[MiIslandPalette.COVER] 时按封面主色动态推导 */
    fun getPalette(): MiIslandPalette = MiIslandPalette.fromId(prefs()?.getString(KEY_PALETTE, null))

    fun setPalette(palette: MiIslandPalette) {
        prefs()?.edit()?.putString(KEY_PALETTE, palette.id)?.apply()
    }

    /**
     * 自定义配色种子色（ARGB）。0 = 未设置，此时回退品牌绿。
     * 仅当 [getPalette] 为 [MiIslandPalette.CUSTOM] 时参与推导。
     */
    fun getCustomColor(): Int = prefs()?.getInt(KEY_CUSTOM_COLOR, 0) ?: 0

    fun setCustomColor(color: Int) {
        prefs()?.edit()?.putInt(KEY_CUSTOM_COLOR, color)?.apply()
    }

    // ---------------- 展开行为 ----------------

    /**
     * 岛内容变化（切歌 / 进入新的歌词段）时，是否让大岛**自动展开浮出**一次。
     *
     * 对应 `miui.focus.*` 载荷的 `islandFirstFloat` 字段：不设置时系统默认为 true，
     * 即每次歌曲切换都会自动弹出大岛（占用状态栏较大区域，部分用户不喜欢）。
     * 本项默认 false —— 岛保持小胶囊态，用户需要时**手动点击**岛再展开。
     */
    fun isAutoExpandOnStart(): Boolean =
        prefs()?.getBoolean(KEY_AUTO_EXPAND, false) ?: false

    fun setAutoExpandOnStart(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_AUTO_EXPAND, enabled)?.apply()
    }

    // ---------------- 小岛文案 ----------------

    /**
     * 小岛标题是否**固定为歌曲名**（开启后不随歌词滚动变化）。
     * 关闭时小岛标题显示当前歌词行，随播放滚动。
     */
    fun isSongTitlePersistent(): Boolean =
        prefs()?.getBoolean(KEY_TITLE_PERSISTENT, false) ?: false

    fun setSongTitlePersistent(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_TITLE_PERSISTENT, enabled)?.apply()
    }

    /** 自定义小岛标题（空 = 用歌曲名）。仅当「标题固定为歌曲名」开启时生效。 */
    fun getCustomTitle(): String = prefs()?.getString(KEY_CUSTOM_TITLE, "").orEmpty()

    fun setCustomTitle(title: String) {
        prefs()?.edit()?.putString(KEY_CUSTOM_TITLE, title.trim().take(24))?.apply()
    }

    // ---------------- 歌词长度上限 ----------------

    /** 是否裁切过长的歌词行（大岛右侧空间有限，过长会换行挤压布局） */
    fun isLyricLengthLimitEnabled(): Boolean =
        prefs()?.getBoolean(KEY_LEN_LIMIT, false) ?: false

    fun setLyricLengthLimitEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_LEN_LIMIT, enabled)?.apply()
    }

    /** 歌词行最大字符数（1 - 50） */
    fun getMaxLyricLength(): Int = (prefs()?.getInt(KEY_MAX_LEN, 12) ?: 12).coerceIn(1, 50)

    fun setMaxLyricLength(length: Int) {
        prefs()?.edit()?.putInt(KEY_MAX_LEN, length.coerceIn(1, 50))?.apply()
    }
}