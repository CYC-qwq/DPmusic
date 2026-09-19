package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.ui.theme.glassPanelColor

/**
 * 相似歌曲面板（网易云源）：
 * - 基于打开面板时正在播放的歌曲拉取相似推荐（不随后续切歌变化）；
 * - 点击播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarSongsSheet(onDismiss: () -> Unit) {
    // 固定打开时的歌曲（避免播放中切歌导致列表跳变）
    val song = remember { AppContainer.player.nowPlaying.value?.song }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(song?.stableKey) {
        val s = song
        if (s == null) {
            loading = false
            error = "没有正在播放的歌曲"
            return@LaunchedEffect
        }
        val cookie = AppContainer.ncm.cookie.value
        runCatching { AppContainer.ncmApi.similarSongs(cookie, s.id, PAGE_SIZE, 0) }
            .onSuccess { list ->
                songs = list
                if (list.isEmpty()) error = "没有找到相似歌曲"
            }
            .onFailure { error = "获取相似歌曲失败：${it.message ?: "网络异常"}" }
        loading = false
    }

    val nowPlaying by AppContainer.player.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingKey = nowPlaying?.song?.stableKey

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)) {
                Text(
                    text = "相似歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = song?.title?.let { "与「$it」相似" } ?: "暂无歌曲",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading && songs.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在获取相似歌曲…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                songs.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = error ?: "没有找到相似歌曲",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    itemsIndexed(songs, key = { _, s -> s.stableKey }) { index, item ->
                        SongRow(
                            song = item,
                            index = index + 1,
                            isPlaying = item.stableKey == nowPlayingKey,
                            onClick = { AppContainer.player.playQueue(songs, index) },
                        )
                    }
                }
            }
        }
    }
}

private const val PAGE_SIZE = 50
