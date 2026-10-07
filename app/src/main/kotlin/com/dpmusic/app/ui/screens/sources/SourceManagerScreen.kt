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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.ScriptKind
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.SourceChain
import com.dpmusic.app.core.model.SourceEngine
import com.dpmusic.app.core.script.MusicFreePlugin
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
    val sourceChains by vm.sourceChains.collectAsStateWithLifecycle()
    val scriptOrders by vm.scriptOrders.collectAsStateWithLifecycle()
    val scriptMemory by vm.scriptMemory.collectAsStateWithLifecycle()
    val pluginMemory by vm.pluginMemory.collectAsStateWithLifecycle()
    val residentScriptIds by vm.residentScriptIds.collectAsStateWithLifecycle()
    val residentPluginIds by vm.residentPluginIds.collectAsStateWithLifecycle()
    val crossPlatformFallback by vm.crossPlatformFallback.collectAsStateWithLifecycle()
    val probingEngine by vm.probingEngine.collectAsStateWithLifecycle()
    val engineProbeResults by vm.engineProbeResults.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val plugins by vm.pluginList.collectAsStateWithLifecycle()
    val enabledPlatforms by vm.enabledPlatforms.collectAsStateWithLifecycle()
    val testResults by vm.testResults.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()

    // 内存占用是「按需采样」的：进页面时刷一次，改动后由各操作回调再刷
    LaunchedEffect(Unit) { vm.refreshMemoryUsage() }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFromUris(context, uris)
    }

    val pluginFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importPluginsFromUris(context, uris)
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
            /* ① 总览：常驻不收纳 —— 一眼看全状态 / 解析链路 / 数量统计 / 占用 / 可用性测试入口 */
            item(key = "overview") {
                SourceOverviewCard(
                    scriptCount = scripts.size,
                    pluginCount = plugins.size,
                    chainSummary = chainSummary(sourceChains),
                    residentCount = residentScriptIds.size + residentPluginIds.size,
                    totalBytes = scriptMemory.values.sum() + pluginMemory.values.sum(),
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
                            else -> "${scripts.size} 个 · 驻留 ${residentScriptIds.size} 个"
                        },
                        expanded = lxExpanded,
                        onToggle = { lxExpanded = !lxExpanded },
                    )
                    CollapseSection(expanded = lxExpanded) {
                        SectionImportRow(
                            hint = "支持标准 LX Music 音源 JS；可一次多选多个文件，内容重复的会自动跳过",
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
                                subtitle = "导入后在下方「JS 顺序」里启用并排序，即可参与解析",
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
                        } else {
                            ScriptOrderEditor(
                                kind = ScriptKind.SCRIPT,
                                orders = scriptOrders,
                                platforms = enabledPlatforms,
                                labelOf = { id -> scripts.find { it.id == id }?.name ?: id },
                                subtitleOf = { id ->
                                    scripts.find { it.id == id }?.let { s ->
                                        listOfNotNull(
                                            s.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                            s.author.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                    }.orEmpty()
                                },
                                memoryOf = { id -> scriptMemory[id] },
                                residentIds = residentScriptIds,
                                statusOf = { id -> vm.scriptStatus(id) },
                                onMove = vm::moveScript,
                                onToggleItem = vm::toggleScript,
                                onDelete = { id -> deleteTarget = scripts.find { it.id == id } },
                                onProbe = { platform, id -> vm.probeScript(platform, id) },
                                probing = probingEngine,
                                probeResults = engineProbeResults,
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
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
                            else -> "${plugins.size} 个 · 驻留 ${residentPluginIds.size} 个"
                        },
                        expanded = pluginExpanded,
                        onToggle = { pluginExpanded = !pluginExpanded },
                    )
                    CollapseSection(expanded = pluginExpanded) {
                        SectionImportRow(
                            hint = "兼容 MusicFree 生态插件（单文件 .js，导出 search / getMediaSource 等）；可一次多选，内容重复的会自动跳过",
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
                                subtitle = "插件为单文件 .js，导入后在下方「JS 顺序」里启用并排序",
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
                        } else {
                            ScriptOrderEditor(
                                kind = ScriptKind.PLUGIN,
                                orders = scriptOrders,
                                platforms = enabledPlatforms,
                                labelOf = { id -> plugins.find { it.id == id }?.name ?: id },
                                subtitleOf = { id ->
                                    plugins.find { it.id == id }?.let { p ->
                                        listOfNotNull(
                                            p.platform.takeIf { it.isNotBlank() },
                                            p.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                        ).joinToString(" · ")
                                    }.orEmpty()
                                },
                                memoryOf = { id -> pluginMemory[id] },
                                residentIds = residentPluginIds,
                                statusOf = { id -> vm.pluginStatusOf(id) },
                                onMove = vm::moveScript,
                                onToggleItem = vm::toggleScript,
                                onDelete = { id -> deletePluginTarget = plugins.find { it.id == id } },
                                onProbe = { platform, id -> vm.probePlugin(platform, id) },
                                probing = probingEngine,
                                probeResults = engineProbeResults,
                                modifier = Modifier.staggeredEntrance(index = 1),
                            )
                        }
                    }
                }
            }

            /* ④ 解析链路（可收纳）：逐平台自定义音源顺序与启停 —— 取代旧版「全局优先级」 */
            item(key = "chains") {
                Column {
                    SectionHeader(
                        icon = Icons.Outlined.SwapVert,
                        title = "解析链路",
                        summary = chainSummary(sourceChains),
                        expanded = priorityExpanded,
                        onToggle = { priorityExpanded = !priorityExpanded },
                    )
                    CollapseSection(expanded = priorityExpanded) {
                        SourceChainEditor(
                            chains = sourceChains,
                            platforms = enabledPlatforms,
                            crossPlatformFallback = crossPlatformFallback,
                            probingEngine = probingEngine,
                            probeResults = engineProbeResults,
                            onMove = vm::moveEngine,
                            onToggleEngine = vm::toggleEngine,
                            onReset = vm::resetChain,
                            onCrossPlatformFallbackChange = vm::setCrossPlatformFallback,
                            onProbe = vm::probeEngine,
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
 * 总览卡：脚本/插件数量 + 解析链路摘要 + **实时占用** + 可用性测试入口。
 * 目的：进页面第一眼就能判断「当前音源能不能用、开了多少东西」。
 */
@Composable
private fun SourceOverviewCard(
    scriptCount: Int,
    pluginCount: Int,
    /** 链路摘要（取代旧的「全局优先级」标签——那个设置已不再驱动解析） */
    chainSummary: String,
    /** 当前**驻留**（已加载进内存）的 JS 总数 */
    residentCount: Int,
    /** 所有驻留 JS 的实时占用合计（字节） */
    totalBytes: Long,
    testing: Boolean,
    results: List<SourceTestResult>,
    resultsExpanded: Boolean,
    onToggleResults: () -> Unit,
    onRunTest: () -> Unit,
) {
    val okCount = results.count { it.success }
    // 占用偏高时给个明确警示。阈值取 64MB：正常脚本/插件单个在几 MB 量级，
    // 到这个量级说明「同时开了很多个」，在低端机上会有 OOM 风险。
    val heavy = totalBytes >= HEAVY_MEMORY_BYTES

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
                        .background(
                            if (heavy) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (heavy) "音源占用偏高" else "音源已就绪",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                MiniPill("链路 ${chainSummary}")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "脚本 $scriptCount · 插件 $pluginCount · 驻留 $residentCount · " +
                    "占用 ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (heavy) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "同时驻留的 JS 较多，每个都要一块独立的运行时内存。" +
                        "可在下方「JS 顺序」里关掉用不到的项，或删除不再使用的脚本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("LX 脚本", "$scriptCount")
                MiniStat("插件", "$pluginCount")
                MiniStat("驻留中", "$residentCount")
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

/* ---------------- ④ 解析链路（收纳内容） ---------------- */

/** 分组头摘要：网易云：概念版 › Key › 脚本 */
private fun chainSummary(chains: List<SourceChain>): String {
    val wy = chains.firstOrNull { it.platformId == MusicPlatform.WY.id } ?: return "逐平台自定义"
    val order = wy.activeEngines().joinToString(" › ") { it.label }
    return "网易云：${order.ifBlank { "未启用任何音源" }}"
}

/**
 * 解析链路编辑器：**每个平台一组**，组内是有序的引擎列表。
 *
 * 交互设计（目标：一眼看懂 + 两步改完）：
 * 1. **平台分页**：顶部三个 Tab（网易云 / QQ / 酷狗）。一屏只看一条链，
 *    避免把 3×3~4 个条目全铺开造成的「一整页开关」压迫感；Tab 上带一个小圆点
 *    标明该平台是否被改过（与默认不同）。
 * 2. **顺序即优先级**：从上到下就是尝试顺序，列表顶部有文字说明；
 *    每行右侧 `↑ ↓` 上下移动，到顶/到底自动禁用（灰掉），不会出现「点了没反应」。
 * 3. **逐项开关**：每行一个 Switch。关掉的项**仍留在原位**（位置不丢，重新打开即恢复），
 *    只是解析时跳过；最后一项不允许关闭。
 * 4. **每项可单独试听**：点行内「试听」直接跑一次真实解析，结果就地显示。
 *    （用户排完顺序最大的疑问是「这么排真的行吗」，就地验证省掉回播放页试的过程。）
 * 5. **不支持的引擎**：如「酷狗概念版」在网易云 / QQ 标签下会显示为
 *    「仅酷狗可用」且不可交互，而不是隐藏 —— 隐藏会让人以为没这个功能。
 * 6. **底部**：跨平台兜底总开关 + 恢复本平台默认。
 */
@Composable
private fun SourceChainEditor(
    chains: List<SourceChain>,
    platforms: List<MusicPlatform>,
    crossPlatformFallback: Boolean,
    probingEngine: String?,
    probeResults: Map<String, SourceTestResult>,
    onMove: (SourceChain, SourceEngine, Boolean) -> Unit,
    onToggleEngine: (SourceChain, SourceEngine) -> Unit,
    onReset: (MusicPlatform) -> Unit,
    onCrossPlatformFallbackChange: (Boolean) -> Unit,
    onProbe: (MusicPlatform, SourceEngine) -> Unit,
) {
    // 只展示**有可配置引擎 且 已启用**的平台（汽水无链路；关掉的音源不必配置）
    val shownPlatforms = remember(chains, platforms) {
        chains.mapNotNull { MusicPlatform.fromId(it.platformId) }
            .filter { it != MusicPlatform.QS && it in platforms }
    }
    var selectedId by rememberSaveable { mutableStateOf(MusicPlatform.WY.id) }
    val selected = shownPlatforms.firstOrNull { it.id == selectedId } ?: shownPlatforms.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected == null) return@Column
        val chain = chains.firstOrNull { it.platformId == selected.id } ?: return@Column

        // ① 平台分页（5 个平台 → 横向滚动，避免长名截断）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shownPlatforms.forEach { platform ->
                val active = platform.id == selected.id
                val edited = chains.firstOrNull { it.platformId == platform.id }
                    ?.let { it != SourceChain.defaultFor(platform) } == true
                FilterChip(
                    selected = active,
                    onClick = { selectedId = platform.id },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(platform.label, style = MaterialTheme.typography.labelLarge)
                            if (edited) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                 )
                             }
                         }
                     },
                )
            }
        }

        // ② 说明（顺序即优先级）
        Text(
            text = "从上到下依次尝试，失败自动落到下一项。拖动右侧 ↑↓ 调整顺序。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ③ 链路段
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            ),
        ) {
            Column {
                chain.order.forEachIndexed { index, engine ->
                    val supported = engine.supports(selected)
                    val enabled = chain.isEnabled(engine)
                    val key = "${selected.id}:${engine.id}"
                    ChainEngineRow(
                        index = index,
                        total = chain.order.size,
                        engine = engine,
                        supported = supported,
                        enabled = enabled,
                        probing = probingEngine == key,
                        probeResult = probeResults[key],
                        onMoveUp = { onMove(chain, engine, true) },
                        onMoveDown = { onMove(chain, engine, false) },
                        onToggle = { onToggleEngine(chain, engine) },
                        onProbe = { onProbe(selected, engine) },
                    )
                    if (index != chain.order.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }

        // ④ 兜底与复位
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("跨平台兜底", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "本平台全部音源失败时，到其他平台找同名曲替换播放",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = crossPlatformFallback,
                        onCheckedChange = onCrossPlatformFallbackChange,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onReset(selected) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "恢复 ${selected.label} 的默认链路",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 链路上的单行：序号 + 名称/说明 + 上下移 + 试听 + 启停开关（+ 试听结果） */
@Composable
private fun ChainEngineRow(
    index: Int,
    total: Int,
    engine: SourceEngine,
    supported: Boolean,
    enabled: Boolean,
    probing: Boolean,
    probeResult: SourceTestResult?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
) {
    val dim = !supported || !enabled
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 序号：让「从上到下 = 尝试顺序」一眼可见
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = engine.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (!supported) "仅酷狗曲库可用" else engine.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 上下移：到顶 / 到底 / 不可用时禁用（灰掉，明确表达「这里不能动」）
            IconButton(
                onClick = onMoveUp,
                enabled = supported && index > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, "上移",
                    modifier = Modifier.size(18.dp),
                    tint = if (supported && index > 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = supported && index < total - 1,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, "下移",
                    modifier = Modifier.size(18.dp),
                    tint = if (supported && index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                )
            }
            Switch(
                checked = enabled && supported,
                onCheckedChange = { onToggle() },
                enabled = supported,
                modifier = Modifier.scale(0.85f),
            )
        }

        // 试听行：不可用的引擎不给点（点了必然是「未配置」，无信息量）
        if (supported) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onProbe,
                    enabled = !probing && enabled,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (probing) "试听中…" else "试听此项",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                probeResult?.let { result ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (result.success) "✓ ${result.latencyMs}ms"
                        else "✗ ${result.detail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * **JS 顺序编辑器**（LX 脚本 / MusicFree 插件共用）：每个平台一列，列表顺序即可尝试顺序。
 *
 * 交互设计（与「解析链路」保持同一套心智模型）：
 * 1. **平台分页**：一屏只看一条链，避免把 3×N 项全铺开；
 * 2. **顺序即优先级**：每行有序号，右侧 `↑↓`；到顶/到底自动禁用变灰；
 * 3. **逐项启停**：关掉的项留在原位（重开即恢复位置），最后一项不可关；
 * 4. **每项可单独试听**：只走这一项、不兜底，就地显示 ✓/✗；
 * 5. **实时占用**：每行显示该项自己的内存占用；总览卡显示合计并在偏高时警示。
 *    这是「不设硬上限、让用户自己判断」策略的配套 —— 自由度交给用户，
 *    代价必须可见，否则用户只会在 OOM 时才发现开太多了。
 */
@Composable
private fun ScriptOrderEditor(
    kind: ScriptKind,
    orders: List<ScriptOrder>,
    platforms: List<MusicPlatform>,
    labelOf: (String) -> String,
    subtitleOf: (String) -> String,
    memoryOf: (String) -> Long?,
    residentIds: Set<String>,
    statusOf: (String) -> Any,
    onMove: (ScriptOrder, String, Boolean) -> Unit,
    onToggleItem: (ScriptOrder, String) -> Unit,
    onDelete: (String) -> Unit,
    onProbe: (MusicPlatform, String) -> Unit,
    probing: String?,
    probeResults: Map<String, SourceTestResult>,
    modifier: Modifier = Modifier,
) {
    // 只展示**已启用**平台的 JS 顺序（汽水无链路；关掉的音源不必排序）
    val shownPlatforms = remember(platforms) {
        platforms.filter { it != MusicPlatform.QS }
    }
    var selectedId by rememberSaveable(kind) { mutableStateOf(MusicPlatform.WY.id) }
    val selected = shownPlatforms.firstOrNull { it.id == selectedId } ?: shownPlatforms.first()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ① 平台分页（横向滚动，理由同 SourceChainEditor）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shownPlatforms.forEach { platform ->
                FilterChip(
                    selected = platform.id == selected.id,
                    onClick = { selectedId = platform.id },
                    label = {
                        Text(platform.label, style = MaterialTheme.typography.labelLarge)
                    },
                )
            }
        }

        val order = orders.firstOrNull { it.platformId == selected.id && it.kind == kind.id }
            ?: ScriptOrder(selected.id, kind.id, emptyList())

        Text(
            text = "从上到下依次尝试，前一项失败自动落到下一项。关掉的项仍留在原位。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            ),
        ) {
            Column {
                order.refs.forEachIndexed { index, ref ->
                    val key = "${selected.id}:${ref.id}"
                    ScriptOrderRow(
                        index = index,
                        total = order.refs.size,
                        enabled = ref.enabled,
                        label = labelOf(ref.id),
                        subtitle = subtitleOf(ref.id),
                        bytes = memoryOf(ref.id),
                        resident = ref.id in residentIds,
                        probing = probing == key,
                        probeResult = probeResults[key],
                        onMoveUp = { onMove(order, ref.id, true) },
                        onMoveDown = { onMove(order, ref.id, false) },
                        onToggle = { onToggleItem(order, ref.id) },
                        onProbe = { onProbe(selected, ref.id) },
                        onDelete = { onDelete(ref.id) },
                    )
                    if (index != order.refs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }
    }
}

/** 顺序里的一行：序号 + 名称/副标题 + 状态 + 占用 + 上下移 + 开关 + 试听 + 删除 */
@Composable
private fun ScriptOrderRow(
    index: Int,
    total: Int,
    enabled: Boolean,
    label: String,
    subtitle: String,
    bytes: Long?,
    resident: Boolean,
    probing: Boolean,
    probeResult: SourceTestResult?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 序号：让「从上到下 = 尝试顺序」一眼可见
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.width(16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题（版本 / 作者 / 平台）+ 加载状态 + 占用
                val parts = buildList {
                    if (subtitle.isNotBlank()) add(subtitle)
                    add(if (resident) "已驻留" else "未驻留")
                    bytes?.takeIf { it > 0 }?.let { add(formatBytes(it)) }
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, "上移",
                    modifier = Modifier.size(18.dp),
                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                )
            }
            IconButton(onClick = onMoveDown, enabled = index < total - 1, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, "下移",
                    modifier = Modifier.size(18.dp),
                    tint = if (index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.scale(0.85f),
            )
        }

        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onProbe,
                enabled = !probing && enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(
                    text = if (probing) "试听中…" else "试听此项",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            probeResult?.let { result ->
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (result.success) "✓ ${result.latencyMs}ms" else "✗ ${result.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 字节 → 人类可读（1 位小数；不足 1KB 显示 B） */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 占用警示阈值：64MB（见总览卡说明） */
private const val HEAVY_MEMORY_BYTES = 64L * 1024 * 1024

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


