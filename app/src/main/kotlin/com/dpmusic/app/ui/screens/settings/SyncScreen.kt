package com.dpmusic.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.lansync.LanDevice
import com.dpmusic.app.ui.components.DpTopAppBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 数据同步页（WebDAV）：
 * - 服务器配置（地址 / 用户名 / 密码 / 同步目录，即时保存）；
 * - 手动上传 / 恢复：设置与音源、歌单与数据（覆盖语义，操作前确认）；
 * - 自动同步：收藏 / 歌单 / 屏蔽规则变更后节流上传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: SyncViewModel = viewModel(factory = AppViewModelFactory)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val lanDevices by vm.lanDevices.collectAsStateWithLifecycle()
    val lanScanning by vm.lanScanning.collectAsStateWithLifecycle()
    val lanMessage by vm.lanMessage.collectAsStateWithLifecycle()
    val lanServerRunning by vm.lanServerRunning.collectAsStateWithLifecycle()
    val lanAddresses by vm.lanLocalAddresses.collectAsStateWithLifecycle()

    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val enabled = settings.webdavEnabled

    // 局域网发现只在页面可见时进行；离开时只断开事件回调（接收端常驻，不受页面影响）
    DisposableEffect(Unit) {
        vm.onEnterLanSection()
        onDispose { vm.onLeaveLanSection() }
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "数据同步",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                bottom = 16.dp + LocalBottomBarInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                LanSyncSection(
                    alias = settings.lanSyncAlias,
                    pin = settings.lanSyncPin,
                    port = settings.lanSyncPort.toString(),
                    serverRunning = lanServerRunning,
                    scanning = lanScanning,
                    devices = lanDevices,
                    localAddresses = lanAddresses,
                    message = lanMessage,
                    onAliasChange = vm::setLanSyncAlias,
                    onPinChange = vm::setLanSyncPin,
                    onPortChange = vm::setLanSyncPort,
                    onServerEnabledChange = vm::setLanServerEnabled,
                    onScan = vm::scanLanDevices,
                    onSend = { device, includeSettings, includeLists ->
                        val scope = buildString {
                            if (includeSettings) append("「设置与音源」")
                            if (includeLists) append(if (isEmpty()) "「歌单与数据」" else " + 「歌单与数据」")
                        }
                        confirmAction = "将把本机的 $scope 推送给「${device.displayName}」，覆盖对方现有数据，不可撤销。" to {
                            vm.sendToLanDevice(device, includeSettings, includeLists)
                        }
                    },
                )
            }
            item {
                ServerCard(
                    enabled = enabled,
                    autoSync = settings.webdavAutoSync,
                    url = settings.webdavUrl,
                    username = settings.webdavUsername,
                    password = settings.webdavPassword,
                    path = settings.webdavPath,
                    busy = busy,
                    onEnabledChange = vm::setWebdavEnabled,
                    onAutoSyncChange = vm::setWebdavAutoSync,
                    onUrlChange = vm::setWebdavUrl,
                    onUsernameChange = vm::setWebdavUsername,
                    onPasswordChange = vm::setWebdavPassword,
                    onPathChange = vm::setWebdavPath,
                    onTest = vm::testConnection,
                )
            }
            item {
                TransferCard(
                    title = "设置与音源",
                    subtitle = "外观 / 播放 / 歌词 / 下载偏好、均衡器、自定义音源脚本",
                    enabled = enabled && !busy,
                    onUpload = {
                        confirmAction = "将使用本地的「设置与音源」覆盖云端数据，不可撤销。" to vm::uploadSettings
                    },
                    onDownload = {
                        confirmAction = "将使用云端的「设置与音源」覆盖本地数据，不可撤销。" to vm::downloadSettings
                    },
                )
            }
            item {
                TransferCard(
                    title = "歌单与数据",
                    subtitle = "收藏 / 歌单 / 播放历史 / 搜索历史 / 屏蔽规则 / 听歌统计 / 下载记录",
                    enabled = enabled && !busy,
                    onUpload = {
                        confirmAction = "将使用本地的「歌单与数据」覆盖云端数据，不可撤销。" to vm::uploadLists
                    },
                    onDownload = {
                        confirmAction = "将使用云端的「歌单与数据」覆盖本地数据，不可撤销。" to vm::downloadLists
                    },
                )
            }
            item {
                StatusCard(
                    lastSyncTime = settings.webdavLastSyncTime,
                    busy = busy,
                    message = message,
                )
            }
        }
    }

    confirmAction?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("确认操作") },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        action()
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            },
        )
    }
}

/* ---------------- 子块 ---------------- */

@Composable
private fun ServerCard(
    enabled: Boolean,
    autoSync: Boolean,
    url: String,
    username: String,
    password: String,
    path: String,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    SyncCard(title = "WebDAV 服务", icon = Icons.Outlined.Cloud) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "启用 WebDAV 同步", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "将设置与数据备份到自己的 WebDAV 服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = "自动同步", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "收藏 / 歌单 / 屏蔽规则变更后自动上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoSync, onCheckedChange = onAutoSyncChange, enabled = enabled)
            }
            Spacer(Modifier.height(10.dp))
            SyncTextField(
                value = url,
                onValueChange = onUrlChange,
                label = "服务器地址",
                placeholder = "https://example.com/dav/",
                enabled = enabled,
            )
            Spacer(Modifier.height(8.dp))
            SyncTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = "用户名",
                enabled = enabled,
            )
            Spacer(Modifier.height(8.dp))
            SyncTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "密码",
                enabled = enabled,
                isPassword = true,
            )
            Spacer(Modifier.height(8.dp))
            SyncTextField(
                value = path,
                onValueChange = onPathChange,
                label = "同步目录",
                placeholder = "/DPmusic/",
                enabled = enabled,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onTest, enabled = enabled && !busy) {
                Text("测试连接")
            }
        }
    }
}

@Composable
private fun TransferCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
) {
    SyncCard(title = title, icon = Icons.Outlined.Sync) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onUpload, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("上传到云端")
            }
            OutlinedButton(onClick = onDownload, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("从云端恢复")
            }
        }
    }
}

@Composable
private fun StatusCard(
    lastSyncTime: Long,
    busy: Boolean,
    message: String?,
) {
    SyncCard(title = "同步状态", icon = Icons.Outlined.Sync) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "上次同步时间：", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (lastSyncTime > 0) TIME_FORMAT.format(Date(lastSyncTime)) else "从未",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在同步…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("失败")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
    }
}

/**
 * 局域网设备同步区块（LocalSend 协议 v2.2）：
 * - 本机身份（显示名 / 端口 / PIN）；
 * - 「允许其他设备向我发送数据」= 接收端开关（仅本页停留期间监听）；
 * - 设备列表 + 扫描；每台设备可推送「设置与音源 / 歌单与数据」。
 */
@Composable
private fun LanSyncSection(
    alias: String,
    pin: String,
    port: String,
    serverRunning: Boolean,
    scanning: Boolean,
    devices: List<LanDevice>,
    localAddresses: List<String>,
    message: String?,
    onAliasChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onServerEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onSend: (LanDevice, Boolean, Boolean) -> Unit,
) {
    SyncCard(title = "局域网设备同步", icon = Icons.Outlined.Wifi) {
        Text(
            text = "同一 Wi-Fi 下与另一台设备直接互传设置与歌单，无需服务器（兼容 LocalSend）；" +
                "「播放流转」（主页入口）也走这条通道，同样需要在此开启接收",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "允许其他设备发现并推送到本机", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (serverRunning) {
                        "正在监听端口 $port" + if (localAddresses.isEmpty()) "" else "（${localAddresses.joinToString("、")}）"
                    } else {
                        "已关闭：对端扫不到本机，也无法向本机发送数据或播放流转"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = serverRunning, onCheckedChange = onServerEnabledChange)
        }

        Spacer(Modifier.height(10.dp))
        SyncTextField(
            value = alias,
            onValueChange = onAliasChange,
            label = "本机显示名",
            placeholder = "默认使用设备型号",
            enabled = true,
        )
        Spacer(Modifier.height(8.dp))
        SyncTextField(
            value = port,
            onValueChange = onPortChange,
            label = "端口",
            placeholder = "53317",
            enabled = true,
        )
        Spacer(Modifier.height(8.dp))
        SyncTextField(
            value = pin,
            onValueChange = onPinChange,
            label = "PIN（可留空）",
            placeholder = "填写后双方需一致",
            enabled = true,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onScan, enabled = !scanning) {
                Text(if (scanning) "扫描中…" else "扫描设备")
            }
            Spacer(Modifier.width(10.dp))
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("失败") || message.contains("拒绝")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            devices.forEach { device ->
                LanDeviceRow(device = device, onSend = onSend)
            }
        }
    }
}

/** 单台已发现设备：显示名 + 地址，两个推送按钮。 */
@Composable
private fun LanDeviceRow(
    device: LanDevice,
    onSend: (LanDevice, Boolean, Boolean) -> Unit,
) {
    var includeSettings by remember(device.host) { mutableStateOf(true) }
    var includeLists by remember(device.host) { mutableStateOf(true) }
    val canSend = includeSettings || includeLists

    Column(Modifier.padding(vertical = 10.dp)) {
        Text(text = device.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${device.host}:${device.info.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeSettings, onCheckedChange = { includeSettings = it })
            Text(text = "设置与音源", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = includeLists, onCheckedChange = { includeLists = it })
            Text(text = "歌单与数据", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onSend(device, includeSettings, includeLists) },
            enabled = canSend,
        ) { Text("发送到该设备") }
    }
}

@Composable
private fun SyncTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    placeholder: String = "",
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SyncCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
