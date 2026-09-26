package com.dpmusic.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 骨架屏（Shimmer Skeleton）
 *
 * 为什么不用转圈：转圈只表达「在忙」，骨架屏同时表达「即将出现什么」——
 * 用户能预判布局结构，感知等待时间显著更短，观感也更高级。
 *
 * 实现要点：
 * - 扫光用**单一** [rememberInfiniteTransition] 驱动（每个骨架块各建动画会在长列表里爆开销）；
 * - 用 `drawBehind` + `size.width` 计算真实像素宽度，光带才会正确扫过（用比例值当 Offset 是无效的）；
 * - 颜色取自 `onSurfaceVariant` 的低透明度，自动适配深浅色 / 动态取色 / 毛玻璃。
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    /** 扫光周期（毫秒）。多个骨架块共享同一节奏时视觉更整齐 */
    periodMillis: Int = 1400,
) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "shimmerSweep",
    )

    Spacer(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                val w = size.width
                val band = w * 0.55f
                // 光带从左侧屏外扫到右侧屏外
                val startX = -band + (w + band * 2f) * progress
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(startX, 0f),
                        end = Offset(startX + band, 0f),
                    ),
                )
            },
    )
}

/**
 * 歌曲行骨架：排版与 [SongRow] 严格对齐
 * （52dp 封面 + 标题 + 副标题 + 尾部时长），加载完成时不会发生"跳动"。
 */
@Composable
fun SongRowSkeleton(
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    showDuration: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            ShimmerBox(Modifier.size(52.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            ShimmerBox(Modifier.fillMaxWidth(0.55f).height(15.dp))
            Spacer(Modifier.height(8.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.34f).height(11.dp))
        }
        if (showDuration) {
            Spacer(Modifier.width(12.dp))
            ShimmerBox(Modifier.size(width = 34.dp, height = 11.dp))
        }
    }
}

/** 歌曲列表骨架：整屏加载态 */
@Composable
fun SongListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 8,
    showCover: Boolean = true,
) {
    Column(modifier) {
        repeat(count) {
            SongRowSkeleton(showCover = showCover)
        }
    }
}

/**
 * 卡片骨架：用于网格 / 横向卡片流（榜单卡片、歌单卡片）。
 * [coverAspect] 为封面宽高比，默认 1:1 方形。
 */
@Composable
fun CardSkeleton(
    modifier: Modifier = Modifier,
    coverAspect: Float = 1f,
    showSubtitle: Boolean = true,
) {
    Column(modifier) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (coverAspect == 1f) Modifier.height(140.dp)
                    else Modifier.height((140 / coverAspect).dp),
                ),
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.height(10.dp))
        ShimmerBox(Modifier.fillMaxWidth(0.8f).height(14.dp))
        if (showSubtitle) {
            Spacer(Modifier.height(6.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.5f).height(11.dp))
        }
    }
}

/** 网格骨架：按 [columns] 列排布 [CardSkeleton] */
@Composable
fun CardGridSkeleton(
    columns: Int,
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) {
                    CardSkeleton(Modifier.weight(1f))
                }
            }
        }
    }
}