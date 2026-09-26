package com.dpmusic.app.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dpmusic.app.AppContainer
import com.dpmusic.app.AppViewModelFactory
import com.dpmusic.app.core.data.NcmSyncState
import com.dpmusic.app.core.data.QqSyncState
import com.dpmusic.app.core.download.DownloadPaths
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmProfile
import com.dpmusic.app.core.model.QqProfile
import com.dpmusic.app.core.lyric.DesktopLyricPreset
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.util.StorageManager
import com.dpmusic.app.core.util.formatRelativeTime
import com.dpmusic.app.ui.components.DesktopLyricSheet
import com.dpmusic.app.ui.components.DislikeManagerSheet
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.NCM_COOKIE_URL
import com.dpmusic.app.ui.components.NCM_LOGIN_COOKIE_KEYS
import com.dpmusic.app.ui.components.NCM_LOGIN_URL
import com.dpmusic.app.ui.components.NCM_MOBILE_UA
import com.dpmusic.app.ui.components.WebLoginDialog
import com.dpmusic.app.ui.theme.ThemePalette
import com.dpmusic.app.ui.theme.themePaletteById
import com.dpmusic.app.ui.theme.themePalettes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dpmusic.app.ui.theme.glassPanelColor
import com.dpmusic.app.ui.theme.LocalBottomBarInset

/**
 * 设置页：
 * - 竖屏：单列卡片；横屏/大屏：双列卡片网格；
 * - 音频偏好、音源服务（Key / 购买 / 声明）、外观（M3 动态调色）、歌词、
 *   存储与缓存、下载、剪贴板、关于与作者信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloadManager: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cacheMessage by vm.cacheMessage.collectAsStateWithLifecycle()
    val ncmProfile by vm.ncmProfile.collectAsStateWithLifecycle()
    val ncmLogin by vm.ncmLogin.collectAsStateWithLifecycle()
    val ncmSyncState by vm.ncmSyncState.collectAsStateWithLifecycle()
    val qqProfile by vm.qqProfile.collectAsStateWithLifecycle()
    val qqLogin by vm.qqLogin.collectAsStateWithLifecycle()
    val qqSyncState by vm.qqSyncState.collectAsStateWithLifecycle()
    val storageUsage by vm.storageUsage.collectAsStateWithLifecycle()
    val downloadPathCheck by vm.downloadPathCheck.collectAsStateWithLifecycle()
    val dislikeRules by AppContainer.dislike.rules.collectAsStateWithLifecycle()
    var showDislikeManager by remember { mutableStateOf(false) }
    var showDesktopLyric by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val defaultDownloadPath = remember { DownloadPaths.defaultPath(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 购买音源 Key：跳转官方商店
    val onBuyKey: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHOP_URL)))
        }
    }

    // 加入 QQ 群：优先唤起 QQ 群卡片；未安装 QQ 时复制群号兜底
    val onJoinQQGroup: () -> Unit = {
        val opened = runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP&card_type=group&source=qrcode"),
                ),
            )
        }.isSuccess
        if (!opened) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("QQ群", QQ_GROUP))
            scope.launch { snackbarHostState.showSnackbar("未找到 QQ 应用，已复制群号：$QQ_GROUP") }
        }
    }

    LaunchedEffect(cacheMessage) {
        cacheMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    // 列表 / 网格滚动状态外提：形态切换时锚定保持
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            DpTopAppBar(
                title = "设置",
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
        if (compact) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                bottom = 16.dp + LocalBottomBarInset.current,
            ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AudioPreferenceCard(
                        platform = settings.defaultPlatform,
                        quality = settings.quality,
                        onPlatformChange = vm::setPlatform,
                        onQualityChange = vm::setQuality,
                    )
                }
                item {
                    AudioSourceCard(
                        lxApiKey = settings.lxApiKey,
                        onLxApiKeyChange = vm::setLxApiKey,
                        onBuyKey = onBuyKey,
                    )
                }
                item { SourceManagerCard(onOpenSources = onOpenSources) }
                item {
                    NcmCard(
                        profile = ncmProfile,
                        login = ncmLogin,
                        syncState = ncmSyncState,
                        onSaveCookie = vm::saveNcmCookie,
                        onClearCookie = vm::clearNcmCookie,
                        onConsumeMessage = vm::consumeNcmLoginMessage,
                        onSyncNow = vm::syncNcmLikes,
                        onConsumeSyncMessage = vm::consumeNcmSyncMessage,
                    )
                }
                item {
                    QqCard(
                        profile = qqProfile,
                        login = qqLogin,
                        syncState = qqSyncState,
                        onSaveCookie = vm::saveQqCookie,
                        onClearCookie = vm::clearQqCookie,
                        onConsumeMessage = vm::consumeQqLoginMessage,
                        onSyncNow = vm::syncQqLikes,
                        onConsumeSyncMessage = vm::consumeQqSyncMessage,
                    )
                }
                item {
                    AppearanceCard(
                        dynamicColor = settings.dynamicColor,
                        darkMode = settings.darkMode,
                        onDynamicColorChange = vm::setDynamicColor,
                        onDarkModeChange = vm::setDarkMode,
                        themeColor = settings.themeColor,
                        onThemeColorChange = vm::setThemeColor,
                        glass = settings.glassMode,
                        onGlassChange = vm::setGlassMode,
                    )
                }
                item {
                    LyricsCard(
                        verbatim = settings.verbatimLyric,
                        onVerbatimChange = vm::setVerbatimLyric,
                        simulated = settings.simulatedVerbatim,
                        onSimulatedChange = vm::setSimulatedVerbatim,
                        s2t = settings.lyricS2T,
                        onS2tChange = vm::setLyricS2T,
                    )
                }
                item {
                    DesktopLyricCard(
                        enabled = settings.desktopLyricEnabled,
                        presetId = settings.desktopLyricPreset,
                        onOpen = { showDesktopLyric = true },
                    )
                }
                item {
                    ListDisplayCard(
                        showSource = settings.listShowSource,
                        onShowSourceChange = vm::setListShowSource,
                        showAlbumName = settings.listShowAlbumName,
                        onShowAlbumNameChange = vm::setListShowAlbumName,
                        showDuration = settings.listShowDuration,
                        onShowDurationChange = vm::setListShowDuration,
                        showCover = settings.listShowCover,
                        onShowCoverChange = vm::setListShowCover,
                        dislikeCount = dislikeRules.size,
                        onOpenDislikeManager = { showDislikeManager = true },
                    )
                }
                item {
                    CacheCard(
                        usage = storageUsage,
                        capMb = settings.maxStorageMb,
                        onCapChange = vm::setMaxStorageMb,
                        onSmartClean = vm::smartClean,
                        onClearCoverCache = vm::clearCoverCache,
                        onClearResolveCache = vm::clearResolveCache,
                    )
                }
                item {
                    DownloadCard(
                        dirRaw = settings.downloadDir,
                        defaultPath = defaultDownloadPath,
                        pathCheck = downloadPathCheck,
                        writeTags = settings.downloadWriteTags,
                        onWriteTagsChange = vm::setDownloadWriteTags,
                        writeCover = settings.downloadWriteCover,
                        onWriteCoverChange = vm::setDownloadWriteCover,
                        embedLyric = settings.downloadEmbedLyric,
                        onEmbedLyricChange = vm::setDownloadEmbedLyric,
                        onOpenManager = onOpenDownloadManager,
                        onSaveIfValid = vm::saveDownloadPathIfValid,
                        onConsumeCheck = vm::consumeDownloadPathCheck,
                    )
                }
                item { SyncCard(onOpenSync = onOpenSync) }
                item {
                    ClipboardCard(
                        autoRead = settings.clipboardAutoRead,
                        onAutoReadChange = vm::setClipboardAutoRead,
                    )
                }
                item { LogsCard(onOpenLogs = onOpenLogs) }
                item { AboutCard() }
                item {
                    AuthorCard(onJoinQQGroup = onJoinQQGroup)
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                // 自适应列数：宽屏自动增加列（窄屏 2 列，平板 / 桌面 3~4 列）
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AudioPreferenceCard(
                        platform = settings.defaultPlatform,
                        quality = settings.quality,
                        onPlatformChange = vm::setPlatform,
                        onQualityChange = vm::setQuality,
                    )
                }
                item {
                    AudioSourceCard(
                        lxApiKey = settings.lxApiKey,
                        onLxApiKeyChange = vm::setLxApiKey,
                        onBuyKey = onBuyKey,
                    )
                }
                item { SourceManagerCard(onOpenSources = onOpenSources) }
                item {
                    NcmCard(
                        profile = ncmProfile,
                        login = ncmLogin,
                        syncState = ncmSyncState,
                        onSaveCookie = vm::saveNcmCookie,
                        onClearCookie = vm::clearNcmCookie,
                        onConsumeMessage = vm::consumeNcmLoginMessage,
                        onSyncNow = vm::syncNcmLikes,
                        onConsumeSyncMessage = vm::consumeNcmSyncMessage,
                    )
                }
                item {
                    QqCard(
                        profile = qqProfile,
                        login = qqLogin,
                        syncState = qqSyncState,
                        onSaveCookie = vm::saveQqCookie,
                        onClearCookie = vm::clearQqCookie,
                        onConsumeMessage = vm::consumeQqLoginMessage,
                        onSyncNow = vm::syncQqLikes,
                        onConsumeSyncMessage = vm::consumeQqSyncMessage,
                    )
                }
                item {
                    AppearanceCard(
                        dynamicColor = settings.dynamicColor,
                        darkMode = settings.darkMode,
                        onDynamicColorChange = vm::setDynamicColor,
                        onDarkModeChange = vm::setDarkMode,
                        themeColor = settings.themeColor,
                        onThemeColorChange = vm::setThemeColor,
                        glass = settings.glassMode,
                        onGlassChange = vm::setGlassMode,
                    )
                }
                item {
                    LyricsCard(
                        verbatim = settings.verbatimLyric,
                        onVerbatimChange = vm::setVerbatimLyric,
                        simulated = settings.simulatedVerbatim,
                        onSimulatedChange = vm::setSimulatedVerbatim,
                        s2t = settings.lyricS2T,
                        onS2tChange = vm::setLyricS2T,
                    )
                }
                item {
                    DesktopLyricCard(
                        enabled = settings.desktopLyricEnabled,
                        presetId = settings.desktopLyricPreset,
                        onOpen = { showDesktopLyric = true },
                    )
                }
                item {
                    ListDisplayCard(
                        showSource = settings.listShowSource,
                        onShowSourceChange = vm::setListShowSource,
                        showAlbumName = settings.listShowAlbumName,
                        onShowAlbumNameChange = vm::setListShowAlbumName,
                        showDuration = settings.listShowDuration,
                        onShowDurationChange = vm::setListShowDuration,
                        showCover = settings.listShowCover,
                        onShowCoverChange = vm::setListShowCover,
                        dislikeCount = dislikeRules.size,
                        onOpenDislikeManager = { showDislikeManager = true },
                    )
                }
                item {
                    CacheCard(
                        usage = storageUsage,
                        capMb = settings.maxStorageMb,
                        onCapChange = vm::setMaxStorageMb,
                        onSmartClean = vm::smartClean,
                        onClearCoverCache = vm::clearCoverCache,
                        onClearResolveCache = vm::clearResolveCache,
                    )
                }
                item {
                    DownloadCard(
                        dirRaw = settings.downloadDir,
                        defaultPath = defaultDownloadPath,
                        pathCheck = downloadPathCheck,
                        writeTags = settings.downloadWriteTags,
                        onWriteTagsChange = vm::setDownloadWriteTags,
                        writeCover = settings.downloadWriteCover,
                        onWriteCoverChange = vm::setDownloadWriteCover,
                        embedLyric = settings.downloadEmbedLyric,
                        onEmbedLyricChange = vm::setDownloadEmbedLyric,
                        onOpenManager = onOpenDownloadManager,
                        onSaveIfValid = vm::saveDownloadPathIfValid,
                        onConsumeCheck = vm::consumeDownloadPathCheck,
                    )
                }
                item { SyncCard(onOpenSync = onOpenSync) }
                item {
                    ClipboardCard(
                        autoRead = settings.clipboardAutoRead,
                        onAutoReadChange = vm::setClipboardAutoRead,
                    )
                }
                item { LogsCard(onOpenLogs = onOpenLogs) }
                item { AboutCard() }
                item {
                    AuthorCard(onJoinQQGroup = onJoinQQGroup)
                }
            }
        }
    }

    // 屏蔽管理面板（不喜欢的歌曲）
    if (showDislikeManager) {
        DislikeManagerSheet(onDismiss = { showDislikeManager = false })
    }

    // 桌面歌词设置面板（预设 / 实时预览 / 自定义）
    if (showDesktopLyric) {
        DesktopLyricSheet(onDismiss = { showDesktopLyric = false })
    }
}

/* ---------------- 卡片组件 ---------------- */

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun AudioPreferenceCard(
    platform: MusicPlatform,
    quality: PlayQuality,
    onPlatformChange: (MusicPlatform) -> Unit,
    onQualityChange: (PlayQuality) -> Unit,
) {
    SettingsCard(title = "音频偏好", icon = Icons.Outlined.Tune) {
        Text(
            text = "默认音源平台",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicPlatform.entries.forEach { item ->
                FilterChip(
                    selected = item == platform,
                    onClick = { onPlatformChange(item) },
                    label = { Text(item.shortLabel) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "默认播放音质",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayQuality.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        FilterChip(
                            selected = item == quality,
                            onClick = { onQualityChange(item) },
                            label = { Text(item.label) },
                            leadingIcon = if (item == quality) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

/** 音源服务卡片：Key 填写 + 购买入口 + 官方声明 */
@Composable
private fun AudioSourceCard(
    lxApiKey: String,
    onLxApiKeyChange: (String) -> Unit,
    onBuyKey: () -> Unit,
) {
    SettingsCard(title = "音源服务", icon = Icons.Outlined.Key) {
        Text(
            text = "音源 Key",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ApiKeyField(
            value = lxApiKey,
            onValueChange = onLxApiKeyChange,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onBuyKey) {
            Text("购买 Key")
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text(
                text = "声明：本项目非该音源官方项目，仅为开发用途内置了该音源的解析链接。请自行在官网获取有效 Key 并遵守其服务条款。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** 音源 Key 输入框：默认脱敏（•••）显示，可切换明文；填写后自动保存 */
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var masked by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(value) }
    var edited by remember { mutableStateOf(false) }

    // 设置异步加载完成时回填一次（仅当用户尚未编辑）
    LaunchedEffect(value) {
        if (!edited) text = value
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            edited = true
            text = it
            onValueChange(it)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("音源 Key") },
        placeholder = { Text("粘贴音源服务 Key") },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = {
            IconButton(onClick = { masked = !masked }) {
                Icon(
                    imageVector = if (masked) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (masked) "显示 Key" else "隐藏 Key",
                )
            }
        },
        supportingText = {
            Text("用于解析在线播放地址；填写后自动保存，留空将无法播放")
        },
    )
}

/** 网易云音乐卡片：账号状态 + Cookie 填写（「一起听」依赖此登录） */
@Composable
private fun NcmCard(
    profile: NcmProfile?,
    login: NcmLoginUi,
    syncState: NcmSyncState,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onConsumeMessage: () -> Unit,
    onSyncNow: () -> Unit,
    onConsumeSyncMessage: () -> Unit,
) {
    var showCookieDialog by remember { mutableStateOf(false) }
    var showWebLogin by remember { mutableStateOf(false) }

    // 同步提示 6 秒后自动消失
    val syncMessage = syncState.message
    LaunchedEffect(syncMessage) {
        if (!syncMessage.isNullOrBlank()) {
            delay(6_000)
            onConsumeSyncMessage()
        }
    }

    SettingsCard(title = "网易云音乐", icon = Icons.Outlined.Cloud) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.nickname ?: "未登录",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (profile != null) {
                        "UID ${profile.userId} · 一起听已就绪"
                    } else {
                        "登录后可在「一起听」中与好友同步播放"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (profile?.avatarUrl?.isNotBlank() == true) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (profile == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onConsumeMessage()
                        showWebLogin = true
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("快速登录")
                }
                OutlinedButton(
                    onClick = {
                        onConsumeMessage()
                        showCookieDialog = true
                    },
                ) {
                    Text("填写 Cookie")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "快速登录：在官方页面里登录，自动获取登录态，无需手动复制 Cookie",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(onClick = onClearCookie) {
                Text("退出登录")
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "红心同步（云端 → 本地）",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when {
                            syncState.syncing -> "正在同步…"
                            syncState.lastSyncAt > 0 -> "上次同步：${formatRelativeTime(syncState.lastSyncAt)}"
                            else -> "启动后自动拉取云端红心"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (syncState.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onSyncNow) {
                        Text("立即同步")
                    }
                }
            }
            if (!syncState.message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = syncState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showCookieDialog) {
        NcmCookieDialog(
            busy = login.busy,
            message = login.message,
            success = login.success,
            onSave = onSaveCookie,
            onDismiss = {
                showCookieDialog = false
                onConsumeMessage()
            },
        )
    }

    if (showWebLogin) {
        WebLoginDialog(
            title = "网易云音乐登录",
            subtitle = "在官方页面完成登录，自动获取登录态",
            startUrl = NCM_LOGIN_URL,
            cookieUrl = NCM_COOKIE_URL,
            requiredCookieKeys = NCM_LOGIN_COOKIE_KEYS,
            userAgent = NCM_MOBILE_UA,
            busy = login.busy,
            message = login.message,
            success = login.success,
            onCookie = onSaveCookie,
            onFallbackPaste = {
                showWebLogin = false
                showCookieDialog = true
            },
            onDismiss = {
                showWebLogin = false
                onConsumeMessage()
            },
        )
    }
}

/** Cookie 填写弹窗：粘贴 → 保存并验证 */
@Composable
private fun NcmCookieDialog(
    busy: Boolean,
    message: String?,
    success: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写网易云 Cookie") },
        text = {
            Column {
                Text(
                    text = "获取方式：在浏览器登录网易云音乐网页版后，用开发者工具（或抓包工具）复制 Cookie，至少需包含 MUSIC_U。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("Cookie") },
                    placeholder = { Text("MUSIC_U=…; __csrf=…") },
                )
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在验证…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !busy && text.isNotBlank(),
            ) {
                Text("保存并验证")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}


/** QQ 音乐卡片：账号状态 + Cookie 填写（红心同步依赖此登录） */
@Composable
private fun QqCard(
    profile: QqProfile?,
    login: QqLoginUi,
    syncState: QqSyncState,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onConsumeMessage: () -> Unit,
    onSyncNow: () -> Unit,
    onConsumeSyncMessage: () -> Unit,
) {
    var showCookieDialog by remember { mutableStateOf(false) }
    // 同步提示 6 秒后自动消失
    val syncMessage = syncState.message
    LaunchedEffect(syncMessage) {
        if (!syncMessage.isNullOrBlank()) {
            delay(6_000)
            onConsumeSyncMessage()
        }
    }
    SettingsCard(title = "QQ 音乐", icon = Icons.Outlined.MusicNote) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.nickname ?: "未登录",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (profile != null) {
                        "UIN ${profile.userId} · 红心同步已就绪"
                    } else {
                        "登录后可同步「我喜欢」（本地 ⇄ QQ 音乐）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (profile?.avatarUrl?.isNotBlank() == true) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (profile == null) {
            OutlinedButton(
                onClick = {
                    onConsumeMessage()
                    showCookieDialog = true
                },
            ) {
                Text("填写 Cookie")
            }
        } else {
            OutlinedButton(onClick = onClearCookie) {
                Text("退出登录")
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "红心自动同步",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when {
                            syncState.syncing -> "正在同步…"
                            syncState.lastSyncAt > 0 -> "上次同步：${formatRelativeTime(syncState.lastSyncAt)}"
                            else -> "启动后自动同步（本地 ⇄ QQ 音乐）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (syncState.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onSyncNow) {
                        Text("立即同步")
                    }
                }
            }
            if (!syncState.message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = syncState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

    }
    if (showCookieDialog) {
        QqCookieDialog(
            busy = login.busy,
            message = login.message,
            success = login.success,
            onSave = onSaveCookie,
            onDismiss = {
                showCookieDialog = false
                onConsumeMessage()
            },
        )
    }
}

/** Cookie 填写弹窗：粘贴 → 保存并验证 */
@Composable
private fun QqCookieDialog(
    busy: Boolean,
    message: String?,
    success: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写 QQ 音乐 Cookie") },
        text = {
            Column {
                Text(
                    text = "获取方式：在浏览器登录 y.qq.com 后，用开发者工具（或抓包工具）复制 Cookie，至少需包含 uin 与 qqmusic_key（qm_keyst）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("Cookie") },
                    placeholder = { Text("uin=…; qqmusic_key=…") },
                )
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在验证…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !busy && text.isNotBlank(),
            ) {
                Text("保存并验证")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun AppearanceCard(
    dynamicColor: Boolean,
    darkMode: Int,
    themeColor: String,
    onDynamicColorChange: (Boolean) -> Unit,
    onDarkModeChange: (Int) -> Unit,
    onThemeColorChange: (String) -> Unit,
    glass: Boolean,
    onGlassChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "外观", icon = Icons.Outlined.Palette) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "M3 动态取色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "跟随系统壁纸提取主题色（Android 12+）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = dynamicColor,
                onCheckedChange = onDynamicColorChange,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "主题色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            themePalettes.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { palette ->
                        ThemeColorSwatch(
                            palette = palette,
                            selected = palette.id == themeColor,
                            onClick = { onThemeColorChange(palette.id) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "动态取色开启中：点击色块将关闭动态取色并应用所选配色"
            } else {
                "当前主题色：${themePaletteById(themeColor).label}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "深色模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val options = listOf("跟随系统", "浅色", "深色")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = darkMode == index,
                    onClick = { onDarkModeChange(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "玻璃风格", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "半透明磨砂面板 + 全局流光背景；Android 12+ 支持真实模糊，低版本自动降级",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = glass,
                onCheckedChange = onGlassChange,
            )
        }
    }
}

/** 主题色块：种子色圆点，选中显示描边 + 勾选 */
@Composable
private fun ThemeColorSwatch(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(palette.swatch)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = if (palette.swatch.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 剪贴板卡片：自动读取开关 */
@Composable
private fun ClipboardCard(
    autoRead: Boolean,
    onAutoReadChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "剪贴板", icon = Icons.Outlined.ContentPaste) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "剪切板自动读取", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "从其他应用切回时，自动识别剪贴板中的歌曲 / 歌单 / 一起听链接并询问是否处理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoRead,
                onCheckedChange = onAutoReadChange,
            )
        }
    }
}

/** 歌词卡片：逐字歌词 / 模拟逐字开关 */
@Composable
private fun LyricsCard(
    verbatim: Boolean,
    onVerbatimChange: (Boolean) -> Unit,
    simulated: Boolean,
    onSimulatedChange: (Boolean) -> Unit,
    s2t: Boolean,
    onS2tChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "歌词", icon = Icons.Outlined.Lyrics) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "逐字歌词", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "卡拉OK式逐字高亮（网易云 / QQ / 酷狗）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = verbatim,
                onCheckedChange = onVerbatimChange,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "模拟逐字", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "获取不到字级歌词时，按行时长均匀切分生成逐字效果（需开启上方逐字歌词）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = simulated,
                onCheckedChange = onSimulatedChange,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "繁体歌词", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "将歌词中的简体字转为繁体显示（港澳台地区适用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = s2t,
                onCheckedChange = onS2tChange,
            )
        }
    }
}

/** 桌面歌词卡片：总开关入口 + 当前预设 + 打开样式面板 */
@Composable
private fun DesktopLyricCard(
    enabled: Boolean,
    presetId: String,
    onOpen: () -> Unit,
) {
    val preset = DesktopLyricPreset.fromId(presetId)
    SettingsCard(title = "桌面歌词", icon = Icons.Outlined.Lyrics) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "悬浮显示", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "在其他应用之上显示逐字歌词 · 当前预设「${preset.label}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onOpen() },
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("自定义样式与预设")
        }
    }
}

/**
 * 列表显示卡片：歌曲行元素开关（来源 / 专辑名 / 时长 / 封面）+ 不喜欢管理入口。
 */
@Composable
private fun ListDisplayCard(
    showSource: Boolean,
    onShowSourceChange: (Boolean) -> Unit,
    showAlbumName: Boolean,
    onShowAlbumNameChange: (Boolean) -> Unit,
    showDuration: Boolean,
    onShowDurationChange: (Boolean) -> Unit,
    showCover: Boolean,
    onShowCoverChange: (Boolean) -> Unit,
    dislikeCount: Int,
    onOpenDislikeManager: () -> Unit,
) {
    SettingsCard(title = "列表显示", icon = Icons.AutoMirrored.Outlined.ViewList) {
        ListDisplayToggle(
            title = "显示平台来源",
            subtitle = "在歌名右侧显示网易云 / QQ / 酷狗徽标",
            checked = showSource,
            onCheckedChange = onShowSourceChange,
        )
        Spacer(Modifier.height(16.dp))
        ListDisplayToggle(
            title = "显示专辑名",
            subtitle = "在歌手后追加「· 专辑名」",
            checked = showAlbumName,
            onCheckedChange = onShowAlbumNameChange,
        )
        Spacer(Modifier.height(16.dp))
        ListDisplayToggle(
            title = "显示时长",
            subtitle = "在歌曲行右侧显示时长",
            checked = showDuration,
            onCheckedChange = onShowDurationChange,
        )
        Spacer(Modifier.height(16.dp))
        ListDisplayToggle(
            title = "显示封面",
            subtitle = "显示歌曲缩略封面（关闭更省流量）",
            checked = showCover,
            onCheckedChange = onShowCoverChange,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenDislikeManager)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "不喜欢的歌曲", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (dislikeCount == 0) "屏蔽规则 · 自动切歌时跳过" else "已屏蔽 $dislikeCount 条 · 自动切歌时跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 列表显示开关行（标题 + 说明 + Switch） */
@Composable
private fun ListDisplayToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CacheCard(
    usage: StorageManager.Usage,
    capMb: Int,
    onCapChange: (Int) -> Unit,
    onSmartClean: () -> Unit,
    onClearCoverCache: () -> Unit,
    onClearResolveCache: () -> Unit,
) {
    SettingsCard(title = "存储与缓存", icon = Icons.Outlined.CleaningServices) {
        // 占用概览：封面 / 音源图片缓存 + 临时文件
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "封面 / 音源图片缓存", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = StorageManager.formatBytes(usage.imageCacheBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "临时文件", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = StorageManager.formatBytes(usage.tempBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "最大可占用（超出后自动清理）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        // 状态行：当前总占用 / 上限；超出时高亮提示「将自动清理」
        val capBytes = StorageManager.capBytesOf(capMb)
        val overCap = capMb > 0 && usage.totalCacheBytes > capBytes
        Text(
            text = when {
                capMb <= 0 -> "当前占用 ${StorageManager.formatBytes(usage.totalCacheBytes)}（不限制）"
                overCap -> "当前占用 ${StorageManager.formatBytes(usage.totalCacheBytes)}，已超出上限，" +
                    "将自动清理"
                else -> "当前占用 ${StorageManager.formatBytes(usage.totalCacheBytes)} / " +
                    "上限 ${StorageManager.capLabel(capMb)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (overCap) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            STORAGE_CAP_OPTIONS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (mb, label) ->
                        FilterChip(
                            selected = capMb == mb,
                            onClick = { onCapChange(mb) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSmartClean) {
                Text("立即清理")
            }
            OutlinedButton(onClick = onClearCoverCache) {
                Text("清空图片缓存")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "音源解析缓存", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "清理已解析的播放地址，下次播放将重新解析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClearResolveCache) {
                Text("清理")
            }
        }
    }
}

/** 图片缓存上限选项（MB → 标签；0 = 不限制） */
private val STORAGE_CAP_OPTIONS = listOf(
    256 to "256MB",
    512 to "512MB",
    1024 to "1GB",
    2048 to "2GB",
    0 to "不限",
)

@Composable
private fun LogsCard(onOpenLogs: () -> Unit) {
    SettingsCard(title = "诊断与日志", icon = Icons.AutoMirrored.Outlined.Article) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenLogs)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "运行日志", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "查看运行记录与异常，支持导出分享",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 自定义音源卡片：进入音源管理（导入 / 启用 LX Music 音源脚本） */
@Composable
private fun SourceManagerCard(onOpenSources: () -> Unit) {
    SettingsCard(title = "自定义音源", icon = Icons.Outlined.Extension) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenSources)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "音源管理", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "导入 LX Music 音源脚本（JS / 链接），自定义播放解析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutCard() {
    SettingsCard(title = "关于", icon = Icons.Outlined.Info) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "DPmusic", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "多平台聚合音乐播放器 · Jetpack Media3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "v1.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(
    dirRaw: String,
    defaultPath: String,
    pathCheck: DownloadPaths.Check?,
    writeTags: Boolean,
    onWriteTagsChange: (Boolean) -> Unit,
    writeCover: Boolean,
    onWriteCoverChange: (Boolean) -> Unit,
    embedLyric: Boolean,
    onEmbedLyricChange: (Boolean) -> Unit,
    onOpenManager: () -> Unit,
    onSaveIfValid: (String) -> Unit,
    onConsumeCheck: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    SettingsCard(title = "下载", icon = Icons.Outlined.Download) {
        // 下载管理入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenManager)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "下载管理", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "查看下载任务进度，支持继续 / 重试 / 移除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        // 下载完成后写入文件（元数据增强）
        Text(
            text = "下载完成后写入文件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        DownloadSwitchRow(
            title = "写入歌曲信息",
            subtitle = "歌名 / 歌手 / 专辑标签（MP3 / FLAC）",
            checked = writeTags,
            onCheckedChange = onWriteTagsChange,
        )
        DownloadSwitchRow(
            title = "写入封面",
            subtitle = "将歌曲封面嵌入音频文件",
            checked = writeCover,
            onCheckedChange = onWriteCoverChange,
        )
        DownloadSwitchRow(
            title = "嵌入歌词",
            subtitle = "歌词写入音频文件（MP3）",
            checked = embedLyric,
            onCheckedChange = onEmbedLyricChange,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Text(
            text = "下载音乐路径",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (dirRaw.isBlank()) "$defaultPath（默认）" else dirRaw,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "下载的歌曲将保存到此目录；保存前会自动检查路径是否可写",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                onConsumeCheck()
                showDialog = true
            },
        ) {
            Text("修改下载路径")
        }

        if (showDialog) {
            DownloadPathDialog(
                initialPath = dirRaw.ifBlank { defaultPath },
                defaultPath = defaultPath,
                pathCheck = pathCheck,
                onSaveIfValid = onSaveIfValid,
                onConsumeCheck = onConsumeCheck,
                onClose = { showDialog = false },
            )
        }
    }
}

/** 下载增强开关行 */
@Composable
private fun DownloadSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 数据同步入口卡片 */
@Composable
private fun SyncCard(onOpenSync: () -> Unit) {
    SettingsCard(title = "数据同步", icon = Icons.Outlined.Sync) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onOpenSync)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "WebDAV 数据同步", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "备份与恢复设置、音源、歌单、收藏与下载记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadPathDialog(
    initialPath: String,
    defaultPath: String,
    pathCheck: DownloadPaths.Check?,
    onSaveIfValid: (String) -> Unit,
    onConsumeCheck: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialPath) }

    // 校验通过（已保存）→ 自动关闭
    LaunchedEffect(pathCheck) {
        if (pathCheck is DownloadPaths.Check.Ok) onClose()
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onSaveIfValid(text) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onSaveIfValid(text) }

    AlertDialog(
        onDismissRequest = {
            onConsumeCheck()
            onClose()
        },
        title = { Text("下载音乐路径") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("保存目录") },
                    placeholder = { Text(defaultPath) },
                    singleLine = true,
                    supportingText = { Text("例如：$defaultPath") },
                )
                Spacer(Modifier.height(10.dp))
                when (val check = pathCheck) {
                    null -> Text(
                        text = "填写后点击「验证并保存」检查路径是否可写",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is DownloadPaths.Check.Ok -> Text(
                        text = "✓ 路径可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is DownloadPaths.Check.NeedAllFilesAccess -> {
                        Text(
                            text = "该路径需要「所有文件访问权限」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { allFilesLauncher.launch(intent) }.onFailure {
                                    runCatching {
                                        allFilesLauncher.launch(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("去授予权限")
                        }
                    }

                    is DownloadPaths.Check.NeedStoragePermission -> {
                        Text(
                            text = "该路径需要存储权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            },
                        ) {
                            Text("授予权限")
                        }
                    }

                    is DownloadPaths.Check.NotWritable -> Text(
                        text = "✗ ${check.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { text = defaultPath }) {
                    Text("恢复默认路径")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSaveIfValid(text) }) {
                Text("验证并保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onConsumeCheck()
                    onClose()
                },
            ) {
                Text("取消")
            }
        },
    )
}

/* ---------------- 关于作者卡片 ---------------- */

/** 关于作者卡片：作者信息 + QQ 群一键加入 */
@Composable
private fun AuthorCard(
    onJoinQQGroup: () -> Unit,
) {
    SettingsCard(title = "关于作者", icon = Icons.Outlined.Person) {
        AuthorInfoRow(label = "作者", value = "CYC")
        Spacer(Modifier.height(10.dp))
        AuthorInfoRow(label = "QQ", value = AUTHOR_QQ)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthorInfoRow(
                label = "QQ群",
                value = QQ_GROUP,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onJoinQQGroup) {
                Text("加入Q群")
            }
        }
    }
}

/** 作者信息行：左标签 + 右内容 */
@Composable
private fun AuthorInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/* ---------------- 联系与链接常量 ---------------- */

/** 音源 Key 购买入口 */
private const val SHOP_URL = "https://shop.shiqianjiang.cn/"

/** 作者联系方式 */
private const val AUTHOR_QQ = "3188144633"
private const val QQ_GROUP = "1125132981"