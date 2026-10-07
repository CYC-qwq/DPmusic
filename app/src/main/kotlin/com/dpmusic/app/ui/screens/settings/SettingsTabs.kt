package com.dpmusic.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 设置页分区。
 *
 * 原先 20+ 张卡片平铺在同一个滚动列表里，查找成本高；现按用途切成 5 个分区，
 * 每页只承载 2~5 张卡片，一屏基本可见，无需再额外折叠。
 */
enum class SettingsTab(val label: String) {
    Common("常用"),
    Sources("音源"),
    Playback("播放"),
    Storage("存储"),
    More("关于"),
}

/**
 * 分区选择条。
 *
 * 标签刻意取短（2 字）并等宽均分，保证 5 个分区在手机上一屏全显，不靠横向滚动
 * 藏起后半段——否则「存储」「关于」这类低频入口会被用户直接忽略。
 *
 * 选中态用一枚滑块胶囊承载：切换时胶囊以弹簧动画滑到新位置，配合文字的
 * 颜色 / 字重过渡，让"换了分区"这件事有明确的动势；点击时另有按压缩放反馈。
 */
@Composable
fun SettingsTabRow(
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = SettingsTab.entries
    // 固定行高：滑块要精确覆盖单个标签，不能依赖子项测量（Box 内 fillMaxHeight 会撑满父级）
    val chipHeight = 40.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(4.dp)) {
            // 等宽均分：滑块位置只需靠索引算
            val tabWidth = maxWidth / tabs.size
            val targetOffset = tabWidth * tabs.indexOf(selected)
            val offset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "tabIndicatorOffset",
            )

            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .width(tabWidth)
                    .height(chipHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chipHeight),
            ) {
                tabs.forEach { entry ->
                    SettingsTabChip(
                        label = entry.label,
                        selected = entry == selected,
                        onClick = { onSelect(entry) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(240),
        label = "tabContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabScale",
    )

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = content,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * 单个分区的卡片内容。
 *
 * 直接平铺，卡片进出时用 [animateItem] 做位移与淡入淡出，避免切页后内容"硬跳"。
 * 竖屏单列、宽屏自适应多列，两种形态共用同一份卡片列表。
 */
@Composable
fun SettingsTabContent(
    compact: Boolean,
    cards: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    val spacing = 16.dp
    val bottom = if (compact) 16.dp + LocalBottomBarInset.current else 16.dp
    // 顶部留白略小：紧接分区选择条，视觉上算作同一组
    val contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottom)

    if (compact) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(count = cards.size, key = { "card-$it" }) { index ->
                Box(Modifier.animateItem()) { cards[index]() }
            }
        }
    } else {
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            // 自适应列数：宽屏自动增加列（窄屏 2 列，平板 / 桌面 3~4 列）
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(count = cards.size, key = { "card-$it" }) { index ->
                Box(Modifier.animateItem()) { cards[index]() }
            }
        }
    }
}