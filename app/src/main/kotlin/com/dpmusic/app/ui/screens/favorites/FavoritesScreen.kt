package com.dpmusic.app.ui.screens.favorites

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.SwipeableSongRow
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.theme.LocalBottomBarInset

@Composable
internal fun FavoritesContent(
    vm: FavoritesViewModel,
    favorites: List<com.dpmusic.app.core.model.Song>,
    nowPlayingKey: String?,
    onSongLongClick: (com.dpmusic.app.core.model.Song) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${favorites.size} 首收藏",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))

        // 空态 ⇄ 列表内容以 AnimatedContent 平滑切换（尺寸变化阻尼过渡）
        AnimatedContent(
            targetState = favorites.isEmpty(),
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "还没有收藏的歌曲",
                    subtitle = "在播放页点击心形图标即可收藏",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp + LocalBottomBarInset.current),
                ) {
                    itemsIndexed(
                        items = favorites,
                        key = { _, song -> song.stableKey },
                    ) { index, song ->
                        SwipeableSongRow(
                            song = song,
                            isPlaying = nowPlayingKey == song.stableKey,
                            onClick = { vm.playAt(index) },
                            onDelete = { vm.remove(song) },
                            onPlayNext = { vm.playNext(song) },
                            onLongClick = { onSongLongClick(song) },
                            modifier = Modifier
                                .staggeredEntrance(index = index, enabled = index < 12)
                                // 增删动画：移除时该项淡出、其余项平滑上移补位。
                                // 只保留 fadeOut（禁用 fadeIn）——入场交给 staggeredEntrance，
                                // 避免两套 alpha 动画叠加导致"入场发虚"。
                                .animateItem(fadeInSpec = null),
                        )
                    }
                }
            }
        }
    }
}