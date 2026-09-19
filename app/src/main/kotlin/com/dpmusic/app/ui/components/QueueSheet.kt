package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.playback.QueueSnapshot

/**
 * 播放队列抽屉（ModalBottomSheet）：
 * - 当前曲高亮 + 均衡器动效图标；
 * - 点击任意曲目直接跳播。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: QueueSnapshot,
    onDismiss: () -> Unit,
    onSongClick: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addHost = rememberAddToPlaylistHost()
    var filter by remember { mutableStateOf("") }

    // 过滤后的队列条目：(原始队列索引, 歌曲) —— 保留原始索引用于跳播
    val filteredItems = remember(queue.songs, filter) {
        queue.songs.mapIndexedNotNull { index, song ->
            if (songMatches(song, filter)) index to song else null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "播放队列 · ${queue.songs.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { addHost.show(if (filter.isBlank()) queue.songs else filteredItems.map { it.second }) },
                    enabled = queue.songs.isNotEmpty(),
                ) {
                    Text("全部加入歌单")
                }
            }
            Spacer(Modifier.height(4.dp))
            if (queue.songs.isNotEmpty()) {
                ListFilterBar(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = "搜索队列歌曲",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(2.dp))
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                if (filteredItems.isEmpty() && filter.isNotBlank()) {
                    item(key = "filter_empty") {
                        FilterEmptyHint(filter)
                    }
                }
                items(
                    items = filteredItems,
                    key = { (index, song) -> "$index-${song.stableKey}" },
                ) { (index, song) ->
                    val isCurrent = index == queue.currentIndex
                    SongRow(
                        song = song,
                        index = index + 1,
                        isPlaying = isCurrent,
                        onClick = { onSongClick(index) },
                        onLongClick = { addHost.show(listOf(song)) },
                        trailing = {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Outlined.GraphicEq,
                                    contentDescription = "当前播放",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    Text(
                                        text = "",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    AddToPlaylistHost(addHost)
}
