package com.dpmusic.app.ui.screens.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.core.model.AlbumDetail
import com.dpmusic.app.core.model.formatPublishYear
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.SongListSkeleton
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.navigation.AlbumDetailRoute
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 专辑详情页（网易云源）：
 * - 头部：封面 / 专辑名 / 歌手 / 发行时间 / 曲目数；
 * - 歌曲列表：点击播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    route: AlbumDetailRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: AlbumDetailViewModel = viewModel()
    LaunchedEffect(route.albumId) { vm.init(route.albumId) }

    val album by vm.album.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingKey = nowPlaying?.song?.stableKey

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = album?.name ?: "专辑",
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
            val detail = album
            when {
                loading && detail == null -> SongListSkeleton(count = 6)
                error != null && detail == null -> ErrorState(message = error ?: "加载失败", onRetry = vm::retry)
                detail == null -> EmptyState(title = "专辑不存在", subtitle = "请返回重试")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
                ) {
                    item(key = "header") { AlbumHeader(detail) }
                    itemsIndexed(detail.songs, key = { _, s -> s.stableKey }) { index, song ->
                        SongRow(
                            song = song,
                            index = index + 1,
                            isPlaying = song.stableKey == nowPlayingKey,
                            onClick = { vm.playSong(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: AlbumDetail) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row {
            CoverArt(
                url = album.coverUrl.takeIf { it.isNotBlank() },
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album.artist.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    formatPublishYear(album.publishTime).takeIf { it.isNotBlank() }?.let { "$it 年发行" },
                    album.trackCount.takeIf { it > 0 }?.let { "共 $it 首" },
                    album.company.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (album.description.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = album.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
