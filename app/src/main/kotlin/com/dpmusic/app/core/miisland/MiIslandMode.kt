package com.dpmusic.app.core.miisland

/**
 * 小米 HyperOS 超级岛展示形态。
 *
 * 三种形态对应 `miui.focus.param` 载荷的三种参数组合（均由 focus-api 的 V3 模板描述）：
 * - [OFF]：不注入任何 extras，通知栏保持 Media3 默认样式（等同未开启该功能）。
 * - [LYRIC]：注入 V3 模板，渲染为高级歌词超级岛 —— 大岛左歌曲信息 / 右当前歌词，
 *   小岛为封面 + 环形进度。
 * - [LYRIC_GLOW]：在 [LYRIC] 基础上追加 `outer_glow` 外发光效果（`outEffectSrc`
 *   与 `miui.bigIsland.effect.src` 两处同时设置，摘要态与展开态一致发光）。
 *
 * 刻意**不**注入 `miui.focus.param.media`：该键是系统媒体胶囊的自有载荷，由 MediaSession
 * 流程自动写入；App 重复注入会形成两个竞争数据源，正是「岛显示不稳定」的常见成因。
 * 本功能只在系统原生媒体通知**之外**追加高级形态。
 */
enum class MiIslandMode(
    val value: String,
    val label: String,
    val description: String,
    /** 是否注入歌词超级岛载荷 */
    val usesLyricIsland: Boolean,
    /** 是否追加外发光 */
    val usesOuterGlow: Boolean,
) {
    OFF(
        "off",
        "关闭",
        "不注入超级岛载荷，保持系统原生媒体通知",
        usesLyricIsland = false,
        usesOuterGlow = false,
    ),
    LYRIC(
        "lyric",
        "歌词超级岛",
        "显示歌曲信息与当前歌词",
        usesLyricIsland = true,
        usesOuterGlow = false,
    ),
    LYRIC_GLOW(
        "lyric_glow",
        "发光歌词超级岛",
        "歌词超级岛 + 外发光效果",
        usesLyricIsland = true,
        usesOuterGlow = true,
    );

    companion object {
        /** 默认关闭：该功能依赖 Shizuku 等额外条件，由用户主动开启 */
        val default: MiIslandMode = OFF

        fun fromValue(value: String?): MiIslandMode =
            entries.firstOrNull { it.value == value } ?: default
    }
}