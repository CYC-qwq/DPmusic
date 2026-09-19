package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.ui.theme.glassPanelColor
import kotlin.math.roundToInt

/** 预设速度档位 */
private val SpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * 播放速度面板：预设档位 + 精细滑杆（0.5x - 2.0x，变速不变调）。
 * 拖动即时生效（试听），松手后写入设置作为默认速度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(onDismiss: () -> Unit) {
    val appSettings by AppContainer.settings.settings.collectAsStateWithLifecycle()
    val current = appSettings.playbackSpeed
    var sliderValue by remember(current) { mutableStateOf(current) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            // ---- 标题 ----
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)) {
                Text(
                    text = "播放速度",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "变速不变调 · 拖动即时生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            // ---- 当前速度（大号数字） ----
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatPlaybackSpeed(sliderValue),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (sliderValue != 1f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            // ---- 预设档位 ----
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeedPresets.forEach { preset ->
                    FilterChip(
                        selected = current == preset,
                        onClick = { AppContainer.player.setPlaybackSpeed(preset) },
                        label = { Text(formatPlaybackSpeed(preset)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // ---- 精细滑杆 ----
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    sliderValue = value
                    AppContainer.player.setPlaybackSpeed(value, persist = false)
                },
                onValueChangeFinished = {
                    AppContainer.player.setPlaybackSpeed(sliderValue, persist = true)
                },
                valueRange = PlayerConnection.MIN_PLAYBACK_SPEED..PlayerConnection.MAX_PLAYBACK_SPEED,
                steps = 29,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    text = "0.5×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "2.0×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Text(
                text = "速度仅影响播放节奏，进度与歌词自动保持同步。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
        }
    }
}

/** 格式化播放速度：0.5 → "0.5×"、1 → "1.0×"、1.25 → "1.25×" */
internal fun formatPlaybackSpeed(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return when {
        frac == 0 -> "$whole.0×"
        frac % 10 == 0 -> "$whole.${frac / 10}×"
        else -> "$whole.${frac.toString().padStart(2, '0')}×"
    }
}
