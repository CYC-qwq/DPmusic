package com.dpmusic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.launch

/**
 * 「添加到歌单」对话框：
 * - 列出全部本地歌单（含曲目数），点击即添加（同曲自动去重）；
 * - 支持现场新建歌单并直接添加；
 * - 支持单曲 / 批量（整队列）两种输入。
 */
@Composable
fun AddToPlaylistDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val playlists by AppContainer.userPlaylists.playlists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("歌单名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val n = name.trim()
                        if (n.isBlank()) return@TextButton
                        scope.launch {
                            val id = AppContainer.userPlaylists.create(n)
                            val added = AppContainer.userPlaylists.addSongs(id, songs)
                            onAdded(addResultMessage(n, added, songs.size))
                            onDismiss()
                        }
                    },
                ) { Text("创建并添加") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("返回") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (songs.size > 1) "添加 ${songs.size} 首到歌单" else "添加到歌单") },
        text = {
            if (playlists.isEmpty()) {
                Text("还没有歌单，点击下方「新建歌单」创建一个吧")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val added = AppContainer.userPlaylists.addSongs(pl.id, songs)
                                        onAdded(addResultMessage(pl.name, added, songs.size))
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = pl.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${pl.songs.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = true }) { Text("新建歌单") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 统一的添加结果文案（单曲 / 批量自适应） */
private fun addResultMessage(playlistName: String, added: Int, total: Int): String = when {
    added == 0 && total == 1 -> "「$playlistName」中已有这首歌"
    added == 0 -> "这些歌曲都已存在于「$playlistName」"
    total == 1 -> "已添加到「$playlistName」"
    added == total -> "已添加 $added 首到「$playlistName」"
    else -> "已添加 $added 首（${total - added} 首已存在）到「$playlistName」"
}