package com.dpmusic.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.ui.components.AccountPlaylistMiniCard
import com.dpmusic.app.ui.components.CoverArt
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.InlineLoading
import com.dpmusic.app.ui.components.RecognitionSheet
import com.dpmusic.app.ui.components.pressScale
import com.dpmusic.app.ui.motion.DPMotion
import com.dpmusic.app.ui.theme.NcmBrandColor
import com.dpmusic.app.ui.theme.QqBrandColor
import java.util.Calendar
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset
import com.dpmusic.app.ui.util.rememberDpHaptics

/**
 * 主页：
 * - 时段问候 + 「继续收听」（最近播放置顶曲目）；
 * - 账号内容区：登录后优先展示网易云 / QQ 音乐专属内容，未登录时展示连接引导；
 * - 本地收藏双卡：我的喜欢 / 我的歌单（实时统计）；
 * - 平台热榜预览（每平台横向滑动，点击直达榜单详情）；
 * - 顶栏「听歌识曲」入口（环境音 → 双引擎识别：酷狗 + 网易云）；
 * - 「播放流转」入口：把当前播放队列与精确进度交给同一 Wi-Fi 下的另一台
 *   DPmusic 接着播（发现与传输复用 LocalSend 协议，载荷仅本应用可识别）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    onOpenMine: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenRankDetail: (RankSummary) -> Unit,
    onOpenDaily: () -> Unit,
    onOpenNcmPlaylists: () -> Unit,
    onOpenNcmPlaylist: (id: String, title: String) -> Unit,
    onOpenQqPlaylists: () -> Unit,
    onOpenQqPlaylist: (id: String, title: String) -> Unit,
    onOpenQqRecommend: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favoriteCount by vm.favoriteCount.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val toplists by vm.toplists.collectAsStateWithLifecycle()
    val toplistsLoading by vm.toplistsLoading.collectAsStateWithLifecycle()
    val ncmLoggedIn by vm.ncmLoggedIn.collectAsStateWithLifecycle()
    val ncmPlaylists by vm.ncmPlaylists.collectAsStateWithLifecycle()
    val ncmLoading by vm.ncmLoading.collectAsStateWithLifecycle()
    val likedPlaylistId by vm.likedPlaylistId.collectAsStateWithLifecycle()
    val ncmMessage by vm.ncmMessage.collectAsStateWithLifecycle()
    val qqLoggedIn by vm.qqLoggedIn.collectAsStateWithLifecycle()
    val qqPlaylists by vm.qqPlaylists.collectAsStateWithLifecycle()
    val qqLoading by vm.qqLoading.collectAsStateWithLifecycle()
    val qqLikedTid by vm.qqLikedTid.collectAsStateWithLifecycle()
    val qqMessage by vm.qqMessage.collectAsStateWithLifecycle()
    // 当前播放态：供「继续收听」卡片的按钮与迷你播放条保持同步
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    // 汽水音源开关：决定「场景电台」入口是否出现（关掉则整个入口收敛）
    val settingsState by AppContainer.settings.settings.collectAsStateWithLifecycle()
    val qishuiEnabled = settingsState.qishuiEnabled

    val compactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    // 听歌识曲 Sheet 开关
    var showRecognize by remember { mutableStateOf(false) }
    // 汽水电台/歌单弹窗开关
    var showQishui by remember { mutableStateOf(false) }

    // 播放流转 Sheet 开关
    var showCast by remember { mutableStateOf(false) }

    // 我喜欢的音乐：歌单 id 就绪后自动跳转
    var pendingLiked by remember { mutableStateOf(false) }
    // QQ 我喜欢：tid 就绪后自动跳转
    var pendingQqLiked by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(likedPlaylistId, pendingLiked) {
        val id = likedPlaylistId
        if (pendingLiked && !id.isNullOrBlank()) {
            pendingLiked = false
            onOpenNcmPlaylist(id, "我喜欢的音乐")
        }
    }
    LaunchedEffect(qqLikedTid, pendingQqLiked) {
        val tid = qqLikedTid
        if (pendingQqLiked && !tid.isNullOrBlank()) {
            pendingQqLiked = false
            onOpenQqPlaylist(tid, "我喜欢")
        }
    }
    LaunchedEffect(ncmMessage) {
        ncmMessage?.let {
            snackbarHostState.showSnackbar(it)
            pendingLiked = false
            vm.consumeNcmMessage()
        }
    }
    LaunchedEffect(qqMessage) {
        qqMessage?.let {
            snackbarHostState.showSnackbar(it)
            pendingQqLiked = false
            vm.consumeQqMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DpTopAppBar(
                title = "主页",
                windowSizeClass = windowSizeClass,
                actions = {
                    // 播放流转：从主页网格上提到顶栏（紧邻麦克风），属「操作」而非「内容区块」
                    IconButton(onClick = { showCast = true }) {
                        Icon(Icons.Outlined.Cast, contentDescription = "播放流转")
                    }
                    IconButton(onClick = { showRecognize = true }) {
                        Icon(Icons.Outlined.Mic, contentDescription = "听歌识曲")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = if (compactHeight) 8.dp else 16.dp, end = 16.dp, bottom = 24.dp + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 12.dp else 16.dp),
        ) {
            item(key = "greeting") {
                GreetingHeader()
            }

            val continueSong = recent.firstOrNull()?.song
            if (continueSong != null) {
                item(key = "continue") {
                    // 与迷你播放条同步：同一首歌时按钮显示「暂停」，点击即切换
                    ContinueCard(
                        title = continueSong.title,
                        artist = continueSong.artist,
                        coverUrl = continueSong.coverUrl,
                        isPlaying = nowPlaying?.song?.stableKey == continueSong.stableKey &&
                            nowPlaying?.isPlaying == true,
                        onPlay = { vm.toggleOrResume(continueSong) },
                    )
                }
            }

            if (ncmLoggedIn) {
                item(key = "ncm") {
                    NcmSection(
                        playlists = ncmPlaylists,
                        loading = ncmLoading,
                        onOpenDaily = onOpenDaily,
                        onStartFm = vm::startFm,
                        onOpenLiked = {
                            val id = likedPlaylistId
                            if (!id.isNullOrBlank()) {
                                onOpenNcmPlaylist(id, "我喜欢的音乐")
                            } else {
                                vm.loadNcmContent()
                                pendingLiked = true
                            }
                        },
                        onOpenMyPlaylists = onOpenNcmPlaylists,
                        onOpenPlaylist = { pl -> onOpenNcmPlaylist(pl.id, pl.name) },
                    )
                }
            }
            if (qqLoggedIn) {
                item(key = "qq") {
                    QqSection(
                        playlists = qqPlaylists,
                        loading = qqLoading,
                        onOpenRecommend = onOpenQqRecommend,
                        onOpenLiked = {
                            val tid = qqLikedTid
                            if (!tid.isNullOrBlank()) {
                                onOpenQqPlaylist(tid, "我喜欢")
                            } else {
                                vm.loadQqContent()
                                pendingQqLiked = true
                            }
                        },
                        onOpenMyPlaylists = onOpenQqPlaylists,
                        onOpenPlaylist = { pl -> onOpenQqPlaylist(pl.tid, pl.name) },
                    )
                }
            }

            // 账号引导（仅未登录）：做成一条**细横幅**贴顶，不占整块大卡 —— 它是「顺带的提示」而非功能
            if (!ncmLoggedIn && !qqLoggedIn) {
                item(key = "account_nudge") {
                    AccountNudgeBanner(onClick = onOpenSettings)
                }
            }

            // 快捷入口：把原先「两张并排卡 + 播放流转大卡 + 汽水大卡」四块**收纳**为一个 2×2 网格。
            // 目的：降低主页纵向铺开感，同类信息聚合成一个视觉单元，一眼扫完。
            // 汽水音源关闭时「场景电台」整体不出现 —— 入口不给，也就不存在点了才发现不可用的死角。
            item(key = "quick_grid") {
                QuickAccessGrid(
                    favoriteCount = favoriteCount,
                    playlistCount = playlists.size,
                    qishuiEnabled = qishuiEnabled,
                    onOpenFavorites = onOpenMine,
                    onOpenPlaylists = onOpenPlaylists,
                    onOpenQishui = { showQishui = true },
                )
            }

            // 热榜速览：原先 5 个平台各占一整段纵向铺开（滚动很长、重复度极高），
            // 现收拢为**单区块 + 平台分段**，同一时刻只展示一个平台的榜单。
            if (toplists.any { it.value.isNotEmpty() }) {
                item(key = "hot_ranks") {
                    HotRankSection(
                        toplists = toplists,
                        onOpenRankDetail = onOpenRankDetail,
                    )
                }
            } else if (toplistsLoading) {
                item(key = "loading") {
                    InlineLoading()
                }
            }
        }
    }

    if (showRecognize) {
        RecognitionSheet(
            onDismiss = { showRecognize = false },
            onPlay = { song ->
                vm.playSong(song)
                showRecognize = false
            },
        )
    }

    if (showCast) {
        PlaybackCastSheet(onDismiss = { showCast = false })
    }
    if (showQishui) {
        com.dpmusic.app.ui.components.QishuiPlaylistsDialog(onDismiss = { showQishui = false })
    }
}

/* ---------------- 问候区 ---------------- */

@Composable
private fun GreetingHeader() {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }
    Column {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "今天想听点什么？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------- 继续收听 ---------------- */

/**
 * 「继续收听」卡片。
 *
 * 播放按钮与底部迷你播放条**保持同步**（同一个动作、同一套视觉与反馈）：
 * - 图标随 [isPlaying] 在 `Pause` / `PlayArrow` 间切换；
 * - 点击走 [onPlay]（当前曲 → 播放/暂停；否则起播）；
 * - 按压有触觉反馈，与迷你条一致。
 */
@Composable
private fun ContinueCard(
    title: String,
    artist: String,
    coverUrl: String,
    isPlaying: Boolean,
    onPlay: () -> Unit,
) {
    val haptics = rememberDpHaptics()
    GlassSurface(
        onClick = { haptics.click(); onPlay() },
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                url = coverUrl,
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "继续收听",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = { haptics.click(); onPlay() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                )
            }
        }
    }
}

/* ---------------- 紧凑入口 ---------------- */

@Composable
private fun QuickEntry(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------- 账号引导（细横幅：仅提示，不占整块大卡） ---------------- */

/**
 * 未登录时的账号引导。
 *
 * 设计取舍：早期实现是一块与「继续收听」「播放流转」同样厚重的**大卡**，
 * 但它的信息量（一句提示）远小于视觉体量，把主页撑得很散。
 * 现改为**细横幅**：一行提示 + 一个轻量入口，视觉权重降到「提示级」，
 * 与下方的功能网格形成清晰的主次节奏。
 */
@Composable
private fun AccountNudgeBanner(onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "连接音乐账号，解锁每日推荐与私人FM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "去连接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/* ---------------- 快捷入口网格（收纳 4 个平级入口） ---------------- */

/**
 * 快捷入口网格。
 *
 * 收纳动机：主页原先有四个**平级**入口纵向排开、体量不一，既拉长滚动又让人分不清主次。
 *
 * **只保留「内容型」入口**：本地收藏 / 本地歌单 / 场景电台。
 * 「播放流转」是**操作**而非内容区块，已上提到顶栏（紧邻麦克风）——放进网格会与
 * 内容入口争夺注意力，且它本身没有可展示的状态。
 *
 * **列数随可用项数自适应**，避免出现孤立的整宽卡（那正是要消除的「体量失衡」）：
 * - 3 项（汽水开） → 一行 3 格等宽；
 * - 2 项（汽水关） → 一行 2 格等宽。
 */
@Composable
private fun QuickAccessGrid(
    favoriteCount: Int,
    playlistCount: Int,
    qishuiEnabled: Boolean,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenQishui: () -> Unit,
) {
    val haptics = rememberDpHaptics()
    val items = buildList {
        add(QuickAction(Icons.Outlined.FavoriteBorder, "本地收藏", "$favoriteCount 首", onOpenFavorites))
        add(QuickAction(Icons.AutoMirrored.Outlined.QueueMusic, "本地歌单", "$playlistCount 个", onOpenPlaylists))
        if (qishuiEnabled) {
            add(QuickAction(Icons.Outlined.Radio, "场景电台", "汽水 · 45 个场景", onOpenQishui))
        }
    }
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { item ->
                QuickActionCard(
                    icon = item.icon,
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = {
                        haptics.click()
                        item.onClick()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 快捷入口的一项（供网格按列数切分） */
private data class QuickAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/**
 * 网格内单个快捷入口：图标 + 标题 + 一行副信息。
 * 副信息一律单行省略，保证四格高度一致、严格对齐。
 */
@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------- 热榜速览（单区块 + 平台分段） ---------------- */

/**
 * 热榜速览：把原先「每个平台一整段」的纵向堆叠，收拢为**一个区块**。
 *
 * 交互：顶部一排平台分段（复用界面已有的分段控件语言），切换即换榜单内容；
 * 横向滑动的榜单卡不滚动时静止，切换分段带淡入位移，避免生硬替换。
 */
@Composable
private fun HotRankSection(
    toplists: Map<MusicPlatform, List<RankSummary>>,
    onOpenRankDetail: (RankSummary) -> Unit,
) {
    // 只有确实有榜单的平台才出现在分段里（B 站等无榜单的平台自动隐藏）
    val available = remember(toplists) { MusicPlatform.entries.filter { toplists[it].orEmpty().isNotEmpty() } }
    if (available.isEmpty()) return
    var selected by rememberSaveable(available) { mutableStateOf(available.first()) }
    // 榜单数据后来居上（先空后有）时，纠正失效选择
    val current = if (selected in available) selected else available.first()
    val ranks = toplists[current].orEmpty()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "热榜速览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "点卡片看完整榜单",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (available.size > 1) {
            PlatformSegmentedRow(
                platforms = available,
                selected = current,
                onSelect = { selected = it },
            )
            Spacer(Modifier.height(12.dp))
        }
// 切换分段时榜单卡淡入上浮：内容替换有过渡，不生硬
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (fadeIn(tween(DPMotion.Medium, easing = DPMotion.Decelerate)) +
                    slideInVertically(
                        animationSpec = tween(DPMotion.Medium, easing = DPMotion.Decelerate),
                        initialOffsetY = { it / 8 },
                    )).togetherWith(fadeOut(tween(DPMotion.Fast)))
            },
            label = "rankSwitch",
        ) { platform ->
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(toplists[platform].orEmpty(), key = { "${platform.id}:${it.id}" }) { rank ->
                    RankMiniCard(rank = rank, onClick = { onOpenRankDetail(rank) })
                }
            }
        }
    }
}

/** 平台分段：胶囊描边 + 选中实心，切换带弹簧与轻触觉 */
@Composable
private fun PlatformSegmentedRow(
    platforms: List<MusicPlatform>,
    selected: MusicPlatform,
    onSelect: (MusicPlatform) -> Unit,
) {
    val haptics = rememberDpHaptics()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        platforms.forEach { platform ->
            val active = platform.id == selected.id
            Surface(
                onClick = { haptics.click(); onSelect(platform) },
                shape = RoundedCornerShape(50),
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = platform.shortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/* ---------------- 平台热榜 ---------------- */

@Composable
private fun RankMiniCard(
    rank: RankSummary,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(132.dp)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        CoverArt(
            url = rank.coverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = rank.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ---------------- 网易云账号区（需登录） ---------------- */

@Composable
private fun NcmSection(
    playlists: List<NcmPlaylist>,
    loading: Boolean,
    onOpenDaily: () -> Unit,
    onStartFm: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenMyPlaylists: () -> Unit,
    onOpenPlaylist: (NcmPlaylist) -> Unit,
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
            Text(
                text = "登录专属 · 每日更新",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry(
                icon = Icons.Outlined.CalendarMonth,
                title = "每日推荐",
                onClick = onOpenDaily,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.Outlined.Radio,
                title = "私人FM",
                onClick = onStartFm,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.Outlined.FavoriteBorder,
                title = "我喜欢",
                onClick = onOpenLiked,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                title = "我的歌单",
                onClick = onOpenMyPlaylists,
                modifier = Modifier.weight(1f),
            )
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "为你推荐",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { it.id }) { pl ->
                    AccountPlaylistMiniCard(
                        name = pl.name,
                        coverUrl = pl.coverUrl,
                        subtitle = if (pl.trackCount > 0) "${pl.trackCount} 首" else formatPlayCount(pl.playCount),
                        onClick = { onOpenPlaylist(pl) },
                    )
                }
            }
        } else if (loading) {
            Spacer(Modifier.height(12.dp))
            InlineLoading()
        }
    }
}

/* ---------------- QQ 音乐账号区（需登录） ---------------- */

@Composable
private fun QqSection(
    playlists: List<QqPlaylist>,
    loading: Boolean,
    onOpenRecommend: (String) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenMyPlaylists: () -> Unit,
    onOpenPlaylist: (QqPlaylist) -> Unit,
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
            Text(
                text = "登录专属 · 红心同步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry(
                icon = Icons.Outlined.Radio,
                title = "猜你喜欢",
                onClick = { onOpenRecommend("radio") },
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.Outlined.Radar,
                title = "雷达推荐",
                onClick = { onOpenRecommend("radar") },
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.Outlined.FavoriteBorder,
                title = "我喜欢",
                onClick = onOpenLiked,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                title = "我的歌单",
                onClick = onOpenMyPlaylists,
                modifier = Modifier.weight(1f),
            )
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "我的歌单",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { it.tid }) { pl ->
                    AccountPlaylistMiniCard(
                        name = pl.name,
                        coverUrl = pl.coverUrl,
                        subtitle = "${pl.trackCount} 首",
                        onClick = { onOpenPlaylist(pl) },
                    )
                }
            }
        } else if (loading) {
            Spacer(Modifier.height(12.dp))
            InlineLoading()
        }
    }
}

/** 播放量格式化：亿 / 万 */
private fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000 -> "${count / 100_000_000}.${(count % 100_000_000) / 10_000_000}亿"
    count >= 10_000 -> "${count / 10_000}万"
    else -> ""
}