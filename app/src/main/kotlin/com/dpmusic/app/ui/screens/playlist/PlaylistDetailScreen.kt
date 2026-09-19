package com.dpmusic.app.ui.screens.playlist

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.core.util.formatCount
import com.dpmusic.app.ui.components.AddToPlaylistHost
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.FilterEmptyHint
import com.dpmusic.app.ui.components.ListFilterBar
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.components.PillButton
import com.dpmusic.app.ui.components.PlatformBadge
import com.dpmusic.app.ui.components.PlatformChips
import com.dpmusic.app.ui.components.SearchField
import com.dpmusic.app.ui.components.SelectionActionBar
import com.dpmusic.app.ui.components.SearchTopBar
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.components.SongSelectionState
import com.dpmusic.app.ui.components.filterSongs
import com.dpmusic.app.ui.components.pressScale
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.components.rememberSongSelection
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.navigation.PlaylistDetailRoute
import com.dpmusic.app.ui.util.panelCoverSize
import com.dpmusic.app.ui.util.panelPadding
import com.dpmusic.app.ui.util.panelShowsExtras
import com.dpmusic.app.ui.util.sidePaneWidth

/** 歌单详情面板（横屏右栏） */
@Composable
fun PlaylistDetailPane(
    playlist: PlaylistSummary?,
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    nowPlaying: NowPlaying?,
    onPlayAll: () -> Unit,
    onSongClick: (Int) -> Unit,
    onRetry: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (playlist != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(
                        url = playlist.coverUrl,
                        modifier = Modifier.size(72.dp),
                        shape = MaterialTheme.shapes.large,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                playlist.platform.label,
                                playlist.creator.takeIf { it.isNotBlank() },
                                playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    PillButton(
                        text = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onPlayAll,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingState(text = "正在解析歌单…")
                    error != null -> ErrorState(message = error, onRetry = onRetry)
                    songs.isEmpty() -> EmptyState(title = "歌单暂无歌曲")
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(
                            items = songs,
                            key = { _, song -> song.stableKey },
                        ) { index, song ->
                            SongRow(
                                song = song,
                                index = index + 1,
                                isPlaying = nowPlaying?.song?.stableKey == song.stableKey,
                                onClick = { onSongClick(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 歌单详情独立页（竖屏）：
 * - 视差折叠头部：上滑时封面以 0.5 系数跟随平移，产生沉浸折叠感；
 * - 横屏窗口下自动切换为「左固定封面 / 右列表」分栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    route: PlaylistDetailRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: PlaylistDetailViewModel = viewModel(factory = AppViewModelFactory)
    val platform = MusicPlatform.fromId(route.platform)
    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    val songs by vm.songs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    // 详情列表滚动状态外提：横竖屏分栏切换时滚动偏移锚定保持
    val detailListState = rememberLazyListState()
    LaunchedEffect(route.platform, route.playlistId) {
        vm.load(platform, route.playlistId)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()
    val selection = rememberSongSelection()
    var filter by rememberSaveable { mutableStateOf("") }
    val filteredSongs = remember(songs, filter) { filterSongs(songs, filter) }
    val allKeys = remember(filteredSongs) { filteredSongs.map { it.stableKey } }

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
                title = if (selection.selecting) "已选 ${selection.count}首" else route.title,
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> LoadingState(text = "正在解析歌单…")
                error != null -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = { vm.retry(platform, route.playlistId) },
                )
                songs.isEmpty() -> EmptyState(title = "歌单暂无歌曲")
                compact -> PlaylistDetailPortrait(
                    vm = vm,
                    songs = filteredSongs,
                    allSongs = songs,
                    title = route.title,
                    filter = filter,
                    onFilterChange = { filter = it },
                    nowPlayingKey = nowPlaying?.song?.stableKey,
                    selection = selection,
                    listState = detailListState,
                )
                else -> PlaylistDetailLandscape(
                    vm = vm,
                    songs = filteredSongs,
                    allSongs = songs,
                    title = route.title,
                    filter = filter,
                    onFilterChange = { filter = it },
                    nowPlayingKey = nowPlaying?.song?.stableKey,
                    selection = selection,
                    listState = detailListState,
                )
            }

            SelectionActionBar(
                state = selection,
                allKeys = allKeys,
                onAddToPlaylist = {
                    addHost.show(songs.filter { it.stableKey in selection.selected })
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
private fun PlaylistDetailPortrait(
    vm: PlaylistDetailViewModel,
    songs: List<Song>,
    allSongs: List<Song>,
    title: String,
    filter: String,
    onFilterChange: (String) -> Unit,
    nowPlayingKey: String?,
    selection: SongSelectionState,
    listState: LazyListState,
) {
    val parallaxOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset * 0.5f
            } else 0f
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = parallaxOffset }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoverArt(
                    url = allSongs.firstOrNull()?.coverUrl,
                    modifier = Modifier.size(200.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "共 ${allSongs.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        text = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        onClick = vm::playAll,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.saveToMyPlaylists(title) }) {
                        Text("保存到我的歌单")
                    }
                }
            }
        }
        item(key = "filter") {
            ListFilterBar(
                value = filter,
                onValueChange = onFilterChange,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        if (songs.isEmpty() && filter.isNotBlank()) {
            item(key = "filter_empty") {
                FilterEmptyHint(filter = filter)
            }
        }
        itemsIndexed(
            items = songs,
            key = { _, song -> song.stableKey },
        ) { index, song ->
            SongRow(
                song = song,
                index = index + 1,
                isPlaying = nowPlayingKey == song.stableKey,
                onClick = {
                    if (selection.selecting) selection.toggle(song.stableKey) else vm.playSong(song)
                },
                onLongClick = { selection.start(song.stableKey) },
                selectionMode = selection.selecting,
                selected = song.stableKey in selection.selected,
                modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
            )
        }
    }
}

@Composable
private fun PlaylistDetailLandscape(
    vm: PlaylistDetailViewModel,
    songs: List<Song>,
    allSongs: List<Song>,
    title: String,
    filter: String,
    onFilterChange: (String) -> Unit,
    nowPlayingKey: String?,
    selection: SongSelectionState,
    listState: LazyListState,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 侧栏宽度随可用宽自适应（窄窗口按比例收缩，宽窗口保持设计上限）
        val paneWidth = sidePaneWidth(availableWidth = maxWidth, maxWidth = 320.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：封面面板（封面按可用空间自适应）
            BoxWithConstraints(
                modifier = Modifier
                    .width(paneWidth)
                    .fillMaxHeight(),
            ) {
                val pad = panelPadding(maxHeight)
                val coverSize = panelCoverSize(maxWidth, maxHeight, pad)
                val showExtras = panelShowsExtras(maxHeight, coverSize)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CoverArt(
                        url = allSongs.firstOrNull()?.coverUrl,
                        modifier = Modifier.size(coverSize),
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    Spacer(Modifier.height(if (showExtras) 20.dp else 10.dp))
                    if (showExtras) {
                        Text(
                            text = "共 ${allSongs.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillButton(
                            text = "全部播放",
                            icon = Icons.Filled.PlayArrow,
                            onClick = vm::playAll,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.saveToMyPlaylists(title) }) {
                            Text("保存到我的歌单")
                        }
                    }
                }
            }

            // 右：歌曲列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                item(key = "filter") {
                    ListFilterBar(
                        value = filter,
                        onValueChange = onFilterChange,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (songs.isEmpty() && filter.isNotBlank()) {
                    item(key = "filter_empty") {
                        FilterEmptyHint(filter = filter)
                    }
                }
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.stableKey },
                ) { index, song ->
                    SongRow(
                        song = song,
                        index = index + 1,
                        isPlaying = nowPlayingKey == song.stableKey,
                        onClick = {
                            if (selection.selecting) selection.toggle(song.stableKey) else vm.playSong(song)
                        },
                        onLongClick = { selection.start(song.stableKey) },
                        selectionMode = selection.selecting,
                        selected = song.stableKey in selection.selected,
                    )
                }
            }
        }
    }
}