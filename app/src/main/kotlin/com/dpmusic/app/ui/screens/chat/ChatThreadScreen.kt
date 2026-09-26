package com.dpmusic.app.ui.screens.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.ChatMessage
import com.dpmusic.app.core.model.ChatMessageKind
import com.dpmusic.app.core.model.ChatThreadState
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.motion.DPMotion
import com.dpmusic.app.ui.util.rememberDpHaptics
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 会话详情（网易云私信）。
 *
 * 体验设计：
 * - 进入会话立即拉取记录，之后每 3 秒轮询（对方在官方 App 的回复也能及时出现）；
 * - 发送为**乐观更新**：气泡立刻出现（半透明「发送中」），失败标红并可点击重试；
 * - 歌曲卡片可直接点击播放；
 * - 超过 5 分钟间隔插入时间分隔，列表随新消息自动滚动到底部；
 * - 平板 / 横屏限宽居中，输入区随键盘上浮。
 */
@Composable
fun ChatThreadScreen(
    userId: Long,
    title: String,
    avatarUrl: String,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val vm: ChatThreadViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val haptics = rememberDpHaptics()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(userId) { vm.open(userId) }
    DisposableEffect(userId) {
        onDispose { vm.close() }
    }
    LaunchedEffect(Unit) {
        vm.notice.collect { snackbar.showSnackbar(it) }
    }
    val messages = (state as? ChatThreadState.Ready)?.messages.orEmpty()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = title.ifBlank { "聊天" },
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (avatarUrl.isNotBlank()) {
                        CoverArt(
                            url = avatarUrl,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(32.dp)
                                .clip(CircleShape),
                            shape = CircleShape,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val wide = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (wide) Modifier.widthIn(max = 720.dp) else Modifier)
                    .fillMaxWidth(),
            ) {
                when (val s = state) {
                    is ChatThreadState.Loading -> LoadingState(text = "正在加载聊天记录…")

                    is ChatThreadState.Error -> ErrorState(
                        message = s.message,
                        onRetry = { vm.open(userId) },
                    )

                    is ChatThreadState.Ready -> {
                        if (s.messages.isEmpty()) {
                            EmptyState(
                                icon = Icons.Outlined.MusicNote,
                                title = "还没有聊天记录",
                                subtitle = "发条消息打个招呼吧～",
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = LocalBottomBarInset.current),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(
                                    items = s.messages,
                                    key = { _, m -> m.id },
                                ) { index, message ->
                                    Column {
                                        TimeSeparatorIfNeeded(
                                            current = message.time,
                                            previous = s.messages.getOrNull(index - 1)?.time,
                                        )
                                        MessageRow(
                                            message = message,
                                            onPlaySong = {
                                                haptics.confirm()
                                                vm.playSong(message)
                                                onOpenPlayer()
                                            },
                                            onRetry = {
                                                haptics.tick()
                                                vm.retry(message)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Composer(
                text = input,
                onTextChange = { input = it },
                sending = sending,
                canShareSong = nowPlaying != null,
                onShareSong = {
                    haptics.click()
                    vm.sendCurrentSong()
                },
                onSend = {
                    val content = input
                    if (content.isBlank()) {
                        haptics.reject()
                    } else {
                        haptics.confirm()
                        input = ""
                        vm.sendText(content)
                    }
                },
            )
        }
    }
}

/** 输入区：文本框 + 分享当前歌曲 + 发送 */
@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    canShareSong: Boolean,
    onShareSong: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = onShareSong,
                enabled = canShareSong,
            ) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = "分享当前歌曲",
                    tint = if (canShareSong) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_INPUT_LENGTH) onTextChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("发消息…") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            val sendInteraction = remember { MutableInteractionSource() }
            val pressed by sendInteraction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                animationSpec = DPMotion.snappy(),
                label = "chatSendScale",
            )
            Surface(
                onClick = onSend,
                interactionSource = sendInteraction,
                shape = CircleShape,
                color = if (text.isBlank()) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.scale(scale),
            ) {
                Box(
                    modifier = Modifier.size(46.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (text.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 单条消息（气泡 + 头像占位；卡片类消息渲染为可点击卡片） */
@Composable
private fun MessageRow(
    message: ChatMessage,
    onPlaySong: () -> Unit,
    onRetry: () -> Unit,
) {
    val bubbleColor = if (message.fromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (message.fromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.fromMe) 18.dp else 6.dp,
                bottomEnd = if (message.fromMe) 6.dp else 18.dp,
            ),
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .alpha(if (message.pending) 0.65f else 1f)
                .then(
                    if (message.failed) {
                        Modifier.clickableNoRipple(onRetry)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                when (message.kind) {
                    ChatMessageKind.SONG -> SongCardBody(
                        message = message,
                        contentColor = contentColor,
                        onPlay = onPlaySong,
                    )

                    ChatMessageKind.PLAYLIST, ChatMessageKind.ALBUM -> CardBody(
                        message = message,
                        contentColor = contentColor,
                    )

                    ChatMessageKind.INVITE -> Text(
                        text = "🎧 ${message.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )

                    else -> Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                }
                if (message.text.isNotBlank() && message.kind == ChatMessageKind.SONG) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.75f),
                    )
                }
                if (message.pending || message.failed) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.failed) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (message.failed) "发送失败，点击重试" else "发送中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                contentColor.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 歌曲卡片内容：封面 + 标题/歌手 + 播放提示 */
@Composable
private fun SongCardBody(
    message: ChatMessage,
    contentColor: Color,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = DPMotion.snappy(),
        label = "chatSongCardScale",
    )
    Surface(
        onClick = onPlay,
        interactionSource = interaction,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.scale(scale),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                url = message.coverUrl,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.widthIn(max = 170.dp)) {
                Text(
                    text = message.songName.orEmpty().ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                )
                Text(
                    text = message.songArtist.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 歌单 / 专辑卡片内容 */
@Composable
private fun CardBody(
    message: ChatMessage,
    contentColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverArt(
            url = message.cardCoverUrl,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.widthIn(max = 170.dp)) {
            Text(
                text = message.cardTitle?.takeIf { it.isNotBlank() }
                    ?: if (message.kind == ChatMessageKind.PLAYLIST) "歌单" else "专辑",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
            if (!message.cardSubtitle.isNullOrBlank()) {
                Text(
                    text = message.cardSubtitle.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** 间隔超过 5 分钟时插入时间分隔（居中细字） */
@Composable
private fun TimeSeparatorIfNeeded(current: Long, previous: Long?) {
    if (previous != null && current - previous <= SEPARATOR_GAP_MS) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = chatTimeLabel(current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** 无涟漪点击（失败气泡重试用，避免视觉噪音） */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

private const val SEPARATOR_GAP_MS = 5 * 60_000L

/** 输入长度上限（官方 1000 字，留一点余量给表情） */
private const val MAX_INPUT_LENGTH = 1000