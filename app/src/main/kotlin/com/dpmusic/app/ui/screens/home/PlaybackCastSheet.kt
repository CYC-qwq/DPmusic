package com.dpmusic.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.lansync.LanDevice
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.theme.glassPanelColor

/**
 * 播放流转 Sheet：主页「流转」按钮唤出。
 *
 * 交互刻意只有一步 —— **点设备就推**：
 * - 内容是「当前正在播什么」，无需用户再选歌单 / 勾选项；
 * - 流转不覆盖对端任何持久化数据（纯播放指令），因此不做破坏性确认；
 * - 结果（成功 / 失败原因）就地展示在 Sheet 内，不弹 Snackbar 打断。
 *
 * 与同步页的区别：那里是**双向的数据同步**（可收可发、覆盖式、需确认），
 * 这里是**单向的播放交接**（只发、立即生效、可打断）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackCastSheet(
    onDismiss: () -> Unit,
) {
    val vm: PlaybackCastViewModel = viewModel(factory = AppViewModelFactory)
    val devices by vm.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val castingHost by vm.castingHost.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 只在 Sheet 存续期间扫描与持有发现结果（与同步页同样的边界：不留过期设备）
    DisposableEffect(Unit) {
        vm.onEnter()
        onDispose { vm.onLeave() }
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Cast,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "播放流转",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "把当前的播放队列与进度交给同一 Wi-Fi 下另一台 DPmusic，接着播" +
                    "（发现与传输复用 LocalSend 协议，但只有 DPmusic 能识别流转内容）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            CastingContent(
                songTitle = nowPlaying?.song?.title,
                artist = nowPlaying?.song?.artist,
                coverUrl = nowPlaying?.song?.coverUrl.orEmpty(),
                positionMs = nowPlaying?.positionMs ?: 0L,
                isPlaying = nowPlaying?.isPlaying == true,
                queueSize = queue.songs.size,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = vm::scan,
                    enabled = !scanning && castingHost == null,
                ) {
                    Text(if (scanning) "扫描中…" else "扫描设备")
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = vm::scan, enabled = !scanning && castingHost == null) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("失败") || it.contains("未发现") || it.contains("还没有")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在搜索同一 Wi-Fi 下的设备…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(devices, key = { it.info.fingerprint }) { device ->
                        DeviceRow(
                            device = device,
                            casting = castingHost == device.host,
                            // 有设备正在推送时禁用其它设备，避免并发协商互相抢会话
                            enabled = castingHost == null,
                            onCast = { vm.castTo(device) },
                        )
                    }
                }
            }
        }
    }
}

/** 待流转内容预览：用户在点设备前就能确认「推的是哪首、从哪儿开始」。 */
@Composable
private fun CastingContent(
    songTitle: String?,
    artist: String?,
    coverUrl: String,
    positionMs: Long,
    isPlaying: Boolean,
    queueSize: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = coverUrl,
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = songTitle ?: "当前没有播放内容",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (songTitle == null) {
                    "先播放一首歌，再点下方设备流转"
                } else {
                    val state = if (isPlaying) "播放中" else "已暂停"
                    "$state ${formatTime(positionMs)} · 队列 $queueSize 首" +
                        (artist?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 单台设备：点击整行即流转（无需先勾选再点按钮）。 */
@Composable
private fun DeviceRow(
    device: LanDevice,
    casting: Boolean,
    enabled: Boolean,
    onCast: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${device.host}:${device.info.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (casting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onCast, enabled = enabled) { Text("流转到此处") }
        }
    }
}

/** 毫秒 → `分:秒`（与播放页一致，避免用户自行换算） */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}