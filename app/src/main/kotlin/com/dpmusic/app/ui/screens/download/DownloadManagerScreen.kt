package com.dpmusic.app.ui.screens.download

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.download.DownloadTask
import com.dpmusic.app.core.download.DownloadTaskStatus
import com.dpmusic.app.core.download.MetaStatus
import com.dpmusic.app.core.util.StorageManager
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载管理页：
 * - 任务列表（等待 / 下载中 / 已完成 / 失败 / 已中断）；
 * - 操作：继续下载 / 重试（含元数据重试）/ 移除 / 清除已完成；
 * - 已完成任务显示元数据写入状态（标签 / 封面 / 歌词）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val store = AppContainer.downloadTasks
    val tasks by store.tasks.collectAsStateWithLifecycle()
    val activeCount = tasks.count { it.isActive }
    val finishedCount = tasks.count {
        it.status == DownloadTaskStatus.COMPLETED || it.status == DownloadTaskStatus.ERROR
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = if (activeCount > 0) "下载管理 · $activeCount 个进行中" else "下载管理",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (finishedCount > 0) {
                        TextButton(onClick = { store.clearFinished() }) { Text("清除已完成") }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (tasks.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Download,
                    title = "暂无下载任务",
                    subtitle = "在播放页「⋯ → 下载歌曲」中添加到下载队列",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskRow(
                            task = task,
                            onResume = { store.resume(task.id) },
                            onRetry = { store.retry(task.id) },
                            onRemove = { store.remove(task.id) },
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 任务行 ---------------- */

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                url = task.song.coverUrl.takeIf { it.isNotBlank() },
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.song.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = task.song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metaLine(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (task.status) {
                DownloadTaskStatus.PAUSED ->
                    RowAction(Icons.Outlined.PlayArrow, "继续下载", onResume)
                DownloadTaskStatus.ERROR ->
                    RowAction(Icons.Outlined.Refresh, "重试", onRetry)
                DownloadTaskStatus.COMPLETED ->
                    if (hasMetaFail(task)) RowAction(Icons.Outlined.Refresh, "重试元数据", onRetry)
            }
            RowAction(Icons.Outlined.Close, "移除", onRemove)
        }
        Spacer(Modifier.height(8.dp))
        StatusBlock(task)
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StatusBlock(task: DownloadTask) {
    when (task.status) {
        DownloadTaskStatus.WAITING -> Text(
            text = "等待中…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DownloadTaskStatus.DOWNLOADING -> Column {
            if (task.progress > 0f) {
                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (task.progress > 0f) append("${(task.progress * 100).toInt()}%")
                    if (task.totalBytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${StorageManager.formatBytes(task.downloadedBytes)} / ${StorageManager.formatBytes(task.totalBytes)}")
                    } else if (task.downloadedBytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(StorageManager.formatBytes(task.downloadedBytes))
                    }
                    if (task.speedBytesPerSec > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${StorageManager.formatBytes(task.speedBytesPerSec)}/s")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.notes.lastOrNull()?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "• $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DownloadTaskStatus.COMPLETED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            MetaBadge(label = "标签", status = task.metaTags)
            MetaBadge(label = "封面", status = task.metaCover)
            MetaBadge(label = "歌词", status = task.metaLyric)
            if (task.lyricsSaved) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "· .lrc",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DownloadTaskStatus.ERROR -> Text(
            text = task.errorMsg ?: "下载失败",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        DownloadTaskStatus.PAUSED -> Text(
            text = "已中断，可继续下载",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetaBadge(label: String, status: String) {
    if (status != MetaStatus.SUCCESS && status != MetaStatus.FAIL) return
    val ok = status == MetaStatus.SUCCESS
    val color: Color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val icon: ImageVector = if (ok) Icons.Filled.CheckCircle else Icons.Outlined.ErrorOutline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
    }
}

private fun hasMetaFail(task: DownloadTask): Boolean =
    task.metaTags == MetaStatus.FAIL || task.metaCover == MetaStatus.FAIL || task.metaLyric == MetaStatus.FAIL

private fun metaLine(task: DownloadTask): String {
    val parts = mutableListOf<String>()
    parts += task.actualQuality.label
    if (task.totalBytes > 0) parts += StorageManager.formatBytes(task.totalBytes)
    parts += TIME_FORMAT.format(Date(task.createdAt))
    return parts.joinToString(" · ")
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
