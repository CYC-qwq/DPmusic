package com.dpmusic.app.ui.screens.logs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.core.util.AppLogger
import com.dpmusic.app.core.util.LogEntry
import com.dpmusic.app.core.util.LogLevel
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import java.io.File

/** 日志筛选 */
private enum class LogFilter(val label: String) {
    ALL("全部"),
    WARN_UP("警告+"),
    ERROR_ONLY("仅异常"),
}

/**
 * 运行日志页：
 * - 时间倒序展示（时间 / 级别色点 / 标签 / 内容）；
 * - 异常（ERROR）行高亮 + 图标标注，顶部统计异常数量；
 * - 支持筛选（全部 / 警告+ / 仅异常）与导出分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val logs by AppLogger.logs.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val context = LocalContext.current

    val filtered = remember(logs, filter) {
        when (filter) {
            LogFilter.ALL -> logs
            LogFilter.WARN_UP -> logs.filter { it.level.ordinal >= LogLevel.WARN.ordinal }
            LogFilter.ERROR_ONLY -> logs.filter { it.level == LogLevel.ERROR }
        }
    }
    val errorCount = remember(logs) { logs.count { it.level == LogLevel.ERROR } }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "运行日志",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { shareLogs(context) }) {
                        Icon(Icons.Outlined.Share, contentDescription = "导出分享")
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = if (errorCount > 0) "共 ${logs.size} 条 · 异常 $errorCount 条" else "共 ${logs.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    LogFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Article,
                    title = "暂无日志",
                    subtitle = "运行过程中的记录与异常会显示在这里",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(filtered.asReversed()) { _, entry ->
                        LogEntryRow(entry = entry)
                    }
                }
            }
        }
    }
}

/* ---------------- 日志行 ---------------- */

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val isError = entry.level == LogLevel.ERROR
    val accent = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isError) {
                    Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AppLogger.formatTime(entry.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = if (isError) FontWeight.Bold else FontWeight.Medium,
                )
                if (isError) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = "异常",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/* ---------------- 导出分享 ---------------- */

private fun shareLogs(context: Context) {
    runCatching {
        val text = AppLogger.exportText()
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "dpmusic_log_${System.currentTimeMillis()}.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }
}
