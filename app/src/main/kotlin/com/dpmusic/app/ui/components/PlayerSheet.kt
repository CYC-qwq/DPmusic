@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.dpmusic.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.core.util.formatDuration
import com.dpmusic.app.ui.player.PlayerLyricsState
import com.dpmusic.app.ui.util.rememberDpHaptics
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 全屏播放器抽屉（Mini 播放条的「展开态」）。
 *
 * 设计语言（HTML 概念稿 × 项目基因融合）：
 * - 视听舞台：封面 ⇄ 歌词流「同空间切换」——点击原地淡出封面、浮入歌词，
 *   而非打开浮层；歌词当前行以主色光晕点亮；
 * - 环境流光：双光球自封面 Palette 提取色缓慢漂移（18s / 22s），播放时呼吸放大；
 * - 顶栏：下拉手柄 + 收起 + 音质胶囊（发光状态点）+ 更多「⋯」；
 * - 控制卡片：进度 + 控制收进 shaped container；进度条拖动时轨道 4→8dp 增粗，
 *   触碰浮现 12dp 滑块并弹出时间气泡；
 * - 收纳策略：音质与播放队列收进「⋯」菜单；收藏 ♡ 与平台徽标常驻。
 *
 * 形变机制：由外部注入的 sheetProgress(0..1) 驱动——
 * - 布局级位移 (1 - p) × 容器高度：从底部滑入（布局坐标参与共享元素计算）；
 * - 顶部圆角 28dp -> 0dp 随进度收敛；
 * - 手势上拉 / 下拉由宿主统一处理（消费端对弹簧过冲做夹紧防护）。
 */
@Composable
fun PlayerSheetHost(
    progress: Animatable<Float, AnimationVector1D>,
    nowPlaying: NowPlaying?,
    lyricsState: PlayerLyricsState,
    paletteColors: List<Color>,
    isFavorite: Boolean,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectQuality: (PlayQuality) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShowArtist: (String, String) -> Unit,
    onShowAlbum: (String) -> Unit,
    onShowSimilarSongs: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryLyrics: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    // 弹簧过冲防护：打开弹簧会短暂超过 1.0，夹紧避免负圆角等非法几何值
    val p = progress.value.coerceIn(0f, 1f)
    if (p <= 0.002f) return

    val dragModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> onDragDelta(delta) },
        onDragStopped = { velocity -> onDragEnd(velocity) },
    )

    var showMenu by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showDesktopLyric by remember { mutableStateOf(false) }
    var showNcmShare by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                // 布局级位移（而非 graphicsLayer 平移）：让共享元素系统读取到真实的滑动坐标
                val placeable = measurable.measure(constraints)
                val slide = ((1f - progress.value.coerceIn(0f, 1f)) * constraints.maxHeight).roundToInt()
                layout(placeable.width, placeable.height) { placeable.place(0, slide) }
            }
            .clip(RoundedCornerShape(28.dp * (1f - p))),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        // 【玻璃规范 L0｜不透明层】播放页是长时间阅读场景（歌词），不做透明玻璃：
        // 用「封面调色板染色的不透明竖向渐变」做底，既保留专辑氛围又保证对比度。
        val glassBase = MaterialTheme.colorScheme.surfaceContainerLowest
        val glassMix = if (isSystemInDarkTheme()) 0.26f else 0.34f
        val bgTop = paletteColors.getOrNull(0)?.let { lerp(glassBase, it, glassMix) } ?: glassBase
        val bgMid = paletteColors.getOrNull(1)?.let { lerp(glassBase, it, glassMix * 0.62f) } ?: glassBase
        val bgLow = paletteColors.getOrNull(2)?.let { lerp(glassBase, it, glassMix * 0.34f) } ?: glassBase
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(bgTop, bgMid, bgLow, glassBase),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )
                },
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight * 1.15f
            val openMenu: () -> Unit = { showMenu = true }

            // 歌词字号 / 行距：横竖屏分别记忆（持久化到设置，重开应用后保留）
            val appSettings by AppContainer.settings.settings.collectAsStateWithLifecycle()
            // 私人FM入口的登录态（需登录才显示）
            val ncmCookie by AppContainer.ncm.cookie.collectAsStateWithLifecycle()
            val ncmLoggedIn = ncmCookie.isNotBlank()
            val scope = rememberCoroutineScope()
            val lyricScale = if (isLandscape) appSettings.lyricScaleLandscape else appSettings.lyricScalePortrait
            val verbatimLyric = appSettings.verbatimLyric
            val simulatedVerbatim = appSettings.simulatedVerbatim
            val onLyricScaleChange: (Float) -> Unit = { scale ->
                scope.launch {
                    if (isLandscape) AppContainer.settings.setLyricScaleLandscape(scale)
                    else AppContainer.settings.setLyricScalePortrait(scale)
                }
            }
            val lyricSpacing = if (isLandscape) appSettings.lyricSpacingLandscape else appSettings.lyricSpacingPortrait
            val onLyricSpacingChange: (Float) -> Unit = { scale ->
                scope.launch {
                    if (isLandscape) AppContainer.settings.setLyricSpacingLandscape(scale)
                    else AppContainer.settings.setLyricSpacingPortrait(scale)
                }
            }

            if (isLandscape) {
                PlayerContentLandscape(
                    nowPlaying = nowPlaying,
                    lyricsState = lyricsState,
                    paletteColors = paletteColors,
                    isFavorite = isFavorite,
                    dragModifier = dragModifier,
                    coverModifier = coverModifier,
                    onCollapse = onCollapse,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onToggleFavorite = onToggleFavorite,
                    onOpenShare = { showShare = true },
                    lyricScale = lyricScale,
                    onLyricScaleChange = onLyricScaleChange,
                    lyricSpacing = lyricSpacing,
                    onLyricSpacingChange = onLyricSpacingChange,
                    verbatim = verbatimLyric,
                    simulatedVerbatim = simulatedVerbatim,
                    onOpenMenu = openMenu,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onRetryLyrics = onRetryLyrics,
                )
            } else {
                PlayerContentPortrait(
                    nowPlaying = nowPlaying,
                    lyricsState = lyricsState,
                    paletteColors = paletteColors,
                    isFavorite = isFavorite,
                    dragModifier = dragModifier,
                    coverModifier = coverModifier,
                    onCollapse = onCollapse,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onToggleFavorite = onToggleFavorite,
                    onOpenShare = { showShare = true },
                    lyricScale = lyricScale,
                    onLyricScaleChange = onLyricScaleChange,
                    lyricSpacing = lyricSpacing,
                    onLyricSpacingChange = onLyricSpacingChange,
                    verbatim = verbatimLyric,
                    simulatedVerbatim = simulatedVerbatim,
                    onOpenMenu = openMenu,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onRetryLyrics = onRetryLyrics,
                )
            }

            // 「⋯」更多菜单（横竖屏共用：播放队列 + 音质选择）
            if (showMenu) {
                PlayerMenuSheet(
                    song = nowPlaying?.song,
                    current = nowPlaying?.quality ?: PlayQuality.HIGH,
                    desired = nowPlaying?.desiredQuality ?: PlayQuality.HIGH,
                    onSelectQuality = { quality ->
                        onSelectQuality(quality)
                        showMenu = false
                    },
                    onOpenQueue = {
                        showMenu = false
                        onOpenQueue()
                    },
                    onAddToPlaylist = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                    onOpenEqualizer = {
                        showMenu = false
                        showEqualizer = true
                    },
                    onOpenDownload = {
                        showMenu = false
                        showDownload = true
                    },
                    onOpenComments = {
                        showMenu = false
                        showComments = true
                    },
                    onOpenArtist = {
                        showMenu = false
                        nowPlaying?.song?.let { s ->
                            s.extra["wy_artist_id"]?.let { id -> onShowArtist(id, s.artist) }
                        }
                    },
                    onOpenAlbum = {
                        showMenu = false
                        nowPlaying?.song?.let { s ->
                            s.extra["wy_album_id"]?.let { id -> onShowAlbum(id) }
                        }
                    },
                    onOpenSimilar = {
                        showMenu = false
                        onShowSimilarSongs()
                    },
                    onOpenSpeed = {
                        showMenu = false
                        showSpeed = true
                    },
                    onOpenSleepTimer = {
                        showMenu = false
                        showSleepTimer = true
                    },
                    onDislike = {
                        showMenu = false
                        AppContainer.player.dislikeCurrent()
                    },
                    showFm = ncmLoggedIn,
                    onStartFm = {
                        showMenu = false
                        AppContainer.ncmFm.start()
                    },
                    onOpenDesktopLyric = {
                        showMenu = false
                        showDesktopLyric = true
                    },
                    desktopLyricOn = appSettings.desktopLyricEnabled,
                    onDismiss = { showMenu = false },
                )
            }

            // 音效均衡器（全屏底部面板）
            if (showEqualizer) {
                EqualizerSheet(onDismiss = { showEqualizer = false })
            }

            // 播放速度（变速不变调）
            if (showSpeed) {
                PlaybackSpeedSheet(onDismiss = { showSpeed = false })
            }

            // 定时退出（到点自动暂停）
            if (showSleepTimer) {
                SleepTimerSheet(onDismiss = { showSleepTimer = false })
            }

            // 下载歌曲（音质选择 + 可选歌词）
            if (showDownload) {
                DownloadSheet(
                    song = nowPlaying?.song,
                    defaultQuality = nowPlaying?.desiredQuality ?: PlayQuality.HIGH,
                    onDismiss = { showDownload = false },
                )
            }

            // 分享歌曲（官方 / 音源链接组合）
            if (showShare) {
                nowPlaying?.song?.let { shareSong ->
                    SongShareSheet(
                        song = shareSong,
                        quality = nowPlaying?.quality ?: PlayQuality.HIGH,
                        onDismiss = { showShare = false },
                        onShareToNcm = { showNcmShare = true },
                    )
                }
            }

            // 分享给网易云好友（歌曲卡片 → 网易云私信）
            if (showNcmShare) {
                nowPlaying?.song?.let { ncmSong ->
                    ShareToNcmFriendDialog(
                        song = ncmSong,
                        onDismiss = { showNcmShare = false },
                        onSent = { nickname ->
                            showNcmShare = false
                            Toast.makeText(
                                context,
                                "已分享给 ${nickname.ifBlank { "好友" }}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }

            // 歌曲评论（热门 + 最新）
            if (showComments) {
                nowPlaying?.song?.let { commentSong ->
                    CommentsSheet(
                        song = commentSong,
                        onDismiss = { showComments = false },
                    )
                }
            }

            // 桌面歌词（悬浮窗样式与预设）
            if (showDesktopLyric) {
                DesktopLyricSheet(onDismiss = { showDesktopLyric = false })
            }
        }
    }
}

/* ---------------- 竖屏：视听舞台（封面 ⇄ 歌词同空间切换）+ 信息 + 控制卡片 ---------------- */

@Composable
private fun PlayerContentPortrait(
    nowPlaying: NowPlaying?,
    lyricsState: PlayerLyricsState,
    paletteColors: List<Color>,
    isFavorite: Boolean,
    dragModifier: Modifier,
    coverModifier: Modifier,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenShare: () -> Unit,
    lyricScale: Float,
    onLyricScaleChange: (Float) -> Unit,
    lyricSpacing: Float,
    onLyricSpacingChange: (Float) -> Unit,
    verbatim: Boolean,
    simulatedVerbatim: Boolean,
    onOpenMenu: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryLyrics: () -> Unit,
) {
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    val modeT by animateFloatAsState(
        targetValue = if (lyricsMode) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "lyricsMode",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // 双光球环境流光（封面主色，缓慢漂移）
        AmbientBackdrop(
            glowColors = paletteColors,
            isPlaying = nowPlaying?.isPlaying == true,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 顶栏：下拉手柄 + 收起 + 音质胶囊 + 更多
            PlayerTopBar(
                nowPlaying = nowPlaying,
                dragModifier = dragModifier,
                onCollapse = onCollapse,
                onOpenMenu = onOpenMenu,
            )

            // 视听舞台：封面 ⇄ 歌词（同空间切换）
            // 切换点击由「封面层 / 歌词层」各自承担；非激活层不组合点击节点——
            // Compose 中 disabled clickable 仍会 consume 事件（阻断外层手势）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(vertical = 8.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.Center,
            ) {
                // 封面层：淡出 + 缩小 + 上移（点击 → 切到歌词）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 1f - modeT
                            val s = 1f - 0.12f * modeT
                            scaleX = s
                            scaleY = s
                            translationY = -28.dp.toPx() * modeT
                        }
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = !lyricsMode,
                            onClick = { lyricsMode = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtworkBlock(
                        url = nowPlaying?.song?.coverUrl,
                        glowColors = paletteColors,
                        isPlaying = nowPlaying?.isPlaying == true,
                        coverModifier = coverModifier,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .aspectRatio(1f),
                    )
                }

                // 歌词层：淡入 + 展开 + 上浮（点击空白 → 切回封面）
                // 仅在激活或淡出动画期间组合（封面模式下整棵子树不存在，避免拦截命中测试）
                if (lyricsMode || modeT > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = modeT
                                val s = 0.94f + 0.06f * modeT
                                scaleX = s
                                scaleY = s
                                translationY = 32.dp.toPx() * (1f - modeT)
                            }
                            .then(
                                if (lyricsMode) {
                                    Modifier.clickable(
                                        interactionSource = null,
                                        indication = null,
                                        onClick = { lyricsMode = false },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        when (lyricsState) {
                            is PlayerLyricsState.Content -> LyricsView(
                                lyrics = lyricsState.lyrics,
                                positionMs = nowPlaying?.positionMs ?: 0L,
                                isPlaying = nowPlaying?.let { it.isPlaying && !it.isBuffering } == true,
                                onLineClick = onSeek,
                                // 歌词模式下才响应滚动与行点击
                                interactive = lyricsMode,
                                verbatim = verbatim,
                                simulatedVerbatim = simulatedVerbatim,
                                fontScale = lyricScale,
                                onFontScaleChange = onLyricScaleChange,
                                spacingScale = lyricSpacing,
                                onSpacingScaleChange = onLyricSpacingChange,
                            )
                            PlayerLyricsState.Loading -> LoadingState(text = "歌词加载中…")
                            PlayerLyricsState.Empty -> EmptyState(
                                title = "暂无歌词",
                                onRetry = onRetryLyrics,
                            )
                            is PlayerLyricsState.Error -> ErrorState(
                                message = lyricsState.message,
                                onRetry = onRetryLyrics,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 歌曲信息（左对齐）+ 收藏 + 平台徽标
            SongInfoRow(
                song = nowPlaying?.song,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onShareClick = onOpenShare,
                modifier = Modifier.padding(horizontal = 24.dp),
                showBadge = true,
                large = true,
            )

            Spacer(Modifier.height(16.dp))

            // 控制卡片（shaped container）：进度 + 控制收成一个"组"
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    PlayerProgressBar(
                        positionMs = nowPlaying?.positionMs ?: 0L,
                        durationMs = nowPlaying?.durationMs ?: 0L,
                        onSeek = onSeek,
                    )
                    Spacer(Modifier.height(2.dp))
                    PlayerControlsRow(
                        nowPlaying = nowPlaying,
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ---------------- 横屏：双栏（左 40% 封面控制 / 右 60% 歌词流） ---------------- */

@Composable
private fun PlayerContentLandscape(
    nowPlaying: NowPlaying?,
    lyricsState: PlayerLyricsState,
    paletteColors: List<Color>,
    isFavorite: Boolean,
    dragModifier: Modifier,
    coverModifier: Modifier,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenShare: () -> Unit,
    lyricScale: Float,
    onLyricScaleChange: (Float) -> Unit,
    lyricSpacing: Float,
    onLyricSpacingChange: (Float) -> Unit,
    verbatim: Boolean,
    simulatedVerbatim: Boolean,
    onOpenMenu: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryLyrics: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackdrop(
            glowColors = paletteColors,
            isPlaying = nowPlaying?.isPlaying == true,
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 左栏 40%：封面 + 信息 + 进度 + 控制
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .then(dragModifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtworkBlock(
                        url = nowPlaying?.song?.coverUrl,
                        glowColors = paletteColors,
                        isPlaying = nowPlaying?.isPlaying == true,
                        coverModifier = coverModifier,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .aspectRatio(1f),
                        onClick = onTogglePlay,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SongInfoRow(
                    song = nowPlaying?.song,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onShareClick = onOpenShare,
                    showBadge = false,
                )
                Spacer(Modifier.height(8.dp))
                PlayerProgressBar(
                    positionMs = nowPlaying?.positionMs ?: 0L,
                    durationMs = nowPlaying?.durationMs ?: 0L,
                    onSeek = onSeek,
                )
                Spacer(Modifier.height(2.dp))
                PlayerControlsRow(
                    nowPlaying = nowPlaying,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    compact = true,
                )
            }

            // 右栏 60%：顶栏（音质胶囊 + 更多 + 收起）+ 大尺寸歌词流
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .then(dragModifier)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QualityChip(
                        quality = nowPlaying?.quality,
                        degraded = nowPlaying != null && nowPlaying.quality != nowPlaying.desiredQuality,
                        onClick = onOpenMenu,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = "更多选项",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "收起播放页",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (lyricsState) {
                        is PlayerLyricsState.Content -> LyricsView(
                            lyrics = lyricsState.lyrics,
                            positionMs = nowPlaying?.positionMs ?: 0L,
                            isPlaying = nowPlaying?.let { it.isPlaying && !it.isBuffering } == true,
                            onLineClick = onSeek,
                            verbatim = verbatim,
                            simulatedVerbatim = simulatedVerbatim,
                            fontScale = lyricScale,
                            onFontScaleChange = onLyricScaleChange,
                            spacingScale = lyricSpacing,
                            onSpacingScaleChange = onLyricSpacingChange,
                        )
                        PlayerLyricsState.Loading -> LoadingState(text = "歌词加载中…")
                        PlayerLyricsState.Empty -> EmptyState(
                            title = "暂无歌词",
                            onRetry = onRetryLyrics,
                        )
                        is PlayerLyricsState.Error -> EmptyState(
                            title = "歌词加载失败",
                            subtitle = lyricsState.message,
                            onRetry = onRetryLyrics,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/* ---------------- 核心视觉：大画幅封面（呼吸 + 双层环境光晕 + 投影） ---------------- */

@Composable
private fun ArtworkBlock(
    url: String?,
    glowColors: List<Color>,
    isPlaying: Boolean,
    coverModifier: Modifier,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // 呼吸：播放时 1.02f 往复，暂停平滑休眠
    val breathTransition = rememberInfiniteTransition(label = "artworkBreath")
    val breathRaw by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "artworkBreathRaw",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) breathRaw else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "artworkScale",
    )

    val coverInteraction = remember { MutableInteractionSource() }
    val coverShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = coverScale
            scaleY = coverScale
        },
        contentAlignment = Alignment.Center,
    ) {
        if (glowColors.isNotEmpty()) {
            // 近层光晕（48dp 扩散）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.10f
                        scaleY = 1.10f
                    }
                    .blur(48.dp)
                    .background(
                        Brush.radialGradient(
                            colors = glowColors.take(3).map { it.copy(alpha = 0.5f) } + Color.Transparent,
                        ),
                    ),
            )
            // 远层光晕（64dp 扩散）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.26f
                        scaleY = 1.26f
                    }
                    .blur(64.dp)
                    .background(
                        Brush.radialGradient(
                            colors = glowColors.take(2).map { it.copy(alpha = 0.26f) } + Color.Transparent,
                        ),
                    ),
            )
        }

        // 封面（共享元素：Mini 条 ⇄ 全屏；可选点击）
        CoverArt(
            url = url,
            modifier = coverModifier
                .shadow(
                    elevation = 20.dp,
                    shape = coverShape,
                    clip = false,
                )
                .fillMaxSize()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = coverInteraction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            shape = coverShape,
        )

        // 内描边（0.5dp 高光，12% 透明度）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(coverShape)
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), coverShape),
        )
    }
}

/* ---------------- 歌曲信息（左对齐 + 收藏 + 平台徽标） ---------------- */

@Composable
private fun SongInfoRow(
    song: Song?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    showBadge: Boolean = true,
    large: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song?.title.orEmpty(),
                style = if (large) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    song?.artist,
                    song?.album?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = if (large) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        FavoriteButton(
            isFavorite = isFavorite,
            onClick = onToggleFavorite,
        )
        Spacer(Modifier.width(2.dp))
        ShareButton(onClick = onShareClick)
        if (showBadge) {
            Spacer(Modifier.width(6.dp))
            song?.platform?.let { PlatformBadge(platform = it) }
        }
    }
}

/* ---------------- 进度条：轨道拖动增粗 + 隐形滑块 + 时间气泡 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1L)
    val displayValue = dragValue ?: positionMs.toFloat()
    val haptics = rememberDpHaptics()

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 拖动中的时间气泡（正上方悬浮）
            val drag = dragValue
            if (drag != null) {
                val fraction = (displayValue / duration).coerceIn(0f, 1f)
                val bubbleWidth = 64.dp
                val offsetX = (maxWidth - bubbleWidth) * fraction
                Surface(
                    modifier = Modifier
                        .offset(x = offsetX, y = (-36).dp)
                        .width(bubbleWidth),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = formatDuration(drag.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }

            Slider(
                value = displayValue.coerceIn(0f, duration.toFloat()),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it.toLong()) }
                    dragValue = null
                    // 松手落位：轻震一下，给"已定位"的确认感
                    haptics.gestureEnd()
                },
                valueRange = 0f..duration.toFloat(),
                thumb = { state ->
                    // 隐形滑块：静止时缩放为 0（纯进度条），触碰时弹性展开 12dp
                    val thumbScale by animateFloatAsState(
                        targetValue = if (state.isDragging) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                        label = "thumbAppear",
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer {
                                scaleX = thumbScale
                                scaleY = thumbScale
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                },
                track = { state ->
                    // 轨道：静止 4dp，拖动时增粗至 8dp（弹簧过渡）
                    val trackHeight by animateDpAsState(
                        targetValue = if (state.isDragging) 8.dp else 4.dp,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                        label = "trackHeight",
                    )
                    val fraction = state.coercedValueAsFraction
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayValue.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/* ---------------- 控制排：循环 / 上一首 / 播放 76dp / 下一首 / 随机 ---------------- */

@Composable
private fun PlayerControlsRow(
    nowPlaying: NowPlaying?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val data = nowPlaying ?: return
    val playSize = if (compact) 64.dp else 76.dp
    val sideIcon = if (compact) 22.dp else 28.dp
    val playIcon = if (compact) 32.dp else 36.dp

    val haptics = rememberDpHaptics()

    val repeatSource = remember { MutableInteractionSource() }
    val previousSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val nextSource = remember { MutableInteractionSource() }
    val shuffleSource = remember { MutableInteractionSource() }
    val repeatScale = pressBounce(repeatSource)
    val previousScale = pressBounce(previousSource)
    val playScale = pressBounce(playSource)
    val nextScale = pressBounce(nextSource)
    val shuffleScale = pressBounce(shuffleSource)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 循环模式（左一）
        IconButton(
            onClick = { haptics.tick(); onToggleRepeat() },
            interactionSource = repeatSource,
            modifier = Modifier.graphicsLayer {
                scaleX = repeatScale
                scaleY = repeatScale
            },
        ) {
            Icon(
                imageVector = if (data.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne
                else Icons.Filled.Repeat,
                contentDescription = "循环模式",
                modifier = Modifier.size(sideIcon),
                tint = if (data.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 上一首（左二）
        IconButton(
            onClick = { haptics.click(); onPrevious() },
            interactionSource = previousSource,
            modifier = Modifier.graphicsLayer {
                scaleX = previousScale
                scaleY = previousScale
            },
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "上一首",
                modifier = Modifier.size(sideIcon),
            )
        }

        // 播放 / 暂停（中央主键，主色光晕投影，缓冲时显示加载圈）
        FilledIconButton(
            onClick = { haptics.click(); onTogglePlay() },
            interactionSource = playSource,
            modifier = Modifier
                .size(playSize)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    spotColor = MaterialTheme.colorScheme.primary,
                ),
        ) {
            if (data.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(playIcon - 8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                AnimatedContent(
                    targetState = data.isPlaying,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                    label = "playPauseIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(playIcon),
                    )
                }
            }
        }

        // 下一首（右二）
        IconButton(
            onClick = { haptics.click(); onNext() },
            interactionSource = nextSource,
            modifier = Modifier.graphicsLayer {
                scaleX = nextScale
                scaleY = nextScale
            },
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "下一首",
                modifier = Modifier.size(sideIcon),
            )
        }

        // 随机播放（右一，开启时底部浮现微型发光点）
        Box {
            IconButton(
                onClick = { haptics.tick(); onToggleShuffle() },
                interactionSource = shuffleSource,
                modifier = Modifier.graphicsLayer {
                    scaleX = shuffleScale
                    scaleY = shuffleScale
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "随机播放",
                    modifier = Modifier.size(sideIcon),
                    tint = if (data.shuffleEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val dotAlpha by animateFloatAsState(
                targetValue = if (data.shuffleEnabled) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                label = "shuffleDot",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(4.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 按压弹性回弹（M3 Expressive 弹簧：0.94f 缩放） */
@Composable
private fun pressBounce(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.94f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "pressBounce",
    )
    return scale
}

/* ---------------- 顶栏：下拉手柄 + 收起 + 音质胶囊 + 更多 ---------------- */

@Composable
private fun PlayerTopBar(
    nowPlaying: NowPlaying?,
    dragModifier: Modifier,
    onCollapse: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(dragModifier)
            .padding(horizontal = 8.dp),
    ) {
        // 下拉手柄（顶部中央，提示收起手势）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "收起播放页",
                modifier = Modifier.size(24.dp),
            )
        }
        QualityChip(
            quality = nowPlaying?.quality,
            degraded = nowPlaying != null && nowPlaying.quality != nowPlaying.desiredQuality,
            onClick = onOpenMenu,
            modifier = Modifier.align(Alignment.Center),
        )
        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "更多选项",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 音质胶囊（Micro-Pill）：发光状态点 + 短标签，玻璃质感 */
@Composable
private fun QualityChip(
    quality: PlayQuality?,
    degraded: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val chipShape = RoundedCornerShape(50)
    val dotColor = if (degraded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(chipShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), chipShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 发光状态点（外环柔光 + 内芯）；降级时切换为琥珀色提醒
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.25f)),
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = buildString {
                append(quality?.shortLabel ?: "320K")
                if (degraded) append(" ·降")
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/* ---------------- 「⋯」更多菜单：快捷区 + 二级分组收纳 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMenuSheet(
    song: Song?,
    current: PlayQuality,
    desired: PlayQuality,
    onSelectQuality: (PlayQuality) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenSimilar: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenDesktopLyric: () -> Unit,
    desktopLyricOn: Boolean,
    onDislike: () -> Unit,
    showFm: Boolean,
    onStartFm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appSettings by AppContainer.settings.settings.collectAsStateWithLifecycle()
    val sleepTimerState by AppContainer.sleepTimer.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 手风琴：同一时间只展开一个分组（点击标题展开 / 收起）
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // 快捷区：三个高频入口保持一级直达
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PlayerQuickAction(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "播放队列",
                    onClick = { onOpenQueue() },
                    modifier = Modifier.weight(1f),
                )
                if (showFm) {
                    PlayerQuickAction(
                        icon = Icons.Outlined.Radio,
                        label = "私人FM",
                        onClick = { onStartFm() },
                        modifier = Modifier.weight(1f),
                    )
                }
                PlayerQuickAction(
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    label = "添加到歌单",
                    onClick = { onAddToPlaylist() },
                    modifier = Modifier.weight(1f),
                )
            }

            // 分组一：音质与播放
            PlayerMenuGroup(
                title = "音质与播放",
                icon = Icons.Outlined.Tune,
                summary = buildString {
                    append(current.label)
                    if (appSettings.playbackSpeed != 1f) {
                        append(" · ")
                        append(formatPlaybackSpeed(appSettings.playbackSpeed))
                    }
                    if (sleepTimerState.active || sleepTimerState.pendingSongEnd) append(" · 定时")
                },
                expanded = expandedGroup == GROUP_PLAYBACK,
                onToggle = {
                    expandedGroup = if (expandedGroup == GROUP_PLAYBACK) null else GROUP_PLAYBACK
                },
            ) {
                if (current != desired) {
                    Text(
                        text = "首选「${desired.label}」当前不可用，已自动降级为「${current.label}」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayQuality.entries.forEach { item ->
                        val leading: (@Composable () -> Unit)? = if (item == current) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        }
                        FilterChip(
                            selected = item == current,
                            onClick = { onSelectQuality(item) },
                            label = { Text(item.label) },
                            leadingIcon = leading,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                PlayerMenuItem(
                    icon = Icons.Outlined.Equalizer,
                    label = "音效均衡器",
                    onClick = onOpenEqualizer,
                )
                PlayerMenuItem(
                    icon = Icons.Outlined.Speed,
                    label = "播放速度",
                    trailing = formatPlaybackSpeed(appSettings.playbackSpeed),
                    trailingHighlight = appSettings.playbackSpeed != 1f,
                    onClick = onOpenSpeed,
                )
                PlayerMenuItem(
                    icon = Icons.Outlined.Timer,
                    label = "定时退出",
                    trailing = when {
                        sleepTimerState.pendingSongEnd -> "本曲结束后"
                        sleepTimerState.active -> formatSleepRemaining(sleepTimerState.remainingMs) + "后"
                        else -> "未开启"
                    },
                    trailingHighlight = sleepTimerState.active || sleepTimerState.pendingSongEnd,
                    onClick = onOpenSleepTimer,
                )
            }

            // 分组二：歌曲详情
            val hasArtist = song?.platform == MusicPlatform.WY &&
                !song.extra["wy_artist_id"].isNullOrBlank()
            val hasAlbum = song?.platform == MusicPlatform.WY &&
                !song.extra["wy_album_id"].isNullOrBlank()
            val hasSimilar = song?.platform == MusicPlatform.WY
            PlayerMenuGroup(
                title = "歌曲详情",
                icon = Icons.Outlined.Info,
                summary = song?.let { listOf(it.title, it.artist).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                    .orEmpty(),
                expanded = expandedGroup == GROUP_DETAILS,
                onToggle = {
                    expandedGroup = if (expandedGroup == GROUP_DETAILS) null else GROUP_DETAILS
                },
            ) {
                if (hasArtist) {
                    PlayerMenuItem(icon = Icons.Outlined.Person, label = "查看歌手", onClick = onOpenArtist)
                }
                if (hasAlbum) {
                    PlayerMenuItem(icon = Icons.Outlined.Album, label = "查看专辑", onClick = onOpenAlbum)
                }
                PlayerMenuItem(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "查看评论",
                    onClick = onOpenComments,
                )
                if (hasSimilar) {
                    PlayerMenuItem(icon = Icons.Outlined.AutoAwesome, label = "相似歌曲", onClick = onOpenSimilar)
                }
                PlayerMenuItem(icon = Icons.Outlined.Download, label = "下载歌曲", onClick = onOpenDownload)
            }

            // 分组三：更多
            PlayerMenuGroup(
                title = "更多",
                icon = Icons.Filled.MoreHoriz,
                summary = if (desktopLyricOn) "桌面歌词已开启" else null,
                expanded = expandedGroup == GROUP_MORE,
                onToggle = {
                    expandedGroup = if (expandedGroup == GROUP_MORE) null else GROUP_MORE
                },
            ) {
                PlayerMenuItem(
                    icon = Icons.Outlined.Lyrics,
                    label = "桌面歌词",
                    trailing = if (desktopLyricOn) "已开启" else "未开启",
                    trailingHighlight = desktopLyricOn,
                    onClick = onOpenDesktopLyric,
                )
                PlayerMenuItem(icon = Icons.Outlined.ThumbDown, label = "不喜欢此歌", onClick = onDislike)
            }
        }
    }
}

/** 分组标识（手风琴：同一时间只展开一个分组） */
private const val GROUP_PLAYBACK = "playback"
private const val GROUP_DETAILS = "details"
private const val GROUP_MORE = "more"

/** 快捷入口：图标 + 文字的等宽卡片 */
@Composable
private fun PlayerQuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * 可展开的二级分组：标题行（图标 + 名称 + 摘要 + 箭头）+ 展开内容。
 * 收起时摘要仍传达当前状态；展开后一次点击即可触达组内功能。
 */
@Composable
private fun PlayerMenuGroup(
    title: String,
    icon: ImageVector,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "menuGroupArrow",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                content = content,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

/** 菜单项行（图标 + 标题），点击执行操作 */
@Composable
private fun PlayerMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    trailingHighlight: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = if (trailingHighlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/* ---------------- 通用：双光球环境流光 / 收藏按钮 ---------------- */

/** 双光球环境流光：封面主色缓慢漂移（18s / 22s），播放时呼吸放大 */
@Composable
private fun AmbientBackdrop(
    glowColors: List<Color>,
    isPlaying: Boolean,
) {
    if (glowColors.isEmpty()) return
    val primaryGlow = glowColors.first()
    val secondaryGlow = glowColors.getOrNull(1) ?: primaryGlow

    val transition = rememberInfiniteTransition(label = "ambientDrift")
    val drift1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift1",
    )
    val drift2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift2",
    )
    val playBoost by animateFloatAsState(
        targetValue = if (isPlaying) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "playBoost",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 光球 1（中偏左上）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-70).dp, y = (-160).dp)
                .size(500.dp)
                .graphicsLayer {
                    translationX = drift1 * 130f
                    translationY = -drift1 * 90f
                    val s = (0.92f + 0.23f * drift1) * playBoost
                    scaleX = s
                    scaleY = s
                }
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.36f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // 光球 2（中偏右下）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 90.dp, y = 140.dp)
                .size(440.dp)
                .graphicsLayer {
                    translationX = -drift2 * 110f
                    translationY = drift2 * 70f
                    val s = 0.9f + 0.2f * drift2
                    scaleX = s
                    scaleY = s
                }
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            secondaryGlow.copy(alpha = 0.26f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/** 收藏按钮：点击时爱心弹性缩放，收藏状态以主色点亮 */
@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val haptics = rememberDpHaptics()

    IconButton(
        onClick = {
            // 新收藏 → 确认感（有"收下了"的满足）；取消收藏 → 轻触
            if (isFavorite) haptics.tick() else haptics.confirm()
            onClick()
            scope.launch {
                scale.snapTo(0.75f)
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isFavorite) "取消收藏" else "收藏",
            tint = if (isFavorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

@Composable
private fun ShareButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = "分享",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}