package com.dpmusic.app.ui.screens.playlist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dpmusic.app.core.model.AutoUpdateMode
import com.dpmusic.app.core.model.UserPlaylist
import java.io.File

/**
 * 歌单操作面板（底部弹出，分组收纳）：
 * - 更新区（仅链接歌单）：从链接更新 / 定时更新频率；
 * - 分享与导出：分享文件（系统分享）/ 分享链接 / 导出文件（SAF）/ 复制链接；
 * - 管理：重命名 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistActionsSheet(
    playlist: UserPlaylist,
    updating: Boolean,
    onUpdate: () -> Unit,
    onAutoUpdate: (AutoUpdateMode) -> Unit,
    onShareFile: () -> Unit,
    onShareLink: () -> Unit,
    onExportFile: () -> Unit,
    onCopyLink: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // ── 头部：名称 + 元信息
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = metaLine(playlist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 更新区（仅链接歌单）
            if (playlist.isLinked) {
                HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))
                SheetActionRow(
                    icon = Icons.Outlined.Refresh,
                    label = "从链接更新",
                    subtitle = "重新获取歌单最新歌曲",
                    enabled = !updating,
                    trailing = {
                        if (updating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    onClick = onUpdate,
                )
                Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text(
                        text = "定时更新（打开歌单页时自动检查）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoUpdateMode.entries.forEach { mode ->
                            FilterChip(
                                selected = playlist.autoUpdate == mode,
                                onClick = { onAutoUpdate(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                }
            }

            // ── 分享与导出
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
            SheetSectionLabel("分享与导出")
            SheetActionRow(
                icon = Icons.Outlined.Share,
                label = "分享文件",
                subtitle = "通过系统分享发送 JSON 备份",
                onClick = onShareFile,
            )
            if (playlist.isLinked) {
                SheetActionRow(
                    icon = Icons.Outlined.Link,
                    label = "分享链接",
                    subtitle = "发送歌单源链接",
                    onClick = onShareLink,
                )
            }
            SheetActionRow(
                icon = Icons.Outlined.FileDownload,
                label = "导出文件",
                subtitle = "保存为 JSON 备份文件",
                onClick = onExportFile,
            )
            if (playlist.isLinked) {
                SheetActionRow(
                    icon = Icons.Outlined.ContentCopy,
                    label = "复制链接",
                    subtitle = "复制源链接到剪贴板",
                    onClick = onCopyLink,
                )
            }

            // ── 管理
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
            SheetSectionLabel("管理")
            SheetActionRow(
                icon = Icons.Outlined.Edit,
                label = "重命名",
                onClick = onRename,
            )
            SheetActionRow(
                icon = Icons.Outlined.Delete,
                label = "删除",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
internal fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SheetActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/* ---------------- 元信息 ---------------- */

private fun metaLine(playlist: UserPlaylist): String {
    val parts = mutableListOf<String>()
    parts += "${playlist.songs.size} 首"
    if (playlist.isLinked) {
        val platform = platformLabel(playlist.sourcePlatformId)
        parts += if (platform != null) "链接导入（$platform）" else "链接导入"
        parts += "上次更新：${relativeTime(playlist.lastUpdatedAt)}"
    } else {
        parts += "本地歌单"
    }
    return parts.joinToString(" · ")
}

private fun platformLabel(platformId: String?): String? = when (platformId) {
    "wy" -> "网易云音乐"
    "qq" -> "QQ音乐"
    "kg" -> "酷狗音乐"
    else -> null
}

private fun relativeTime(time: Long): String {
    if (time <= 0L) return "从未"
    val diff = System.currentTimeMillis() - time
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        else -> "${diff / day} 天前"
    }
}

/* ---------------- 分享 / 导出工具 ---------------- */

/**
 * 歌单分享 / 导出工具：
 * - 文件：写入 cache/shared，经 FileProvider 走系统分享；
 * - 链接：系统分享文本 / 复制到剪贴板。
 */
object PlaylistShare {

    /** 建议文件名（SAF 导出用） */
    fun suggestedFileName(name: String): String = "dpmusic_${sanitize(name)}.json"

    /** 写入缓存目录，返回分享用文件 */
    fun writeJsonToCache(context: Context, json: String, name: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "dpmusic_${sanitize(name)}_${System.currentTimeMillis()}.json")
        file.writeText(json)
        return file
    }

    /** 分享 JSON 文件（系统分享面板） */
    fun shareFile(context: Context, file: File, playlistName: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "歌单「$playlistName」")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享歌单文件"))
    }

    /** 分享歌单源链接（系统分享面板） */
    fun shareLink(context: Context, link: String, playlistName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "分享歌单「$playlistName」：$link")
        }
        context.startActivity(Intent.createChooser(intent, "分享歌单链接"))
    }

    /** 复制源链接到剪贴板 */
    fun copyLink(context: Context, link: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("歌单链接", link))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(32).ifBlank { "playlist" }
}

/** 账号歌单操作面板（同步歌单：分享 / 导出） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPlaylistActionsSheet(
    platformLabel: String,
    playlistName: String,
    trackCount: Int,
    preparing: Boolean,
    onShareFile: () -> Unit,
    onShareLink: () -> Unit,
    onExportFile: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!preparing) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = playlistName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$platformLabel · $trackCount 首 · 账号同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))
            SheetSectionLabel("分享与导出")
            SheetActionRow(
                icon = Icons.Outlined.Share,
                label = "分享文件",
                subtitle = "通过系统分享发送 JSON 备份",
                enabled = !preparing,
                trailing = {
                    if (preparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                onClick = onShareFile,
            )
            SheetActionRow(
                icon = Icons.Outlined.Link,
                label = "分享链接",
                subtitle = "发送歌单源链接",
                enabled = !preparing,
                onClick = onShareLink,
            )
            SheetActionRow(
                icon = Icons.Outlined.FileDownload,
                label = "导出文件",
                subtitle = "保存为 JSON 备份文件",
                enabled = !preparing,
                onClick = onExportFile,
            )
            SheetActionRow(
                icon = Icons.Outlined.ContentCopy,
                label = "复制链接",
                subtitle = "复制源链接到剪贴板",
                enabled = !preparing,
                onClick = onCopyLink,
            )
        }
    }
}
