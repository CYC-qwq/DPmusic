package com.dpmusic.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.ChatConversation
import com.dpmusic.app.core.model.ChatListState
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.ErrorState
import com.dpmusic.app.ui.components.LoadingState
import com.dpmusic.app.ui.motion.DPMotion
import com.dpmusic.app.ui.util.rememberDpHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 「消息」列表：网易云私信会话。
 *
 * 交互与视觉：
 * - 进入即拉取；按钮可刷新（刷新时图标旋转 + 触感反馈）；
 * - 行内含头像、昵称、最近一条消息预览（卡片类消息带 `[歌曲]/[歌单]` 前缀）、时间、未读角标；
 * - 每行按下有缩放 + 触感反馈，列表项进入有淡入上浮动效；
 * - 平板 / 横屏限宽居中，避免超宽行难读。
 */
@Composable
fun ChatListScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenThread: (ChatConversation) -> Unit,
    onOpenLogin: () -> Unit,
) {
    val vm: ChatViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val haptics = rememberDpHaptics()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.notice.collect { snackbar.showSnackbar(it) }
    }

    val spin by animateFloatAsState(
        targetValue = if (refreshing) 360f else 0f,
        animationSpec = tween(600),
        label = "chatRefreshSpin",
    )

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "消息",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.tick()
                            vm.refresh(silent = true)
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.rotate(spin),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val wide = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = if (wide) {
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                when (val s = state) {
                    is ChatListState.Loading -> LoadingState(text = "正在加载会话…")

                    is ChatListState.NotLoggedIn -> EmptyState(
                        icon = Icons.AutoMirrored.Outlined.Login,
                        title = "尚未登录网易云",
                        subtitle = "请到「设置 → 网易云音乐」登录，之后即可与好友私信聊天（对方在官方 App 也能收到并回复）",
                        onRetry = onOpenLogin,
                    )

                    is ChatListState.Error -> ErrorState(
                        message = s.message,
                        onRetry = { vm.refresh() },
                    )

                    is ChatListState.Ready -> {
                        if (s.conversations.isEmpty()) {
                            EmptyState(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = "还没有聊天",
                                subtitle = "在网易云 App 里给好友发一条消息，这里就会出现会话",
                                onRetry = { vm.refresh() },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomBarInset.current),
                            ) {
                                items(
                                    items = s.conversations,
                                    key = { it.userId },
                                ) { conv ->
                                    ConversationRow(
                                        conversation = conv,
                                        onClick = {
                                            haptics.click()
                                            onOpenThread(conv)
                                        },
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

/** 会话行：头像 + 昵称 + 预览 + 时间 + 未读角标；按下缩放反馈 */
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = DPMotion.snappy(),
        label = "chatRowScale",
    )
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(DPMotion.enter()) + slideInVertically(DPMotion.enter()) { it / 4 },
        exit = fadeOut(DPMotion.exit()),
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .scale(scale)
                .clip(RoundedCornerShape(18.dp)),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = conversation.avatarUrl,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape),
                    shape = CircleShape,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.nickname,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            if (conversation.lastFromMe) append("我：")
                            append(conversation.lastMessage.ifBlank { "…" })
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = chatTimeLabel(conversation.lastTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    if (conversation.unreadCount > 0) {
                        UnreadBadge(conversation.unreadCount)
                    }
                }
            }
        }
    }
}

/** 未读角标（99+ 截断） */
@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** 会话时间标签：今天 → 时:分；昨天 → 昨天；今年 → 月日；更早 → 年月日 */
internal fun chatTimeLabel(ms: Long): String {
    if (ms <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        sameYear && dayDiff == 1 -> "昨天"
        sameYear -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ms))
    }
}