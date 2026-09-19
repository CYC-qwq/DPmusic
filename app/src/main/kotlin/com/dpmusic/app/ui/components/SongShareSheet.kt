package com.dpmusic.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.launch

/** 分享内容组合方式 */
private enum class ShareOption(
    val label: String,
    val description: String,
    /** 是否需要先解析音源代理直链 */
    val needsProxy: Boolean,
) {
    OFFICIAL("歌曲名 + 歌手 + 官方链接", "官方音乐平台网页链接", false),
    PROXY("歌曲名 + 歌手 + 音源链接", "音源代理解析的播放直链（临时有效）", true),
    BOTH("歌曲名 + 歌手 + 官方 + 音源链接", "官方链接 + 音源直链（临时有效）", true),
}

/**
 * 分享歌曲面板（播放页 → 分享）：
 * - 三种组合可选：仅官方链接 / 仅音源直链 / 两者都带；
 * - 音源直链按当前播放音质实时解析，失败时给出提示；
 * - 选择后调起系统分享面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongShareSheet(
    song: Song,
    quality: PlayQuality,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var resolving by remember { mutableStateOf<ShareOption?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun share(option: ShareOption) {
        if (resolving != null) return
        errorMessage = null
        if (!option.needsProxy) {
            shareText(context, buildShareText(song, officialUrl(song), null))
            onDismiss()
            return
        }
        resolving = option
        scope.launch {
            val proxyUrl = runCatching {
                AppContainer.musicRepository.resolveForPlayback(song, quality).url
            }.getOrElse { e ->
                resolving = null
                errorMessage = "音源链接获取失败：${e.message ?: "网络异常"}"
                return@launch
            }
            resolving = null
            shareText(
                context,
                buildShareText(
                    song = song,
                    official = if (option == ShareOption.BOTH) officialUrl(song) else null,
                    proxy = proxyUrl,
                ),
            )
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = "分享歌曲", style = MaterialTheme.typography.titleMedium)
            }

            ShareSongLine(song)

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Text(
                text = "选择分享内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
            )

            ShareOption.entries.forEachIndexed { index, option ->
                ShareOptionRow(
                    option = option,
                    busy = resolving == option,
                    enabled = resolving == null,
                    onClick = { share(option) },
                )
                if (index != ShareOption.entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }

            errorMessage?.let {
                Text(
                    text = "• $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
                )
            }

            Text(
                text = "选择后将打开系统分享面板",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
            )
        }
    }
}

@Composable
private fun ShareOptionRow(
    option: ShareOption,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (option) {
                ShareOption.OFFICIAL -> Icons.Outlined.Language
                ShareOption.PROXY -> Icons.Outlined.Cloud
                ShareOption.BOTH -> Icons.Outlined.Link
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ShareSongLine(song: Song) {
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

/* ---------------- 工具 ---------------- */

/** 官方网页链接 */
private fun officialUrl(song: Song): String = when (song.platform) {
    MusicPlatform.WY -> "https://music.163.com/#/song?id=${song.id}"
    MusicPlatform.QQ -> "https://y.qq.com/n/ryqq/songDetail/${song.id}"
    MusicPlatform.KG -> "https://www.kugou.com/song/#hash=${song.id}"
}

/** 组合分享文本：分享描述 + 歌名歌手 + 带标签的链接（仅包含需要的部分） */
private fun buildShareText(song: Song, official: String?, proxy: String?): String {
    val links = listOfNotNull(
        official?.let { "官方链接：$it" },
        proxy?.let { "音源链接：$it" },
    )
    return buildString {
        append("🎵 分享歌曲《${song.title}》 - ${song.artist}")
        if (links.isNotEmpty()) {
            append("\n\n")
            append(links.joinToString("\n"))
        }
    }
}

/** 调起系统分享面板 */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享歌曲"))
}