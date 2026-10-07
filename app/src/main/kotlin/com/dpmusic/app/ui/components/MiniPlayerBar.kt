package com.dpmusic.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.util.rememberDpHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底部迷你播放条（悬浮胶囊卡片）：
 * - 顶部 3dp 实时进度线（随卡片圆角自然裁剪）；
 * - 44dp 圆角封面 + 双行文本 + 播放 / 下一首快捷控制；
 * - **横向滑动切歌**：左滑下一首、右滑上一首（跟手位移 + 阈值触觉 + 弹簧归位），
 *   与「点击 / 上拉展开播放器」的纵向手势分属不同轴，互不干扰；
 * - 点击 / 上拉 → 展开全屏播放器（纵向手势由外部宿主注入）。
 */
@Composable
fun MiniPlayerBar(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    coverModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onPrevious: (() -> Unit)? = null,
) {
    val np = nowPlaying ?: return
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberDpHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 横向跟手位移：拖动时内容侧移，松手后弹簧归位
    val offsetX = remember { Animatable(0f) }
    val maxDragPx = with(density) { 96.dp.toPx() }
    // 触发切歌的位移阈值（约 1/3 卡片高度的手感最自然）
    val triggerPx = with(density) { 40.dp.toPx() }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shadowElevation = 6.dp,
    ) {
        Column {
            if (np.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    // 横向滑动切歌：与纵向展开手势分属不同轴，互不抢焦
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val next = (offsetX.value + delta).coerceIn(-maxDragPx, maxDragPx)
                                offsetX.snapTo(next)
                            }
                        },
                        onDragStopped = { velocity ->
                            val settled = offsetX.value
                            val beyondThreshold = abs(settled) > triggerPx || abs(velocity) > 1200f
                            if (beyondThreshold) {
                                // 越过阈值给一次轻触觉：确认「松手即切歌」
                                haptics.click()
                                // 左滑（负值）= 下一首；右滑（正值）= 上一首
                                if (settled < 0f) onNext() else onPrevious?.invoke()
                            }
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        },
                    )
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .alpha(1f - (abs(offsetX.value) / maxDragPx) * 0.25f)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onExpand,
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = np.song.coverUrl,
                    modifier = coverModifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = np.song.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = np.song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = { haptics.click(); onTogglePlay() }) {
                    Icon(
                        imageVector = if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (np.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { haptics.click(); onNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
        }
    }
}