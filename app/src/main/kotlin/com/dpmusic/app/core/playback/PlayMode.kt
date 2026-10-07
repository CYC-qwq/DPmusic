package com.dpmusic.app.core.playback

/**
 * 播放模式（四态，用于外部入口的按钮图标与文案）。
 *
 * 为什么用单一枚举而不是分别读 `shuffleModeEnabled` / `repeatMode`：
 * 外部入口（超级岛 / 通知）只能放一个模式按钮，需要「当前是什么」这一个答案；
 * 两个独立布尔的组合（开随机 + 单曲循环）在语义上是矛盾的，这里收敛成互斥四态，
 * 避免调用方各自拼装判断而出现不一致。
 */
enum class PlayMode(
    val label: String,
    /** 供超级岛 / 通知选择的图标形态 */
    val icon: PlayModeIcon,
) {
    /** 顺序播放（不循环、不随机） */
    SEQUENCE("顺序播放", PlayModeIcon.SEQUENCE),

    /** 列表循环 */
    REPEAT_ALL("列表循环", PlayModeIcon.REPEAT_ALL),

    /** 单曲循环 */
    REPEAT_ONE("单曲循环", PlayModeIcon.REPEAT_ONE),

    /** 随机播放 */
    SHUFFLE("随机播放", PlayModeIcon.SHUFFLE),
}

/** 播放模式的图标语义（由 UI 层映射到具体 drawable / ImageVector） */
enum class PlayModeIcon { SEQUENCE, REPEAT_ALL, REPEAT_ONE, SHUFFLE }