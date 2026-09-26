package com.dpmusic.app.ui.screens.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.script.MusicFreePlugin
import com.dpmusic.app.core.script.PluginEngineStatus
import com.dpmusic.app.core.script.ScriptEngineStatus
import com.dpmusic.app.core.script.SourceTestResult
import com.dpmusic.app.core.script.UserScript
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.staggeredEntrance
import com.dpmusic.app.ui.theme.LocalBottomBarInset
import com.dpmusic.app.ui.theme.glassPanelColor

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
    val plugins by vm.pluginList.collectAsStateWithLifecycle()
    val activePluginId by vm.activePluginId.collectAsStateWithLifecycle()
    val pluginStatus by vm.pluginStatus.collectAsStateWithLifecycle()
    val testResults by vm.testResults.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { vm.importFromUri(context, it) }
    }

    val pluginFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { vm.importPluginFromUri(context, it) }
    }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showPluginUrlDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UserScript?>(null) }
    var deletePluginTarget by remember { mutableStateOf<MusicFreePlugin?>(null) }

    // 收纳状态：总览常驻；脚本默认展开（最常用），其余默认收起，用户选择会被记住
    var lxExpanded by rememberSaveable { mutableStateOf(true) }
    var pluginExpanded by rememberSaveable { mutableStateOf(false) }
    var priorityExpanded by rememberSaveable { mutableStateOf(false) }
    var tipsExpanded by rememberSaveable { mutableStateOf(false) }
    var testExpanded by rememberSaveable { mutableStateOf(false) }

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
            contentPadding = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                bottom = 16.dp + LocalBottomBarInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            /* ① 总览：常驻不收纳 —— 一眼看全状态 / 激活音源 / 数量统计 / 可用性测试入口 */
            item(key = "overview") {
                SourceOverviewCard(
                    status = engineStatus,
                    activeScript = scripts.find { it.id == activeId },
                    scriptCount = scripts.size,
                    pluginCount = plugins.size,
                    activePluginName = plugins.find { it.id == activePluginId }?.name,
                    priority = sourcePriority,
                    testing = testing,
                    results = testResults,
                    resultsExpanded = testExpanded,
                    onToggleResults = { testExpanded = !testExpanded },
                    onRunTest = {
                        testExpanded = true
                        vm.runSourceTests()
                    },
                )
            }

            /* ② LX 音源脚本（可收纳）
             * 分组头与内容放在同一个 item 里 —— 展开/收起时高度平滑过渡。
             * 拆成两个 item 的话，LazyColumn 的 item 间距会在收起状态留下多余空隙。 */
            item(key = "lx") {
                Column {
                    SectionHeader(
                        icon = Icons.Outlined.Code,
                        title = "LX 音源脚本",
                        summary = when {
                            scripts.isEmpty() -> "尚未导入 · 点开可导入 .js 或直链"
                            else -> buildString {
                                append("${scripts.size} 个")
                                scripts.find { it.id == activeId }?.let { append(" · 启用中：${it.name}") }
                                    ?: append(" · 未启用")
                            }
                        },
                        expanded = lxExpanded,
                        onToggle = { lxExpanded = !lxExpanded },
                    )
                    CollapseSection(expanded = lxExpanded) {
                        SectionImportRow(
                            hint = "支持标准 LX Music 音源 JS（.js 文件或直链）",
                            primaryLabel = "选择 JS 文件",
                            importing = importing,
                            onPickFile = {
                                fileLauncher.launch(
                                    arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"),
                                )
                            },
                            onImportUrl = { showUrlDialog = true },
                            modifier = Modifier.staggeredEntrance(index = 0),
                        )
                        if (scripts.isEmpty()) {
                            SectionEmptyHint(
                                icon = Icons.Outlined.Extension,
                                title = "还没有导入音源脚本",
                                subtitle = "从文件或链接导入后点「启用」，即加载脚本提供解析能力",
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
                        } else {
                            scripts.forEachIndexed { index, script ->
                                ScriptCard(
                                    script = script,
                                    active = script.id == activeId,
                                    status = engineStatus,
                                    onToggle = { vm.toggleActive(script.id) },
                                    onDelete = { deleteTarget = script },
                                    modifier = Modifier.staggeredEntrance(
                                        index = index + 1,
                                        enabled = index < 12,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            /* ③ MusicFree 插件（可收纳） */
            item(key = "plugin") {
                Column {
                    SectionHeader(
                        icon = Icons.Outlined.Extension,
                        title = "MusicFree 插件",
                        summary = when {
                            plugins.isEmpty() -> "尚未导入 · Key 与脚本都失败时的最后兜底"
                            else -> buildString {
                                append("${plugins.size} 个")
                                plugins.find { it.id == activePluginId }?.let { append(" · 启用中：${it.name}") }
                                    ?: append(" · 未启用")
                            }
                        },
                        expanded = pluginExpanded,
                        onToggle = { pluginExpanded = !pluginExpanded },
                    )
                    CollapseSection(expanded = pluginExpanded) {
                        SectionImportRow(
                            hint = "兼容 MusicFree 生态插件（单文件 .js，导出 search / getMediaSource 等）",
                            primaryLabel = "选择 JS 插件",
                            importing = importing,
                            onPickFile = {
                                pluginFileLauncher.launch(
                                    arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"),
                                )
                            },
                            onImportUrl = { showPluginUrlDialog = true },
                            modifier = Modifier.staggeredEntrance(index = 0),
                        )
                        if (plugins.isEmpty()) {
                            SectionEmptyHint(
                                icon = Icons.Outlined.Extension,
                                title = "还没有导入插件",
                                subtitle = "插件为单文件 .js，导入后点「启用」即可参与解析",
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
                        } else {
                            plugins.forEachIndexed { index, plugin ->
                                PluginCard(
                                    plugin = plugin,
                                    active = plugin.id == activePluginId,
                                    status = pluginStatus,
                                    onToggle = { vm.togglePlugin(plugin.id) },
                                    onDelete = { deletePluginTarget = plugin },
                                    modifier = Modifier.staggeredEntrance(
                                        index = index + 1,
                                        enabled = index < 12,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            /* ④ 解析优先级（可收纳） */
            item(key = "priority") {
                Column {
                    SectionHeader(
                        icon = Icons.Outlined.SwapVert,
                        title = "解析优先级",
                        summary = "当前：${sourcePriority.label}",
                        expanded = priorityExpanded,
                        onToggle = { priorityExpanded = !priorityExpanded },
                    )
                    CollapseSection(expanded = priorityExpanded) {
                        PriorityOptions(
                            current = sourcePriority,
                            onSelect = vm::setSourcePriority,
                        )
                    }
                }
            }

            /* ⑤ 使用说明（可收纳，默认收起） */
            item(key = "tips") {
                Column {
                    SectionHeader(
                        icon = Icons.Outlined.Info,
                        title = "使用说明",
                        summary = "导入 / 启用 / 安全提示",
                        expanded = tipsExpanded,
                        onToggle = { tipsExpanded = !tipsExpanded },
                    )
                    CollapseSection(expanded = tipsExpanded) {
                        TipsContent()
                    }
                }
            }
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

    if (showPluginUrlDialog) {
        UrlImportDialog(
            title = "从链接导入插件",
            label = "插件链接",
            placeholder = "https://example.com/musicfree-plugin.js",
            hint = "支持 MusicFree 生态插件（.js 直链）",
            onDismiss = { showPluginUrlDialog = false },
            onConfirm = { url ->
                showPluginUrlDialog = false
                vm.importPluginFromUrl(url)
            },
        )
    }

    deletePluginTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletePluginTarget = null },
            title = { Text("删除插件") },
            text = { Text("确定删除「${target.name}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removePlugin(target.id)
                    deletePluginTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePluginTarget = null }) { Text("取消") }
            },
        )
    }
}
/* ---------------- ① 总览卡（常驻，不收纳） ---------------- */

/**
 * 总览卡：状态灯 + 激活音源 + 支持平台 + 数量统计 + 可用性测试入口。
 * 目的：进页面第一眼就能判断「当前音源能不能用」，不用往下翻。
 */
@Composable
private fun SourceOverviewCard(
    status: ScriptEngineStatus,
    activeScript: UserScript?,
    scriptCount: Int,
    pluginCount: Int,
    activePluginName: String?,
    priority: SourcePriority,
    testing: Boolean,
    results: List<SourceTestResult>,
    resultsExpanded: Boolean,
    onToggleResults: () -> Unit,
    onRunTest: () -> Unit,
) {
    val (dotColor, title) = when (status) {
        is ScriptEngineStatus.Idle -> MaterialTheme.colorScheme.outline to "未启用音源脚本"
        is ScriptEngineStatus.Loading -> MaterialTheme.colorScheme.tertiary to "正在加载脚本…"
        is ScriptEngineStatus.Ready -> MaterialTheme.colorScheme.primary to "已就绪"
        is ScriptEngineStatus.Failed -> MaterialTheme.colorScheme.error to "加载失败"
    }
    val okCount = results.count { it.success }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        strong = true,
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
                Spacer(Modifier.weight(1f))
                MiniPill("优先级 ${priority.label}")
            }
            Spacer(Modifier.height(6.dp))
            when (status) {
                is ScriptEngineStatus.Idle -> {
                    Text(
                        text = "导入 LX Music 音源脚本并启用后，可获得在线播放解析能力。",
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
                    Spacer(Modifier.height(6.dp))
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

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("LX 脚本", "$scriptCount")
                MiniStat("插件", "$pluginCount")
                MiniStat("启用中", activeScript?.name ?: activePluginName ?: "无")
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRunTest, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("测试中")
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("可用性测试")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (results.isEmpty()) "尚未测试" else "$okCount / ${results.size} 项可用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (results.isEmpty()) {
                            "逐项实测各音源链路（含真实解析请求）"
                        } else {
                            "点右侧箭头查看逐项明细"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (results.isNotEmpty()) {
                    IconButton(onClick = onToggleResults) {
                        Icon(
                            imageVector = if (resultsExpanded) {
                                Icons.Outlined.ExpandLess
                            } else {
                                Icons.Outlined.ExpandMore
                            },
                            contentDescription = "测试明细",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            CollapseSection(
                expanded = resultsExpanded && results.isNotEmpty(),
                spacing = 4.dp,
            ) {
                results.forEachIndexed { index, result ->
                    SourceTestRow(
                        result = result,
                        modifier = Modifier.staggeredEntrance(index = index, enabled = index < 12),
                    )
                }
            }
        }
    }
}

/** 小胶囊标签（总览右上角） */
@Composable
private fun MiniPill(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 统计小方块（总览中部） */
@Composable
private fun MiniStat(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ---------------- ② 可收纳分组：分组头 ---------------- */

/**
 * 收纳分组的内容容器：展开/收起走「高度 + 透明度」联动动画（M3 标准时长）。
 *
 * 设计要点：
 * - 顶部间距放在动画内部（[spacing]）——收起时内容与间距一起消失，分组头之间不会留下多余空隙；
 * - 进入用 260ms 高度 + 60ms 延迟的淡入（先撑开再显影，避免内容"贴边弹出"）；
 * - 退出更快（200ms 高度 + 120ms 淡出），符合「收起来要干脆、展开要舒展」的手感；
 * - 收起后 `AnimatedVisibility` 不再组合子树，零额外开销。
 */
@Composable
private fun CollapseSection(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 60)),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = tween(durationMillis = 120)),
    ) {
        Column {
            Spacer(Modifier.height(spacing))
            Column(verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    }
}

/**
 * 分组头（收纳开关）：图标 + 标题 + 摘要 + 展开箭头。
 * 整行可点，触控区域足够大；箭头带旋转动画给出状态反馈。
 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "sectionChevron",
    )
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                if (summary.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------- ③ 分组内的导入行 ---------------- */

@Composable
private fun SectionImportRow(
    hint: String,
    primaryLabel: String,
    importing: Boolean,
    onPickFile: () -> Unit,
    onImportUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPickFile, enabled = !importing) {
                    Icon(
                        imageVector = Icons.Outlined.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(primaryLabel)
                }
                OutlinedButton(onClick = onImportUrl, enabled = !importing) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
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

/** 分组内空态（紧凑版，替代整屏空状态） */
@Composable
private fun SectionEmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---------------- ④ 解析优先级（收纳内容） ---------------- */

/** 解析优先级：脚本音源 vs Key 音源的先后与回退控制 */
@Composable
private fun PriorityOptions(
    current: SourcePriority,
    onSelect: (SourcePriority) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            SourcePriority.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

/** 单个脚本卡：名称 / 版本 / 描述 / 作者 + 启用停用 / 删除 */
@Composable
private fun ScriptCard(
    script: UserScript,
    active: Boolean,
    status: ScriptEngineStatus,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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

/** 链接导入对话框（脚本 / 插件共用） */
@Composable
private fun UrlImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String = "从链接导入",
    label: String = "脚本链接",
    placeholder: String = "https://example.com/lx-source.js",
    hint: String = "链接需直接返回脚本文本（以 .js 结尾的直链）",
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
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

/** 使用说明（收纳内容：标题由分组头承担） */
@Composable
private fun TipsContent() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            TipLine("1. 导入后点「启用」即加载脚本；启用状态会持久保存，重启应用自动恢复。")
            TipLine("2. 播放解析已接入：脚本与 Key 音源的先后顺序可在「解析优先级」中切换。")
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

/* ---------------- 音源可用性测试 ---------------- */

/* 可用性测试已合并进总览卡（SourceOverviewCard + SourceTestRow） */

@Composable
private fun SourceTestRow(
    result: SourceTestResult,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (result.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.sourceName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (result.latencyMs > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${result.latencyMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


/** 单个插件卡：名称 / 平台 / 版本 / 能力 + 启用停用 / 删除 */
@Composable
private fun PluginCard(
    plugin: MusicFreePlugin,
    active: Boolean,
    status: PluginEngineStatus,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (plugin.version.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "v${plugin.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val subtitle = buildString {
                if (plugin.platform.isNotBlank()) append("平台：${plugin.platform}")
                if (plugin.author.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("作者：${plugin.author}")
                }
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 已就绪时展示插件实际能力（由插件自报）
            val readyMeta = (status as? PluginEngineStatus.Ready)
                ?.takeIf { it.pluginId == plugin.id }?.meta
            if (readyMeta != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (readyMeta.methods.isEmpty()) {
                        "未声明任何能力"
                    } else {
                        "能力：${readyMeta.methods.joinToString(" · ")}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (readyMeta.userVariables.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "需配置：${readyMeta.userVariables.joinToString("、") { it.name }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (active && status is PluginEngineStatus.Failed) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    ActiveBadge(
                        when (status) {
                            is PluginEngineStatus.Loading -> ScriptEngineStatus.Loading(plugin.id)
                            is PluginEngineStatus.Ready -> ScriptEngineStatus.Ready(plugin.id, emptyMap())
                            is PluginEngineStatus.Failed -> ScriptEngineStatus.Failed(plugin.id, status.message)
                            is PluginEngineStatus.Idle -> ScriptEngineStatus.Idle
                        },
                    )
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
                        contentDescription = "删除插件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
