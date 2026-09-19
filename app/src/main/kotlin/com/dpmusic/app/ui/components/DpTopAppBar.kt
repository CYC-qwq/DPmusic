package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 矮窗口（横屏手机等）下的紧凑顶栏高度（≥48dp 触控标准） */
private val CompactTopBarHeight = 52.dp

/**
 * 响应式顶栏（全页面统一）：
 *
 * - 高度与标题字号由「窗口高度尺寸类」推导——矮窗口（横屏手机 / 分屏窄高）自动紧凑
 *   （52dp + titleMedium），高窗口保持标准规格（64dp + titleLarge），
 *   不含设备 / 方向 / 魔法断点判断；
 * - 透明容器：与页面背景完全融合，消除顶部与内容区之间的色带割裂（"黑边"感）；
 * - 标题统一单行省略，超长文案（榜单名 / 歌单名）不换行不挤压。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpTopAppBar(
    title: String,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val compact = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    TopAppBar(
        title = {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = if (compact) CompactTopBarHeight
        else TopAppBarDefaults.TopAppBarExpandedHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
}
