package com.dpmusic.app.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.core.model.AlbumDetail
import com.dpmusic.app.core.model.ArtistDetail
import com.dpmusic.app.core.model.formatPublishYear
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.InlineLoading
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.components.SongListSkeleton
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.navigation.ArtistDetailRoute
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 歌手详情页（网易云源）：
 * - 头部：头像 / 名字 / 别名 / 简介（点击展开）；
 * - 「所有歌曲」：热门 / 时间排序 + 分页；
 * - 「所有专辑」：发行年份 + 曲目数，点击进入专辑页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    route: ArtistDetailRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val vm: ArtistDetailViewModel = viewModel()
    LaunchedEffect(route.artistId) { vm.init(route.artistId) }

    val artist by vm.artist.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val songsLoading by vm.songsLoading.collectAsStateWithLifecycle()
    val songsHasMore by vm.songsHasMore.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val albumsLoading by vm.albumsLoading.collectAsStateWithLifecycle()
    val albumsHasMore by vm.albumsHasMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingKey = nowPlaying?.song?.stableKey

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = artist?.name ?: route.name,
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                error != null && artist == null -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = vm::retry,
                )

                artist == null && songs.isEmpty() -> SongListSkeleton(count = 6)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
                ) {
                    item(key = "header") { ArtistHeader(artist) }
                    item(key = "tabs") { ArtistTabs(tab = tab, onTabChange = vm::switchTab) }
                    if (tab == ArtistTab.Songs) {
                        item(key = "sorts") { ArtistSorts(sort = sort, onSortChange = vm::switchSort) }
                        if (songs.isEmpty() && songsLoading) {
                            item(key = "songs_loading") { SongListSkeleton(count = 6) }
                        } else if (songs.isEmpty()) {
                            item(key = "songs_empty") { EmptyState(title = "暂无歌曲", subtitle = "换个排序或稍后再试") }
                        } else {
                            itemsIndexed(songs, key = { _, s -> s.stableKey }) { index, song ->
                                SongRow(
                                    song = song,
                                    index = index + 1,
                                    isPlaying = song.stableKey == nowPlayingKey,
                                    onClick = { vm.playSong(index) },
                                )
                            }
                            if (songsLoading) {
                                item(key = "songs_more_loading") { InlineLoading() }
                            } else if (songsHasMore) {
                                item(key = "songs_more") {
                                    LaunchedEffect(songs.size) { vm.loadMoreSongs() }
                                    InlineLoading()
                                }
                            }
                        }
                    } else {
                        if (albums.isEmpty() && albumsLoading) {
                            item(key = "albums_loading") { SongListSkeleton(count = 6) }
                        } else if (albums.isEmpty()) {
                            item(key = "albums_empty") { EmptyState(title = "暂无专辑", subtitle = "稍后再试") }
                        } else {
                            itemsIndexed(albums, key = { _, a -> a.id }) { _, album ->
                                AlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
                            }
                            if (albumsLoading) {
                                item(key = "albums_more_loading") { InlineLoading() }
                            } else if (albumsHasMore) {
                                item(key = "albums_more") {
                                    LaunchedEffect(albums.size) { vm.loadMoreAlbums() }
                                    InlineLoading()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(artist: ArtistDetail?) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                url = artist?.avatarUrl?.takeIf { it.isNotBlank() },
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist?.name ?: "未知歌手",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist?.alias.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "别名：${artist?.alias}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!artist?.identities.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artist?.identities.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val desc = artist?.briefDesc
        if (!desc.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun ArtistTabs(tab: ArtistTab, onTabChange: (ArtistTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ArtistTabItem(label = "所有歌曲", active = tab == ArtistTab.Songs) { onTabChange(ArtistTab.Songs) }
        ArtistTabItem(label = "所有专辑", active = tab == ArtistTab.Albums) { onTabChange(ArtistTab.Albums) }
    }
}

@Composable
private fun ArtistTabItem(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(20.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

@Composable
private fun ArtistSorts(sort: String, onSortChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = sort == "hot", onClick = { onSortChange("hot") }, label = { Text("热门") })
        FilterChip(selected = sort == "time", onClick = { onSortChange("time") }, label = { Text("时间") })
    }
}

@Composable
private fun AlbumRow(album: AlbumDetail, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(url = album.coverUrl.takeIf { it.isNotBlank() }, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                formatPublishYear(album.publishTime).takeIf { it.isNotBlank() },
                album.trackCount.takeIf { it > 0 }?.let { "$it 首" },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
