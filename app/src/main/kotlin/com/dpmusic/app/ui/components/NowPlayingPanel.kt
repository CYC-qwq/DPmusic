package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.core.util.formatDuration
import com.dpmusic.app.ui.util.panelCoverSize
import com.dpmusic.app.ui.util.panelPadding
import com.dpmusic.app.ui.util.panelShowsExtras

/**
 * 横屏/大屏「即时播放浮层」：
 * 搜索页、收藏页等双栏布局的右侧常驻播放面板。
 *
 * 响应式策略（任意窗口尺寸自洽，无设备 / 断点判断）：
 * - 封面尺寸 = min(可用宽, 可用高 × 45%)——宽矮窗口受高度约束、窄窗口受宽度约束；
 * - 时间行 /「打开全屏播放器」是否展示，由「可用高是否装得下封面 + 完整内容」推导；
 * - 内边距 / 控件尺寸随可用空间缩放（触控下限 48dp）。
 */
@Composable
fun NowPlayingPanel(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        BoxWithConstraints {
            val pad = panelPadding(maxHeight)
            val coverSize = panelCoverSize(maxWidth, maxHeight, pad)
            val showExtras = panelShowsExtras(maxHeight, coverSize)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val np = nowPlaying
                if (np == null) {
                    EmptyState(
                        title = "暂无播放",
                        subtitle = "从左侧列表点选歌曲即可开始",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CoverArt(
                        url = np.song.coverUrl,
                        modifier = Modifier.size(coverSize),
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    Spacer(Modifier.height(if (showExtras) 20.dp else 10.dp))
                    Text(
                        text = np.song.title,
                        style = if (showExtras) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = np.song.artist,
                        style = if (showExtras) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(if (showExtras) 16.dp else 8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (np.durationMs > 0) {
                                (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)
                            } else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(4.dp))
                    if (showExtras) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatDuration(np.positionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatDuration(np.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        IconButton(onClick = onPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "上一首",
                                modifier = Modifier.size(if (showExtras) 32.dp else 26.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier.size(if (showExtras) 60.dp else 48.dp),
                        ) {
                            Icon(
                                imageVector = if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (np.isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(if (showExtras) 30.dp else 24.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = onNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "下一首",
                                modifier = Modifier.size(if (showExtras) 32.dp else 26.dp),
                            )
                        }
                    }
                    if (showExtras) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onOpenPlayer) {
                            Text("打开全屏播放器")
                        }
                    }
                }
            }
        }
    }
}