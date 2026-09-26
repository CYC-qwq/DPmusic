package com.dpmusic.app

import android.content.Context
import com.dpmusic.app.core.audio.AudioEffectsManager
import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.data.HistoryRepository
import com.dpmusic.app.core.data.NcmChatRepository
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.data.NcmSyncService
import com.dpmusic.app.core.data.EqualizerRepository
import com.dpmusic.app.core.data.ListeningStatsStore
import com.dpmusic.app.core.data.PlaybackSessionStore
import com.dpmusic.app.core.data.QqRepository
import com.dpmusic.app.core.data.QqSyncService
import com.dpmusic.app.core.data.SearchHistoryRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.data.appDataStore
import com.dpmusic.app.core.download.DownloadTaskStore
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.net.KgApi
import com.dpmusic.app.core.net.LxResolver
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.QqApi
import com.dpmusic.app.core.net.WyApi
import com.dpmusic.app.core.playback.NcmFmController
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.playback.SleepTimerController
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.script.MusicFreeEngine
import com.dpmusic.app.core.script.MusicFreePluginRepository
import com.dpmusic.app.core.script.MusicFreeResolver
import com.dpmusic.app.core.script.ScriptMusicResolver
import com.dpmusic.app.core.script.UserApiEngine
import com.dpmusic.app.core.script.UserApiRepository
import com.dpmusic.app.core.sync.SyncManager
import com.dpmusic.app.core.together.TogetherInviteWatcher
import com.dpmusic.app.core.together.TogetherSession
import com.dpmusic.app.core.widget.WidgetUpdater

/**
 * 轻量手工依赖注入容器（替代 Hilt，保持零注解处理开销）。
 * 所有单例均为进程级懒加载。
 */
object AppContainer {

    lateinit var appContext: Context
        private set

    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            apis = mapOf(
                MusicPlatform.WY to WyApi(),
                MusicPlatform.QQ to qqApi,
                MusicPlatform.KG to KgApi(),
            ),
            resolver = LxResolver(apiKeyProvider = { settings.settings.value.lxApiKey }),
            scriptResolver = scriptResolver,
            priorityProvider = { settings.settings.value.sourcePriority },
            pluginResolver = musicFreeResolver,
        )
    }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext.appDataStore) }
    val favorites: FavoritesRepository by lazy { FavoritesRepository(appContext.appDataStore) }
    val dislike: DislikeRepository by lazy { DislikeRepository(appContext.appDataStore) }

    val history: HistoryRepository by lazy { HistoryRepository(appContext.appDataStore) }
    val userPlaylists: UserPlaylistRepository by lazy { UserPlaylistRepository(appContext.appDataStore) }

    /** 搜索历史（最近搜索关键词） */
    val searchHistory: SearchHistoryRepository by lazy { SearchHistoryRepository(appContext.appDataStore) }

    /** 网易云账号（一起听）：Cookie 与资料缓存（仅本地保存） */
    val ncm: NcmRepository by lazy { NcmRepository(appContext.appDataStore) }

    /** 网易云 eapi 直连客户端 */
    val ncmApi: NcmApi by lazy { NcmApi(deviceIdProvider = { ncmDeviceId }) }

    /** 网易云私信（聊天）：会话列表 / 聊天记录 / 发送文本与卡片 */
    val ncmChat: NcmChatRepository by lazy { NcmChatRepository(api = ncmApi, ncm = ncm) }

    /** 一起听会话（进程级单例：轮询同步 + 心跳 + 播放跟随） */
    val togetherSession: TogetherSession by lazy {
        TogetherSession(
            api = ncmApi,
            ncm = ncm,
            player = player,
            musicRepository = musicRepository,
        )
    }

    /** 私人 FM（依赖登录 Cookie；播放队列动态续杯） */
    val ncmFm: NcmFmController by lazy {
        NcmFmController(api = ncmApi, ncm = ncm, player = player, dislike = dislike)
    }

    /** 红心同步（单向：云端 → 本地；需登录 Cookie；启动延迟 + 定期 + 手动触发） */
    val ncmSync: NcmSyncService by lazy {
        NcmSyncService(api = ncmApi, ncm = ncm, favorites = favorites)
    }

    /** QQ 音乐账号：Cookie 与资料缓存（仅本地保存） */
    val qq: QqRepository by lazy { QqRepository(appContext.appDataStore) }

    /** QQ 音乐直连客户端（共享实例：匿名检索 + 账号能力） */
    val qqApi: QqApi by lazy { QqApi(cookieProvider = { qq.cookie.value }) }

    /** QQ 红心自动双向同步（需登录 Cookie；启动延迟 + 定期 + 手动触发） */
    val qqSync: QqSyncService by lazy {
        QqSyncService(api = qqApi, qq = qq, favorites = favorites)
    }

    /** 自定义音源脚本引擎（QuickJS；LX Music 音源 JS 支持） */
    val userApiEngine: UserApiEngine by lazy { UserApiEngine(appContext) }

    /** 脚本音源解析器（自定义音源 JS 优先于远端代理） */
    val scriptResolver: ScriptMusicResolver by lazy { ScriptMusicResolver(userApiEngine) }

    /** 自定义音源脚本仓库（导入 / 启用 / 删除；激活脚本自动加载到引擎） */
    val userApi: UserApiRepository by lazy { UserApiRepository(appContext.appDataStore, userApiEngine) }

    /** MusicFree 插件引擎（QuickJS；MusicFree 音源插件支持，与 LX 脚本引擎相互独立） */
    val musicFreeEngine: MusicFreeEngine by lazy { MusicFreeEngine(appContext) }

    /** MusicFree 插件解析器（Key 与脚本都失败时的最后一道音源兜底） */
    val musicFreeResolver: MusicFreeResolver by lazy { MusicFreeResolver(musicFreeEngine) }

    /** MusicFree 插件仓库（导入 / 启用 / 删除 / 用户变量；激活插件自动挂载） */
    val musicFreePlugins: MusicFreePluginRepository by lazy {
        MusicFreePluginRepository(appContext.appDataStore, musicFreeEngine)
    }

    /** 下载任务队列（串行执行 + 持久化 + 元数据增强） */
    val downloadTasks: DownloadTaskStore by lazy {
        DownloadTaskStore(appContext, musicRepository, settings)
    }

    /** WebDAV 数据同步（设置 / 音源 / 歌单 / 收藏 / 下载记录备份与恢复） */
    val syncManager: SyncManager by lazy {
        SyncManager(
            dataStore = appContext.appDataStore,
            settings = settings,
            downloadTasks = downloadTasks,
            favorites = favorites,
            playlists = userPlaylists,
            dislike = dislike,
        )
    }

    /** 桌面播放控件：播放状态 → 小组件推送（进程启动即监听） */
    val widgetUpdater: WidgetUpdater by lazy {
        WidgetUpdater(appContext, player)
    }

    /** 一起听邀请守望（官方私信卡片识别；登录后自动轮询，空闲时提示加入） */
    val togetherInviteWatcher: TogetherInviteWatcher by lazy {
        TogetherInviteWatcher(api = ncmApi, ncm = ncm, session = togetherSession)
    }

    /** eapi 请求用的稳定设备标识（本机 ANDROID_ID 派生） */
    private val ncmDeviceId: String by lazy {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull().orEmpty()
        ("DP" + androidId.uppercase().padEnd(50, '0')).take(52)
    }

    /** 播放会话（队列 + 进度）持久化，供「继续收听」恢复 */
    val playbackSession: PlaybackSessionStore by lazy { PlaybackSessionStore(appContext.appDataStore) }

    val player: PlayerConnection by lazy {
        PlayerConnection(
            context = appContext,
            repository = musicRepository,
            history = history,
            favorites = favorites,
            settings = settings,
            sessionStore = playbackSession,
            dislike = dislike,
        )
    }

    /** 定时退出：到点自动暂停播放（支持「播完当前歌曲后停止」） */
    val sleepTimer: SleepTimerController by lazy {
        SleepTimerController(player = player, settings = settings)
    }

    /** 听歌时长统计（按日累计 + 本次会话计时） */
    val listeningStats: ListeningStatsStore by lazy {
        ListeningStatsStore(appContext.appDataStore, player)
    }

    /** 音效均衡器设置持久化 */
    val equalizerRepo: EqualizerRepository by lazy {
        EqualizerRepository(appContext.appDataStore)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 预热听歌统计：进程启动即开始本次会话计时
        listeningStats
        // 预热音效设置：确保 UI 打开均衡器时已加载持久化数据
        AudioEffectsManager.prewarm()
        // 预热红心同步：已登录时自动启动（启动延迟 + 运行期定期）
        ncmSync
        // 预热一起听邀请守望：登录后自动轮询私信卡片（官方邀请识别）
        togetherInviteWatcher.start()
        // 预热自定义音源：已激活的脚本启动即加载
        userApi
        // 预热 MusicFree 插件：已激活的插件启动即挂载
        musicFreePlugins
        // 预热 WebDAV 数据同步：自动同步监听（收藏 / 歌单 / 屏蔽规则变更后节流上传）
        syncManager
        // 预热桌面播放控件：播放状态 → 小组件推送
        widgetUpdater
    }
}