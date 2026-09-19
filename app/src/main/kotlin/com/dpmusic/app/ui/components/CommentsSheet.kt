package com.dpmusic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.CommentItem
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.util.formatCount
import com.dpmusic.app.core.util.formatRelativeTime
import com.dpmusic.app.ui.theme.glassPanelColor
import kotlinx.coroutines.launch

/**
 * 歌曲评论抽屉（网易云 / QQ 音乐）：
 * - 热门评论 + 最新评论分区展示；
 * - 分页「加载更多」；
 * - 平台不支持时显示空态提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    song: Song,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var hot by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var latest by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableStateOf(1) }
    var reloadKey by remember { mutableStateOf(0) }

    // 首屏加载（歌曲变化 / 重试时重新拉取）
    LaunchedEffect(song.stableKey, reloadKey) {
        loading = true
        error = null
        unsupported = false
        hot = emptyList()
        latest = emptyList()
        total = 0
        hasMore = false
        pageIndex = 1
        runCatching { AppContainer.musicRepository.comments(song, page = 1) }
            .onSuccess { p ->
                if (p == null) {
                    unsupported = true
                } else {
                    hot = p.hot
                    latest = p.items
                    total = p.total
                    hasMore = p.hasMore
                }
            }
            .onFailure { error = it.message ?: "网络异常" }
        loading = false
    }

    // 加载下一页
    val onLoadMore: () -> Unit = loadMore@{
        if (loadingMore || !hasMore) return@loadMore
        loadingMore = true
        scope.launch {
            val next = pageIndex + 1
            runCatching { AppContainer.musicRepository.comments(song, page = next) }
                .onSuccess { p ->
                    if (p == null) {
                        hasMore = false
                    } else {
                        val existing = latest.map { it.id }.toSet()
                        latest = latest + p.items.filter { it.id !in existing }
                        pageIndex = next
                        hasMore = p.hasMore
                    }
                }
            loadingMore = false
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
                    text = "评论",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (total > 0) {
                    Text(
                        text = "${formatCount(total.toLong())}条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    LoadingState(text = "正在加载评论…")
                }
                error != null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    ErrorState(message = error ?: "加载失败", onRetry = { reloadKey++ })
                }
                unsupported -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                ) {
                    EmptyState(title = "该平台暂不支持评论", subtitle = "目前支持网易云 / QQ 音乐")
                }
                hot.isEmpty() && latest.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                ) {
                    EmptyState(title = "暂无评论", subtitle = "快去听歌抢占沙发吧")
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    if (hot.isNotEmpty()) {
                        item(key = "hot_title") {
                            CommentSectionTitle("热门评论")
                        }
                        items(hot, key = { "hot-${it.id}" }) { c ->
                            CommentRow(c)
                        }
                    }
                    if (latest.isNotEmpty()) {
                        item(key = "new_title") {
                            CommentSectionTitle("最新评论")
                        }
                        items(latest, key = { "new-${it.id}" }) { c ->
                            CommentRow(c)
                        }
                    }
                    if (hasMore) {
                        item(key = "load_more") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onLoadMore() }
                                    .padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (loadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "加载中…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        text = "加载更多",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    } else {
                        item(key = "no_more") {
                            Text(
                                text = "— 已经到底啦 —",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun CommentRow(c: CommentItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        CoverArt(
            url = c.avatarUrl,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = c.nickname,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatRelativeTime(c.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = c.content,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (c.likedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUp,
                        contentDescription = "点赞",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatCount(c.likedCount.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
