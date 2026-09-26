package com.dpmusic.app.ui.screens.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.RecentPlay
import com.dpmusic.app.core.util.dayLabel
import com.dpmusic.app.core.util.formatDuration
import com.dpmusic.app.core.util.formatRelativeTime
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ListeningStatsCard
import com.dpmusic.app.ui.components.NowPlayingPanel
import com.dpmusic.app.ui.components.PlatformBadge
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.util.sidePaneWidth
import com.dpmusic.app.ui.theme.LocalBottomBarInset


@Composable
internal fun RecentContent(
    vm: RecentViewModel,
    recent: List<RecentPlay>,
    nowPlayingKey: String?,
    onSongLongClick: (com.dpmusic.app.core.model.Song) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val statsDays by vm.statsDays.collectAsStateWithLifecycle()
    val sessionSeconds by vm.sessionSeconds.collectAsStateWithLifecycle()
    val sessionStartMs by vm.sessionStartMs.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val isPlaying = nowPlaying?.isPlaying == true

    Box(modifier = modifier) {
        if (recent.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ListeningStatsCard(
                    days = statsDays,
                    sessionSeconds = sessionSeconds,
                    sessionStartMs = sessionStartMs,
                    isPlaying = isPlaying,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    EmptyState(
                        icon = Icons.Outlined.History,
                        title = "暂无播放记录",
                        subtitle = "播放过的歌曲会按时间轴展示在这里",
                    )
                }
            }
        } else {
            // 时间轴分组
            val groups = remember(recent) {
                recent.mapIndexed { index, item -> IndexedValue(index, item) }
                    .groupBy { dayLabel(it.value.playedAt) }
                    .toList()
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
            ) {
                item(key = "listening_stats") {
                    ListeningStatsCard(
                        days = statsDays,
                        sessionSeconds = sessionSeconds,
                        sessionStartMs = sessionStartMs,
                        isPlaying = isPlaying,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                groups.forEach { (label, items) ->
                    item(key = "header_$label") {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(
                        items = items,
                        key = { _, indexed -> indexed.value.song.stableKey },
                    ) { position, indexed ->
                        TimelineItem(
                            entry = indexed.value,
                            isPlaying = nowPlayingKey == indexed.value.song.stableKey,
                            isLast = position == items.lastIndex,
                            onClick = { vm.playAt(indexed.index) },
                            onLongClick = { onSongLongClick(indexed.value.song) },
                            modifier = Modifier
                                .staggeredEntrance(
                                    index = position,
                                    enabled = position < 12,
                                )
                                // 清空 / 移除记录时：该项淡出、其余项平滑补位
                                .animateItem(fadeInSpec = null),
                        )
                    }
                }
            }
        }
    }
}

/** 时间轴节点 + 记录卡片 */
@Composable
private fun TimelineItem(
    entry: RecentPlay,
    isPlaying: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // 时间轴列：圆点 + 竖线
        Column(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                )
            }
        }

        // 记录卡片
        Column(modifier = Modifier.weight(1f)) {
            SongRow(
                song = entry.song,
                onClick = onClick,
                isPlaying = isPlaying,
                onLongClick = onLongClick,
                subtitleOverride = buildString {
                    append(entry.song.artist)
                    if (entry.progressMs > 0 && entry.durationMs > 0) {
                        val percent = (entry.progressFraction * 100).toInt()
                        append(" · 上次听到 ${formatDuration(entry.progressMs)}（$percent%）")
                    }
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatRelativeTime(entry.playedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        PlatformBadge(platform = entry.song.platform)
                    }
                },
            )
        }
    }
}