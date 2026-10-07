package com.dpmusic.app.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.material.icons.outlined.ToggleOn
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import com.dpmusic.app.core.data.enabledPlatforms
import com.dpmusic.app.core.download.DownloadPaths
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmProfile
import com.dpmusic.app.core.model.KgLiteProfile
import com.dpmusic.app.core.model.QqProfile
import com.dpmusic.app.core.lyric.DesktopLyricPreset
import com.dpmusic.app.core.miisland.MiIslandController
import com.dpmusic.app.core.miisland.MiIslandLayout
import com.dpmusic.app.core.miisland.MiIslandMode
import com.dpmusic.app.core.miisland.MiIslandPalette
import com.dpmusic.app.core.miisland.MiIslandPrefs
import com.dpmusic.app.core.miisland.MiSuperIslandMagic
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.net.KgLiteClaimResult
import com.dpmusic.app.core.util.StorageManager
import com.dpmusic.app.core.audio.BitPerfectController
import com.dpmusic.app.core.util.formatRelativeTime
import com.dpmusic.app.ui.components.DesktopLyricSheet
import com.dpmusic.app.ui.components.DislikeManagerSheet
import com.dpmusic.app.ui.components.VisualizerMode
import com.dpmusic.app.ui.components.DpTopAppBar
import com.dpmusic.app.ui.components.GlassSurface
import com.dpmusic.app.ui.components.NCM_COOKIE_URL
import com.dpmusic.app.ui.components.NCM_LOGIN_COOKIE_KEYS
import com.dpmusic.app.ui.components.NCM_LOGIN_URL
import com.dpmusic.app.ui.components.NCM_MOBILE_UA
import com.dpmusic.app.ui.components.WebLoginDialog
import com.dpmusic.app.ui.components.QishuiAccountCard
import com.dpmusic.app.ui.theme.CoverColorStyle
import com.dpmusic.app.ui.theme.ThemePalette
import com.dpmusic.app.ui.theme.themePaletteById
import com.dpmusic.app.ui.theme.themePalettes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import com.dpmusic.app.ui.theme.glassPanelColor

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
    onOpenQishuiPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
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
    val kgLiteProfile by vm.kgLiteProfile.collectAsStateWithLifecycle()
    val kgLiteUi by vm.kgLiteUi.collectAsStateWithLifecycle()
    val biliAccount by vm.biliAccount.collectAsStateWithLifecycle()
    val biliLogin by vm.biliLogin.collectAsStateWithLifecycle()
    val qishuiRelayCheck by vm.qishuiRelayCheck.collectAsStateWithLifecycle()
    val kgLiteClaimResult by vm.kgLiteClaimResult.collectAsStateWithLifecycle()
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

    // 分区状态：配置变更（旋转 / 分屏）后保持，避免回到首屏
    var tabName by rememberSaveable { mutableStateOf(SettingsTab.Common.name) }
    val tab = SettingsTab.entries.firstOrNull { it.name == tabName } ?: SettingsTab.Common

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SettingsTabRow(
                selected = tab,
                onSelect = { tabName = it.name },
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            )

            // 切页过渡：按分区顺序决定滑动方向，避免"回跳"错觉
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally(tween(300)) { full ->
                        if (forward) full / 8 else -full / 8
                    } + fadeIn(tween(260))
                    val exit = slideOutHorizontally(tween(300)) { full ->
                        if (forward) -full / 8 else full / 8
                    } + fadeOut(tween(180))
                    enter togetherWith exit
                },
                label = "settingsTabContent",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                val cards: List<@Composable () -> Unit> = when (current) {
                    SettingsTab.Common -> listOf(
                        {
                            AudioPreferenceCard(
                                platform = settings.defaultPlatform,
                                platforms = settings.enabledPlatforms(),
                                quality = settings.quality,
                                onPlatformChange = vm::setPlatform,
                                onQualityChange = vm::setQuality,
                                autoHighest = settings.qualityAutoHighest,
                                onAutoHighestChange = vm::setQualityAutoHighest,
                            )
                        },
                        {
                            AppearanceCard(
                                dynamicColor = settings.dynamicColor,
                                darkMode = settings.darkMode,
                                onDynamicColorChange = vm::setDynamicColor,
                                onDarkModeChange = vm::setDarkMode,
                                themeColor = settings.themeColor,
                                onThemeColorChange = vm::setThemeColor,
                                glass = settings.glassMode,
                                onGlassChange = vm::setGlassMode,
                                coverDynamicColor = settings.coverDynamicColor,
                                onCoverDynamicColorChange = vm::setCoverDynamicColor,
                                coverColorStyle = settings.coverColorStyle,
                                onCoverColorStyleChange = vm::setCoverColorStyle,
                            )
                        },
                        {
                            ListDisplayCard(
                                showSource = settings.listShowSource,
                                onShowSourceChange = vm::setListShowSource,
                                showAlbumName = settings.listShowAlbumName,
                                onShowAlbumNameChange = vm::setListShowAlbumName,
                                showDuration = settings.listShowDuration,
                                onShowDurationChange = vm::setListShowDuration,
                                showCover = settings.listShowCover,
                                onShowCoverChange = vm::setListShowCover,
                                showQuality = settings.listShowQuality,
                                onShowQualityChange = vm::setListShowQuality,
                                dislikeCount = dislikeRules.size,
                                onOpenDislikeManager = { showDislikeManager = true },
                            )
                        },
                        {
                            ClipboardCard(
                                autoRead = settings.clipboardAutoRead,
                                onAutoReadChange = vm::setClipboardAutoRead,
                            )
                        },
                    )

                    SettingsTab.Sources -> listOf(
                        {
                            AudioSourceCard(
                                lxApiKey = settings.lxApiKey,
                                onLxApiKeyChange = vm::setLxApiKey,
                                onBuyKey = onBuyKey,
                            )
                        },
                        { SourceManagerCard(onOpenSources = onOpenSources) },
                        {
                            SourceToggleCard(
                                qishuiEnabled = settings.qishuiEnabled,
                                biliEnabled = settings.biliEnabled,
                                onQishuiChange = vm::setQishuiEnabled,
                                onBiliChange = vm::setBiliEnabled,
                            )
                        },
                        {
                            QishuiRelayCard(
                                url = settings.qishuiRelayUrl,
                                secret = settings.qishuiRelaySecret,
                                lossless = settings.qishuiLossless,
                                check = qishuiRelayCheck,
                                onUrlChange = vm::setQishuiRelayUrl,
                                onSecretChange = vm::setQishuiRelaySecret,
                                onLosslessChange = vm::setQishuiLossless,
                                onTest = vm::checkQishuiRelay,
                                onConsumeCheck = vm::consumeQishuiRelayCheck,
                            )
                        },
                        {
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
                        },
                        {
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
                        },
                        {
                            KgLiteCard(
                                enabled = settings.kgLiteEnabled,
                                force = settings.kgLiteForce,
                                autoClaim = settings.kgLiteAutoClaim,
                                profile = kgLiteProfile,
                                login = kgLiteUi,
                                claimResult = kgLiteClaimResult,
                                onEnabledChange = vm::setKgLiteEnabled,
                                onForceChange = vm::setKgLiteForce,
                                onAutoClaimChange = vm::setKgLiteAutoClaim,
                                onClaimNow = vm::claimKgLiteVipNow,
                                onSendSmsCode = vm::sendKgLiteSmsCode,
                                onLoginByCode = vm::loginKgLiteByCode,
                                onClearLogin = vm::clearKgLiteLogin,
                                onConsumeMessage = vm::consumeKgLiteMessage,
                                onConsumeClaimResult = vm::consumeKgLiteClaimResult,
                            )
                        },
{
                            BiliCard(
                                account = biliAccount,
                                login = biliLogin,
                                onSaveCookie = vm::saveBiliCookie,
                                onClearCookie = vm::clearBiliLogin,
                                onRefresh = vm::refreshBiliAccount,
                                onConsumeMessage = vm::consumeBiliMessage,
                            )
                        },
                        { QishuiAccountCard(onOpenPlaylist = onOpenQishuiPlaylist) },
                    )
                    SettingsTab.Playback -> listOf(
                        {
                            AudioDspCard(
                                bitPerfectEnabled = settings.bitPerfectEnabled,
                                onBitPerfectChange = vm::setBitPerfectEnabled,
                                visualizerEnabled = settings.visualizerEnabled,
                                onVisualizerChange = vm::setVisualizerEnabled,
                                visualizerMode = settings.visualizerMode,
                                onVisualizerModeChange = vm::setVisualizerMode,
                            )
                        },
                        {
                            LyricsCard(
                                verbatim = settings.verbatimLyric,
                                onVerbatimChange = vm::setVerbatimLyric,
                                simulated = settings.simulatedVerbatim,
                                onSimulatedChange = vm::setSimulatedVerbatim,
                                s2t = settings.lyricS2T,
                                onS2tChange = vm::setLyricS2T,
                                carLyric = settings.carLyricEnabled,
                                onCarLyricChange = vm::setCarLyricEnabled,
                                carLyricFullLrc = settings.carLyricFullLrc,
                                onCarLyricFullLrcChange = vm::setCarLyricFullLrc,
                            )
                        },
                        {
                            DesktopLyricCard(
                                enabled = settings.desktopLyricEnabled,
                                presetId = settings.desktopLyricPreset,
                                onOpen = { showDesktopLyric = true },
                            )
                        },
                        {
                            MiIslandCard(
                                mode = settings.miIslandMode,
                                onModeChange = vm::setMiIslandMode,
                                snackbar = snackbarHostState,
                            )
                        },
                    )

                    SettingsTab.Storage -> listOf(
                        {
                            CacheCard(
                                usage = storageUsage,
                                capMb = settings.maxStorageMb,
                                onCapChange = vm::setMaxStorageMb,
                                onSmartClean = vm::smartClean,
                                onClearCoverCache = vm::clearCoverCache,
                                onClearResolveCache = vm::clearResolveCache,
                            )
                        },
                        {
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
                        },
                    )

                    SettingsTab.More -> listOf(
                        { SyncCard(onOpenSync = onOpenSync) },
                        { LogsCard(onOpenLogs = onOpenLogs) },
                        { AboutCard() },
                        { AuthorCard(onJoinQQGroup = onJoinQQGroup) },
                    )
                }

                SettingsTabContent(
                    compact = compact,
                    cards = cards,
                )
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
    platforms: List<MusicPlatform>,
    quality: PlayQuality,
    onPlatformChange: (MusicPlatform) -> Unit,
    onQualityChange: (PlayQuality) -> Unit,
    autoHighest: Boolean,
    onAutoHighestChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "音频偏好", icon = Icons.Outlined.Tune) {
        Text(
            text = "默认音源平台",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 只列**已启用**的平台：可切换的默认平台不该包含被关掉的音源
            platforms.forEach { item ->
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
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "默认以标定最高音质播放", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (autoHighest) {
                        "每首歌按列表接口标定的最高可用规格起播（如上为兜底档位）"
                    } else {
                        "开启后按每首歌标定的最高可用规格起播，仍有降级保护"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoHighest, onCheckedChange = onAutoHighestChange)
        }
    }
}

/** 音源开关卡片：汽水 / B 站 这类「强偏好型」音源的可选启停 */
@Composable
private fun SourceToggleCard(
    qishuiEnabled: Boolean,
    biliEnabled: Boolean,
    onQishuiChange: (Boolean) -> Unit,
    onBiliChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "音源开关", icon = Icons.Outlined.ToggleOn) {
        Text(
            text = "关掉的音源不会出现在平台选择里，也不参与解析与跨平台兜底。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        SourceToggleRow(
            title = "汽水音乐",
            subtitle = if (qishuiEnabled) {
                "已开启 · 匿名可取免费歌全曲，VIP 曲目 30 秒试听"
            } else {
                "已关闭 · 汽水曲目无法解析"
            },
            checked = qishuiEnabled,
            onCheckedChange = onQishuiChange,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(10.dp))
        SourceToggleRow(
            title = "哔哩哔哩",
            subtitle = if (biliEnabled) {
                "已开启 · 视频站当音乐源，搜歌实为搜视频（登录后有无损）"
            } else {
                "已关闭 · 默认关闭，B 站为强偏好型音源"
            },
            checked = biliEnabled,
            onCheckedChange = onBiliChange,
        )
    }
}

/** 音源开关行：左文右开关，整行可点（扩大点击热区，不用精准戳开关） */
@Composable
private fun SourceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 汽水 relay 卡片：中转地址 + 设备密钥 + 一路「测试连接」（协议 v1） */
@Composable
private fun QishuiRelayCard(
    url: String,
    secret: String,
    lossless: Boolean,
    check: QishuiRelayCheckUi,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onLosslessChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onConsumeCheck: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(check.message, check.success) {
        check.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onConsumeCheck()
        }
    }
    SettingsCard(title = "汽水 relay（家里电脑取链）", icon = Icons.Outlined.Cloud) {
        Text(
            text = "填写后，汽水曲目改由「家里已登录汽水客户端」解析，可拿到完整歌" +
                "（is_full_length=true）与高音质；留空则退回匿名通道（热门曲多为试听片段）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        RelayUrlField(value = url, onValueChange = onUrlChange)
        Spacer(Modifier.height(10.dp))
        RelaySecretField(value = secret, onValueChange = onSecretChange)
        Spacer(Modifier.height(6.dp))
        SourceToggleRow(
            title = "优先真无损",
            subtitle = if (lossless) {
                "已开启 · 请求 FLAC（约 1004 kbps），单曲 ~28 MB，移动网络慎用"
            } else {
                "已关闭 · 使用默认 hi_res（AAC）"
            },
            checked = lossless,
            onCheckedChange = onLosslessChange,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onTest, enabled = !check.busy) {
                if (check.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("测试连接")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (check.success) "链路正常" else "未验证",
                style = MaterialTheme.typography.bodySmall,
                color = if (check.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text(
                text = "说明：取链首次约 18 秒（之后同曲 ~2 秒）；最高为高音质（AAC ~634kbps，无无损）；" +
                    "家中设备离线时取链失败，会自动退回匿名通道。设备密钥为对称密钥，仅保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** 中转地址输入框：本地即时状态 + 异步加载完成后回填一次 */
@Composable
private fun RelayUrlField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var edited by remember { mutableStateOf(false) }
    // 设置异步加载完成时回填一次（仅当用户尚未编辑），避免打字被"回滚"
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
        label = { Text("中转地址") },
        placeholder = { Text("http://122.10.114.177:8080") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

/** 设备密钥输入框：默认脱敏，可切换明文 */
@Composable
private fun RelaySecretField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var masked by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(value) }
    var edited by remember { mutableStateOf(false) }
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
        label = { Text("设备密钥") },
        placeholder = { Text("DEVICE_SECRET") },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = {
            IconButton(onClick = { masked = !masked }) {
                Icon(
                    imageVector = if (masked) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (masked) "显示密钥" else "隐藏密钥",
                )
            }
        },
    )
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

/** 酷狗概念版音源卡片：开关 + 强制优先 + 账号登录（手机号验证码）+ 每日领 VIP */
@Composable
private fun KgLiteCard(
    enabled: Boolean,
    force: Boolean,
    autoClaim: Boolean,
    profile: KgLiteProfile?,
    login: KgLiteLoginUi,
    claimResult: KgLiteClaimResult?,
    onEnabledChange: (Boolean) -> Unit,
    onForceChange: (Boolean) -> Unit,
    onAutoClaimChange: (Boolean) -> Unit,
    onClaimNow: () -> Unit,
    onSendSmsCode: (String) -> Unit,
    onLoginByCode: (String, String) -> Unit,
    onClearLogin: () -> Unit,
    onConsumeMessage: () -> Unit,
    onConsumeClaimResult: () -> Unit,
) {
    var showLogin by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 签到结果 → Snackbar
    LaunchedEffect(claimResult) {
        claimResult?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            onConsumeClaimResult()
        }
    }

    SettingsCard(title = "酷狗概念版音源", icon = Icons.Outlined.MusicNote) {
        // 总开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用概念版音源", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Key / 脚本 / 插件之外的第四条解析通道，仅对酷狗曲库生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(10.dp))
        // 强制优先
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("优先使用概念版", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "开启后概念版优先解析（登录 VIP 账号可提高付费歌全曲命中率）；失败自动回退",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = force, onCheckedChange = onForceChange, enabled = enabled)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        // 账号状态
        Text(
            text = profile?.nickname?.takeIf { it.isNotBlank() } ?: "未登录（匿名仍可解析免费歌全曲）",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (profile != null) {
                "UID ${profile.userId}${if (profile.vipType > 0) " · VIP(${profile.vipType})" else ""}"
            } else {
                "匿名：免费歌全曲可用，付费歌仅 60s 试听；手机号登录后可提升"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (profile == null) {
            FilledTonalButton(onClick = { showLogin = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Login,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("手机号登录")
            }
        } else {
            OutlinedButton(onClick = onClearLogin) { Text("退出登录") }
        }
        if (!login.message.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = login.message,
                style = MaterialTheme.typography.labelSmall,
                color = if (login.success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        // 每日领 VIP
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("每日自动领 VIP", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "启动后自动领取「听歌 / 广告」免费 VIP（需登录；每日一次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoClaim,
                onCheckedChange = onAutoClaimChange,
                enabled = profile != null,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onClaimNow,
            enabled = profile != null,
        ) {
            Icon(Icons.Outlined.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("立即领取")
        }
        if (profile == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "登录后可每日自动领取免费 VIP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
        ) {
            Text(
                text = "注意：自动领取属账号自动化操作，可能违反酷狗用户协议并触发风控（限制登录 / 封号）。" +
                    "仅在了解风险后开启，风险自负。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text(
                text = "说明：免费歌匿名即可取全曲；付费歌与高音质全曲需有效登录态（服务端版权判定）。" +
                    "登录手机号与验证码仅用于本次登录校验，登录态只保存在本机、用于取播放地址。" +
                    "请遵守酷狗用户协议，仅个人学习自用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }

    if (showLogin) {
        KgLiteLoginDialog(
            login = login,
            onSendSmsCode = onSendSmsCode,
            onLoginByCode = onLoginByCode,
            onDismiss = {
                showLogin = false
                onConsumeMessage()
            },
        )
    }
}

/** 酷狗概念版登录弹窗：手机号 + 短信验证码（无需人机验证） */
@Composable
private fun KgLiteLoginDialog(
    login: KgLiteLoginUi,
    onSendSmsCode: (String) -> Unit,
    onLoginByCode: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val phoneOk = remember(mobile) { mobile.trim().matches(Regex("^1\\d{10}$")) }

    // 登录成功后自动关闭
    LaunchedEffect(login.success) {
        if (login.success) {
            delay(900)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("酷狗概念版登录") },
        text = {
            Column {
                Text(
                    text = "输入手机号获取验证码即可登录（概念版验证码登录无需图形/滑块验证）。" +
                        "登录态仅保存在本机，用于取播放地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { if (it.length <= 11) mobile = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !login.busy,
                    label = { Text("手机号") },
                    placeholder = { Text("11 位手机号") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !login.busy,
                        label = { Text("验证码") },
                        placeholder = { Text("短信验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        enabled = phoneOk && !login.smsSending && login.countdown == 0,
                        onClick = { onSendSmsCode(mobile.trim()) },
                    ) {
                        Text(
                            when {
                                login.smsSending -> "发送中…"
                                login.countdown > 0 -> "${login.countdown}s"
                                login.smsSent -> "重新获取"
                                else -> "获取验证码"
                            },
                        )
                    }
                }
                if (login.busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在登录…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!login.message.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = login.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (login.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = !login.busy && phoneOk && code.isNotBlank(),
                onClick = { onLoginByCode(mobile.trim(), code.trim()) },
            ) { Text("登录") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
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

/** 哔哩哔哩卡片：可选登录（Cookie）→ 按账号会员态决定可用的音质上限 */
@Composable
private fun BiliCard(
    account: com.dpmusic.app.core.model.BiliAccount?,
    login: BiliLoginUi,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onRefresh: () -> Unit,
    onConsumeMessage: () -> Unit,
) {
    var showCookieDialog by remember { mutableStateOf(false) }
    // 提示 6 秒后自动消失
    val message = login.message
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            delay(6_000)
            onConsumeMessage()
        }
    }
    SettingsCard(title = "哔哩哔哩", icon = Icons.Outlined.MusicNote) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account?.nickname?.ifBlank { "已登录" } ?: "未登录（匿名）",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        account == null -> "匿名可用：搜索 / 播放（仅 AAC 128K/320K）"
                        account.isVip -> "大会员 · 可解锁无损 FLAC / 全景声（视视频是否提供）"
                        else -> "非大会员 · 仅 AAC（无损需大会员）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account?.avatar?.isNotBlank() == true) {
                AsyncImage(
                    model = account.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (account == null) {
            OutlinedButton(
                onClick = {
                    onConsumeMessage()
                    showCookieDialog = true
                },
            ) {
                Text("填写 Cookie 登录")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "登录后可解锁无损音质；B 站风控更宽松，搜索更稳。不登录也可正常使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onClearCookie) { Text("退出登录") }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onRefresh, enabled = !login.busy) {
                    if (login.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("刷新账号")
                    }
                }
            }
        }
        if (!login.message.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = login.message.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (login.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
    if (showCookieDialog) {
        BiliCookieDialog(
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

/** Cookie 填写弹窗：粘贴 → 保存并验证（至少需 SESSDATA） */
@Composable
private fun BiliCookieDialog(
    busy: Boolean,
    message: String?,
    success: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写哔哩哔哩 Cookie") },
        text = {
            Column {
                Text(
                    text = "获取方式：在浏览器登录 bilibili.com 后，用开发者工具复制 Cookie，" +
                        "至少需包含 SESSDATA（建议连同 bili_jct / DedeUserID 一起）。" +
                        "Cookie 仅保存在本机，用于取流；不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Cookie") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
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
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存并验证")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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

@OptIn(ExperimentalLayoutApi::class)
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
    coverDynamicColor: Boolean,
    onCoverDynamicColorChange: (Boolean) -> Unit,
    coverColorStyle: String,
    onCoverColorStyleChange: (String) -> Unit,
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

        Spacer(Modifier.height(16.dp))

        // 播放页封面动态取色：封面种子色 → 一套 MD3 配色，仅覆盖全屏播放页
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "播放页封面动态取色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "按当前封面提取主色，为播放页单独生成一套 M3 配色（不影响其他页面）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = coverDynamicColor,
                onCheckedChange = onCoverDynamicColorChange,
            )
        }
        if (coverDynamicColor) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "配色风格",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoverColorStyle.entries.forEach { style ->
                    FilterChip(
                        selected = style == CoverColorStyle.fromId(coverColorStyle),
                        onClick = { onCoverColorStyleChange(style.id) },
                        label = { Text(style.label) },
                    )
                }
            }
        }
    }
}

/**
 * 音频 DSP 卡片：Bit-Perfect 独占输出 + 播放页示波器。
 *
 * 两个开关都**默认关闭**，用户可随时启用；状态说明取自 [BitPerfectController]
 * 的实时状态（插拔 USB DAC 会自动刷新文案）。
 */
@Composable
private fun AudioDspCard(
    bitPerfectEnabled: Boolean,
    onBitPerfectChange: (Boolean) -> Unit,
    visualizerEnabled: Boolean,
    onVisualizerChange: (Boolean) -> Unit,
    visualizerMode: String,
    onVisualizerModeChange: (String) -> Unit,
) {
    val bp by BitPerfectController.state.collectAsStateWithLifecycle()
    val modes = remember { VisualizerMode.entries }

    SettingsCard(title = "音频", icon = Icons.Outlined.GraphicEq) {
        // ---- Bit-Perfect ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "USB 独占（Bit-Perfect）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = bp.reason.ifBlank {
                        if (Build.VERSION.SDK_INT >= 34) {
                            "绕过系统重采样，原样直通 USB DAC（Android 14+）"
                        } else {
                            "需要 Android 14 及以上"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bp.active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = bitPerfectEnabled,
                onCheckedChange = onBitPerfectChange,
            )
        }
        if (bitPerfectEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "⚠️ 独占期间均衡器会被强制旁路（任何 DSP 都会破坏位完美直通）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- 示波器 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "播放页示波器", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "播放页显示实时频谱 / 波形（在音频链内旁听，零权限、不改变音质）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = visualizerEnabled,
                onCheckedChange = onVisualizerChange,
            )
        }
        if (visualizerEnabled) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "展示内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = VisualizerMode.fromId(visualizerMode) == m,
                        onClick = { onVisualizerModeChange(m.id) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(m.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
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

/** 歌词卡片：逐字 / 模拟逐字 / 繁体 / 车载蓝牙歌词（含整首 LRC 子开关） */
@Composable
private fun LyricsCard(
    verbatim: Boolean,
    onVerbatimChange: (Boolean) -> Unit,
    simulated: Boolean,
    onSimulatedChange: (Boolean) -> Unit,
    s2t: Boolean,
    onS2tChange: (Boolean) -> Unit,
    carLyric: Boolean,
    onCarLyricChange: (Boolean) -> Unit,
    carLyricFullLrc: Boolean,
    onCarLyricFullLrcChange: (Boolean) -> Unit,
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
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "车载 / 蓝牙歌词", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "把当前歌词行写进系统媒体元数据的标题，供蓝牙、车机与锁屏显示；同时写入 lyricInfo 供 ColorOS 锁屏岛等读取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = carLyric,
                onCheckedChange = onCarLyricChange,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "发送整首 LRC", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "额外把整首带时间轴的歌词写入元数据（lyricInfo / MIUI LYRIC）。蓝牙链路不读它，仅当车机或锁屏岛需要自己滚动整首歌词时开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = carLyricFullLrc,
                // 主开关关闭时该通道本来就不会写入，禁用避免误解为「已生效」
                enabled = carLyric,
                onCheckedChange = onCarLyricFullLrcChange,
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
 * 小米超级岛卡片（HyperOS 专属高级选项）。
 *
 * 三段内容：
 * 1. 形态选择（关闭 / 歌词 / 发光歌词）；
 * 2. 运行条件自检 —— HyperOS、Shizuku 授权与服务、小米服务框架（XMSF，签名校验发起方）；
 * 3. 岛内文案细节 —— 标题固定为歌曲名（可自定义）、歌词长度上限。
 *
 * 非小米设备上该功能不可能生效，直接不渲染（调用处无需再加判定）。
 */
@Composable
private fun MiIslandCard(
    mode: MiIslandMode,
    onModeChange: (MiIslandMode) -> Unit,
    snackbar: SnackbarHostState,
) {
    if (!MiSuperIslandMagic.isXiaomiDevice()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shizuku 状态用本地状态缓存：只在授权回调 / binder 就绪时刷新，避免每帧探测 binder
    var granted by remember { mutableStateOf(MiSuperIslandMagic.isShizukuGranted()) }
    var running by remember { mutableStateOf(MiSuperIslandMagic.isShizukuRunning()) }
    val installed = remember { MiSuperIslandMagic.isShizukuInstalled(context) }

    // 岛内文案细节（独立 SharedPreferences：播放前台服务需同步读取，见 MiIslandPrefs）
    var persistentTitle by remember { mutableStateOf(MiIslandPrefs.isSongTitlePersistent()) }
    var customTitle by remember { mutableStateOf(MiIslandPrefs.getCustomTitle()) }
    var limitLength by remember { mutableStateOf(MiIslandPrefs.isLyricLengthLimitEnabled()) }
    var maxLength by remember { mutableIntStateOf(MiIslandPrefs.getMaxLyricLength()) }
    var autoExpand by remember { mutableStateOf(MiIslandPrefs.isAutoExpandOnStart()) }

    // 卡片样式：布局 × 配色（见 MiIslandStyle.kt）
    var islandLayout by remember { mutableStateOf(MiIslandPrefs.getLayout()) }
    var islandPalette by remember { mutableStateOf(MiIslandPrefs.getPalette()) }
    var customColorHex by remember {
        mutableStateOf(
            MiIslandPrefs.getCustomColor().takeIf { it != 0 }?.let { "#%06X".format(0xFFFFFF and it) }
                ?: "#31C27C",
        )
    }

    // Shizuku 授权结果 / binder 就绪：实时刷新上方状态行
    DisposableEffect(Unit) {
        val binderListener = Shizuku.OnBinderReceivedListener {
            running = true
            granted = MiSuperIslandMagic.isShizukuGranted()
        }
        val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == SHIZUKU_REQUEST_CODE) {
                granted = result == PackageManager.PERMISSION_GRANTED
                scope.launch {
                    snackbar.showSnackbar(
                        if (granted) "Shizuku 已授权，超级岛可正常渲染" else "Shizuku 授权被拒绝",
                    )
                }
            }
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(binderListener)
                Shizuku.removeRequestPermissionResultListener(permissionListener)
            }
        }
    }

    // 自定义标题：击键级落盘过于频繁，防抖后再写
    LaunchedEffect(customTitle) {
        delay(400)
        MiIslandPrefs.setCustomTitle(customTitle)
    }

    val hyperOs = MiSuperIslandMagic.isHyperOs()
    val hasXmsf = MiSuperIslandMagic.xmsfUid(context) > 0

    SettingsCard(title = "小米超级岛", icon = Icons.Outlined.Bolt) {
        Text(
            text = "把当前歌词显示到 HyperOS 超级岛。系统只对通过 MIUI 签名校验的通知渲染超级岛，" +
                "本功能在发送通知的瞬间阻断校验请求（「断网魔法」）以通过校验，因此需要 Shizuku 授权；" +
                "未授权时自动降级为普通媒体通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        MiIslandMode.entries.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { item ->
                    FilterChip(
                        selected = item == mode,
                        onClick = { onModeChange(item) },
                        enabled = hyperOs,
                        label = { Text(item.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = mode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiIslandStatusRow("系统版本", if (hyperOs) "HyperOS" else "不支持", hyperOs)
                MiIslandStatusRow("Shizuku 应用", if (installed) "已安装" else "未安装", installed)
                MiIslandStatusRow("Shizuku 授权", if (granted) "已授权" else "未授权", granted)
                MiIslandStatusRow("Shizuku 服务", if (running) "运行中" else "未运行", running)
                MiIslandStatusRow("小米服务框架", if (hasXmsf) "已安装" else "未安装", hasXmsf)
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            !hyperOs -> Text(
                text = "当前系统不是 HyperOS，超级岛不可用（MIUI 上没有该能力）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            !installed -> OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
                }
            }) {
                Text("安装 Shizuku")
            }

            !granted || !running -> FilledTonalButton(onClick = {
                requestShizukuPermission(scope, snackbar)
            }) {
                Text("申请 Shizuku 授权")
            }

            !hasXmsf -> Text(
                text = "未检测到小米服务框架（com.xiaomi.xmsf），签名校验链路缺失，超级岛无法渲染。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Text(
                text = "条件已就绪，播放歌曲即可在超级岛看到歌词。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (mode.usesLyricIsland) {
            Spacer(Modifier.height(16.dp))

            Text(text = "卡片样式", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "展开态大岛的插槽排布方式（系统按填了哪些插槽决定样式）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiIslandLayout.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = hyperOs) {
                                islandLayout = item
                                MiIslandPrefs.setLayout(item)
                                MiIslandController.requestRefresh()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = item == islandLayout,
                            onClick = null,
                            enabled = hyperOs,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (item.showsLyric) item.label else "${item.label}（无歌词）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(text = "配色", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "「跟随封面」从当前封面提取主色，其余为固定预设",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiIslandPalette.entries.chunked(3).forEach { rowPalettes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPalettes.forEach { item ->
                            FilterChip(
                                selected = item == islandPalette,
                                onClick = {
                                    islandPalette = item
                                    MiIslandPrefs.setPalette(item)
                                    MiIslandController.requestRefresh()
                                },
                                enabled = hyperOs,
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }

            if (islandPalette == MiIslandPalette.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customColorHex,
                    onValueChange = { input ->
                        customColorHex = input
                        parseHexColor(input)?.let { color ->
                            MiIslandPrefs.setCustomColor(color)
                            MiIslandController.requestRefresh()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = parseHexColor(customColorHex) == null,
                    label = { Text("自定义颜色（#RRGGBB）") },
                    leadingIcon = {
                        parseHexColor(customColorHex)?.let {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF000000 or (it.toLong() and 0xFFFFFF))),
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "自动展开大岛", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "开启后切歌 / 内容变化时自动弹出展开态；关闭则保持小胶囊，点击岛再展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoExpand,
                    onCheckedChange = {
                        autoExpand = it
                        MiIslandPrefs.setAutoExpandOnStart(it)
                        MiIslandController.requestRefresh()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "标题固定为歌曲名", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "开启后小岛标题始终显示歌曲名（不随歌词滚动），歌词另占一行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = persistentTitle,
                    onCheckedChange = {
                        persistentTitle = it
                        MiIslandPrefs.setSongTitlePersistent(it)
                        MiIslandController.requestRefresh()
                    },
                )
            }

            if (persistentTitle) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it.take(24) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("自定义标题（留空用歌曲名）") },
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "歌词长度上限", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "大岛右侧空间有限，超长歌词会换行挤压布局",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = limitLength,
                    onCheckedChange = {
                        limitLength = it
                        MiIslandPrefs.setLyricLengthLimitEnabled(it)
                        MiIslandController.requestRefresh()
                    },
                )
            }

            if (limitLength) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LYRIC_LENGTH_OPTIONS.forEach { len ->
                        FilterChip(
                            selected = len == maxLength,
                            onClick = {
                                maxLength = len
                                MiIslandPrefs.setMaxLyricLength(len)
                                MiIslandController.requestRefresh()
                            },
                            label = { Text("$len 字") },
                        )
                    }
                }
            }
        }
    }
}

/** 申请 Shizuku 授权：v11 以下或用户已选「不再询问」时只能去 Shizuku 应用内手动授权 */
private fun requestShizukuPermission(scope: CoroutineScope, snackbar: SnackbarHostState) {
    val manualHint = "请打开 Shizuku 应用，为 DPmusic 手动开启授权"
    try {
        if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) {
            scope.launch { snackbar.showSnackbar(manualHint) }
            return
        }
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    } catch (_: Exception) {
        scope.launch { snackbar.showSnackbar("无法请求授权：请先启动 Shizuku 服务") }
    }
}
/**
 * 解析 `#RRGGBB` / `RRGGBB` 形式的颜色；非法输入返回 null。
 *
 * 设置页对自定义颜色的输入是逐字符的（用户边打边看），因此这里必须容错：
 * 只有完整合法时才返回颜色，非法时调用方只把输入框标红、不落盘。
 */
private fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6) return null
    val value = hex.toLongOrNull(16) ?: return null
    return (0xFF000000L or value).toInt()
}

/** 运行条件行：左标签 + 右状态（满足主色，不满足警示色） */
@Composable
private fun MiIslandStatusRow(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 列表显示卡片：歌曲行元素开关（来源 / 专辑名 / 时长 / 封面）+ 不喜欢管理入口。
 */
@Composable
private fun ListDisplayCard(
    showSource: Boolean,
    onShowSourceChange: (Boolean) -> Unit,
    showQuality: Boolean,
    onShowQualityChange: (Boolean) -> Unit,
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
        ListDisplayToggle(
            title = "显示音质上限",
            subtitle = "歌名右侧标注该曲最高可用规格（无损 / Hi-Res 等），避免逐档试探",
            checked = showQuality,
            onCheckedChange = onShowQualityChange,
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

/** Shizuku 授权请求码（设置页内部唯一即可） */
private const val SHIZUKU_REQUEST_CODE = 7171

/** Shizuku 下载页（GitHub Releases） */
private const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases"

/** 歌词长度上限可选项（字） */
private val LYRIC_LENGTH_OPTIONS = listOf(8, 12, 16, 20)
private const val QQ_GROUP = "1125132981"