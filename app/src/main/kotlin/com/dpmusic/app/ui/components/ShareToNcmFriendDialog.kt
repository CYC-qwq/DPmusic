package com.dpmusic.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.Song

/**
 * 「分享给网易云好友」选择器。
 *
 * 从网易云私信会话列表里选一位好友，把当前歌曲以**歌曲卡片**发过去；
 * 对方在官方 App 的私信里能直接看到卡片并播放 —— 与官方分享行为一致。
 *
 * 复用通用的 [NcmFriendPickerDialog]（与「一起听邀请」共用同一套交互与状态）。
 */
@Composable
fun ShareToNcmFriendDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit,
) {
    NcmFriendPickerDialog(
        title = "分享给网易云好友",
        subtitle = "以歌曲卡片发送到私信 · ${song.title}",
        headerIcon = Icons.Outlined.ChatBubbleOutline,
        onDismiss = onDismiss,
        onPick = { conv -> AppContainer.ncmChat.sendSong(conv.userId, song) },
        onDone = { conv -> onSent(conv.nickname) },
        failureHint = "发送失败，请稍后重试",
    )
}