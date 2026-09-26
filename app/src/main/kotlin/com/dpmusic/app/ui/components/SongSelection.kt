package com.dpmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpmusic.app.ui.theme.glassPanelColor

/**
 * 歌曲多选状态（以 stableKey 为标识）：
 * - 长按进入多选并选中该项；
 * - 点按切换选中；取消最后一个时自动退出；
 * - 支持全选 / 取消全选 / 反选。
 */
@Stable
class SongSelectionState {
    var selecting by mutableStateOf(false)
        private set

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    val count: Int get() = selected.size

    /** 长按：进入多选并选中该项 */
    fun start(key: String) {
        selecting = true
        selected = setOf(key)
    }

    /** 点按：切换选中；取消最后一个时自动退出多选 */
    fun toggle(key: String) {
        if (!selecting) return
        selected = if (key in selected) selected - key else selected + key
        if (selected.isEmpty()) selecting = false
    }

    fun selectAll(keys: Collection<String>) {
        selected = keys.toSet()
    }

    fun clearAll() {
        selected = emptySet()
    }

    fun invert(keys: Collection<String>) {
        val all = keys.toSet()
        selected = all - selected
    }

    fun exit() {
        selecting = false
        selected = emptySet()
    }
}

@Composable
fun rememberSongSelection(): SongSelectionState = remember { SongSelectionState() }

/** 底部多选操作栏：已选计数 + 全选 / 反选 + 添加到歌单（可选批量移除） */
@Composable
fun SelectionActionBar(
    state: SongSelectionState,
    allKeys: List<String>,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.selecting,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val allSelected = allKeys.isNotEmpty() && state.count >= allKeys.size
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${state.count}首",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { if (allSelected) state.clearAll() else state.selectAll(allKeys) },
                ) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                TextButton(onClick = { state.invert(allKeys) }) {
                    Text("反选")
                }
                if (onRemove != null) {
                    TextButton(
                        onClick = onRemove,
                        enabled = state.count > 0,
                    ) {
                        Text("移除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = onAddToPlaylist,
                    enabled = state.count > 0,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("添加到歌单")
                }
            }
        }
    }
}