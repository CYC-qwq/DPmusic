package com.dpmusic.app.ui.screens.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.ui.components.AddToPlaylistHost
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.screens.favorites.FavoritesContent
import com.dpmusic.app.ui.screens.favorites.FavoritesViewModel
import com.dpmusic.app.ui.screens.recent.RecentContent
import com.dpmusic.app.ui.screens.recent.RecentViewModel

/**
 * 我的页（最近播放 + 我的喜欢合并）：
 * - 顶部分段切换两个列表，状态跨形态切换保持；
 * - 最近播放：时间轴分组 + 历史进度胶囊；
 * - 我的喜欢：滑动移除 / 下一首播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    onOpenTogether: () -> Unit,
) {
    val favoritesVm: FavoritesViewModel = viewModel(factory = AppViewModelFactory)
    val recentVm: RecentViewModel = viewModel(factory = AppViewModelFactory)

    val favorites by favoritesVm.favorites.collectAsStateWithLifecycle()
    val recent by recentVm.recent.collectAsStateWithLifecycle()
    val nowPlaying by recentVm.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingKey = nowPlaying?.song?.stableKey

    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()

    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 两个列表的滚动状态分别外提：Tab 切换时偏移锚定保持
    val recentListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "我的",
                windowSizeClass = windowSizeClass,
                actions = {
                    IconButton(onClick = onOpenTogether) {
                        Icon(Icons.Outlined.Headset, contentDescription = "一起听")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("最近播放", style = MaterialTheme.typography.labelMedium)
                }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("我的喜欢", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (tab == 0) {
                RecentContent(
                    vm = recentVm,
                    recent = recent,
                    nowPlayingKey = nowPlayingKey,
                    onSongLongClick = { addHost.show(listOf(it)) },
                    listState = recentListState,
                    modifier = Modifier.weight(1f),
                )
            } else {
                FavoritesContent(
                    vm = favoritesVm,
                    favorites = favorites,
                    nowPlayingKey = nowPlayingKey,
                    onSongLongClick = { addHost.show(listOf(it)) },
                    listState = favoritesListState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    AddToPlaylistHost(addHost, snackbarHostState)

}
