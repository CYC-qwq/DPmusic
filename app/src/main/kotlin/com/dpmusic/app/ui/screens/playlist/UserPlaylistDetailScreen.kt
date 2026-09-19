package com.dpmusic.app.ui.screens.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.ui.components.AddToPlaylistHost
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.FilterEmptyHint
import com.dpmusic.app.ui.components.ListFilterBar
import com.dpmusic.app.ui.components.SelectionActionBar
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.components.filterSongs
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.components.rememberSongSelection
import com.dpmusic.app.ui.navigation.UserPlaylistDetailRoute

/**
 * 本地歌单详情：
 * - 歌曲列表（点击播放 / 行尾菜单移除）；
 * - 空歌单引导提示；
 * - 顶栏「全部播放」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPlaylistDetailScreen(
    route: UserPlaylistDetailRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: UserPlaylistViewModel = viewModel()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == route.playlistId }

    val listState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()
    val selection = rememberSongSelection()
    var filter by rememberSaveable { mutableStateOf("") }

    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = if (selection.selecting) "已选 ${selection.count}首" else (playlist?.name ?: "歌单"),
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    if (selection.selecting) {
                        IconButton(onClick = { selection.exit() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!selection.selecting && playlist != null && playlist.songs.isNotEmpty()) {
                        IconButton(onClick = { vm.playAll(playlist) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "全部播放")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val current = playlist
        val filteredSongs = remember(current?.songs, filter) {
            filterSongs(current?.songs.orEmpty(), filter)
        }
        Box(modifier = Modifier.fillMaxSize()) {
        when {
            current == null -> {
                EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "歌单不存在",
                    subtitle = "可能已被删除",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            current.songs.isEmpty() -> {
                EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "歌单还没有歌曲",
                    subtitle = "在搜索页长按歌曲即可添加到歌单",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "filter") {
                        ListFilterBar(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (filteredSongs.isEmpty() && filter.isNotBlank()) {
                        item(key = "filter_empty") {
                            FilterEmptyHint(filter)
                        }
                    }
                    itemsIndexed(
                        items = filteredSongs,
                        key = { _, song -> song.stableKey },
                    ) { index, song ->
                        UserSongRow(
                            song = song,
                            index = index,
                            isPlaying = nowPlaying?.song?.stableKey == song.stableKey,
                            selectionMode = selection.selecting,
                            selected = song.stableKey in selection.selected,
                            onClick = {
                                if (selection.selecting) selection.toggle(song.stableKey) else vm.playSong(current, song.stableKey)
                            },
                            onLongClick = { selection.start(song.stableKey) },
                            onRemove = { vm.removeSong(current.id, song.stableKey) },
                            onAddToOther = { addHost.show(listOf(song)) },
                        )
                    }
                }
            }
        }

            SelectionActionBar(
                state = selection,
                allKeys = filteredSongs.map { it.stableKey },
                onAddToPlaylist = {
                    current?.let { pl ->
                        addHost.show(pl.songs.filter { it.stableKey in selection.selected })
                    }
                    selection.exit()
                },
                onRemove = {
                    current?.let { pl -> vm.removeSongs(pl.id, selection.selected) }
                    selection.exit()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }


    AddToPlaylistHost(addHost, snackbarHostState)
}

@Composable
private fun UserSongRow(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onAddToOther: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SongRow(
            song = song,
            index = index + 1,
            isPlaying = isPlaying,
            onClick = onClick,
            onLongClick = onLongClick,
            selectionMode = selectionMode,
            selected = selected,
            trailing = if (selectionMode) {
                null
            } else {
                {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("添加到其他歌单") },
                                onClick = {
                                    menuOpen = false
                                    onAddToOther()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从歌单移除") },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            },
        )
    }

}
