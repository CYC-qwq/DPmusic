package com.dpmusic.app.ui.screens.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
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
import com.dpmusic.app.ui.theme.NcmBrandColor
import com.dpmusic.app.ui.theme.QqBrandColor
import java.util.Calendar
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 主页：
 * - 时段问候 + 「继续收听」（最近播放置顶曲目）；
 * - 账号内容区：登录后优先展示网易云 / QQ 音乐专属内容，未登录时展示连接引导；
 * - 本地收藏双卡：我的喜欢 / 我的歌单（实时统计）；
 * - 平台热榜预览（每平台横向滑动，点击直达榜单详情）；
 * - 顶栏「听歌识曲」入口（环境音 → 双引擎识别：酷狗 + 网易云）。
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

    val compactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    // 听歌识曲 Sheet 开关
    var showRecognize by remember { mutableStateOf(false) }

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
                    ContinueCard(
                        title = continueSong.title,
                        artist = continueSong.artist,
                        coverUrl = continueSong.coverUrl,
                        onPlay = vm::resumeRecent,
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
            if (!ncmLoggedIn && !qqLoggedIn) {
                item(key = "account_prompt") {
                    AccountPromptCard(onClick = onOpenSettings)
                }
            }

            item(key = "quick") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickCard(
                        icon = Icons.Outlined.FavoriteBorder,
                        title = "本地收藏",
                        subtitle = "$favoriteCount 首",
                        onClick = onOpenMine,
                        modifier = Modifier.weight(1f),
                    )
                    QuickCard(
                        icon = Icons.AutoMirrored.Outlined.QueueMusic,
                        title = "本地歌单",
                        subtitle = "${playlists.size} 个",
                        onClick = onOpenPlaylists,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            MusicPlatform.entries.forEach { platform ->
                val ranks = toplists[platform].orEmpty()
                if (ranks.isNotEmpty()) {
                    item(key = "ranks_${platform.id}") {
                        RankSection(
                            platform = platform,
                            ranks = ranks,
                            onOpenRankDetail = onOpenRankDetail,
                        )
                    }
                }
            }

            if (toplistsLoading && toplists.isEmpty()) {
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

@Composable
private fun ContinueCard(
    title: String,
    artist: String,
    coverUrl: String,
    onPlay: () -> Unit,
) {
    GlassSurface(
        onClick = onPlay,
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
            FilledIconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
            }
        }
    }
}

/* ---------------- 快捷卡 ---------------- */

@Composable
private fun QuickCard(
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
                .padding(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------- 账号引导（未登录） ---------------- */

@Composable
private fun AccountPromptCard(onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "连接音乐账号",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "解锁每日推荐、私人FM与专属歌单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/* ---------------- 平台热榜 ---------------- */

@Composable
private fun RankSection(
    platform: MusicPlatform,
    ranks: List<RankSummary>,
    onOpenRankDetail: (RankSummary) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = platform.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "热榜速览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ranks, key = { "${platform.id}:${it.id}" }) { rank ->
                RankMiniCard(rank = rank, onClick = { onOpenRankDetail(rank) })
            }
        }
    }
}

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