package com.dpmusic.app.ui.screens.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.script.ScriptEngineStatus
import com.dpmusic.app.core.script.UserScript
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.EmptyState

/**
 * 音源管理页：
 * - 展示脚本引擎状态（未启用 / 加载中 / 已就绪 / 失败）与激活脚本；
 * - 支持从 JS 文件（SAF）或直链导入 LX Music 音源脚本；
 * - 脚本列表：启用 / 停用 / 删除，启用状态持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
) {
    val vm: SourceManagerViewModel = viewModel(factory = AppViewModelFactory)
    val scripts by vm.scripts.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val engineStatus by vm.engineStatus.collectAsStateWithLifecycle()
    val sourcePriority by vm.sourcePriority.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { vm.importFromUri(context, it) }
    }

    var showUrlDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UserScript?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "音源管理",
                windowSizeClass = windowSizeClass,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                EngineStatusCard(
                    status = engineStatus,
                    activeScript = scripts.find { it.id == activeId },
                )
            }
            item {
                PriorityCard(
                    current = sourcePriority,
                    onSelect = vm::setSourcePriority,
                )
            }
            item {
                ImportCard(
                    importing = importing,
                    onPickFile = {
                        fileLauncher.launch(
                            arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"),
                        )
                    },
                    onImportUrl = { showUrlDialog = true },
                )
            }
            if (scripts.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Extension,
                        title = "还没有导入音源脚本",
                        subtitle = "支持标准 LX Music 音源 JS：从文件或链接导入后点「启用」",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "已导入脚本（${scripts.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                items(scripts, key = { it.id }) { script ->
                    ScriptCard(
                        script = script,
                        active = script.id == activeId,
                        status = engineStatus,
                        onToggle = { vm.toggleActive(script.id) },
                        onDelete = { deleteTarget = script },
                    )
                }
            }
            item { TipsCard() }
        }
    }

    if (showUrlDialog) {
        UrlImportDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                vm.importFromUrl(url)
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「${target.name}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 引擎状态卡：展示当前脚本加载状态与支持平台 */
@Composable
private fun EngineStatusCard(status: ScriptEngineStatus, activeScript: UserScript?) {
    val (dotColor, title) = when (status) {
        is ScriptEngineStatus.Idle -> MaterialTheme.colorScheme.outline to "未启用音源脚本"
        is ScriptEngineStatus.Loading -> MaterialTheme.colorScheme.tertiary to "正在加载脚本…"
        is ScriptEngineStatus.Ready -> MaterialTheme.colorScheme.primary to "已就绪"
        is ScriptEngineStatus.Failed -> MaterialTheme.colorScheme.error to "加载失败"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            when (status) {
                is ScriptEngineStatus.Idle -> {
                    Text(
                        text = "导入 LX Music 音源脚本并启用后，可提供在线播放解析能力。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ScriptEngineStatus.Loading -> {
                    Text(
                        text = activeScript?.name.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ScriptEngineStatus.Ready -> {
                    activeScript?.let { script ->
                        Text(
                            text = script.name + if (script.version.isNotBlank()) " · v${script.version}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val labels = status.sources.keys.map { SOURCE_LABELS[it] ?: it }
                    Text(
                        text = if (labels.isEmpty()) "未声明可用平台" else "支持平台：${labels.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ScriptEngineStatus.Failed -> {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 解析优先级卡：脚本音源 vs Key 音源的先后与回退控制 */
@Composable
private fun PriorityCard(
    current: SourcePriority,
    onSelect: (SourcePriority) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = "解析优先级", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "播放时自定义脚本与 Key 音源的先后顺序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SourcePriority.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSelect(option) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option == current,
                        onClick = { onSelect(option) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 导入卡：文件 / 链接两个入口 */
@Composable
private fun ImportCard(
    importing: Boolean,
    onPickFile: () -> Unit,
    onImportUrl: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = "导入音源脚本", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "支持标准 LX Music 音源 JS（.js 文件或直链）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPickFile, enabled = !importing) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择 JS 文件")
                }
                OutlinedButton(onClick = onImportUrl, enabled = !importing) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从链接导入")
                }
            }
            if (importing) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "导入中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 单个脚本卡：名称 / 版本 / 描述 / 作者 + 启用停用 / 删除 */
@Composable
private fun ScriptCard(
    script: UserScript,
    active: Boolean,
    status: ScriptEngineStatus,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (script.version.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "v${script.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (script.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (script.author.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "作者：${script.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    ActiveBadge(status)
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onToggle) {
                    Text(if (active) "停用" else "启用")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除脚本",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 激活脚本状态徽标 */
@Composable
private fun ActiveBadge(status: ScriptEngineStatus) {
    val (color, label) = when (status) {
        is ScriptEngineStatus.Loading -> MaterialTheme.colorScheme.tertiary to "加载中"
        is ScriptEngineStatus.Ready -> MaterialTheme.colorScheme.primary to "已就绪"
        is ScriptEngineStatus.Failed -> MaterialTheme.colorScheme.error to "失败"
        is ScriptEngineStatus.Idle -> MaterialTheme.colorScheme.outline to "未加载"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 链接导入对话框 */
@Composable
private fun UrlImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("脚本链接") },
                    placeholder = { Text("https://example.com/lx-source.js") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "链接需直接返回脚本文本（以 .js 结尾的直链）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 使用说明卡 */
@Composable
private fun TipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = "使用说明", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TipLine("1. 导入后点「启用」即加载脚本；启用状态会持久保存，重启应用自动恢复。")
            TipLine("2. 播放解析已接入：脚本与 Key 音源的先后顺序可在上方「解析优先级」中切换。")
            TipLine("3. 请仅使用可信来源的脚本：脚本运行在隔离环境中，无法访问本地文件，但可发起网络请求。")
        }
    }
}

@Composable
private fun TipLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

/** LX 音源平台标识 → 中文名 */
private val SOURCE_LABELS = mapOf(
    "wy" to "网易云",
    "tx" to "QQ音乐",
    "qq" to "QQ音乐",
    "kw" to "酷狗",
    "kg" to "酷我",
    "mg" to "咪咕",
    "git" to "Git",
    "local" to "本地",
)
