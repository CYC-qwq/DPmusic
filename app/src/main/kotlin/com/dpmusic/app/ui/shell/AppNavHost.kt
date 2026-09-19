package com.dpmusic.app.ui.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.dpmusic.app.ui.navigation.AlbumDetailRoute
import com.dpmusic.app.ui.navigation.ArtistDetailRoute
import com.dpmusic.app.ui.navigation.DailySongsRoute
import com.dpmusic.app.ui.navigation.DownloadManagerRoute
import com.dpmusic.app.ui.navigation.HomeRoute
import com.dpmusic.app.ui.navigation.MineRoute
import com.dpmusic.app.ui.navigation.LogsRoute
import com.dpmusic.app.ui.navigation.NcmPlaylistsRoute
import com.dpmusic.app.ui.navigation.PlaylistDetailRoute
import com.dpmusic.app.ui.navigation.PlaylistRoute
import com.dpmusic.app.ui.navigation.QqPlaylistsRoute
import com.dpmusic.app.ui.navigation.QqRecommendRoute
import com.dpmusic.app.ui.navigation.RankDetailRoute
import com.dpmusic.app.ui.navigation.RankRoute
import com.dpmusic.app.ui.navigation.SearchRoute
import com.dpmusic.app.ui.navigation.SettingsRoute
import com.dpmusic.app.ui.navigation.SourceManagerRoute
import com.dpmusic.app.ui.navigation.SyncRoute
import com.dpmusic.app.ui.navigation.TogetherRoute
import com.dpmusic.app.ui.navigation.UserPlaylistDetailRoute
import com.dpmusic.app.ui.screens.home.HomeScreen
import com.dpmusic.app.ui.screens.mine.MineScreen
import com.dpmusic.app.ui.screens.album.AlbumDetailScreen
import com.dpmusic.app.ui.screens.artist.ArtistDetailScreen
import com.dpmusic.app.ui.screens.download.DownloadManagerScreen
import com.dpmusic.app.ui.screens.ncm.DailySongsScreen
import com.dpmusic.app.ui.screens.logs.LogsScreen
import com.dpmusic.app.ui.screens.ncm.NcmPlaylistsScreen
import com.dpmusic.app.ui.screens.playlist.PlaylistDetailScreen
import com.dpmusic.app.ui.screens.playlist.PlaylistScreen
import com.dpmusic.app.ui.screens.playlist.UserPlaylistDetailScreen
import com.dpmusic.app.ui.screens.qq.QqPlaylistsScreen
import com.dpmusic.app.ui.screens.qq.QqRecommendScreen
import com.dpmusic.app.ui.screens.rank.RankDetailScreen
import com.dpmusic.app.ui.screens.rank.RankScreen
import com.dpmusic.app.ui.screens.search.SearchScreen
import com.dpmusic.app.ui.screens.settings.SettingsScreen
import com.dpmusic.app.ui.screens.settings.SyncScreen
import com.dpmusic.app.ui.screens.sources.SourceManagerScreen
import com.dpmusic.app.ui.screens.together.TogetherScreen

/** 底部导航 / 侧边栏条目 */
data class NavItem(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

/** 五大主页面（底部导航栏与侧边栏共用）：主页 / 搜索 / 榜单 / 歌单 / 我的 */
val mainNavItems = listOf(
    NavItem(HomeRoute, "主页", Icons.Outlined.Home),
    NavItem(SearchRoute, "搜索", Icons.Outlined.Search),
    NavItem(RankRoute, "榜单", Icons.Outlined.EmojiEvents),
    NavItem(PlaylistRoute, "歌单", Icons.Outlined.LibraryMusic),
    NavItem(MineRoute, "我的", Icons.Outlined.Person),
)

/** 主页面路由名集合：用于区分「标签切换」与「详情推进」的过渡动画 */
private val mainTabRouteNames: Set<String?> = mainNavItems.map { it.route::class.qualifiedName }.toSet()

/**
 * 应用导航图（类型安全路由）：
 * 主页面 + 榜单详情 / 平台歌单详情 / 本地歌单详情 / 设置。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    windowSizeClass: WindowSizeClass,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        // 过渡设计（优雅丝滑优先）：
        // - 标签切换：M3 fadeThrough——旧页快速淡出，新页自 92% 缩放淡入（无方向感、连贯优雅）；
        // - 详情推进：共享轴滑动——新页自右侧全宽滑入 + 淡入，被覆盖页左移 1/4 + 淡出；返回完全对称。
        enterTransition = {
            if (initialState.isMainTabRoute() && targetState.isMainTabRoute()) {
                fadeIn(tween(220, delayMillis = 90)) +
                    scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f)
            } else {
                slideInHorizontally(tween(400, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(320))
            }
        },
        exitTransition = {
            if (initialState.isMainTabRoute() && targetState.isMainTabRoute()) {
                fadeOut(tween(110))
            } else {
                slideOutHorizontally(tween(400, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(320))
            }
        },
        popEnterTransition = {
            slideInHorizontally(tween(400, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(320))
        },
        popExitTransition = {
            slideOutHorizontally(tween(400, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(320))
        },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                windowSizeClass = windowSizeClass,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenMine = { navController.navigateToMain(MineRoute) },
                onOpenPlaylists = { navController.navigateToMain(PlaylistRoute) },
                onOpenRankDetail = { rank ->
                    navController.navigate(
                        RankDetailRoute(
                            platform = rank.platform.id,
                            rankId = rank.id,
                            title = rank.name,
                        )
                    )
                },
                onOpenDaily = { navController.navigate(DailySongsRoute) },
                onOpenNcmPlaylists = { navController.navigate(NcmPlaylistsRoute) },
                onOpenNcmPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "wy", playlistId = id, title = title))
                },
                onOpenQqPlaylists = { navController.navigate(QqPlaylistsRoute) },
                onOpenQqPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "qq", playlistId = id, title = title))
                },
                onOpenQqRecommend = { source -> navController.navigate(QqRecommendRoute(source = source)) },
            )
        }

        composable<SearchRoute> {
            SearchScreen(
                windowSizeClass = windowSizeClass,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenPlaylistDetail = { playlist ->
                    navController.navigate(
                        PlaylistDetailRoute(
                            platform = playlist.platform.id,
                            playlistId = playlist.id,
                            title = playlist.name,
                        )
                    )
                },
            )
        }

        composable<RankRoute> {
            RankScreen(
                windowSizeClass = windowSizeClass,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenRankDetail = { rank ->
                    navController.navigate(
                        RankDetailRoute(
                            platform = rank.platform.id,
                            rankId = rank.id,
                            title = rank.name,
                        )
                    )
                },
            )
        }

        composable<RankDetailRoute> { entry ->
            val route = entry.toRoute<RankDetailRoute>()
            RankDetailScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<PlaylistRoute> {
            PlaylistScreen(
                windowSizeClass = windowSizeClass,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenUserPlaylist = { id ->
                    navController.navigate(UserPlaylistDetailRoute(playlistId = id))
                },
                onOpenNcmPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "wy", playlistId = id, title = title))
                },
                onOpenQqPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "qq", playlistId = id, title = title))
                },
                onOpenNcmPlaylists = { navController.navigate(NcmPlaylistsRoute) },
                onOpenQqPlaylists = { navController.navigate(QqPlaylistsRoute) },
            )
        }

        composable<PlaylistDetailRoute> { entry ->
            val route = entry.toRoute<PlaylistDetailRoute>()
            PlaylistDetailScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<UserPlaylistDetailRoute> { entry ->
            val route = entry.toRoute<UserPlaylistDetailRoute>()
            UserPlaylistDetailScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<MineRoute> {
            MineScreen(
                windowSizeClass = windowSizeClass,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenTogether = { navController.navigate(TogetherRoute) },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onOpenLogs = { navController.navigate(LogsRoute) },
                onOpenSources = { navController.navigate(SourceManagerRoute) },
                onOpenDownloadManager = { navController.navigate(DownloadManagerRoute) },
                onOpenSync = { navController.navigate(SyncRoute) },
            )
        }

        composable<LogsRoute> {
            LogsScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<SourceManagerRoute> {
            SourceManagerScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<TogetherRoute> {
            TogetherScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<DailySongsRoute> {
            DailySongsScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }

        composable<ArtistDetailRoute> { entry ->
            val route = entry.toRoute<ArtistDetailRoute>()
            ArtistDetailScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
                onOpenAlbum = { albumId ->
                    navController.navigate(AlbumDetailRoute(albumId = albumId))
                },
            )
        }

        composable<AlbumDetailRoute> { entry ->
            val route = entry.toRoute<AlbumDetailRoute>()
            AlbumDetailScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }
        composable<DownloadManagerRoute> {
            DownloadManagerScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SyncRoute> {
            SyncScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }
        composable<NcmPlaylistsRoute> {
            NcmPlaylistsScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "wy", playlistId = id, title = title))
                },
            )
        }
        composable<QqPlaylistsRoute> {
            QqPlaylistsScreen(
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { id, title ->
                    navController.navigate(PlaylistDetailRoute(platform = "qq", playlistId = id, title = title))
                },
            )
        }
        composable<QqRecommendRoute> { entry ->
            val route = entry.toRoute<QqRecommendRoute>()
            QqRecommendScreen(
                route = route,
                windowSizeClass = windowSizeClass,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** 该返回栈条目是否为主页面（destination.route 为路由类的全限定名） */
private fun NavBackStackEntry.isMainTabRoute(): Boolean = destination.route in mainTabRouteNames

/** 主导航跳转：单顶 + 状态保存 / 恢复（主页面间切换的标准行为） */
private fun NavHostController.navigateToMain(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}