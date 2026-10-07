package com.dpmusic.app

import android.content.Context
import com.dpmusic.app.core.audio.AudioEffectsManager
import com.dpmusic.app.core.audio.BitPerfectController
import com.dpmusic.app.core.audio.DspEngine
import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.data.HistoryRepository
import com.dpmusic.app.core.data.NcmChatRepository
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.data.NcmSyncService
import com.dpmusic.app.core.data.EqualizerRepository
import com.dpmusic.app.core.data.KgLiteClaimService
import com.dpmusic.app.core.data.BiliRepository
import com.dpmusic.app.core.data.enabledPlatforms
import com.dpmusic.app.core.data.KgLiteRepository
import com.dpmusic.app.core.data.ListeningStatsStore
import com.dpmusic.app.core.data.PlaybackSessionStore
import com.dpmusic.app.core.data.QqRepository
import com.dpmusic.app.core.data.QqSyncService
import com.dpmusic.app.core.data.QishuiRepository
import com.dpmusic.app.core.data.SearchHistoryRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.data.appDataStore
import com.dpmusic.app.core.download.DownloadTaskStore
import com.dpmusic.app.core.lansync.LanSyncManager
import com.dpmusic.app.core.lyric.LyricsHub
import com.dpmusic.app.core.miisland.MiIslandController
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.net.KgApi
import com.dpmusic.app.core.net.KgLiteApi
import com.dpmusic.app.core.net.KgLiteClaimApi
import com.dpmusic.app.core.net.KgLiteLoginApi
import com.dpmusic.app.core.net.KgLiteResolver
import com.dpmusic.app.core.net.LxResolver
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.QishuiLoginApi
import com.dpmusic.app.core.net.QishuiApi
import com.dpmusic.app.core.net.QishuiRelayApi
import com.dpmusic.app.core.net.SodaCencFetcher
import com.dpmusic.app.core.net.QishuiPlatformApi
import com.dpmusic.app.core.net.BiliApi
import com.dpmusic.app.core.net.BiliPlatformApi
import com.dpmusic.app.core.net.BiliResolver
import com.dpmusic.app.core.playback.QishuiRadioController
import com.dpmusic.app.core.net.QishuiResolver
import com.dpmusic.app.core.net.QqApi
import com.dpmusic.app.core.net.WyApi
import com.dpmusic.app.core.playback.NcmFmController
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.playback.SleepTimerController
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.script.MusicFreeEngine
import com.dpmusic.app.core.script.PluginEnginePool
import com.dpmusic.app.core.script.ScriptEnginePool
import com.dpmusic.app.core.script.MusicFreePluginRepository
import com.dpmusic.app.core.script.MusicFreeResolver
import com.dpmusic.app.core.script.ScriptMusicResolver
import com.dpmusic.app.core.script.UserApiEngine
import com.dpmusic.app.core.script.UserApiRepository
import com.dpmusic.app.core.sync.SyncManager
import com.dpmusic.app.core.together.TogetherInviteWatcher
import com.dpmusic.app.core.together.TogetherSession
import com.dpmusic.app.core.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                MusicPlatform.QS to QishuiPlatformApi(),
                MusicPlatform.BB to BiliPlatformApi(),
            ),
            resolver = LxResolver(apiKeyProvider = { settings.settings.value.lxApiKey }),
            scriptResolver = scriptResolver,
            chainProvider = { settings.settings.value.sourceChains },
            crossPlatformFallbackProvider = { settings.settings.value.crossPlatformFallback },
            enabledPlatformsProvider = { settings.settings.value.enabledPlatforms() },
            pluginResolver = musicFreeResolver,
            kgLiteResolver = kgLiteResolver,
            qishuiResolver = qishuiResolver,
            biliResolver = biliResolver,
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

    /**
     * 自定义音源脚本池：**多个 JS 同时驻留**，按用户排的顺序依次尝试。
     *
     * 每项一个独立 [UserApiEngine]（独立 QuickJS 上下文）。这是「多个脚本按序解析」
     * 的前提 —— 单上下文里同一时刻只能有一个脚本在跑。
     */
    val scriptPool: ScriptEnginePool by lazy { ScriptEnginePool { UserApiEngine(appContext) } }

    /** 脚本音源解析器（按用户顺序逐个脚本尝试；顺序来自设置里的 script_orders_json） */
    val scriptResolver: ScriptMusicResolver by lazy {
        ScriptMusicResolver(
            pool = scriptPool,
            orders = { settings.settings.value.scriptOrders },
        )
    }

    /** 自定义音源脚本仓库（导入 / 排序 / 停用 / 删除；负责把池与启用列表对齐） */
    val userApi: UserApiRepository by lazy {
        UserApiRepository(appContext.appDataStore, scriptPool)
    }

    /**
     * MusicFree 插件池：与脚本池同构（多个插件同时挂载，按序尝试）。
     * 与脚本池相互独立 —— 两者的 JS 运行时不能混用。
     */
    val pluginPool: PluginEnginePool by lazy { PluginEnginePool { MusicFreeEngine(appContext) } }

    /** MusicFree 插件解析器（按用户顺序逐个插件尝试） */
    val musicFreeResolver: MusicFreeResolver by lazy {
        MusicFreeResolver(
            pool = pluginPool,
            orders = { settings.settings.value.scriptOrders },
        )
    }

    /** MusicFree 插件仓库（导入 / 排序 / 停用 / 删除 / 用户变量） */
    val musicFreePlugins: MusicFreePluginRepository by lazy {
        MusicFreePluginRepository(appContext.appDataStore, pluginPool)
    }

    /* ---------------- 酷狗概念版音源 ---------------- */

    /** 酷狗概念版账号仓储（token / mid / 资料，仅本地保存） */
    val kgLite: KgLiteRepository by lazy { KgLiteRepository(appContext.appDataStore) }

    /** 酷狗概念版取播放地址客户端（匿名即可解析免费歌全曲） */
    val kgLiteApi: KgLiteApi by lazy { KgLiteApi(deviceProvider = { kgLite.device() }) }

    /** 酷狗概念版手机号验证码登录客户端 */
    val kgLiteLoginApi: KgLiteLoginApi by lazy { KgLiteLoginApi(deviceProvider = { kgLite.device() }) }

    /** 酷狗概念版每日领 VIP 客户端（听歌 / 广告，需登录态） */
    val kgLiteClaimApi: KgLiteClaimApi by lazy { KgLiteClaimApi(deviceProvider = { kgLite.device() }) }

    /** 酷狗概念版解析器（第四条音源通道；开关来自设置） */
    val kgLiteResolver: KgLiteResolver by lazy {
        KgLiteResolver(api = kgLiteApi, enabledProvider = { settings.settings.value.kgLiteEnabled })
    }

    /** 酷狗概念版每日签到（已开启自动签到 + 已登录时，启动后自动执行一次） */
    val kgLiteClaim: KgLiteClaimService by lazy {
        KgLiteClaimService(api = kgLiteClaimApi, settings = settings, repository = kgLite)
    }

    /* ---------------- 汽水音乐音源 ---------------- */

    /** 汽水音乐账号仓储（sessionid / 资料，仅本地保存） */
    val qishui: QishuiRepository by lazy { QishuiRepository(appContext.appDataStore) }

    /** 汽水音乐扫码登录客户端（二维码 + 轮询） */
    val qishuiLoginApi: QishuiLoginApi by lazy { QishuiLoginApi() }
    /** 汽水音乐平台接口（匿名元数据 + 登录态「我的歌单」等） */
    val qishuiPlatformApi: QishuiPlatformApi by lazy { QishuiPlatformApi() }
    /** 汽水场景电台控制器（自动续杯：接口每次只回 6 首且无 cursor，靠多次抽样补齐） */
    val qishuiRadio: QishuiRadioController by lazy {
        QishuiRadioController(
            api = qishuiPlatformApi,
            player = player,
            dislike = dislike,
            enabledProvider = { settings.settings.value.qishuiEnabled },
        )
    }

    /** 汽水音乐解析器（直连通道 `h5/seo_track`，匿名免签；仅对汽水曲库生效） */
    val qishuiResolver: QishuiResolver by lazy {
        QishuiResolver(
            enabledProvider = { settings.settings.value.qishuiEnabled },
            relay = qishuiRelayApi,
            cencFetcher = sodaCencFetcher,
            losslessPreferredProvider = { settings.settings.value.qishuiLossless },
        )
    }

    /** 汽水 relay 客户端：走「家里电脑上的汽水客户端」取完整曲目链接（协议 v1） */
    val qishuiRelayApi: QishuiRelayApi by lazy {
        QishuiRelayApi(
            baseUrlProvider = { settings.settings.value.qishuiRelayUrl },
            secretProvider = { settings.settings.value.qishuiRelaySecret },
        )
    }

    /** 汽水加密音频解密器：relay 返回 CENC 加密流时，本地解密到缓存文件再播放 */
    val sodaCencFetcher: SodaCencFetcher by lazy {
        SodaCencFetcher(java.io.File(appContext.cacheDir, "soda_cenc"))
    }

    /* ---------------- 哔哩哔哩音源 ---------------- */

    /** B 站账号仓储（可选登录：Cookie / 资料 / 会员态，仅本地保存） */
    val bili: BiliRepository by lazy { BiliRepository(appContext.appDataStore) }

    /** B 站平台接口（匿名：WBI 签名搜索 / 详情；无需登录） */
    val biliPlatformApi: BiliPlatformApi by lazy { BiliPlatformApi() }

    /**
     * B 站解析器（直连通道：DASH 音频流；仅对 B 站曲库生效）。
     * 会员态决定能否解锁无损 FLAC / 全景声（非会员只取 AAC）。
     */
    val biliResolver: BiliResolver by lazy { BiliResolver(vipProvider = { bili.isVip() }) }

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

    /**
     * 局域网设备同步（LocalSend 协议 v2.2 的发现 + 接收端 + 发送端）。
     *
     * 与 [syncManager] 共用同一份载荷构建 / 应用实现，因此白名单与覆盖语义完全一致。
     * 这里只做懒加载装配，不主动启动接收端 —— 监听端口属于用户显式动作（进同步页时开启）。
     */
    val lanSyncManager: LanSyncManager by lazy {
        LanSyncManager(
            context = appContext,
            settings = settings,
            sync = syncManager,
            // 传 lambda 而非实例：lanSyncManager 的构造不该顺带把播放器（及其
            // MediaController 连接链）也拉起来 —— 数据同步场景根本不碰播放器。
            // 只有真正点「流转」时才会通过它取用。
            playerProvider = { player },
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

    /** 概念版设备标识：31 位十进制随机串（与官方 `new_device()` 同形态，用于 tracker key） */
    private fun kgLiteRandomMid(): String {
        val rnd = kotlin.random.Random
        val first = rnd.nextInt(1, 10)   // 首位非 0，保证 31 位
        return buildString {
            append(first)
            repeat(30) { append(rnd.nextInt(0, 10)) }
        }
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
        // 预热音频 DSP 开关联动：均衡器 / Bit-Perfect / 示波器三者随设置实时生效
        startAudioDspSync()
        // 预热红心同步：已登录时自动启动（启动延迟 + 运行期定期）
        ncmSync
        // 预热一起听邀请守望：登录后自动轮询私信卡片（官方邀请识别）
        togetherInviteWatcher.start()
        // 预热自定义音源：已激活的脚本启动即加载
        userApi
        // 预热 MusicFree 插件：已激活的插件启动即挂载
        musicFreePlugins
        // 预热酷狗概念版：首次使用生成并持久化设备标识（数字串，后续复用）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            kgLite.ensureMid { kgLiteRandomMid() }
                // 酷狗概念版：已开启自动签到时，启动后执行当日领取（同日仅一次）
                kgLiteClaim.scheduleStartupClaim()
        }
        // 接线 B 站登录态：把本地 Cookie 注入 BiliApi（搜索 / 详情 / 取流全链路生效）。
        // 未登录时返回空串 → 匿名链路，行为与旧版一致。
        BiliApi.cookieProvider = { bili.cookie.value }
        // 接线 B 站音源开关：关闭后 BiliResolver 拒绝解析（`canResolve` 为 false），
        // 其曲目即便残留在本地库里也会正确报「无法解析」，而不是继续偷偷联网取流。
        biliResolver.enabledProvider = { settings.settings.value.biliEnabled }
        // 若已登录，启动后异步刷新一次账号资料（会员态可能已变化）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { refreshBiliAccount() }
        // 预热 WebDAV 数据同步：自动同步监听（收藏 / 歌单 / 屏蔽规则变更后节流上传）
        syncManager
        // 预热局域网接收端：按设置常驻监听端口（不依赖同步页存活 —— 否则对端发现不了本机）
        lanSyncManager.applyReceiveSetting()
        // 预热桌面播放控件：播放状态 → 小组件推送
        widgetUpdater
        // 预热全局歌词中心：桌面歌词 / 车载歌词 / 小米超级岛任一启用时接入
        startLyricsHubSync()
        // 预热小米超级岛：模式同步 + 歌词来源接入（实际通知注入在 MusicService 内）
        MiIslandController.attach(appContext)
    }

    /**
     * 刷新 B 站账号资料（会员态可能变化：开/续大会员、过期）。
     * 已登录时才请求；失败静默（保留旧资料）。
     */
    suspend fun refreshBiliAccount() {
        val cookie = bili.cookie.value
        if (cookie.isBlank()) return
        runCatching {
            biliPlatformApi.account(cookie)?.let { acc -> bili.updateAccount(acc) }
        }
    }

    private var lyricsHubSyncStarted = false

    /**
     * 全局歌词中心接入（进程内只启动一次）：
     * 桌面歌词或车载 / 蓝牙歌词任一启用时接入，之后歌词随切歌自动加载并广播，
     * 供桌面歌词悬浮窗与 [PlayerConnection] 的车机歌词刷新共用同一份数据。
     *
     * 刻意「按需接入」：两个功能都关闭时不做任何歌词请求，避免给不相关用户增加网络开销。
     */
    private fun startLyricsHubSync() {
        if (lyricsHubSyncStarted) return
        lyricsHubSyncStarted = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect { s ->
                if (s.desktopLyricEnabled || s.carLyricEnabled || s.miIslandMode.usesLyricIsland) {
                    LyricsHub.attach(player, musicRepository)
                }
            }
        }
    }

    /**
     * 音频 DSP 设置联动（进程内只启动一次）：
     * - `bitPerfectEnabled` → [BitPerfectController]（并强制旁路均衡器）
     * - `visualizerEnabled` → 频谱抽取开关
     * 三者都从 DataStore 实时同步，用户改设置立刻生效。
     */
    private var dspSyncStarted = false

    private fun startAudioDspSync() {
        if (dspSyncStarted) return
        dspSyncStarted = true
        BitPerfectController.prewarm(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.settings.collect { s ->
                BitPerfectController.setUserEnabled(s.bitPerfectEnabled)
                AudioEffectsManager.setBitPerfectBypass(s.bitPerfectEnabled)
                DspEngine.publishSpectrumEnabled(s.visualizerEnabled)
            }
        }
    }
}