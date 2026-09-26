package com.dpmusic.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部栏（Mini 条 + 底部导航栏）的总高度。
 *
 * 页面列表把它并入 `contentPadding.bottom`，使内容可以滚到玻璃栏**下面**再被玻璃采样
 * —— 这正是参考实现（AndroidLiquidGlass）玻璃底栏的观感来源：
 * 玻璃后面有真实内容经过，而不是只有装饰性背景。
 *
 * 注意：只能加到列表的 `contentPadding` 上，不要用 `Modifier.padding`（那会裁掉内容，玻璃下依旧空白）。
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }