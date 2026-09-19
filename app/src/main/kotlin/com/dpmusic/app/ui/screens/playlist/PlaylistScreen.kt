package com.dpmusic.app.ui.screens.playlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.core.ClipboardLinkInbox
import com.dpmusic.app.core.ImportInbox
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.core.model.UserPlaylist
import com.dpmusic.app.ui.components.AccountPlaylistMiniCard
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState
import com.dpmusic.app.ui.components.InlineLoading
import com.dpmusic.app.ui.components.pressScale
import com.dpmusic.app.ui.theme.NcmBrandColor
import com.dpmusic.app.ui.theme.QqBrandColor

/**
 * 歌单页（本地歌单管理）：
 * - 创建 / 重命名 / 删除；
 * - 导入 / 导出（SAF 文件读写，备份格式 JSON）；
 * - 点击卡片进入歌单详情（歌曲列表 + 播放 / 移除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    onOpenUserPlaylist: (String) -> Unit,
    onOpenNcmPlaylist: (id: String, title: String) -> Unit,
    onOpenQqPlaylist: (id: String, title: String) -> Unit,
    onOpenNcmPlaylists: () -> Unit,
    onOpenQqPlaylists: () -> Unit,
) {
    val vm: UserPlaylistViewModel = viewModel()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val updatingId by vm.updatingId.collectAsStateWithLifecycle()
    val pendingImportUri by ImportInbox.pendingUri.collectAsStateWithLifecycle()
    val ncmAccountPlaylists by vm.ncmAccountPlaylists.collectAsStateWithLifecycle()
    val ncmAccountLoading by vm.ncmAccountLoading.collectAsStateWithLifecycle()
    val qqAccountPlaylists by vm.qqAccountPlaylists.collectAsStateWithLifecycle()
    val qqAccountLoading by vm.qqAccountLoading.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作面板 / 单卡导出目标
    var sheetTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var exportTarget by remember { mutableStateOf<UserPlaylist?>(null) }

    // 操作反馈（导入 / 导出结果）
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 打开歌单页：检查到期的定时更新（静默刷新）+ 账号歌单兜底同步
    LaunchedEffect(Unit) {
        vm.checkAutoUpdates()
        vm.syncAccounts()
    }

    // 外部打开 JSON 备份（用其他应用打开 → DPmusic）：消费收件箱并自动导入
    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let { uri ->
            ImportInbox.consume()
            vm.importFrom(context, uri)
        }
    }

    // 剪贴板歌单链接：确认后自动导入
    LaunchedEffect(Unit) {
        ClipboardLinkInbox.pendingPlaylist.collect { link ->
            if (link != null) {
                ClipboardLinkInbox.consumePlaylistImport()
                vm.importFromLink(link)
            }
        }
    }

    // SAF 导入：选择备份 JSON 文件
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { vm.importFrom(context, it) }
    }

    // SAF 导出：选择保存位置（无目标 = 导出全部；有目标 = 导出单个歌单）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val single = exportTarget
        exportTarget = null
        if (uri != null) {
            if (single != null) {
                vm.exportSingleTo(context, uri, single)
            } else {
                vm.exportTo(context, uri)
            }
        }
    }

    // 账号歌单操作面板（同步歌单：长按 → 分享 / 导出）
    var accountSheetTarget by remember { mutableStateOf<AccountSheetTarget?>(null) }
    var accountPreparing by remember { mutableStateOf(false) }
    var pendingAccountExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 账号歌单导出：SAF 保存 JSON（内容在构建完成后暂存）
    val exportAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingAccountExport
        pendingAccountExport = null
        if (uri != null && pending != null) {
            vm.writeAccountJsonTo(context, uri, pending.first, pending.second)
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "歌单",
                windowSizeClass = windowSizeClass,
                actions = {
                    Box {
                        IconButton(onClick = { importMenuOpen = true }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = "导入歌单")
                        }
                        DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("从文件导入") },
                                onClick = {
                                    importMenuOpen = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从链接导入") },
                                onClick = {
                                    importMenuOpen = false
                                    showLinkDialog = true
                                },
                            )
                        }
                    }
                    IconButton(onClick = { exportLauncher.launch("dpmusic_playlists.json") }) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "导出歌单")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "创建歌单")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val hasAccountPlaylists = ncmAccountPlaylists.isNotEmpty() || qqAccountPlaylists.isNotEmpty()
        val accountLoading = ncmAccountLoading || qqAccountLoading
        if (playlists.isEmpty() && !hasAccountPlaylists && !accountLoading) {
            EmptyState(
                icon = Icons.Outlined.LibraryMusic,
                title = "还没有歌单",
                subtitle = "点击右上角 + 创建你的第一个歌单，或导入备份",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            PlaylistContentGrid(
                playlists = playlists,
                ncmAccountPlaylists = ncmAccountPlaylists,
                ncmAccountLoading = ncmAccountLoading,
                qqAccountPlaylists = qqAccountPlaylists,
                qqAccountLoading = qqAccountLoading,
                onOpen = onOpenUserPlaylist,
                onMore = { sheetTarget = it },
                onOpenNcmPlaylist = onOpenNcmPlaylist,
                onOpenQqPlaylist = onOpenQqPlaylist,
                onOpenNcmPlaylists = onOpenNcmPlaylists,
                onOpenQqPlaylists = onOpenQqPlaylists,
                onLongPressNcm = { id, name ->
                    val pl = ncmAccountPlaylists.firstOrNull { it.id == id }
                    accountSheetTarget = AccountSheetTarget(MusicPlatform.WY, id, name, pl?.trackCount ?: 0)
                },
                onLongPressQq = { id, name ->
                    val pl = qqAccountPlaylists.firstOrNull { it.tid == id }
                    accountSheetTarget = AccountSheetTarget(MusicPlatform.QQ, id, name, pl?.trackCount ?: 0)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    // 创建对话框
    if (showCreateDialog) {
        NameInputDialog(
            title = "创建歌单",
            initial = "",
            onConfirm = { name ->
                vm.create(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // 重命名对话框
    renameTarget?.let { target ->
        NameInputDialog(
            title = "重命名歌单",
            initial = target.name,
            onConfirm = { name ->
                vm.rename(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 歌单操作面板（更新 / 分享 / 导出 / 重命名 / 删除）
    sheetTarget?.let { target ->
        val current = playlists.firstOrNull { it.id == target.id } ?: target
        PlaylistActionsSheet(
            playlist = current,
            updating = updatingId == current.id,
            onUpdate = { vm.updatePlaylist(current.id) },
            onAutoUpdate = { mode -> vm.setAutoUpdate(current.id, mode) },
            onShareFile = {
                runCatching {
                    val json = vm.exportJsonOf(current)
                    val file = PlaylistShare.writeJsonToCache(context, json, current.name)
                    PlaylistShare.shareFile(context, file, current.name)
                }.onFailure { vm.postMessage("分享失败：${it.message ?: "未知错误"}") }
                sheetTarget = null
            },
            onShareLink = {
                current.sourceLink?.let { link -> PlaylistShare.shareLink(context, link, current.name) }
                sheetTarget = null
            },
            onExportFile = {
                exportTarget = current
                sheetTarget = null
                exportLauncher.launch(PlaylistShare.suggestedFileName(current.name))
            },
            onCopyLink = {
                current.sourceLink?.let { link ->
                    PlaylistShare.copyLink(context, link)
                    vm.postMessage("链接已复制")
                }
                sheetTarget = null
            },
            onRename = {
                renameTarget = current
                sheetTarget = null
            },
            onDelete = {
                deleteTarget = current
                sheetTarget = null
            },
            onDismiss = { sheetTarget = null },
        )
    }

    // 账号歌单操作面板（同步歌单：分享 / 导出）
    accountSheetTarget?.let { target ->
        AccountPlaylistActionsSheet(
            platformLabel = accountPlatformLabel(target.platform),
            playlistName = target.name,
            trackCount = target.trackCount,
            preparing = accountPreparing,
            onShareFile = {
                accountPreparing = true
                vm.buildAccountPlaylistJson(target.platform, target.id, target.name) { json ->
                    accountPreparing = false
                    accountSheetTarget = null
                    if (json != null) {
                        runCatching {
                            val file = PlaylistShare.writeJsonToCache(context, json, target.name)
                            PlaylistShare.shareFile(context, file, target.name)
                        }.onFailure { vm.postMessage("分享失败：${it.message ?: "未知错误"}") }
                    }
                }
            },
            onShareLink = {
                accountSheetTarget = null
                PlaylistShare.shareLink(context, accountPlaylistLink(target.platform, target.id), target.name)
            },
            onExportFile = {
                accountPreparing = true
                vm.buildAccountPlaylistJson(target.platform, target.id, target.name) { json ->
                    accountPreparing = false
                    accountSheetTarget = null
                    if (json != null) {
                        pendingAccountExport = json to target.name
                        exportAccountLauncher.launch(PlaylistShare.suggestedFileName(target.name))
                    }
                }
            },
            onCopyLink = {
                accountSheetTarget = null
                PlaylistShare.copyLink(context, accountPlaylistLink(target.platform, target.id))
                vm.postMessage("链接已复制")
            },
            onDismiss = { accountSheetTarget = null },
        )
    }

    // 链接导入对话框
    if (showLinkDialog) {
        LinkImportDialog(
            importing = importing,
            onImport = { link -> vm.importFromLink(link) },
            onDismiss = { showLinkDialog = false },
        )
    }
}

/* ---------------- 歌单网格 ---------------- */

@Composable
private fun PlaylistContentGrid(
    playlists: List<UserPlaylist>,
    ncmAccountPlaylists: List<NcmPlaylist>,
    ncmAccountLoading: Boolean,
    qqAccountPlaylists: List<QqPlaylist>,
    qqAccountLoading: Boolean,
    onOpen: (String) -> Unit,
    onMore: (UserPlaylist) -> Unit,
    onOpenNcmPlaylist: (id: String, title: String) -> Unit,
    onOpenQqPlaylist: (id: String, title: String) -> Unit,
    onOpenNcmPlaylists: () -> Unit,
    onOpenQqPlaylists: () -> Unit,
    onLongPressNcm: (id: String, title: String) -> Unit,
    onLongPressQq: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasAccountArea = ncmAccountPlaylists.isNotEmpty() || ncmAccountLoading ||
        qqAccountPlaylists.isNotEmpty() || qqAccountLoading
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ncmAccountPlaylists.isNotEmpty() || ncmAccountLoading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "ncm_account") {
                NcmAccountSection(
                    playlists = ncmAccountPlaylists,
                    loading = ncmAccountLoading,
                    onOpenPlaylist = onOpenNcmPlaylist,
                    onOpenAll = onOpenNcmPlaylists,
                    onLongPress = onLongPressNcm,
                )
            }
        }
        if (qqAccountPlaylists.isNotEmpty() || qqAccountLoading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "qq_account") {
                QqAccountSection(
                    playlists = qqAccountPlaylists,
                    loading = qqAccountLoading,
                    onOpenPlaylist = onOpenQqPlaylist,
                    onOpenAll = onOpenQqPlaylists,
                    onLongPress = onLongPressQq,
                )
            }
        }
        if (playlists.isNotEmpty()) {
            if (hasAccountArea) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "local_header") {
                    Text(
                        text = "本地歌单",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(
                items = playlists,
                key = { it.id },
            ) { playlist ->
                UserPlaylistCard(
                    playlist = playlist,
                    onOpen = { onOpen(playlist.id) },
                    onMore = { onMore(playlist) },
                )
            }
        }
    }
}

/* ---------------- 账号歌单分区 ---------------- */

@Composable
private fun NcmAccountSection(
    playlists: List<NcmPlaylist>,
    loading: Boolean,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAll: () -> Unit,
    onLongPress: (id: String, title: String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(NcmBrandColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "网易云音乐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenAll) { Text("全部") }
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                playlists.forEach { pl ->
                    item(key = pl.id) {
                        AccountPlaylistMiniCard(
                            name = pl.name,
                            coverUrl = pl.coverUrl,
                            subtitle = "${pl.trackCount} 首",
                            onClick = { onOpenPlaylist(pl.id, pl.name) },
                            onLongClick = { onLongPress(pl.id, pl.name) },
                        )
                    }
                }
            }
        } else if (loading) {
            Spacer(Modifier.height(12.dp))
            InlineLoading()
        }
    }
}

@Composable
private fun QqAccountSection(
    playlists: List<QqPlaylist>,
    loading: Boolean,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAll: () -> Unit,
    onLongPress: (id: String, title: String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(QqBrandColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "QQ 音乐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenAll) { Text("全部") }
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                playlists.forEach { pl ->
                    item(key = pl.tid) {
                        AccountPlaylistMiniCard(
                            name = pl.name,
                            coverUrl = pl.coverUrl,
                            subtitle = "${pl.trackCount} 首",
                            onClick = { onOpenPlaylist(pl.tid, pl.name) },
                            onLongClick = { onLongPress(pl.tid, pl.name) },
                        )
                    }
                }
            }
        } else if (loading) {
            Spacer(Modifier.height(12.dp))
            InlineLoading()
        }
    }
}

@Composable
private fun UserPlaylistCard(
    playlist: UserPlaylist,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onOpen,
            ),
    ) {
        Box {
            CoverArt(
                url = playlist.coverUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.large,
            )
            // 更多操作（更新 / 分享 / 导出 / 重命名 / 删除 → 底部操作面板）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.32f)),
            ) {
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${playlist.songs.size} 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
    }
}

/* ---------------- 名称输入对话框 ---------------- */

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("歌单名称") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/* ---------------- 链接导入对话框 ---------------- */

@Composable
private fun LinkImportDialog(
    importing: Boolean,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    // 导入完成（成功或失败）后自动关闭
    LaunchedEffect(importing) {
        if (submitted && !importing) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("从链接导入歌单") },
        text = {
            Column {
                Text(
                    text = "粘贴网易云 / QQ音乐 / 酷狗歌单分享链接（或纯数字歌单 ID），自动匹配平台并解析导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    enabled = !importing,
                    placeholder = { Text("https://... 或歌单 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    onImport(link)
                },
                enabled = link.isNotBlank() && !importing,
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (importing) "解析中…" else "解析导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("取消") }
        },
    )
}

/* ---------------- 账号歌单操作辅助 ---------------- */

/** 账号歌单操作目标（歌单页同步歌单：长按 → 分享 / 导出） */
private data class AccountSheetTarget(
    val platform: MusicPlatform,
    val id: String,
    val name: String,
    val trackCount: Int,
)

private fun accountPlatformLabel(platform: MusicPlatform): String = when (platform) {
    MusicPlatform.WY -> "网易云音乐"
    MusicPlatform.QQ -> "QQ 音乐"
    MusicPlatform.KG -> "酷狗音乐"
}

private fun accountPlaylistLink(platform: MusicPlatform, playlistId: String): String = when (platform) {
    MusicPlatform.WY -> "https://music.163.com/playlist?id=$playlistId"
    MusicPlatform.QQ -> "https://y.qq.com/n/ryqq/playlist/$playlistId"
    MusicPlatform.KG -> "https://www.kugou.com/yy/special/single/$playlistId.html"
}