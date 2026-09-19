package com.dpmusic.app.ui.screens.favorites

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.NowPlayingPanel
import com.dpmusic.app.ui.components.SwipeableSongRow
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.util.sidePaneWidth


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

        Box(modifier = Modifier.weight(1f)) {
            // 空态 ⇄ 列表内容以 AnimatedContent 平滑切换（尺寸变化阻尼过渡）
            AnimatedContent(
                targetState = favorites.isEmpty(),
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
                        contentPadding = PaddingValues(bottom = 96.dp),
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
                                modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
                            )
                        }
                    }
                }
            }

            // 悬浮「全部播放」：Extended FAB，随列表显隐做胶囊入场 / 退场
            PlayAllFab(
                visible = favorites.isNotEmpty(),
                onClick = vm::playAll,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

/** 悬浮「全部播放」FAB（独立函数：避免外层 Column 作用域干扰 AnimatedVisibility 重载解析） */
@Composable
private fun PlayAllFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
    ) {
        ExtendedFloatingActionButton(
            text = { Text("全部播放") },
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = onClick,
        )
    }
}