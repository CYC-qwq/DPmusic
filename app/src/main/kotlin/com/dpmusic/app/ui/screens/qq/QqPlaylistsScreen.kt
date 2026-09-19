package com.dpmusic.app.ui.screens.qq

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.LoadingState

/**
 * 我的歌单（QQ 音乐账号创建，需登录）：
 * - 点击任意歌单 → 复用「歌单详情」页加载播放（平台固定 qq，id 传 tid）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QqPlaylistsScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenPlaylist: (id: String, title: String) -> Unit,
) {
    val vm: QqPlaylistsViewModel = viewModel()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "我的歌单",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::load) {
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
                loading && playlists.isEmpty() -> LoadingState(text = "正在获取歌单…")
                error != null && playlists.isEmpty() -> ErrorState(
                    message = error ?: "加载失败",
                    onRetry = vm::load,
                )
                playlists.isEmpty() -> EmptyState(
                    title = "暂无歌单",
                    subtitle = "去 QQ 音乐 App 创建歌单后再来吧",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(playlists, key = { it.tid }) { pl ->
                        QqPlaylistRow(playlist = pl, onClick = { onOpenPlaylist(pl.tid, pl.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun QqPlaylistRow(
    playlist: QqPlaylist,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(url = playlist.coverUrl, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playlist.special) {
                    Text(
                        text = "我喜欢",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = "${playlist.trackCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
