package com.dpmusic.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.util.rememberDpHaptics

/**
 * 底部迷你播放条（悬浮胶囊卡片）：
 * - 顶部 3dp 实时进度线（随卡片圆角自然裁剪）；
 * - 44dp 圆角封面 + 双行文本 + 播放 / 下一首快捷控制；
 * - 点击 / 上拉 → 展开全屏播放器（手势由外部宿主注入）。
 */
@Composable
fun MiniPlayerBar(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    coverModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val np = nowPlaying ?: return
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberDpHaptics()

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        shadowElevation = 6.dp,
    ) {
        Column {
            if (np.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onExpand,
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = np.song.coverUrl,
                    modifier = coverModifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = np.song.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = np.song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = { haptics.click(); onTogglePlay() }) {
                    Icon(
                        imageVector = if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (np.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { haptics.click(); onNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
        }
    }
}