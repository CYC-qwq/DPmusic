package com.dpmusic.app.ui.screens.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dpmusic.app.AppContainer
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.ClipboardLinkInbox
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.TogetherRoomInfo
import com.dpmusic.app.core.together.TogetherUiState
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.components.NcmFriendPickerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 一起听页：
 * - 未登录：引导前往设置完成网易云登录（填写 Cookie）；
 * - 空闲：创建房间邀请好友加入，或粘贴好友的分享链接加入（含剪贴板自动识别投递；自动处理「已在旧房间」的清理重试）；
 * - 房间中：成员 / 当前播放（实时进度 + 控制）/ 房间歌单（点歌切换），播放状态自动跟随到本地播放器；
 * - 自动切歌：开启后歌曲结束由本端发起切换（可完整播放 VIP 歌曲；不影响对方操作）；
 * - 退出 / 结束：退出仅本端停止同步（对方不受影响）；「结束一起听」则关闭整个房间（双方退出）；
 * - 结束：展示本次聆听小结。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: TogetherViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val lastRoomId by vm.lastRoomId.collectAsStateWithLifecycle()
    val autoAdvance by vm.autoAdvance.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showChat by remember { mutableStateOf(false) }
    val chatMessages by vm.chatMessages.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { vm.closeChat() }
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeNotice()
        }
    }

    // 剪贴板一起听邀请：弹窗确认后进入本页自动发起加入
    LaunchedEffect(Unit) {
        ClipboardLinkInbox.pendingTogether.collect { raw ->
            if (raw != null) {
                ClipboardLinkInbox.consumeTogetherJoin()
                vm.join(raw)
            }
        }
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "一起听",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state is TogetherUiState.InRoom) {
                        IconButton(onClick = { if (vm.openChat()) showChat = true }) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "聊天")
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Outlined.Sync, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                TogetherUiState.Idle -> {
                    if (profile == null) {
                        NotLoggedInView(onOpenSettings = onOpenSettings)
                    } else {
                        IdleView(
                            lastRoomId = lastRoomId,
                            onJoin = vm::join,
                            onCreate = vm::createRoom,
                            onResume = vm::resumeLast,
                        )
                    }
                }

                is TogetherUiState.Joining -> LoadingState(text = s.step)

                is TogetherUiState.InRoom -> InRoomView(
                    state = s,
                    myUserId = profile?.userId ?: 0L,
                    autoAdvance = autoAdvance,
                    onToggleAutoAdvance = vm::setAutoAdvance,
                    onPlayPause = vm::playPause,
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onSeek = vm::seekTo,
                    onJump = vm::jumpTo,
                    onExitRoom = vm::exitRoom,
                    onEndRoom = vm::endRoom,
                    onGetShareLink = vm::shareLink,
                    onInviteFriend = vm::inviteFriend,
                    onSyncQueue = vm::syncQueueToRoom,
                    onImportPlaylist = vm::importPlaylist,
                )

                is TogetherUiState.Ended -> EndedView(
                    summary = s.summary,
                    onDone = vm::acknowledgeEnd,
                )

                is TogetherUiState.Error -> ErrorView(
                    state = s,
                    onCleanup = vm::cleanupAndJoin,
                    onRetry = vm::retryJoin,
                    onDismiss = vm::dismissError,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    if (showChat) {
        val partner = vm.chatPartner()
        TogetherChatDialog(
            title = if (partner != null) "和 ${partner.second}聊天" else "聊天",
            messages = chatMessages,
            onSendText = vm::sendChatText,
            onSendSong = vm::sendChatSong,
            onDismiss = {
                showChat = false
                vm.closeChat()
            },
        )
    }
}

/* ---------------- 未登录 ---------------- */

@Composable
private fun NotLoggedInView(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Headset,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(16.dp))
        Text("和朋友一起听", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "共享同一个播放列表，同步播放同一首歌。\n需要先登录网易云音乐账号。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("前往设置登录")
        }
    }
}

/* ---------------- 空闲：加入房间 ---------------- */

@Composable
private fun IdleView(
    lastRoomId: String,
    onJoin: (String) -> Unit,
    onCreate: () -> Unit,
    onResume: () -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Icon(
            imageVector = Icons.Outlined.Headset,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text("和朋友一起听", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "创建房间邀请好友加入，或粘贴好友的分享链接加入；好友发来的官方邀请卡片会自动识别提示。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            label = { Text("一起听分享链接") },
            placeholder = { Text("https://st.music.163.com/listen-together/…") },
            trailingIcon = {
                IconButton(
                    onClick = {
                        val text = readClipboard(context)
                        if (text.isNotBlank()) input = text
                    },
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = "粘贴")
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onJoin(input) },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("加入房间")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("创建房间，邀请好友加入")
        }

        if (lastRoomId.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("检测到上次未结束的一起听", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "可能仍在房间中，可尝试恢复连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onResume) { Text("恢复") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "提示：好友（房主）需保持网易云 App 停留在「一起听」房间页，房间约 30 分钟内有效，" +
                    "收到邀请请尽快加入；加入成功后，房间的播放状态会自动同步到本应用播放器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

/* ---------------- 房间中 ---------------- */

@Composable
private fun InRoomView(
    state: TogetherUiState.InRoom,
    myUserId: Long,
    autoAdvance: Boolean,
    onToggleAutoAdvance: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onJump: (Int) -> Unit,
    onExitRoom: () -> Unit,
    onEndRoom: () -> Unit,
    onGetShareLink: () -> String?,
    onInviteFriend: suspend (Long) -> Boolean,
    onSyncQueue: () -> Unit,
    onImportPlaylist: (String) -> Unit,
) {
    var showEndConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showAddSongs by remember { mutableStateOf(false) }
    val playing = state.command?.playStatus == "PLAY"

    // 实时进度滴答：播放中高频刷新，暂停时低频
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(playing) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(if (playing) 500L else 2000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                bottom = 16.dp + LocalBottomBarInset.current,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            MembersCard(
                room = state.room,
                myUserId = myUserId,
                onInvite = { showInvite = true },
                onExitRoom = { showExitConfirm = true },
                onEndRoom = { showEndConfirm = true },
            )
        }
        item {
            NowPlayingCard(
                state = state,
                now = tick,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeek = onSeek,
            )
        }
        item {
            AutoAdvanceCard(
                enabled = autoAdvance,
                onToggle = onToggleAutoAdvance,
            )
        }
        item {
            PlaylistCard(
                songs = state.songs,
                currentSongId = state.command?.targetSongId,
                onJump = onJump,
                onAddSongs = { showAddSongs = true },
            )
        }
        item {
            Text(
                text = "连接正常 · 1 秒同步 / 5 秒心跳",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("结束一起听？") },
            text = {
                Text(
                    "注意：你将结束整个房间，所有人（包括房主）都会退出。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndConfirm = false
                        onEndRoom()
                    },
                ) {
                    Text("结束", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出房间？") },
            text = {
                Text("仅你退出，房间与对方不受影响；之后仍可尝试「恢复连接」返回。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        onExitRoom()
                    },
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showInvite) {
        InviteDialog(
            shareLink = onGetShareLink().orEmpty(),
            onInviteFriend = onInviteFriend,
            onDismiss = { showInvite = false },
        )
    }

    if (showAddSongs) {
        PlaylistImportDialog(
            onSyncQueue = {
                showAddSongs = false
                onSyncQueue()
            },
            onPickPlaylist = { id ->
                showAddSongs = false
                onImportPlaylist(id)
            },
            onDismiss = { showAddSongs = false },
        )
    }

}

@Composable
private fun MembersCard(
    room: TogetherRoomInfo,
    myUserId: Long,
    onInvite: () -> Unit,
    onExitRoom: () -> Unit,
    onEndRoom: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("房间成员", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onExitRoom) {
                    Text(
                        text = "退出",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(onClick = onEndRoom) {
                    Text(
                        text = "结束一起听",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (room.roomUsers.isEmpty()) {
                Text(
                    text = "成员信息暂不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                room.roomUsers.forEach { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (user.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = user.avatarUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.nickname.ifBlank { "用户${user.userId}" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (user.userId == room.creatorId) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = "房主",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (user.userId == myUserId) {
                            Text(
                                text = "我",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (room.roomUsers.size < 2) {
                TextButton(
                    onClick = onInvite,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("邀请好友加入")
                }
            }
        }
    }
}

@Composable
private fun InviteDialog(
    shareLink: String,
    onInviteFriend: suspend (Long) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uidText by rememberSaveable { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showUidInput by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("邀请好友一起听") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current)) {
                // 方式一：从好友列表选择（推荐，无需记 UID）
                Text("方式一：邀请网易云好友", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("从好友列表选择")
                }
                Text(
                    text = "对方会在网易云「消息」里收到邀请卡片，点开即可加入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(16.dp))

                // 方式二：复制邀请链接
                Text("方式二：复制邀请链接发给好友", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = shareLink.ifBlank { "（链接暂不可用）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("一起听邀请", shareLink))
                        Toast.makeText(context, "邀请链接已复制", Toast.LENGTH_SHORT).show()
                    },
                    enabled = shareLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("复制邀请链接")
                }

                Spacer(Modifier.height(16.dp))

                // 方式三：按 UID 邀请（折叠，供知道对方 UID 的场景）
                TextButton(
                    onClick = { showUidInput = !showUidInput },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(if (showUidInput) "收起「按 UID 邀请」" else "按 UID 邀请（进阶）")
                }
                if (showUidInput) {
                    OutlinedTextField(
                        value = uidText,
                        onValueChange = { uidText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("对方网易云 UID") },
                        placeholder = { Text("例如 8303321017") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val uid = uidText.trim().toLongOrNull() ?: return@FilledTonalButton
                            scope.launch {
                                val ok = runCatching { onInviteFriend(uid) }.getOrDefault(false)
                                Toast.makeText(
                                    context,
                                    if (ok) {
                                        "邀请已发送"
                                    } else {
                                        "邀请发送失败：需与对方互相关注；可改用「复制邀请链接」"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        enabled = uidText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("发送邀请")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    if (showPicker) {
        NcmFriendPickerDialog(
            title = "邀请网易云好友",
            subtitle = "对方会收到一起听邀请卡片，点开即可加入",
            headerIcon = Icons.Outlined.PersonAdd,
            onDismiss = { showPicker = false },
            onPick = { conv -> onInviteFriend(conv.userId) },
            onDone = { conv ->
                showPicker = false
                Toast.makeText(
                    context,
                    "邀请已发送给 ${conv.nickname.ifBlank { "好友" }}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            actionDescription = "邀请",
            failureHint = "邀请发送失败：需与对方互相关注、且房间保持在线；可改用「复制邀请链接」",
        )
    }
}

@Composable
private fun NowPlayingCard(
    state: TogetherUiState.InRoom,
    now: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val command = state.command
    val song = state.currentSong

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在播放", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (command != null && command.userId != 0L) {
                    val controllerName = state.room.roomUsers
                        .firstOrNull { it.userId == command.userId }?.nickname
                    val label = when {
                        command.userId == state.room.creatorId -> "房主控制中"
                        !controllerName.isNullOrBlank() -> "$controllerName 控制中"
                        else -> ""
                    }
                    if (label.isNotBlank()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            if (command == null || song == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when {
                            state.songs.isEmpty() -> "等待房主添加歌曲…"
                            command == null -> "暂无播放中的歌曲"
                            else -> "歌曲信息加载中…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "等待房间开始播放…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(
                    url = song.coverUrl,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val duration = song.durationMs.coerceAtLeast(1L)
            val livePosition = state.positionNow(now)
            var dragFraction by remember(song.id) { mutableStateOf<Float?>(null) }
            val liveFraction = (livePosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val shownFraction = dragFraction ?: liveFraction
            val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: livePosition

            Slider(
                value = shownFraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { onSeek((it * duration).toLong()) }
                    dragFraction = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatMs(shownPosition),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatMs(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = state.songs.size > 1,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(20.dp))
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (command.playStatus == "PLAY") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "播放/暂停",
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(20.dp))
                IconButton(
                    onClick = onNext,
                    enabled = state.songs.size > 1,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoAdvanceCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动切歌", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "歌曲结束后由本端自动切到下一首；可完整播放 VIP 歌曲（不影响对方切歌与拖动进度）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PlaylistCard(
    songs: List<Song>,
    currentSongId: String?,
    onJump: (Int) -> Unit,
    onAddSongs: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(vertical = 20.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("房间歌单", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${songs.size} 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (songs.isNotEmpty()) {
                    TextButton(onClick = onAddSongs) {
                        Text(
                            text = "添加",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (songs.isEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "歌单为空，添加歌曲后双方即可一起听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onAddSongs) {
                        Text("添加歌曲")
                    }
                }
            } else {
                songs.forEachIndexed { index, song ->
                    val current = song.id == currentSongId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(index) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (current) {
                                Icon(
                                    imageVector = Icons.Outlined.GraphicEq,
                                    contentDescription = "播放中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatMs(song.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 添加歌曲到房间：同步当前队列 / 从我的网易云歌单导入（REPLACE 整个房间歌单） */
@Composable
private fun PlaylistImportDialog(
    onSyncQueue: () -> Unit,
    onPickPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<NcmPlaylist>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val cookie = AppContainer.ncm.cookie.value
        val uid = AppContainer.ncm.profile.value?.userId ?: 0L
        if (cookie.isBlank() || uid <= 0L) {
            failed = true
            return@LaunchedEffect
        }
        val list = runCatching { AppContainer.ncmApi.userPlaylists(cookie, uid) }.getOrNull()
        if (list == null) failed = true else playlists = list
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加歌曲到房间") },
        text = {
            Column {
                FilledTonalButton(
                    onClick = onSyncQueue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同步当前播放队列")
                }
                Spacer(Modifier.height(14.dp))
                Text("或从我的网易云歌单添加", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val list = playlists
                when {
                    failed -> Text(
                        text = "歌单加载失败（请确认已登录网易云）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    list == null -> Text(
                        text = "歌单加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    list.isEmpty() -> Text(
                        text = "还没有网易云歌单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(list, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickPlaylist(pl.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${pl.trackCount}首",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/* ---------------- 结束 / 错误 ---------------- */

@Composable
private fun EndedView(summary: String?, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text("一起听已结束", style = MaterialTheme.typography.titleMedium)
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onDone) { Text("完成") }
    }
}

@Composable
private fun ErrorView(
    state: TogetherUiState.Error,
    onCleanup: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.staleRoomId != null -> FilledTonalButton(onClick = onCleanup) {
                    Text(if (state.pendingCreate) "结束旧房间并创建" else "结束旧房间并重试")
                }

                state.canRetry -> FilledTonalButton(onClick = onRetry) {
                    Text("重试")
                }

                state.needLogin -> FilledTonalButton(onClick = onOpenSettings) {
                    Text("前往设置")
                }
            }
            TextButton(onClick = onDismiss) { Text("返回") }
        }
    }
}

/* ---------------- 工具 ---------------- */

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}