package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.util.StorageManager
import com.dpmusic.app.ui.player.DownloadUiState
import com.dpmusic.app.ui.player.DownloadViewModel
import kotlin.math.roundToInt

/**
 * 下载歌曲面板（播放页 ⋯ → 下载歌曲）：
 * - 音质选择（解析失败自动逐级降级并提示）；
 * - 可选同时下载歌词（.lrc）；
 * - 下载中显示进度，可取消；完成 / 失败均有明确反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    song: Song?,
    defaultQuality: PlayQuality,
    onDismiss: () -> Unit,
) {
    val vm: DownloadViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val dirPath by vm.dirPath.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var quality by remember(song?.stableKey) { mutableStateOf(defaultQuality) }
    var withLyrics by remember(song?.stableKey) { mutableStateOf(true) }

    val dismiss: () -> Unit = {
        val s = vm.state.value
        if (s is DownloadUiState.Idle || s is DownloadUiState.Done || s is DownloadUiState.Failed) {
            vm.reset()
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = "下载歌曲", style = MaterialTheme.typography.titleMedium)
            }

            val currentSong = song
            if (currentSong == null) {
                Text(
                    text = "当前没有播放中的歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                SongInfoRow(currentSong)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                when (val s = state) {
                    is DownloadUiState.Idle,
                    is DownloadUiState.Preparing,
                    is DownloadUiState.Downloading -> DownloadForm(
                        state = s,
                        quality = quality,
                        onQualityChange = { quality = it },
                        withLyrics = withLyrics,
                        onWithLyricsChange = { withLyrics = it },
                        dirPath = dirPath,
                        onStart = { vm.start(currentSong, quality, withLyrics) },
                        onCancel = { vm.cancel() },
                    )

                    is DownloadUiState.Done -> DownloadDoneBlock(
                        state = s,
                        dirPath = dirPath,
                        onClose = dismiss,
                    )

                    is DownloadUiState.Failed -> DownloadFailedBlock(
                        message = s.message,
                        onRetry = { vm.start(currentSong, quality, withLyrics) },
                        onClose = dismiss,
                    )
                }
            }
        }
    }
}

/* ---------------- 子块 ---------------- */

@Composable
private fun SongInfoRow(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = song.coverUrl,
            modifier = Modifier.size(46.dp),
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadForm(
    state: DownloadUiState,
    quality: PlayQuality,
    onQualityChange: (PlayQuality) -> Unit,
    withLyrics: Boolean,
    onWithLyricsChange: (Boolean) -> Unit,
    dirPath: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = state !is DownloadUiState.Idle

    Text(
        text = "下载音质",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayQuality.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    FilterChip(
                        selected = item == quality,
                        onClick = { if (!busy) onQualityChange(item) },
                        enabled = !busy,
                        label = { Text(item.label) },
                    )
                }
            }
        }
    }
    Text(
        text = "所选音质不可用时会自动逐级降级，并提示实际下载音质",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "同时下载歌词", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "保存同名 .lrc 文件（含翻译）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = withLyrics,
            onCheckedChange = { if (!busy) onWithLyricsChange(it) },
            enabled = !busy,
        )
    }

    Text(
        text = "保存到：$dirPath",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
    )
    Text(
        text = "可在「设置 → 下载」中修改下载路径",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
    )

    Spacer(Modifier.height(6.dp))

    when (state) {
        is DownloadUiState.Idle -> {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始下载")
            }
        }

        is DownloadUiState.Preparing -> {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在解析「${state.quality.label}」音源…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }

        is DownloadUiState.Downloading -> {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        if (state.progress >= 0f) append("正在下载 ${(state.progress * 100).roundToInt()}%")
                        else append("正在下载")
                        if (state.totalBytes > 0) {
                            append(" · ${StorageManager.formatBytes(state.receivedBytes)} / ${StorageManager.formatBytes(state.totalBytes)}")
                        } else if (state.receivedBytes > 0) {
                            append(" · ${StorageManager.formatBytes(state.receivedBytes)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.note?.let { NoteLine(it) }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("取消下载") }
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun DownloadDoneBlock(
    state: DownloadUiState.Done,
    dirPath: String,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "下载完成", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "已按「${state.outcome.quality.label}」保存",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = state.outcome.file.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        state.outcome.lyricsFile?.let {
            Text(
                text = "歌词已保存：${it.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.notes.forEach { NoteLine(it) }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "保存到：$dirPath",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("完成")
        }
    }
}

@Composable
private fun DownloadFailedBlock(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "下载失败", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试") }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("关闭") }
        }
    }
}

@Composable
private fun NoteLine(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(top = 4.dp),
    )
}