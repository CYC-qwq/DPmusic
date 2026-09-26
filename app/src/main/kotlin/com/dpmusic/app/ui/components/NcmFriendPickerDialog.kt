package com.dpmusic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.ChatConversation
import com.dpmusic.app.ui.util.rememberDpHaptics
import kotlinx.coroutines.launch
import com.dpmusic.app.ui.theme.glassPanelColor

/**
 * 网易云好友选择器（通用）。
 *
 * 数据源是**私信会话列表**（与官方 App 的好友关系一致，无需额外接口）；
 * 选中某位好友后由调用方决定动作（发歌曲卡片 / 发一起听邀请 …）。
 *
 * 状态覆盖：未登录引导、加载中、失败提示、空列表、发送中、成功/失败触感反馈。
 *
 * @param onPick 选中好友后的动作，返回是否成功（决定成功 / 失败反馈）
 * @param onDone 动作成功后的回调（用于 Toast / 关闭弹窗）
 */
@Composable
fun NcmFriendPickerDialog(
    title: String,
    subtitle: String,
    headerIcon: ImageVector,
    onDismiss: () -> Unit,
    onPick: suspend (ChatConversation) -> Boolean,
    onDone: (ChatConversation) -> Unit,
    actionIcon: ImageVector = Icons.AutoMirrored.Filled.Send,
    actionDescription: String = "发送",
    emptyHint: String = "还没有私信会话。先在网易云 App 里和好友聊过天后，这里就会出现。",
    failureHint: String = "操作失败，请稍后重试",
) {
    val haptics = rememberDpHaptics()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var conversations by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sendingTo by remember { mutableStateOf<Long?>(null) }
    var loggedIn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val chat = AppContainer.ncmChat
        loggedIn = chat.loggedIn
        if (!loggedIn) {
            loading = false
            return@LaunchedEffect
        }
        runCatching { chat.conversations(limit = 30) }
            .onSuccess { conversations = it }
            .onFailure { error = it.message ?: "加载好友列表失败" }
        loading = false
    }

    Dialog(onDismissRequest = { if (sendingTo == null) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = headerIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = sendingTo == null,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                when {
                    !loggedIn -> HintRow(
                        icon = Icons.AutoMirrored.Outlined.Login,
                        text = "请先在「设置 → 网易云音乐」登录，才能看到好友列表",
                    )

                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }

                    error != null -> HintRow(text = error.orEmpty())

                    conversations.isEmpty() -> HintRow(text = emptyHint)

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        items(items = conversations, key = { it.userId }) { conv ->
                            val busy = sendingTo == conv.userId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = sendingTo == null) {
                                        haptics.click()
                                        sendingTo = conv.userId
                                        scope.launch {
                                            val ok = runCatching { onPick(conv) }.getOrDefault(false)
                                            sendingTo = null
                                            if (ok) {
                                                haptics.confirm()
                                                onDone(conv)
                                            } else {
                                                haptics.reject()
                                                error = failureHint
                                            }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverArt(
                                    url = conv.avatarUrl,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape),
                                    shape = CircleShape,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = conv.nickname.ifBlank { "网易云用户" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(10.dp))
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = actionIcon,
                                        contentDescription = actionDescription,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
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
private fun HintRow(
    text: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
