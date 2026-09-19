package com.dpmusic.app.ui.screens.rank

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.EmojiEvents
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.NowPlaying
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
import com.dpmusic.app.ui.components.SelectionActionBar
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.components.SongSelectionState
import com.dpmusic.app.ui.components.filterSongs
import com.dpmusic.app.ui.components.pressScale
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.components.rememberSongSelection
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.navigation.RankDetailRoute
import com.dpmusic.app.ui.util.panelCoverSize
import com.dpmusic.app.ui.util.panelPadding
import com.dpmusic.app.ui.util.panelShowsExtras
import com.dpmusic.app.ui.util.sidePaneWidth

/**
 * 排行榜页：
 * - 竖屏：瀑布流卡片网格（2 列，错落比例 + 悬浮微光 + 模糊衬底）；
 * - 横屏/大屏：左榜单网格 / 右联动详情（Master-Detail）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    onOpenRankDetail: (RankSummary) -> Unit,
) {
    val vm: RankViewModel = viewModel(factory = AppViewModelFactory)
    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val compactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    // 网格 / 列表滚动状态外提：形态切换与双栏联动时滚动偏移锚定保持
    val gridState = rememberLazyStaggeredGridState()
    val paneListState = rememberLazyListState()

    val platform by vm.platform.collectAsStateWithLifecycle()
    val ranks by vm.ranks.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "排行榜",
                windowSizeClass = windowSizeClass,
                actions = {
                    if (compactHeight) {
                        // 横屏矮窗口：chips 收入顶栏，省出一整行纵向空间
                        PlatformChips(
                            selected = platform,
                            onSelect = vm::onPlatformChange,
                        )
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
            if (!compactHeight) {
                PlatformChips(
                    selected = platform,
                    onSelect = vm::onPlatformChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (compact) {
                RankGrid(
                    ranks = ranks,
                    loading = loading,
                    error = error,
                    onRetry = vm::loadRanks,
                    onRankClick = onOpenRankDetail,
                    gridState = gridState,
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                )
            } else {
                // 横屏：左网格 + 右详情
                val selectedRank by vm.selectedRank.collectAsStateWithLifecycle()
                val detailSongs by vm.detailSongs.collectAsStateWithLifecycle()
                val detailLoading by vm.detailLoading.collectAsStateWithLifecycle()
                val detailError by vm.detailError.collectAsStateWithLifecycle()
                val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

                Row(modifier = Modifier.weight(1f)) {
                    RankGrid(
                        ranks = ranks,
                        loading = loading,
                        error = error,
                        onRetry = vm::loadRanks,
                        onRankClick = vm::selectRank,
                        selectedRankId = selectedRank?.id,
                        gridState = gridState,
                        columns = StaggeredGridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.weight(0.92f),
                    )
                    RankDetailPane(
                        rank = selectedRank,
                        songs = detailSongs,
                        loading = detailLoading,
                        error = detailError,
                        nowPlaying = nowPlaying,
                        onPlayAll = vm::playAll,
                        onSongClick = vm::playSong,
                        onSongLongClick = { addHost.show(listOf(it)) },
                        onRetry = vm::retryDetail,
                        listState = paneListState,
                        modifier = Modifier
                            .weight(1.08f)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    AddToPlaylistHost(addHost, snackbarHostState)
}

@Composable
private fun RankGrid(
    ranks: List<RankSummary>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRankClick: (RankSummary) -> Unit,
    gridState: LazyStaggeredGridState,
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
    selectedRankId: String? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> LoadingState(text = "正在拉取榜单…")
            error != null && ranks.isEmpty() -> ErrorState(message = error, onRetry = onRetry)
            ranks.isEmpty() -> EmptyState(
                icon = Icons.Outlined.EmojiEvents,
                title = "该平台暂无榜单数据",
            )
            else -> LazyVerticalStaggeredGrid(
                state = gridState,
                columns = columns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                staggeredItemsIndexed(
                    items = ranks,
                    key = { _, rank -> rank.stableId },
                ) { index, rank ->
                    RankCard(
                        rank = rank,
                        index = index,
                        selected = rank.id == selectedRankId,
                        onClick = { onRankClick(rank) },
                        modifier = Modifier.staggeredEntrance(index = index, enabled = index < 10),
                    )
                }
            }
        }
    }
}

private val RankSummary.stableId: String get() = "${platform.id}:$id"

/** 榜单瀑布流卡片：错落比例 + 悬浮微光 + 底部渐变标题 */
@Composable
private fun RankCard(
    rank: RankSummary,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val aspect = if (index % 3 == 1) 0.78f else 1f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(MaterialTheme.shapes.large)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        CoverArt(
            url = rank.coverUrl,
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
        )

        // 悬浮微光（选中增强）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (selected) 0.34f else 0.16f,
                            ),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 底部渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.58f)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Text(
                text = rank.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (rank.updateFrequency.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rank.updateFrequency,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
        }

        PlatformBadge(
            platform = rank.platform,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }
}

/** 榜单详情面板（横屏右栏 / 与竖屏详情页共用内容） */
@Composable
fun RankDetailPane(
    rank: RankSummary?,
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    nowPlaying: NowPlaying?,
    onPlayAll: () -> Unit,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onRetry: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val filteredSongs = remember(songs, filter) { filterSongs(songs, filter) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (rank != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(
                        url = rank.coverUrl,
                        modifier = Modifier.size(72.dp),
                        shape = MaterialTheme.shapes.large,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rank.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = listOf(rank.platform.label, rank.updateFrequency)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
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
            if (!loading && error == null && songs.isNotEmpty()) {
                ListFilterBar(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingState(text = "正在加载榜单歌曲…")
                    error != null -> ErrorState(message = error, onRetry = onRetry)
                    songs.isEmpty() -> EmptyState(
                        title = if (filter.isBlank()) "榜单暂无歌曲" else "没有找到匹配的歌曲",
                        subtitle = if (filter.isBlank()) null else "换个关键词试试",
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (filteredSongs.isEmpty() && filter.isNotBlank()) {
                            item(key = "filter_empty") {
                                FilterEmptyHint(filter)
                            }
                        }
                        itemsIndexed(
                            items = filteredSongs,
                            key = { _, song -> song.stableKey },
                        ) { index, song ->
                            SongRow(
                                song = song,
                                index = index + 1,
                                isPlaying = nowPlaying?.song?.stableKey == song.stableKey,
                                onClick = { onSongClick(song) },
                                onLongClick = { onSongLongClick(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 榜单详情独立页（竖屏导航目标） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankDetailScreen(
    route: RankDetailRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: RankDetailViewModel = viewModel(factory = AppViewModelFactory)
    val platform = MusicPlatform.fromId(route.platform)
    val songs by vm.songs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    LaunchedEffect(route.platform, route.rankId) {
        vm.load(platform, route.rankId)
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
        Box(modifier = Modifier.fillMaxSize()) {
        if (compact) {
            // 竖屏：播放行 + 歌曲列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton(
                        text = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        onClick = vm::playAll,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.saveAsPlaylist(route.title) }) {
                        Text("存为歌单")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${songs.size} 首 · ${platform.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ListFilterBar(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                RankDetailSongs(
                    songs = filteredSongs,
                    loading = loading,
                    error = error,
                    nowPlayingKey = nowPlaying?.song?.stableKey,
                    onRetry = { vm.retry(platform, route.rankId) },
                    onSongClick = { song -> vm.playSong(song) },
                    filter = filter,
                    selection = selection,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // 横屏：左信息面板 + 右歌曲列表（Master-Detail；侧栏宽度随可用宽自适应）
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val paneWidth = sidePaneWidth(availableWidth = maxWidth, maxWidth = 300.dp)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    RankDetailInfoPane(
                        title = route.title,
                        platformLabel = platform.label,
                        coverUrl = songs.firstOrNull()?.coverUrl,
                        songCount = songs.size,
                        onPlayAll = vm::playAll,
                        onSaveAsPlaylist = { vm.saveAsPlaylist(route.title) },
                        modifier = Modifier
                            .width(paneWidth)
                            .fillMaxHeight()
                            .padding(16.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        ListFilterBar(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        RankDetailSongs(
                            songs = filteredSongs,
                            loading = loading,
                            error = error,
                            nowPlayingKey = nowPlaying?.song?.stableKey,
                            onRetry = { vm.retry(platform, route.rankId) },
                            onSongClick = { song -> vm.playSong(song) },
                            filter = filter,
                            selection = selection,
                            listState = listState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
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
/** 榜单歌曲列表（含加载 / 错误 / 空态），竖横屏共用 */
@Composable
private fun RankDetailSongs(
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    nowPlayingKey: String?,
    onRetry: () -> Unit,
    onSongClick: (Song) -> Unit,
    filter: String,
    selection: SongSelectionState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> LoadingState(text = "正在加载榜单歌曲…")
            error != null -> ErrorState(
                message = error,
                onRetry = onRetry,
            )
            songs.isEmpty() -> EmptyState(
                title = if (filter.isBlank()) "榜单暂无歌曲" else "没有找到匹配的歌曲",
                subtitle = if (filter.isBlank()) null else "换个关键词试试",
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.stableKey },
                ) { index, song ->
                    SongRow(
                        song = song,
                        index = index + 1,
                        isPlaying = nowPlayingKey == song.stableKey,
                        onClick = {
                            if (selection.selecting) selection.toggle(song.stableKey) else onSongClick(song)
                        },
                        onLongClick = { selection.start(song.stableKey) },
                        selectionMode = selection.selecting,
                        selected = song.stableKey in selection.selected,
                        modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
                    )
                }
            }
        }
    }
}

/** 榜单详情信息面板（横屏左栏：封面按可用高度自适应，矮窗口不溢出） */
@Composable
private fun RankDetailInfoPane(
    title: String,
    platformLabel: String,
    coverUrl: String?,
    songCount: Int,
    onPlayAll: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoxWithConstraints {
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
                    url = coverUrl,
                    modifier = Modifier.size(coverSize),
                    shape = MaterialTheme.shapes.extraLarge,
                )
                Spacer(Modifier.height(if (showExtras) 16.dp else 10.dp))
                Text(
                    text = title,
                    style = if (showExtras) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (showExtras) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$songCount 首 · $platformLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(if (showExtras) 16.dp else 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        text = "全部播放",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onPlayAll,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onSaveAsPlaylist) {
                        Text("存为歌单")
                    }
                }
            }
        }
    }
}
