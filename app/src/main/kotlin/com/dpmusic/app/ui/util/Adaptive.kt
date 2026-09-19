package com.dpmusic.app.ui.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式尺寸推导工具。
 *
 * 设计原则：一切尺寸从「当前可用空间」按设计比例推导——
 * 不依赖设备型号、屏幕方向或固定断点；手机 / 平板 / 折叠屏 / 分屏 / 自由窗口
 * 等任意尺寸下均自然成立：
 * - 宽而矮的窗口 → 封面受「高度」约束；
 * - 窄窗口 → 封面受「宽度」约束；
 * - 空间充足 → 展示完整内容；空间紧张 → 自动进入紧凑展示。
 */

/** 主视觉（封面）默认占可用高度的比例上限 */
const val CoverHeightRatio = 0.45f

/**
 * 面板式主视觉（封面）尺寸：
 * 取「可用宽（减水平边距）」与「可用高 × 比例」的较小值，并夹在最小尺寸之上。
 */
fun panelCoverSize(
    availableWidth: Dp,
    availableHeight: Dp,
    horizontalPadding: Dp,
    heightRatio: Float = CoverHeightRatio,
    minSize: Dp = 64.dp,
): Dp = minOf(
    availableWidth - horizontalPadding * 2,
    availableHeight * heightRatio,
).coerceAtLeast(minSize)

/**
 * 面板「完整内容」的固定排版高度（与设备无关的排版事实）：
 * 标题行(~26) + 副标题(~20) + 间距(12) + 进度条(3) + 间距(8) + 主控键(48) + 间距(8) ≈ 125dp
 */
private val PanelEssentialHeight = 125.dp

/** 扩展内容（时间行 + 文字按钮）的排版高度 ≈ 72dp */
private val PanelExtrasHeight = 72.dp

/**
 * 面板是否展示扩展内容：
 * 由「可用高」与「封面实际尺寸」共同推导（内容驱动，而非设备驱动）。
 */
fun panelShowsExtras(availableHeight: Dp, coverSize: Dp): Boolean =
    availableHeight >= coverSize + PanelEssentialHeight + PanelExtrasHeight

/**
 * 双栏布局的侧栏宽度：
 * 「设计舒适上限」与「可用宽比例」取小——窄窗口按比例收缩，宽窗口保持面板紧凑。
 */
fun sidePaneWidth(
    availableWidth: Dp,
    maxWidth: Dp = 360.dp,
    widthRatio: Float = 0.42f,
    minWidth: Dp = 200.dp,
): Dp = minOf(maxWidth, availableWidth * widthRatio).coerceAtLeast(minWidth)

/** 面板内边距：随可用空间缩放（12 ~ 24dp） */
fun panelPadding(availableHeight: Dp): Dp =
    (availableHeight * 0.05f).coerceIn(12.dp, 24.dp)