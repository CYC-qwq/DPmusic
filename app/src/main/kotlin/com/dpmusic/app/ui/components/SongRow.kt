package com.dpmusic.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.util.formatDuration
import com.dpmusic.app.ui.theme.LocalGlass
import com.dpmusic.app.ui.util.rememberDpHaptics

/**
 * 通用歌曲行：
 * - 支持序号 / 封面 / 平台徽标 / 播放中态 / 自定义尾部；
 * - 按压 0.97f 回弹 + M3 涟漪。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    index: Int? = null,
    isPlaying: Boolean = false,
    subtitleOverride: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val display = LocalListDisplayOptions.current
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberDpHaptics()

    // 长按处理器：仅在调用方确实提供了长按行为时才绑定，避免"空长按也震动"
    val longClickHandler: (() -> Unit)? = onLongClick?.let { callback ->
        { haptics.longPress(); callback() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selectionMode && selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .pressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    // 触觉与视觉反馈同步：点击即震（系统关闭触觉时自动静默）
                    haptics.click()
                    onClick()
                },
                onLongClick = longClickHandler,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SelectionCheck(selected = selected)
            }
        } else if (index != null) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showCover && display.showCover) {
            CoverArt(
                url = song.coverUrl,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isPlaying) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (display.showSource) {
                    Spacer(Modifier.width(6.dp))
                    PlatformBadge(platform = song.platform)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitleOverride ?: listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() && display.showAlbumName },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else if (display.showDuration) {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 多选勾选框 */
@Composable
private fun SelectionCheck(selected: Boolean) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) active else Color.Transparent)
            .border(2.dp, if (selected) active else inactive, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 收藏页专用：左右双向滑动手势。
 * - 左滑（StartToEnd）→ 红色删除背景，松手即移除；
 * - 右滑（EndToStart）→ 主色「下一首播放」背景，触发后回弹复位。
 */
@Composable
fun SwipeableSongRow(
    song: Song,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = rememberDpHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // 移除是"破坏性"操作：用 reject 语义的触觉，给用户明确的重量感
                    haptics.reject()
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    // 下一首播放是"确认"操作
                    haptics.confirm()
                    onPlayNext()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state.targetValue) },
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        // 行底的作用：盖住 SwipeToDismissBox 的滑动揭示背景（静止时不希望透出红/蓝底）。
        // - 经典模式：不透明 surface，正常兜底；
        // - 毛玻璃模式：**不能留不透明底** —— 会把全局流光底整片盖掉，
        //   列表看起来就是"一坨黑"（`SongRow` 自身背景是 Transparent，这一层是唯一的不透明面）。
        //   此时靠 SwipeBackground 只在真正拖拽时绘制来保证揭示效果。
        val glass = LocalGlass.current
        SongRow(
            song = song,
            onClick = onClick,
            isPlaying = isPlaying,
            onLongClick = onLongClick,
            modifier = if (glass.enabled) {
                Modifier
            } else {
                Modifier.background(MaterialTheme.colorScheme.surface)
            },
        )
    }
}

/**
 * 滑动揭示背景：仅在**已经拖过阈值**（targetValue ≠ Settled）时绘制。
 * 静止态不画任何底色 —— 否则毛玻璃模式下行底透明，整列会被 errorContainer/primaryContainer 染色；
 * 同时也顺带修掉了"小幅拖动先闪一下错误颜色"的问题（未过阈值时不再显示颜色）。
 */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val isDelete = direction == SwipeToDismissBoxValue.StartToEnd
    val container = if (isDelete) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (isDelete) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = 24.dp),
        contentAlignment = if (isDelete) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isDelete) Icons.Filled.Delete else Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = content,
            )
            Text(
                text = if (isDelete) "移除" else "下一首播放",
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}