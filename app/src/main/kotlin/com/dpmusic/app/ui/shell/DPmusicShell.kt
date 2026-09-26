package com.dpmusic.app.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dpmusic.app.AppContainer
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.ClipboardLinkEvent
import com.dpmusic.app.core.ClipboardLinkInbox
import com.dpmusic.app.core.ImportInbox
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.net.PlaylistLinkParser
import com.dpmusic.app.core.net.SongLinkParser
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.core.playback.PlayerCommand
import com.dpmusic.app.ui.components.AddToPlaylistHost
import com.dpmusic.app.ui.components.ClipboardLinkDialog
import com.dpmusic.app.ui.components.DownloadBall
import com.dpmusic.app.ui.components.GlassBackdrop
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.LocalGlassBlur
import com.dpmusic.app.ui.components.LocalGlassSampleOffset
import com.dpmusic.app.ui.components.MiniPlayerBar
import com.dpmusic.app.ui.components.PlayerSheetHost
import com.dpmusic.app.ui.components.QueueSheet
import com.dpmusic.app.ui.components.SimilarSongsSheet
import com.dpmusic.app.ui.components.rememberAddToPlaylistHost
import com.dpmusic.app.ui.navigation.AlbumDetailRoute
import com.dpmusic.app.ui.navigation.ArtistDetailRoute
import com.dpmusic.app.ui.navigation.DownloadManagerRoute
import com.dpmusic.app.ui.navigation.PlaylistRoute
import com.dpmusic.app.ui.navigation.SearchRoute
import com.dpmusic.app.ui.navigation.TogetherRoute
import com.dpmusic.app.ui.player.PlayerViewModel
import com.dpmusic.app.ui.theme.LocalBottomBarInset
import com.dpmusic.app.ui.theme.LocalGlass
import com.dpmusic.app.ui.theme.glassPanelColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
/* 弹簧参数：M3 Expressive 规格（弹性与克制兼备）；配合消费端过冲夹紧 */

private val SheetOpenSpring = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = 380f,
)
private val SheetCloseSpring = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = 380f,
)

/** 封面共享元素飞行：与播放器开合弹簧同节奏（封面飞回 / 飞出的阻尼与页面形变完全同步） */
@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverBoundsTransform = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.8f, stiffness = 380f)
}

/**
 * 应用主外壳（全设备自适应 + 全场景流体动效）：
 *
 * - Compact（竖屏手机）：底部 NavigationBar + 悬浮其上方的 Mini 播放条；
 * - Medium / Expanded（平板 / 折叠屏 / 横屏）：NavigationRail 横向展开、底部导航纵向收起，
 *   两态之间以尺寸动画平滑「变形演进」，内容区宽度随之连续阻尼过渡；
 * - Mini ⇄ 全屏播放器为同一 [Animatable] 进度驱动的流体形变（位移动画 + 圆角收敛），
 *   且封面通过 [SharedTransitionLayout] 共享元素在 Mini 条与全屏页之间无缝飞入飞出；
 * - [LookaheadScope] 提供前瞻坐标，为尺寸突变（导航形态切换 / Mini 条显隐）的阻尼动画兜底；
 * - 消费 [PlayerConnection] 抛出的命令流：展开播放页 / 队列页 / Snackbar 提示；
 * - 预测性返回：播放器展开时，系统返回手势「跟手」折叠播放器，再交还给导航栈。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DPmusicShell(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
) {
    val player = AppContainer.player
    val navController = rememberNavController()
    val playerVm: PlayerViewModel = viewModel(factory = AppViewModelFactory)

    val nowPlaying by player.nowPlaying.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val lyricsState by playerVm.lyrics.collectAsStateWithLifecycle()
    val palette by playerVm.palette.collectAsStateWithLifecycle()
    val favoriteKeys by playerVm.favoriteKeys.collectAsStateWithLifecycle()
    // Liquid Glass 需要「有细节可透」：空闲态（未播放）用最近播放的封面兜底，
    // 否则背景只剩一层平滑渐变色，玻璃面板看起来就是纯色卡片。
    val recentPlays by AppContainer.history.recent.collectAsStateWithLifecycle()
    val backdropCover = nowPlaying?.song?.coverUrl ?: recentPlays.firstOrNull()?.song?.coverUrl

    val snackbarHostState = remember { SnackbarHostState() }
    val addHost = rememberAddToPlaylistHost()
    val scope = rememberCoroutineScope()

    // 外部打开 JSON 备份：收到导入请求时切到歌单页（导入由歌单页消费后执行）
    val pendingImport by ImportInbox.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            navController.navigateToMain(PlaylistRoute)
        }
    }

    // 全屏播放器形变进度：0 = Mini 态，1 = 全屏态（拖拽 / 命令 / 返回手势共同驱动）
    val sheetProgress = remember { Animatable(0f) }
    // 播放器「存在于组合中」的判定（与 PlayerSheetHost 的移除阈值一致）：供预测性返回启用判断
    val sheetVisible by remember { derivedStateOf { sheetProgress.value > 0.002f } }
    var showQueue by remember { mutableStateOf(false) }
    var showSimilarSongs by remember { mutableStateOf(false) }

    // 封面共享元素归属：true = 在全屏播放器（sheet）内，false = 在 Mini 播放条内。
    // 展开：跟手 / 动画一开始即交还给播放器（封面尽早飞入）；
    // 收起：在「收起吸附动画启动的瞬间」就交还 Mini 条——封面立即起飞、与页面收起并行，
    // 避免此前「页面先收完、封面才蹦出来飞走」的割裂感。
    var coverInSheet by remember { mutableStateOf(false) }

    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    val isFavorite = nowPlaying?.song?.stableKey?.let { it in favoriteKeys } == true

    val expandSheet: () -> Unit = {
        coverInSheet = true
        scope.launch { sheetProgress.animateTo(1f, SheetOpenSpring) }
    }
    val collapseSheet: () -> Unit = {
        coverInSheet = false
        scope.launch { sheetProgress.animateTo(0f, SheetCloseSpring) }
    }

    // 播放器命令消费：展开播放页 / 队列页 / Snackbar 提示
    LaunchedEffect(Unit) {
        player.commands.collect { command ->
            when (command) {
                PlayerCommand.OpenPlayerSheet -> {
                    coverInSheet = true
                    launch { sheetProgress.animateTo(1f, SheetOpenSpring) }
                }
                PlayerCommand.ShowQueueSheet -> showQueue = true
                is PlayerCommand.ShowMessage -> launch { snackbarHostState.showSnackbar(command.text) }
            }
        }
    }

    // 预测性返回：播放器展开时先折叠播放器（跟手驱动），手势取消则弹回
    PredictiveBackHandler(enabled = sheetVisible) { progressFlow ->
        try {
            progressFlow.collect { event ->
                sheetProgress.snapTo((1f - event.progress).coerceIn(0.01f, 1f))
            }
            // 手势完成收起：封面立即交还 Mini 条（飞回与收起动画并行）
            coverInSheet = false
            sheetProgress.animateTo(0f, SheetCloseSpring)
        } catch (cancellation: CancellationException) {
            // 手势取消：封面留在播放器内
            coverInSheet = true
            sheetProgress.animateTo(1f, SheetOpenSpring)
            throw cancellation
        }
    }

    /* 共享元素：封面在 Mini 条 ⇄ 全屏播放器之间的无缝飞入飞出（可见性由 coverInSheet 驱动） */
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val playerCoverState = rememberSharedContentState(key = "dp-player-cover")
        val miniCoverModifier = Modifier.sharedElementWithCallerManagedVisibility(
            playerCoverState,
            !coverInSheet,
            boundsTransform = CoverBoundsTransform,
        )
        val sheetCoverModifier = Modifier.sharedElementWithCallerManagedVisibility(
            playerCoverState,
            coverInSheet,
            boundsTransform = CoverBoundsTransform,
        )

        // 前瞻坐标域：尺寸突变（导航形态切换 / Mini 条显隐）的阻尼动画基础设施
        LookaheadScope {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                /* Liquid Glass 内容层：记录「背景 + 页面内容」，供覆盖在内容之上的玻璃面板（Mini 条 / 底栏）采样 */
                val bgLayer = LocalGlassBlur.current
                val contentLayer = rememberGraphicsLayer()
                val layoutDirection = LocalLayoutDirection.current
                val density = LocalDensity.current
                val contentLayerSize = IntSize(constraints.maxWidth, constraints.maxHeight)
                var contentOrigin by remember { mutableStateOf(Offset.Zero) }
                var miniBarHeight by remember { mutableStateOf(0.dp) }
                var navBarHeight by remember { mutableStateOf(0.dp) }

                /* 玻璃风格：全局流光底（经典模式自动跳过；封面模糊层仅 Android 12+ 绘制） */
                GlassBackdrop(
                    coverUrl = backdropCover,
                    isPlaying = nowPlaying?.isPlaying == true,
                    glowColors = palette,
                    modifier = Modifier.fillMaxSize(),
                )

                // Mini 播放条上拉手势：与全屏播放器同一进度源，实现「从条到页」的连续形变
                val onMiniDragDelta: (Float) -> Unit = { delta ->
                    val next = (sheetProgress.value - delta / heightPx).coerceIn(0f, 1f)
                    // 跟手刚起步（> 0.01）即把封面交还给全屏播放器：封面随 sheet 连续上移
                    if (next > 0.01f) coverInSheet = true
                    scope.launch { sheetProgress.snapTo(next) }
                }
                val onMiniDragStop: (Float) -> Unit = { velocity ->
                    scope.launch {
                        val target = when {
                            velocity < -900f -> 1f
                            velocity > 900f -> 0f
                            sheetProgress.value > 0.5f -> 1f
                            else -> 0f
                        }
                        // 吸附收起时封面立即交还 Mini 条（飞回与收起并行）；吸附展开则保持播放器内
                        coverInSheet = target == 1f
                        sheetProgress.animateTo(target, if (target == 1f) SheetOpenSpring else SheetCloseSpring)
                    }
                }
                // 全屏播放器下拉手势：向下拖拽折叠、松手按位置 / 速度吸附
                val onSheetDragDelta: (Float) -> Unit = { delta ->
                    scope.launch { sheetProgress.snapTo((sheetProgress.value - delta / heightPx).coerceIn(0.01f, 1f)) }
                }
                val onSheetDragStop: (Float) -> Unit = { velocity ->
                    scope.launch {
                        val target = when {
                            velocity > 900f -> 0f
                            velocity < -900f -> 1f
                            sheetProgress.value < 0.7f -> 0f
                            else -> 1f
                        }
                        // 吸附收起时封面立即交还 Mini 条（飞回与收起并行）；弹回则保持播放器内
                        coverInSheet = target == 1f
                        sheetProgress.animateTo(target, if (target == 1f) SheetOpenSpring else SheetCloseSpring)
                    }
                }

                // 底部栏总高度：页面列表据此做底部留白，使内容能滚到玻璃栏下面（玻璃才「透」得出内容）
                val bottomBarInset = if (isCompact) miniBarHeight + navBarHeight else miniBarHeight

                Row(modifier = Modifier.fillMaxSize()) {
                    /* ---------- 侧边导航：非紧凑态横向平滑展开（旋转 / 折叠形变） ---------- */
                    AnimatedVisibility(
                        visible = !isCompact,
                        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                    ) {
                        MainNavigationRail(
                            navController = navController,
                            compactHeight = isCompactHeight,
                        )
                    }

                    /* ---------- 内容区 + 底部播放/导航栏（单组合，形态切换不重建） ---------- */
                    Scaffold(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // 左侧挖孔 / 状态栏横向区域已由 NavigationRail 区域承载，
                            // 消费横向 insets，避免页面内容重复避让造成空间浪费
                            .consumeWindowInsets(
                                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
                            )
                            .consumeWindowInsets(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                            ),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            Column(modifier = Modifier.animateContentSize()) {
                                CompositionLocalProvider(
                                    LocalGlassBlur provides contentLayer,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .onSizeChanged { miniBarHeight = with(density) { it.height.toDp() } }
                                            .graphicsLayer {
                                                // 夹紧过冲，保证 alpha / scale 始终处于合法区间
                                                val p = sheetProgress.value.coerceIn(0f, 1f)
                                                scaleX = 1f - 0.04f * p
                                                scaleY = 1f - 0.04f * p
                                                alpha = 1f - p
                                            },
                                    ) {
                                        MiniPlayerHost(
                                            nowPlaying = nowPlaying,
                                            onTogglePlay = { player.togglePlayPause() },
                                            onNext = { player.next() },
                                            onExpand = expandSheet,
                                            onDragDelta = onMiniDragDelta,
                                            onDragStop = onMiniDragStop,
                                            coverModifier = miniCoverModifier,
                                            modifier = if (isCompact) Modifier else Modifier.navigationBarsPadding(),
                                        )
                                    }
                                }
                                /* ---------- 底部导航：紧凑态纵向展开，其余形态收起 ---------- */
                                AnimatedVisibility(
                                    visible = isCompact,
                                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                                ) {
                                    CompositionLocalProvider(
                                        LocalGlassBlur provides contentLayer,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .onSizeChanged { navBarHeight = with(density) { it.height.toDp() } },
                                        ) {
                                            MainNavigationBar(navController = navController)
                                        }
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        // 内容区：录制「背景 + 页面内容」到内容层（root 坐标对齐），供底部玻璃面板采样。
                        // 注意：这里**不**再对内容做底部内边距 —— 内容要能滚到玻璃栏下面（由各页面列表的
                        // contentPadding.bottom = LocalBottomBarInset 承担留白），玻璃才有内容可透。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .consumeWindowInsets(padding)
                                .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                                .drawWithContent {
                                    contentLayer.record(this, layoutDirection, contentLayerSize) {
                                        translate(-contentOrigin.x, -contentOrigin.y) {
                                            bgLayer?.let { drawLayer(it) }
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                    drawLayer(contentLayer)
                                },
                        ) {
                            CompositionLocalProvider(LocalBottomBarInset provides bottomBarInset) {
                                AppNavHost(
                                    navController = navController,
                                    windowSizeClass = windowSizeClass,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                /* ---------- 全屏播放器（最上层覆盖，Mini ⇄ 全屏同一实体） ---------- */
                // 覆盖层（播放页 / 抽屉）在内容之上：把玻璃采样源指向「内容层」，
                // 这样它们透出的是真实页面内容（而非只有装饰性流光底）
                CompositionLocalProvider(LocalGlassBlur provides contentLayer) {
                PlayerSheetHost(
                    progress = sheetProgress,
                    nowPlaying = nowPlaying,
                    lyricsState = lyricsState,
                    paletteColors = palette,
                    isFavorite = isFavorite,
                    onCollapse = collapseSheet,
                    onTogglePlay = { player.togglePlayPause() },
                    onNext = { player.next() },
                    onPrevious = { player.previous() },
                    onSeek = { player.seekTo(it) },
                    onToggleFavorite = { player.toggleFavorite() },
                    onRetryLyrics = { playerVm.retryLyrics() },
                    onSelectQuality = { quality ->
                        player.setQuality(quality)
                    },
                    onOpenQueue = { showQueue = true },
                    onAddToPlaylist = { nowPlaying?.song?.let { addHost.show(listOf(it)) } },
                    onShowArtist = { artistId, artistName ->
                        collapseSheet()
                        navController.navigate(ArtistDetailRoute(artistId = artistId, name = artistName))
                    },
                    onShowAlbum = { albumId ->
                        collapseSheet()
                        navController.navigate(AlbumDetailRoute(albumId = albumId))
                    },
                    onShowSimilarSongs = {
                        collapseSheet()
                        showSimilarSongs = true
                    },
                    onToggleRepeat = {
                        val current = nowPlaying?.repeatMode ?: Player.REPEAT_MODE_OFF
                        val next = when (current) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player.setRepeatMode(next)
                    },
                    onToggleShuffle = { player.toggleShuffle() },
                    onDragDelta = onSheetDragDelta,
                    onDragEnd = onSheetDragStop,
                    coverModifier = sheetCoverModifier,
                )

                /* ---------- 播放队列（Modal 底部抽屉） ---------- */
                if (showQueue) {
                    QueueSheet(
                        queue = queue,
                        onDismiss = { showQueue = false },
                        onSongClick = { index ->
                            player.skipToQueueIndex(index)
                            showQueue = false
                        },
                    )
                }

                /* ---------- 相似歌曲（Modal 底部抽屉） ---------- */
                if (showSimilarSongs) {
                    SimilarSongsSheet(onDismiss = { showSimilarSongs = false })
                }

                /* ---------- 下载悬浮球（有活跃任务 / 刚完成时显示） ---------- */
                DownloadBall(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 96.dp),
                    onClick = { navController.navigate(DownloadManagerRoute) },
                )

                AddToPlaylistHost(addHost, snackbarHostState)
                }

                /* ---------- 剪切板链接识别弹窗 ---------- */
                val clipEvent by ClipboardLinkInbox.event.collectAsStateWithLifecycle()
                clipEvent?.let { event ->
                    when (event) {
                        is ClipboardLinkEvent.Song -> {
                            val platform = SongLinkParser.parse(event.raw)?.platform
                            ClipboardLinkDialog(
                                title = "检测到歌曲链接",
                                message = buildString {
                                    append("剪贴板中有")
                                    platform?.let { append("「${it.label}」") }
                                    append("歌曲链接，是否前往搜索页查看？")
                                },
                                confirmText = "查看",
                                onConfirm = {
                                    ClipboardLinkInbox.consumeEvent()
                                    ClipboardLinkInbox.requestSearch(event.raw)
                                    navController.navigateToMain(SearchRoute)
                                },
                                onDismiss = { ClipboardLinkInbox.consumeEvent() },
                            )
                        }

                        is ClipboardLinkEvent.Playlist -> {
                            val platform = PlaylistLinkParser.parseCandidates(event.raw).firstOrNull()?.platform
                            ClipboardLinkDialog(
                                title = "检测到歌单链接",
                                message = buildString {
                                    append("剪贴板中有")
                                    platform?.let { append("「${it.label}」") }
                                    append("歌单链接，是否导入到「我的歌单」？")
                                },
                                confirmText = "导入",
                                onConfirm = {
                                    ClipboardLinkInbox.consumeEvent()
                                    ClipboardLinkInbox.requestPlaylistImport(event.raw)
                                    navController.navigateToMain(PlaylistRoute)
                                },
                                onDismiss = { ClipboardLinkInbox.consumeEvent() },
                            )
                        }

                        is ClipboardLinkEvent.Together -> {
                            ClipboardLinkDialog(
                                title = "检测到一起听邀请",
                                message = "剪贴板中有网易云「一起听」邀请链接，是否立即前往加入房间？",
                                confirmText = "加入",
                                onConfirm = {
                                    ClipboardLinkInbox.consumeEvent()
                                    ClipboardLinkInbox.requestTogetherJoin(event.raw)
                                    navController.navigate(TogetherRoute) { launchSingleTop = true }
                                },
                                onDismiss = { ClipboardLinkInbox.consumeEvent() },
                            )
                        }
                    }
                }

                /* ---------- 一起听邀请（官方私信卡片）弹窗 ---------- */
                val inviteCard by AppContainer.togetherInviteWatcher.pending.collectAsStateWithLifecycle()
                inviteCard?.let { invite ->
                    ClipboardLinkDialog(
                        title = "收到一起听邀请",
                        message = buildString {
                            append("「")
                            append(invite.inviterName.ifBlank { "好友" })
                            append("」邀请你一起听歌，是否立即加入？")
                        },
                        confirmText = "加入",
                        onConfirm = {
                            AppContainer.togetherInviteWatcher.consume()
                            ClipboardLinkInbox.requestTogetherJoin(invite.shareLink)
                            navController.navigate(TogetherRoute) { launchSingleTop = true }
                        },
                        onDismiss = { AppContainer.togetherInviteWatcher.consume() },
                    )
                }
            }
        }
    }
}

/* ---------------- 自适应导航 ---------------- */

/** 底部导航（Compact）：选中态由当前返回栈目的地推导；容器为真实玻璃面板（模糊 + 边缘折射 + 边缘高光） */
@Composable
private fun MainNavigationBar(navController: NavHostController) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val glass = LocalGlass.current

    if (glass.enabled) {
        // 玻璃模式：导航栏本体透明，由 GlassSurface 提供玻璃底（含模糊 / 折射 / 边缘高光）
        GlassSurface(
            shape = RectangleShape,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            NavigationBar(containerColor = Color.Transparent) {
                MainNavigationItems(navController, currentDestination)
            }
        }
    } else {
        NavigationBar(containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer)) {
            MainNavigationItems(navController, currentDestination)
        }
    }
}

/** 底部导航项（两种容器共用） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.MainNavigationItems(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    mainNavItems.forEach { item ->
        NavigationBarItem(
            selected = currentDestination.isOnRoute(item.route),
            onClick = { navController.navigateToMain(item.route) },
            icon = { Icon(item.icon, contentDescription = item.label) },
            label = { Text(item.label) },
        )
    }
}

/** 侧边导航（Medium / Expanded）：高窗口显示品牌标识；矮窗口（横屏手机等）精简 header 保证全部导航项可见 */
@Composable
private fun MainNavigationRail(
    navController: NavHostController,
    compactHeight: Boolean,
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    NavigationRail(
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
        header = {
            if (!compactHeight) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 12.dp)
                        .size(44.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
    ) {
        mainNavItems.forEach { item ->
            NavigationRailItem(
                selected = currentDestination.isOnRoute(item.route),
                onClick = { navController.navigateToMain(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

/* ---------------- 工具函数 ---------------- */

/** 当前目的地（含其父层级）是否落在目标路由上（兼容带参路由的 pattern 前缀） */
private fun NavDestination?.isOnRoute(route: Any): Boolean {
    val routeName = route::class.qualifiedName ?: return false
    return this?.hierarchy?.any { destination ->
        val pattern = destination.route ?: return@any false
        pattern == routeName || pattern.startsWith("$routeName/")
    } == true
}

/**
 * 主导航跳转（底部导航 / 侧边导航共用）：
 * - `launchSingleTop`：同一个 Tab 不重复入栈；
 * - `popUpTo(start)` **不保存状态**：切 Tab 时把上一个 Tab 的「子页面」
 *   （设置 / 日志 / 音源管理 / 同步 / 下载管理 / 各种详情页）全部弹出，
 *   切回来就是该 Tab 的根页面。
 *
 * 注意：之前用 `saveState = true` + `restoreState = true`（官方多 Tab 示例写法）会
 * **连同子页面一起恢复**，于是「主页 → 打开设置 → 切到搜索 → 切回主页」时显示的
 * 还是设置页，操作上很别扭。Tab 页面自身的状态（滚动位置等）由仍在栈中的根
 * destination 保留，不受影响。
 */
internal fun NavHostController.navigateToMain(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}

/* ---------------- Mini 播放条宿主（注入上拉手势 + 封面共享元素） ---------------- */

@Composable
private fun MiniPlayerHost(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStop: (Float) -> Unit,
    coverModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    MiniPlayerBar(
        nowPlaying = nowPlaying,
        onTogglePlay = onTogglePlay,
        onNext = onNext,
        onExpand = onExpand,
        coverModifier = coverModifier,
        modifier = modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta -> onDragDelta(delta) },
            onDragStopped = { velocity -> onDragStop(velocity) },
        ),
    )
}