package com.dpmusic.app.ui.screens.qq

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.SongListSkeleton
import com.dpmusic.app.ui.components.SongRow
import com.dpmusic.app.ui.navigation.QqRecommendRoute
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * QQ 音乐推荐列表（source：radio=猜你喜欢 / radar=雷达）：
 * - 登录后根据收听口味生成；
 * - 点击单曲播放 / 「播放全部」整列播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QqRecommendScreen(
    route: QqRecommendRoute,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: QqRecommendViewModel = viewModel()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingKey = nowPlaying?.song?.stableKey
    LaunchedEffect(route.source) {
        vm.load(route.source)
    }
    val title = if (route.source == "radar") "雷达推荐" else "猜你喜欢"
    Scaffold(
        topBar = {
            DpTopAppBar(
                title = title,
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.retry(route.source) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
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
                loading && songs.isEmpty() -> SongListSkeleton(count = 8)
                error != null && songs.isEmpty() -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = { vm.retry(route.source) },
                )
                songs.isEmpty() -> EmptyState(
                    title = "暂无推荐",
                    subtitle = "稍后再来看看吧",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
                ) {
                    item(key = "header") {
                        QqRecommendHeader(source = route.source, count = songs.size, onPlayAll = vm::playAll)
                    }
                    itemsIndexed(songs, key = { _, s -> s.stableKey }) { index, song ->
                        SongRow(
                            song = song,
                            index = index + 1,
                            isPlaying = song.stableKey == nowPlayingKey,
                            onClick = { vm.play(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QqRecommendHeader(source: String, count: Int, onPlayAll: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = if (source == "radar") "基于红心与收藏的关系推荐" else "根据你的收听口味生成",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "共 $count 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onPlayAll) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("播放全部")
        }
    }
}
