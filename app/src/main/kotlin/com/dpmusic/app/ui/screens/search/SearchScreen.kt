package com.dpmusic.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.ClipboardLinkInbox
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.ui.components.AddToPlaylistHost
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.InlineLoading
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.components.NowPlayingPanel
import com.dpmusic.app.ui.components.PlatformChips
import com.dpmusic.app.ui.components.PlaylistRow
import com.dpmusic.app.ui.components.SearchField
import com.dpmusic.app.ui.components.SearchMode
import com.dpmusic.app.ui.components.SearchModeToggle
import com.dpmusic.app.ui.components.SearchTopBar
import com.dpmusic.app.ui.components.SongListSkeleton
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.util.sidePaneWidth
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 搜索页（歌曲 / 歌单双模式）：
 * - 顶部「歌曲 / 歌单」分段切换，模式内沿用平台切换 + 分页加载；
 * - 竖屏：搜索行 + 平台 chips + 单列结果流；
 * - 横屏/大屏：左结果 / 右「即时播放浮层」双栏；矮窗口下搜索框与模式切换收入顶栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    onOpenPlaylistDetail: (PlaylistSummary) -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = AppViewModelFactory)
    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val compactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    val query by vm.query.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()

    // 剪贴板链接确认后：自动填入搜索框并触发链接解析
    LaunchedEffect(Unit) {
        ClipboardLinkInbox.pendingSearch.collect { text ->
            if (text != null) {
                ClipboardLinkInbox.consumeSearch()
                vm.onQueryChange(text)
            }
        }
    }

    // 列表滚动状态外提：模式 / 形态切换时滚动偏移锚定保持
    val resultsListState = rememberLazyListState()
    val playlistsListState = rememberLazyListState()

    // 结果列表滚动时收起搜索联想，避免遮挡内容
    LaunchedEffect(resultsListState.isScrollInProgress, playlistsListState.isScrollInProgress) {
        if (resultsListState.isScrollInProgress || playlistsListState.isScrollInProgress) {
            vm.dismissSuggestions()
        }
    }

    // 长按歌曲 → 添加到歌单
    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()

    // 保存歌单等操作反馈
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            if (!compact && compactHeight) {
                // 横屏矮窗口：搜索框 + 模式切换嵌入顶栏，省出整行纵向空间
                SearchTopBar(
                    value = query,
                    onValueChange = vm::onQueryChange,
                    onSearch = vm::executeSearch,
                    actions = {
                        SearchModeToggle(mode = mode, onModeChange = vm::onModeChange)
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    },
                )
            } else {
                DpTopAppBar(
                    title = "搜索",
                    windowSizeClass = windowSizeClass,
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (compact) {
            SearchPortrait(
                vm = vm,
                contentPadding = padding,
                listState = resultsListState,
                playlistsListState = playlistsListState,
                onOpenPlaylistDetail = onOpenPlaylistDetail,
                onSongLongClick = { addHost.show(listOf(it)) },
            )
        } else {
            SearchLandscape(
                vm = vm,
                contentPadding = padding,
                listState = resultsListState,
                playlistsListState = playlistsListState,
                compactHeight = compactHeight,
                onOpenPlaylistDetail = onOpenPlaylistDetail,
                onSongLongClick = { addHost.show(listOf(it)) },
            )
        }
    }

    // 添加到歌单对话框
    AddToPlaylistHost(addHost, snackbarHostState)
}

@Composable
private fun SearchPortrait(
    vm: SearchViewModel,
    contentPadding: PaddingValues,
    listState: LazyListState,
    playlistsListState: LazyListState,
    onOpenPlaylistDetail: (PlaylistSummary) -> Unit,
    onSongLongClick: (Song) -> Unit,
) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val hotSearch by vm.hotSearch.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = vm.query.collectAsStateWithLifecycle().value,
                onValueChange = vm::onQueryChange,
                onSearch = vm::executeSearch,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SearchModeToggle(mode = mode, onModeChange = vm::onModeChange)
        }
        PlatformChips(
            selected = vm.platform.collectAsStateWithLifecycle().value,
            onSelect = vm::onPlatformChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isBlank() && (history.isNotEmpty() || hotSearch.isNotEmpty())) {
                SearchIdlePanels(
                    history = history,
                    hotSearch = hotSearch,
                    onPickHistory = vm::applyHistory,
                    onRemoveHistory = vm::removeHistory,
                    onClearHistory = vm::clearHistory,
                    onPickHot = vm::applyHotSearch,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (mode == SearchMode.Songs) {
                SearchResults(
                    vm = vm,
                    listState = listState,
                    onSongLongClick = onSongLongClick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlaylistResults(
                    vm = vm,
                    listState = playlistsListState,
                    onOpenPlaylistDetail = onOpenPlaylistDetail,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SearchSuggestionPanel(
                suggestions = suggestions,
                onPick = vm::applySuggestion,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SearchLandscape(
    vm: SearchViewModel,
    contentPadding: PaddingValues,
    listState: LazyListState,
    playlistsListState: LazyListState,
    compactHeight: Boolean,
    onOpenPlaylistDetail: (PlaylistSummary) -> Unit,
    onSongLongClick: (Song) -> Unit,
) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val hotSearch by vm.hotSearch.collectAsStateWithLifecycle()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // 侧栏宽度随可用宽自适应（窄窗口按比例收缩，宽窗口保持设计上限）
        val paneWidth = sidePaneWidth(maxWidth)
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f)) {
                // 矮窗口下搜索框与模式切换已嵌入顶栏；高窗口保持独立行
                if (!compactHeight) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            value = vm.query.collectAsStateWithLifecycle().value,
                            onValueChange = vm::onQueryChange,
                            onSearch = vm::executeSearch,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        SearchModeToggle(mode = mode, onModeChange = vm::onModeChange)
                    }
                }
                PlatformChips(
                    selected = vm.platform.collectAsStateWithLifecycle().value,
                    onSelect = vm::onPlatformChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(if (compactHeight) 4.dp else 8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isBlank() && (history.isNotEmpty() || hotSearch.isNotEmpty())) {
                        SearchIdlePanels(
                            history = history,
                            hotSearch = hotSearch,
                            onPickHistory = vm::applyHistory,
                            onRemoveHistory = vm::removeHistory,
                            onClearHistory = vm::clearHistory,
                            onPickHot = vm::applyHotSearch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (mode == SearchMode.Songs) {
                        SearchResults(
                            vm = vm,
                            listState = listState,
                            onSongLongClick = onSongLongClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlaylistResults(
                            vm = vm,
                            listState = playlistsListState,
                            onOpenPlaylistDetail = onOpenPlaylistDetail,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    SearchSuggestionPanel(
                        suggestions = suggestions,
                        onPick = vm::applySuggestion,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            // 右侧即时播放浮层
            val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
            NowPlayingPanel(
                nowPlaying = nowPlaying,
                onTogglePlay = vm::togglePlay,
                onNext = vm::next,
                onPrevious = vm::previous,
                onOpenPlayer = vm::openPlayer,
                modifier = Modifier
                    .width(paneWidth)
                    .padding(16.dp),
            )
        }
    }
}

/* ---------------- 歌曲结果 ---------------- */

@Composable
private fun SearchResults(
    vm: SearchViewModel,
    listState: LazyListState,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val linkPlatform by vm.linkPlatform.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> SongListSkeleton(count = 8)

            error != null && results.isEmpty() -> ErrorState(
                message = error ?: "搜索失败",
                onRetry = vm::retry,
            )

            query.isBlank() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = "搜索三平台海量曲库",
                subtitle = "输入歌名、歌手或专辑；也可粘贴网易云 / QQ音乐 / 酷狗官方歌曲链接",
            )

            results.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = "没有找到与「$query」相关的歌曲",
                subtitle = "试试更换关键词或切换平台",
            )

            else -> {
                val lp = linkPlatform
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
                ) {
                    if (lp != null) {
                        item(key = "link_banner") { LinkBanner(platform = lp) }
                    }
                    itemsIndexed(
                        items = results,
                        key = { _, song -> song.stableKey },
                    ) { index, song ->
                        SongRow(
                            song = song,
                            index = index + 1,
                            isPlaying = nowPlaying?.song?.stableKey == song.stableKey,
                            onClick = { vm.playAt(index) },
                            onLongClick = { onSongLongClick(song) },
                            modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
                        )
                    }
                    if (loadingMore) {
                        item(key = "loading_more") { InlineLoading() }
                    } else if (hasMore) {
                        item(key = "load_more_trigger") {
                            LaunchedEffect(Unit) { vm.loadMore() }
                            InlineLoading()
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 歌单结果 ---------------- */

@Composable
private fun PlaylistResults(
    vm: SearchViewModel,
    listState: LazyListState,
    onOpenPlaylistDetail: (PlaylistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val savingKey by vm.savingKey.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> SongListSkeleton(count = 7)

            error != null && playlists.isEmpty() -> ErrorState(
                message = error ?: "搜索失败",
                onRetry = vm::retry,
            )

            query.isBlank() -> EmptyState(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                title = "搜索三平台歌单",
                subtitle = "输入关键词，发现更多优质歌单",
            )

            playlists.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                title = "没有找到与「$query」相关的歌单",
                subtitle = "试试更换关键词或切换平台",
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
            ) {
                itemsIndexed(
                    items = playlists,
                    key = { _, pl -> "${pl.platform.id}:${pl.id}" },
                ) { index, playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { onOpenPlaylistDetail(playlist) },
                        trailing = {
                            val key = "${playlist.platform.id}:${playlist.id}"
                            val saving = savingKey == key
                            IconButton(
                                onClick = { vm.savePlaylist(playlist) },
                                enabled = savingKey == null,
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = "保存到我的歌单",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
                    )
                }
                if (loadingMore) {
                    item(key = "loading_more") { InlineLoading() }
                } else if (hasMore) {
                    item(key = "load_more_trigger") {
                        LaunchedEffect(Unit) { vm.loadMore() }
                        InlineLoading()
                    }
                }
            }
        }
    }
}

/* ---------------- 搜索联想（输入预测） ---------------- */

/** 搜索联想浮层：展示平台接口返回的建议关键词，点击即填入并立即搜索 */
@Composable
private fun SearchSuggestionPanel(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            suggestions.take(6).forEach { text ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(text) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/* ---------------- 空闲态：搜索历史 + 热搜榜 ---------------- */

/**
 * 空闲态（未输入）容器：搜索历史与热搜榜**共用一个滚动区**。
 *
 * 为什么必须合并：底部栏 inset（mini 条 + 导航栏，竖屏约 140dp）只能加在
 * **真正滚到屏幕底部的那一层**。历史面板下面还有热搜榜，早期实现把它也加了 inset，
 * 于是历史词条下方多出一整块死空白（≈ mini 条 + 导航栏的高度），把热搜榜压到屏幕下半部分。
 *
 * 合并后：inset 只出现一次，且仅在内容滚到尽头时才可见 —— 内容从玻璃栏下方穿过，
 * 正是 `LocalBottomBarInset` 想要的观感。同时避免了「历史词条过多时把热搜榜挤成 0 高」。
 */
@Composable
private fun SearchIdlePanels(
    history: List<String>,
    hotSearch: List<String>,
    onPickHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onPickHot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current),
    ) {
        if (history.isNotEmpty()) {
            SearchHistoryPanel(
                history = history,
                onPick = onPickHistory,
                onRemove = onRemoveHistory,
                onClear = onClearHistory,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hotSearch.isNotEmpty()) {
            HotSearchPanel(
                keywords = hotSearch,
                onPick = onPickHot,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ---------------- 搜索历史 ---------------- */

/** 搜索历史面板：标题行（清空）+ 流式词条（点按搜索 / 点 × 删除单条）。滚动由外层统一负责。 */
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // 注意：这里**不能**加 LocalBottomBarInset —— 它不是屏幕最底部（下面还有热搜榜）。
        // 底部 inset 由外层 SearchIdlePanels 统一加一次，否则会出现大块死空白。
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索历史",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { keyword ->
                HistoryChip(
                    keyword = keyword,
                    onPick = { onPick(keyword) },
                    onRemove = { onRemove(keyword) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 热搜榜面板：标题 + 排名列表（点按填入搜索框）。滚动由外层统一负责。 */
@Composable
private fun HotSearchPanel(
    keywords: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (keywords.isEmpty()) return
    Column(
        // 同 SearchHistoryPanel：底部 inset 归外层 SearchIdlePanels，这里只留水平内边距。
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "热搜榜",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        keywords.forEachIndexed { index, keyword ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onPick(keyword) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (index < 3) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = keyword,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 单条历史词条：点按搜索；右侧 × 删除 */
@Composable
private fun HistoryChip(
    keyword: String,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(50),
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = keyword,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "删除「$keyword」",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/* ---------------- 链接识别提示条 ---------------- */

/** 链接识别提示条：告知当前结果来自官方歌曲链接解析 */
@Composable
private fun LinkBanner(platform: MusicPlatform) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已识别「${platform.label}」歌曲链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}