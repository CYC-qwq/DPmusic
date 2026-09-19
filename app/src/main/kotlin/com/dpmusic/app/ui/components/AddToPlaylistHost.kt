package com.dpmusic.app.ui.components

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.launch

/** 「添加到歌单」交互宿主状态：管理目标歌曲与对话框显隐 */
@Stable
class AddToPlaylistHostState {
    var targets by mutableStateOf<List<Song>?>(null)
        private set

    /** 打开对话框（单曲 / 批量） */
    fun show(songs: List<Song>) {
        if (songs.isNotEmpty()) targets = songs
    }

    fun dismiss() {
        targets = null
    }
}

@Composable
fun rememberAddToPlaylistHost(): AddToPlaylistHostState = remember { AddToPlaylistHostState() }

/**
 * 渲染「添加到歌单」对话框（配合 [rememberAddToPlaylistHost] 使用）：
 * - 添加结果优先通过 [snackbarHostState] 反馈；
 * - 为 null 时（如 ModalBottomSheet 内）自动退化为 Toast。
 */
@Composable
fun AddToPlaylistHost(
    state: AddToPlaylistHostState,
    snackbarHostState: SnackbarHostState? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    state.targets?.let { songs ->
        AddToPlaylistDialog(
            songs = songs,
            onDismiss = state::dismiss,
            onAdded = { message ->
                state.dismiss()
                if (snackbarHostState != null) {
                    scope.launch { snackbarHostState.showSnackbar(message) }
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}