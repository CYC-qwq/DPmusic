package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.miisland.MiIslandMode
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.ScriptKind
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.SourceChain
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 内置 LX 音源 Key 默认值。
 *
 * 已按交付要求**清空**：不内置任何 Key，由用户在「设置 → 音源 Key」中自行填写
 * （留空则无法解析在线播放地址）。
 */
private const val DefaultLxApiKey = ""

/** 应用设置快照 */
data class AppSettings(
    val defaultPlatform: MusicPlatform = MusicPlatform.KG,
    val quality: PlayQuality = PlayQuality.HIGH,
    /**
     * LX 音源服务 Key。
     *
     * ⚠️ 当前内置的是**测试用 Key**（仅为联调/体验方便）：
     * 正式发布前请改为空字符串，或让用户自行在「设置 → 音源 Key」中配置。
     */
    val lxApiKey: String = DefaultLxApiKey,
    /** 图片缓存最大占用（MB；0 = 不限制） */
    val maxStorageMb: Int = 1024,
    /** 音乐下载目录（空 = 默认公共音乐目录 DPmusic） */
    val downloadDir: String = "",
    /** 歌词字号缩放：竖屏（0.75 - 1.6） */
    val lyricScalePortrait: Float = 1f,
    /** 歌词字号缩放：横屏（0.75 - 1.6） */
    val lyricScaleLandscape: Float = 1f,
    /** 歌词行距缩放：竖屏（0.5 - 2.0） */
    val lyricSpacingPortrait: Float = 1f,
    /** 歌词行距缩放：横屏（0.5 - 2.0） */
    val lyricSpacingLandscape: Float = 1f,
    /** 逐字歌词（卡拉OK式字级高亮；无逐字数据时自动回退行级） */
    val verbatimLyric: Boolean = true,
    /** 无字级数据时：按行时长匀速模拟逐字（需配合 verbatimLyric 使用） */
    val simulatedVerbatim: Boolean = true,
    val dynamicColor: Boolean = true,
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val darkMode: Int = 0,
    /** 剪切板自动读取（从其他应用切回时识别剪贴板中的歌曲 / 歌单 / 一起听链接并询问） */
    val clipboardAutoRead: Boolean = false,
    /** 内部：上次已处理的剪贴板文本（去重，避免重复弹窗） */
    val lastClipboardHandled: String = "",
    /** 主题色板 id（见 ThemePalettes.kt；default = 品牌回退配色） */
    val themeColor: String = "default",
    /** 玻璃风格模式（半透明磨砂面板 + 全局流光底；Android 12+ 支持真实模糊） */
    val glassMode: Boolean = false,
    /**
     * 播放页封面动态取色：用当前封面提取的种子色生成一整套 MD3 配色，
     * **仅作用于全屏播放页**（不改动全局主题）。
     *
     * 默认关闭：开启后播放页配色会随封面变化，属明显观感变更，交由用户主动选择。
     */
    val coverDynamicColor: Boolean = false,
    /** 播放页封面动态取色：配色风格 id（见 [com.dpmusic.app.ui.theme.CoverColorStyle]） */
    val coverColorStyle: String = "content",
    /** 音源解析优先级（自定义脚本 vs 远端代理 Key）；默认 Key 优先 */
    val sourcePriority: SourcePriority = SourcePriority.KEY_FIRST,
    /** 播放速度倍率（0.5 - 2.0；变速不变调） */
    val playbackSpeed: Float = 1f,
    /** 定时退出：勾选「播完当前歌曲后停止」偏好（下次打开面板保持） */
    val sleepTimerWaitSongEnd: Boolean = true,
    /** 歌词繁体化（中文歌词转换为繁体显示） */
    val lyricS2T: Boolean = false,
    /**
     * 车载 / 蓝牙歌词：把**当前歌词行**写进系统媒体元数据，让蓝牙 / 车机 / 锁屏显示歌词。
     *
     * 走的是唯一一条真能到外设的通道：`MediaMetadata.METADATA_KEY_TITLE`（标准 AVRCP
     * 元数据），与 NeriPlayer / Melodia 的做法一致；同时附带 `lyricInfo` / MIUI LYRIC
     * 等 extras 供 ColorOS 锁屏岛、HyperOS 等能读 extras 的组件使用。
     */
    val carLyricEnabled: Boolean = false,
    /**
     * 「发送整首 LRC」：把**整首**带时间轴的歌词一并写进 extras
     * （`lyricInfo` JSON + MIUI 的 `android.media.metadata.LYRIC`）。
     *
     * 默认关闭。原因：标准 AVRCP 链路不读 extras，蓝牙设备实际只认 [carLyricEnabled]
     * 写入的当前行标题；而整首 LRC 的 extras 会随每首歌每次元数据更新一起序列化并跨进程序列化，
     * 在部分 ROM 的媒体通知里会挤占载荷预算、影响媒体通知稳定性。
     * 仅当用户的外设（ColorOS 锁屏岛、HyperOS 歌词组件、车机自带歌词解析）确实读 extras 时再开启。
     */
    val carLyricFullLrc: Boolean = false,
    /**
     * 小米超级岛（HyperOS 专属高级选项）。
     *
     * 走**额外**的一条通道：把 `miui.focus.*` 载荷挂到媒体通知上，由 HyperOS 渲染成超级岛。
     * 渲染需要绕过 MIUI 签名校验（「断网魔法」），因此依赖 Shizuku 授权；
     * 未授权时自动降级为普通通知（不影响播放与通知栏本身）。
     */
    val miIslandMode: MiIslandMode = MiIslandMode.OFF,
    /** 列表显示：专辑名 */
    val listShowAlbumName: Boolean = true,
    /** 列表显示：歌曲时长 */
    val listShowDuration: Boolean = true,
    /** 列表显示：封面 */
    val listShowCover: Boolean = true,
    /** 列表显示：来源徽标 */
    val listShowSource: Boolean = true,
    /** 列表显示：最高可用音质徽标 */
    val listShowQuality: Boolean = true,
    /**
     * 默认以「歌曲标定的最高可用音质」播放。
     *
     * 开启后，每首歌都会把列表接口标定的 [Song.maxQuality] 作为解析起点
     * （再走原有降档链），而不是统一用 `quality` 那一档。
     * 标定缺失（列表接口未提供元数据）的歌曲回退到 `quality`。
     */
    val qualityAutoHighest: Boolean = false,
    /** 下载完成后：写入歌曲信息标签（MP3 / FLAC） */
    val downloadWriteTags: Boolean = false,
    /** 下载完成后：写入封面 */
    val downloadWriteCover: Boolean = false,
    /** 下载完成后：嵌入歌词（MP3） */
    val downloadEmbedLyric: Boolean = false,
    /** WebDAV 数据同步：启用 */
    val webdavEnabled: Boolean = false,
    /** WebDAV 服务器地址（如 https://dav.example.com/） */
    val webdavUrl: String = "",
    /** WebDAV 用户名 */
    val webdavUsername: String = "",
    /** WebDAV 密码（仅本机保存，不参与同步） */
    val webdavPassword: String = "",
    /** 云端同步目录（默认 /DPmusic/） */
    val webdavPath: String = "/DPmusic/",
    /** 自动同步：收藏 / 歌单 / 屏蔽规则变更后节流上传 */
    val webdavAutoSync: Boolean = false,
    /** 上次同步时间（0 = 从未） */
    val webdavLastSyncTime: Long = 0L,
    /**
     * 局域网设备同步：本机在局域网中的显示名。
     *
     * 空时回退为设备型号 —— 对端（可能是 LocalSend）看到的就是这个名字。
     */
    val lanSyncAlias: String = "",
    /**
     * 局域网设备同步：本机指纹（协议 §2）。
     *
     * 明文 HTTP 模式下它只是随机串，但职责明确：**避免发现自己**。
     * 首次使用时生成一次并持久化，之后每台设备身份稳定。
     */
    val lanSyncFingerprint: String = "",
    /** 局域网设备同步：PIN（空 = 不校验；接收端强制校验，发送端据此填写） */
    val lanSyncPin: String = "",
    /**
     * 局域网设备同步：是否允许被其他设备发现并接收其推送。
     *
     * **默认开启**，且与界面无关（进程存活即生效）。早期版本把监听绑在同步页上，
     * 离开页面就停端口 —— 对端因此既扫不到、也推不过来，表现为「找不到设备」。
     */
    val lanSyncReceiveEnabled: Boolean = true,
    /** 局域网设备同步：协议端口（默认 53317，与 LocalSend 一致） */
    val lanSyncPort: Int = 53317,
    /** 局域网设备同步：上次成功同步时间（0 = 从未） */
    val lanSyncLastSyncTime: Long = 0L,
    /** 桌面歌词：启用（悬浮窗总开关） */
    val desktopLyricEnabled: Boolean = false,
    /** 桌面歌词：样式预设 id（见 core/lyric/DesktopLyricStyle.kt） */
    val desktopLyricPreset: String = "aurora",
    /** 桌面歌词：字号（sp） */
    val desktopLyricFontSize: Float = 22f,
    /** 桌面歌词：字间距（em） */
    val desktopLyricLetterSpacing: Float = 0.02f,
    /** 桌面歌词：整体不透明度（0.3 - 1.0） */
    val desktopLyricOpacity: Float = 1f,
    /** 桌面歌词：背景样式（preset / none / solid / glass） */
    val desktopLyricBackground: String = "preset",
    /** 桌面歌词：背景色（ARGB；0 = 跟随预设） */
    val desktopLyricBackgroundColor: Int = 0,
    /** 桌面歌词：背景浓度（0.1 - 1.0） */
    val desktopLyricBackgroundAlpha: Float = 0.45f,
    /** 桌面歌词：圆角（dp） */
    val desktopLyricCorner: Float = 22f,
    /** 桌面歌词：文字色（ARGB；0 = 跟随预设） */
    val desktopLyricTextColor: Int = 0,
    /** 桌面歌词：高亮色（ARGB；0 = 跟随预设 / 主题色） */
    val desktopLyricHighlightColor: Int = 0,
    /** 桌面歌词：描边宽度（dp；0 = 无描边） */
    val desktopLyricStrokeWidth: Float = 0f,
    /** 桌面歌词：描边色（ARGB） */
    val desktopLyricStrokeColor: Int = 0xFF0B0B10.toInt(),
    /** 桌面歌词：文字阴影 */
    val desktopLyricShadow: Boolean = true,
    /** 桌面歌词：逐字卡拉OK */
    val desktopLyricVerbatim: Boolean = true,
    /** 桌面歌词：显示翻译 */
    val desktopLyricShowTranslation: Boolean = true,
    /** 桌面歌词：显示下一行 */
    val desktopLyricShowNextLine: Boolean = true,
    /** 桌面歌词：迷你控制条（上一首 / 播放暂停 / 下一首 / 关闭） */
    val desktopLyricControls: Boolean = true,
    /** 桌面歌词：锁定位置（锁定后不可拖动） */
    val desktopLyricLocked: Boolean = false,
    /** 桌面歌词：触摸穿透（开启后点击 / 拖动穿透到下层应用） */
    val desktopLyricTouchThrough: Boolean = false,
    /** 桌面歌词：悬浮位置 X 偏移（px；-1 = 未设置，默认底部居中） */
    val desktopLyricOffsetX: Float = -1f,
    /** 桌面歌词：悬浮位置 Y 偏移（px；-1 = 未设置） */
    val desktopLyricOffsetY: Float = -1f,
    /** USB / 外接 DAC 的 Bit-Perfect 独占输出（Android 14+；开启时强制旁路均衡器） */
    val bitPerfectEnabled: Boolean = false,
    /** 播放页示波器（FFT 频谱 + 波形可视化；关闭时零开销） */
    val visualizerEnabled: Boolean = false,
    /** 示波器展示模式：`bars` = 频谱柱，`wave` = 波形，`both` = 两者 */
    val visualizerMode: String = "both",
    /**
     * 酷狗概念版音源：启用。
     *
     * **默认开启**：该通道**匿名即可取免费歌全曲**，是本应用「开箱可播」的基础通道
     * （Key 音源需自备 Key，脚本/插件需自行导入，三者均非默认可用）。
     */
    val kgLiteEnabled: Boolean = true,
    /**
     * 酷狗概念版音源：强制优先。
     * **默认开启**：概念版**匿名即可取免费歌全曲**，优先它可避免先撞上「未配置 Key」
     * 的空转与误导报错；失败仍会自动回退到 Key/脚本/插件。
     */
    val kgLiteForce: Boolean = true,
    /**
     * 酷狗概念版：每日自动签到领 VIP。
     * **默认关闭**——自动化账号操作可能触发平台风控，由用户主动开启并自负风险。
     */
    val kgLiteAutoClaim: Boolean = false,
    /**
     * 汽水音乐音源：启用。
     * **默认开启**：**匿名即可取免费歌全曲**（`h5/seo_track` 免签直连），且 VIP 曲目仍给 30s 试听。
     * 关闭后汽水曲目将无法解析（该平台无 Key/脚本/概念版通道），且**不再出现在各处平台选择中**。
     */
    val qishuiEnabled: Boolean = true,
    /**
     * 汽水 relay 中转地址（如 `http://122.10.114.177:8080`）。
     *
     * 配了「地址 + 密钥」后，汽水解析改走 relay（家机上的汽水客户端），
     * 能拿到 `is_full_length=true` 的**完整歌**与 `hi_res` 档；
     * 留空则退回匿名 `h5/seo_track`（热门曲常只有试听片段）。
     */
    val qishuiRelayUrl: String = "",
    /**
     * 汽水 relay 设备密钥（`DEVICE_SECRET`，HMAC 签名用）。
     *
     * ⚠️ 对称密钥，反编译 APK 即可读出，属「防误用」级别而非强鉴权（协议 §8）。
     * 仅本地保存，不随日志/导出泄漏。
     */
    val qishuiRelaySecret: String = "",
    /**
     * 汽水：优先请求**真无损**（`lossless`，FLAC ~1004 kbps）。
     *
     * ⚠️ 单曲 ~28 MB（协议 §5），移动网络下慎用；关闭时用默认 `hi_res`（AAC ~325 kbps）。
     */
    val qishuiLossless: Boolean = false,
    /**
     * 哔哩哔哩音源：启用。
     * **默认关闭** —— B 站是「视频站当音乐源」，搜歌实为搜视频、无歌词 / 榜单，
     * 属于**强偏好型**音源，由用户按需开启；开启后其曲目可搜索 / 播放（需登录才有无损）。
     * 关闭后：不在各处平台选择中显示，也不参与解析与跨平台兜底。
     */
    val biliEnabled: Boolean = false,
    /**
     * 逐平台解析链路（网易云 / QQ / 酷狗 各一条）。
     *
     * 取代旧的全局 [sourcePriority]：那个只能「二选一 + 回退」，无法表达
     * 「三/四个引擎的任意顺序、逐项启停」。旧值仍在，只作为**首次生成链路时的迁移依据**
     * （见 [SourceChain.defaultsFor]），用户改过链路后以链路为准。
     *
     * 汽水音乐不在此列：它只有直连通道（[qishuiEnabled] 控制），没有可排序的引擎。
     */
    val sourceChains: List<SourceChain> = defaultSourceChains(),
    /**
     * 「全部引擎都失败 → 跨平台找同名曲」总兜底开关。
     *
     * 关掉后，某平台的歌解析失败就**直接报错**，不会再自动换成别的平台的同曲。
     */
    val crossPlatformFallback: Boolean = true,
    /**
     * **JS 顺序与启停**（逐平台 × 逐类型）。
     *
     * 记录「LX 脚本 / MusicFree 插件」在**每个平台**上的尝试顺序与启停。
     * 与 [sourceChains] 的分工：链路决定「Key / 脚本 / 插件 / 概念版」四个**环节**的先后，
     * 这里决定「脚本环节内部」多个 JS 的先后 —— 是链路的下一层细化。
     *
     * 存的是**原始配置**：不包含「当前导入了哪些脚本」这一维度（那属于脚本仓库）。
     * 读取时只做结构性补齐（保证 平台 × 类型 的组合齐全），
     * 与「实际可用条目」的对齐由 [ScriptOrder.reconcile] 在使用处完成。
     */
    val scriptOrders: List<ScriptOrder> = defaultScriptOrders(),
)

/** 各平台的出厂默认链路（与旧版默认行为一致：酷狗概念版优先） */
fun defaultSourceChains(): List<SourceChain> =
    MusicPlatform.entries.map { SourceChain.defaultFor(it) }

/**
 * 当前**已启用**的平台列表（顺序与 [MusicPlatform.entries] 一致）。
 *
 * 单一事实来源：各处「平台选择」（搜索 / 榜单 / 歌单 / 音源管理 / 默认平台）
 * 都据此过滤，消费方不必各自判断开关，避免出现「某处忘了过滤」的不一致。
 *
 * 当前可被开关控制的平台：
 * - [MusicPlatform.QS] 汽水 —— [AppSettings.qishuiEnabled]
 * - [MusicPlatform.BB] 哔哩哔哩 —— [AppSettings.biliEnabled]
 * 其余平台（网易云 / QQ / 酷狗）为常驻音源，不提供关闭开关。
 */
fun AppSettings.enabledPlatforms(): List<MusicPlatform> = MusicPlatform.entries.filter { platform ->
    when (platform) {
        MusicPlatform.QS -> qishuiEnabled
        MusicPlatform.BB -> biliEnabled
        else -> true
    }
}

/** 单个平台是否已启用（含义同上） */
fun AppSettings.isPlatformEnabled(platform: MusicPlatform): Boolean =
    when (platform) {
        MusicPlatform.QS -> qishuiEnabled
        MusicPlatform.BB -> biliEnabled
        else -> true
    }

/**
 * 榜单页可展示的平台：**已启用**且提供榜单内容的音源。
 *
 * 目前 [MusicPlatform.hasToplist] 对五个音源均为 true（各有其内容形态：
 * 汽水=场景电台、B 站=音乐区排行），故等价于 [enabledPlatforms]。
 * 保留该抽象是为了：将来若某音源确实无榜单，只需改一处判断。
 */
fun AppSettings.toplistPlatforms(): List<MusicPlatform> =
    enabledPlatforms().filter { it.hasToplist }

/** 榜单页默认平台：优先用户默认平台（需同时满足「已启用 + 有榜单」），否则取首个可用 */
fun AppSettings.effectiveToplistPlatform(): MusicPlatform =
    defaultPlatform.takeIf { isPlatformEnabled(it) && it.hasToplist }
        ?: toplistPlatforms().firstOrNull()
        ?: MusicPlatform.WY

/**
 * 「默认平台」的**有效值**：若用户当前默认平台已被开关关闭，回退到第一个启用平台。
 *
 * 只影响**消费侧的初值选择**，不改用户已保存的 [AppSettings.defaultPlatform]——
 * 这样用户重开某音源时，默认平台仍是当初选的那个，不必重选。
 */
fun AppSettings.effectiveDefaultPlatform(): MusicPlatform =
    defaultPlatform.takeIf { isPlatformEnabled(it) }
        ?: enabledPlatforms().firstOrNull()
        ?: MusicPlatform.WY

/**
 * 各平台 × 各 JS 类型的出厂默认顺序（无外部可用列表时用：空链）。
 *
 * 真实的默认顺序依赖「已导入的脚本 / 插件列表」，由 [ScriptOrder.sanitize] 在读取时补齐；
 * 这里只给一个空壳，保证「还没导入任何东西」时不崩。
 */
fun defaultScriptOrders(): List<ScriptOrder> =
    ScriptKind.entries.flatMap { kind ->
        MusicPlatform.entries.map { ScriptOrder.defaultFor(it, kind, emptyList()) }
    }

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                defaultPlatform = prefs[KEY_PLATFORM]?.let { MusicPlatform.fromId(it) } ?: MusicPlatform.KG,
                quality = PlayQuality.fromId(prefs[KEY_QUALITY]),
                lxApiKey = prefs[KEY_LX_API_KEY] ?: DefaultLxApiKey,
                maxStorageMb = prefs[KEY_MAX_STORAGE_MB] ?: 1024,
                downloadDir = prefs[KEY_DOWNLOAD_DIR].orEmpty(),
                lyricScalePortrait = prefs[KEY_LYRIC_SCALE_PORTRAIT] ?: 1f,
                lyricScaleLandscape = prefs[KEY_LYRIC_SCALE_LANDSCAPE] ?: 1f,
                lyricSpacingPortrait = prefs[KEY_LYRIC_SPACING_PORTRAIT] ?: 1f,
                lyricSpacingLandscape = prefs[KEY_LYRIC_SPACING_LANDSCAPE] ?: 1f,
                verbatimLyric = prefs[KEY_VERBATIM_LYRIC] ?: true,
                simulatedVerbatim = prefs[KEY_SIMULATED_VERBATIM] ?: true,
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
                darkMode = prefs[KEY_DARK_MODE] ?: 0,
                clipboardAutoRead = prefs[KEY_CLIPBOARD_AUTO_READ] ?: false,
                lastClipboardHandled = prefs[KEY_LAST_CLIPBOARD_HANDLED].orEmpty(),
                themeColor = prefs[KEY_THEME_COLOR] ?: "default",
                glassMode = prefs[KEY_GLASS_MODE] ?: false,
                coverDynamicColor = prefs[KEY_COVER_DYNAMIC_COLOR] ?: false,
                coverColorStyle = prefs[KEY_COVER_COLOR_STYLE] ?: "content",
                sourcePriority = SourcePriority.fromId(prefs[KEY_SOURCE_PRIORITY]),
                sourceChains = SourceChain.sanitize(
                    stored = decodeSourceChains(prefs[KEY_SOURCE_CHAINS]),
                    // 旧版全局优先级作为迁移依据：没存过链路时按它生成默认链
                    legacy = prefs[KEY_SOURCE_PRIORITY]?.let { SourcePriority.fromId(it) },
                ),
                crossPlatformFallback = prefs[KEY_CROSS_PLATFORM_FALLBACK] ?: true,
                scriptOrders = decodeScriptOrders(prefs[KEY_SCRIPT_ORDERS]),
                playbackSpeed = prefs[KEY_PLAYBACK_SPEED] ?: 1f,
                sleepTimerWaitSongEnd = prefs[KEY_SLEEP_TIMER_WAIT_SONG_END] ?: true,
                lyricS2T = prefs[KEY_LYRIC_S2T] ?: false,
                carLyricEnabled = prefs[KEY_CAR_LYRIC] ?: false,
                carLyricFullLrc = prefs[KEY_CAR_LYRIC_FULL_LRC] ?: false,
                miIslandMode = MiIslandMode.fromValue(prefs[KEY_MI_ISLAND_MODE]),
                listShowAlbumName = prefs[KEY_LIST_SHOW_ALBUM_NAME] ?: true,
                listShowDuration = prefs[KEY_LIST_SHOW_DURATION] ?: true,
                listShowCover = prefs[KEY_LIST_SHOW_COVER] ?: true,
                listShowSource = prefs[KEY_LIST_SHOW_SOURCE] ?: true,
                listShowQuality = prefs[KEY_LIST_SHOW_QUALITY] ?: true,
                qualityAutoHighest = prefs[KEY_QUALITY_AUTO_HIGHEST] ?: false,
                downloadWriteTags = prefs[KEY_DOWNLOAD_WRITE_TAGS] ?: false,
                downloadWriteCover = prefs[KEY_DOWNLOAD_WRITE_COVER] ?: false,
                downloadEmbedLyric = prefs[KEY_DOWNLOAD_EMBED_LYRIC] ?: false,
                webdavEnabled = prefs[KEY_WEBDAV_ENABLED] ?: false,
                webdavUrl = prefs[KEY_WEBDAV_URL].orEmpty(),
                webdavUsername = prefs[KEY_WEBDAV_USERNAME].orEmpty(),
                webdavPassword = prefs[KEY_WEBDAV_PASSWORD].orEmpty(),
                webdavPath = prefs[KEY_WEBDAV_PATH] ?: "/DPmusic/",
                webdavAutoSync = prefs[KEY_WEBDAV_AUTO_SYNC] ?: false,
                webdavLastSyncTime = prefs[KEY_WEBDAV_LAST_SYNC_TIME] ?: 0L,
                lanSyncAlias = prefs[KEY_LAN_SYNC_ALIAS].orEmpty(),
                lanSyncFingerprint = prefs[KEY_LAN_SYNC_FINGERPRINT].orEmpty(),
                lanSyncPin = prefs[KEY_LAN_SYNC_PIN].orEmpty(),
                lanSyncReceiveEnabled = prefs[KEY_LAN_SYNC_RECEIVE_ENABLED] ?: true,
                lanSyncPort = prefs[KEY_LAN_SYNC_PORT] ?: 53317,
                lanSyncLastSyncTime = prefs[KEY_LAN_SYNC_LAST_SYNC_TIME] ?: 0L,
                desktopLyricEnabled = prefs[KEY_DESKTOP_LYRIC_ENABLED] ?: false,
                desktopLyricPreset = prefs[KEY_DESKTOP_LYRIC_PRESET] ?: "aurora",
                desktopLyricFontSize = prefs[KEY_DESKTOP_LYRIC_FONT_SIZE] ?: 22f,
                desktopLyricLetterSpacing = prefs[KEY_DESKTOP_LYRIC_LETTER_SPACING] ?: 0.02f,
                desktopLyricOpacity = prefs[KEY_DESKTOP_LYRIC_OPACITY] ?: 1f,
                desktopLyricBackground = prefs[KEY_DESKTOP_LYRIC_BACKGROUND] ?: "preset",
                desktopLyricBackgroundColor = prefs[KEY_DESKTOP_LYRIC_BACKGROUND_COLOR] ?: 0,
                desktopLyricBackgroundAlpha = prefs[KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA] ?: 0.45f,
                desktopLyricCorner = prefs[KEY_DESKTOP_LYRIC_CORNER] ?: 22f,
                desktopLyricTextColor = prefs[KEY_DESKTOP_LYRIC_TEXT_COLOR] ?: 0,
                desktopLyricHighlightColor = prefs[KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR] ?: 0,
                desktopLyricStrokeWidth = prefs[KEY_DESKTOP_LYRIC_STROKE_WIDTH] ?: 0f,
                desktopLyricStrokeColor = prefs[KEY_DESKTOP_LYRIC_STROKE_COLOR] ?: 0xFF0B0B10.toInt(),
                desktopLyricShadow = prefs[KEY_DESKTOP_LYRIC_SHADOW] ?: true,
                desktopLyricVerbatim = prefs[KEY_DESKTOP_LYRIC_VERBATIM] ?: true,
                desktopLyricShowTranslation = prefs[KEY_DESKTOP_LYRIC_SHOW_TRANSLATION] ?: true,
                desktopLyricShowNextLine = prefs[KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE] ?: true,
                desktopLyricControls = prefs[KEY_DESKTOP_LYRIC_CONTROLS] ?: true,
                desktopLyricLocked = prefs[KEY_DESKTOP_LYRIC_LOCKED] ?: false,
                desktopLyricTouchThrough = prefs[KEY_DESKTOP_LYRIC_TOUCH_THROUGH] ?: false,
                desktopLyricOffsetX = prefs[KEY_DESKTOP_LYRIC_OFFSET_X] ?: -1f,
                desktopLyricOffsetY = prefs[KEY_DESKTOP_LYRIC_OFFSET_Y] ?: -1f,
                bitPerfectEnabled = prefs[KEY_BIT_PERFECT_ENABLED] ?: false,
                visualizerEnabled = prefs[KEY_VISUALIZER_ENABLED] ?: false,
                visualizerMode = prefs[KEY_VISUALIZER_MODE] ?: "both",
                kgLiteEnabled = prefs[KEY_KGLITE_ENABLED] ?: true,
                kgLiteForce = prefs[KEY_KGLITE_FORCE] ?: true,
                kgLiteAutoClaim = prefs[KEY_KGLITE_AUTO_CLAIM] ?: false,
                qishuiEnabled = prefs[KEY_QISHUI_ENABLED] ?: true,
                qishuiRelayUrl = prefs[KEY_QISHUI_RELAY_URL].orEmpty(),
                qishuiRelaySecret = prefs[KEY_QISHUI_RELAY_SECRET].orEmpty(),
                qishuiLossless = prefs[KEY_QISHUI_LOSSLESS] ?: false,
                biliEnabled = prefs[KEY_BILI_ENABLED] ?: false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setDefaultPlatform(platform: MusicPlatform) =
        dataStore.edit { it[KEY_PLATFORM] = platform.id }

    suspend fun setQuality(quality: PlayQuality) =
        dataStore.edit { it[KEY_QUALITY] = quality.id }

    suspend fun setLxApiKey(key: String) =
        dataStore.edit { it[KEY_LX_API_KEY] = key.trim() }

    suspend fun setMaxStorageMb(mb: Int) =
        dataStore.edit { it[KEY_MAX_STORAGE_MB] = mb }

    suspend fun setDownloadDir(path: String) =
        dataStore.edit { it[KEY_DOWNLOAD_DIR] = path.trim() }

    suspend fun setLyricScalePortrait(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SCALE_PORTRAIT] = scale }

    suspend fun setLyricScaleLandscape(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SCALE_LANDSCAPE] = scale }

    suspend fun setLyricSpacingPortrait(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SPACING_PORTRAIT] = scale }

    suspend fun setLyricSpacingLandscape(scale: Float) =
        dataStore.edit { it[KEY_LYRIC_SPACING_LANDSCAPE] = scale }

    suspend fun setVerbatimLyric(enabled: Boolean) =
        dataStore.edit { it[KEY_VERBATIM_LYRIC] = enabled }

    suspend fun setSimulatedVerbatim(enabled: Boolean) =
        dataStore.edit { it[KEY_SIMULATED_VERBATIM] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) =
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setDarkMode(mode: Int) =
        dataStore.edit { it[KEY_DARK_MODE] = mode }

    suspend fun setClipboardAutoRead(enabled: Boolean) =
        dataStore.edit { it[KEY_CLIPBOARD_AUTO_READ] = enabled }

    suspend fun setLastClipboardHandled(text: String) =
        dataStore.edit { it[KEY_LAST_CLIPBOARD_HANDLED] = text }

    suspend fun setThemeColor(id: String) =
        dataStore.edit { it[KEY_THEME_COLOR] = id }

    suspend fun setGlassMode(enabled: Boolean) =
        dataStore.edit { it[KEY_GLASS_MODE] = enabled }

    /** 播放页封面动态取色开关 */
    suspend fun setCoverDynamicColor(enabled: Boolean) =
        dataStore.edit { it[KEY_COVER_DYNAMIC_COLOR] = enabled }

    /** 播放页封面动态取色：配色风格（[com.dpmusic.app.ui.theme.CoverColorStyle.id]） */
    suspend fun setCoverColorStyle(id: String) =
        dataStore.edit { it[KEY_COVER_COLOR_STYLE] = id }

    /**
     * 旧版「全局解析优先级」写入入口。
     *
     * ⚠️ 已无调用方 —— 解析改由逐平台链路驱动（[setSourceChain]）。
     * 保留是为了让「需要时把链路写回旧格式」这一动作有现成的落点，
     * 避免后来者重新发明一遍 DAO 层。新代码请勿使用。
     */
    @Deprecated("解析链路已取代全局优先级，请使用 setSourceChain")
    suspend fun setSourcePriority(priority: SourcePriority) =
        dataStore.edit { it[KEY_SOURCE_PRIORITY] = priority.id }

    /** 保存某个平台的解析链路（按 platformId 覆盖，其余平台不动） */
    suspend fun setSourceChain(chain: SourceChain) = dataStore.edit { prefs ->
        val current = decodeSourceChains(prefs[KEY_SOURCE_CHAINS])
        val merged = MusicPlatform.entries.map { platform ->
            if (platform.id == chain.platformId) chain
            else current.firstOrNull { it.platformId == platform.id }
                ?: SourceChain.defaultFor(platform)
        }
        prefs[KEY_SOURCE_CHAINS] = AppJson.encodeToString(merged)
    }

    /** 把某平台的链路恢复为出厂默认 */
    suspend fun resetSourceChain(platform: MusicPlatform) =
        setSourceChain(SourceChain.defaultFor(platform))

    /** 「全部失败 → 跨平台兜底」总开关 */
    suspend fun setCrossPlatformFallback(enabled: Boolean) =
        dataStore.edit { it[KEY_CROSS_PLATFORM_FALLBACK] = enabled }

    /**
     * 保存某个「平台 × JS 类型」的顺序配置。
     *
     * 只覆盖命中的那一格，其余格保持不变 —— 与 [setSourceChain] 同一约定。
     */
    suspend fun setScriptOrder(order: ScriptOrder) = dataStore.edit { prefs ->
        val current = decodeScriptOrders(prefs[KEY_SCRIPT_ORDERS])
        val merged = current.map { existing ->
            if (existing.platformId == order.platformId && existing.kind == order.kind) order
            else existing
        }.let { list ->
            // 目标格不在现有列表里（例如首次写入）→ 补上
            if (list.any { it.platformId == order.platformId && it.kind == order.kind }) list
            else list + order
        }
        prefs[KEY_SCRIPT_ORDERS] = AppJson.encodeToString(merged)
    }

    /**
     * 解析 JS 顺序 JSON。
     *
     * 容错与 [decodeSourceChains] 一致：非法 / 旧格式一律当作「没存过」，
     * 返回空列表，由 [ScriptOrder.sanitize] 用默认值补齐。
     */
    private fun decodeScriptOrders(raw: String?): List<ScriptOrder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<ScriptOrder>>(raw) }
            .getOrDefault(emptyList())
    }

    /**
     * 解析链路 JSON。
     *
     * 容错：非法 JSON / 旧版本写入的结构一律当作「没存过」（返回空列表），
     * 由 [SourceChain.sanitize] 用默认链补齐 —— 绝不让一次格式问题导致应用起不来。
     */
    private fun decodeSourceChains(raw: String?): List<SourceChain> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { AppJson.decodeFromString<List<SourceChain>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setPlaybackSpeed(speed: Float) =
        dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed }

    suspend fun setSleepTimerWaitSongEnd(enabled: Boolean) =
        dataStore.edit { it[KEY_SLEEP_TIMER_WAIT_SONG_END] = enabled }

    suspend fun setLyricS2T(enabled: Boolean) =
        dataStore.edit { it[KEY_LYRIC_S2T] = enabled }

    /** 车载 / 蓝牙歌词开关 */
    suspend fun setCarLyricEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_CAR_LYRIC] = enabled }

    /** 「发送整首 LRC」（把整首带时间轴歌词写进 extras） */
    suspend fun setCarLyricFullLrc(enabled: Boolean) =
        dataStore.edit { it[KEY_CAR_LYRIC_FULL_LRC] = enabled }

    /** 小米超级岛形态（关闭 / 歌词 / 发光歌词） */
    suspend fun setMiIslandMode(mode: MiIslandMode) =
        dataStore.edit { it[KEY_MI_ISLAND_MODE] = mode.value }

    suspend fun setListShowAlbumName(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_ALBUM_NAME] = enabled }

    suspend fun setListShowDuration(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_DURATION] = enabled }

    suspend fun setListShowCover(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_COVER] = enabled }

    suspend fun setListShowSource(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_SOURCE] = enabled }

    suspend fun setListShowQuality(enabled: Boolean) =
        dataStore.edit { it[KEY_LIST_SHOW_QUALITY] = enabled }

    suspend fun setQualityAutoHighest(enabled: Boolean) =
        dataStore.edit { it[KEY_QUALITY_AUTO_HIGHEST] = enabled }

    suspend fun setDownloadWriteTags(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_WRITE_TAGS] = enabled }

    suspend fun setDownloadWriteCover(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_WRITE_COVER] = enabled }

    suspend fun setDownloadEmbedLyric(enabled: Boolean) =
        dataStore.edit { it[KEY_DOWNLOAD_EMBED_LYRIC] = enabled }

    suspend fun setWebdavEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_WEBDAV_ENABLED] = enabled }

    suspend fun setWebdavUrl(url: String) =
        dataStore.edit { it[KEY_WEBDAV_URL] = url.trim() }

    suspend fun setWebdavUsername(name: String) =
        dataStore.edit { it[KEY_WEBDAV_USERNAME] = name.trim() }

    suspend fun setWebdavPassword(password: String) =
        dataStore.edit { it[KEY_WEBDAV_PASSWORD] = password }

    suspend fun setWebdavPath(path: String) =
        dataStore.edit { it[KEY_WEBDAV_PATH] = path.trim() }

    suspend fun setWebdavAutoSync(enabled: Boolean) =
        dataStore.edit { it[KEY_WEBDAV_AUTO_SYNC] = enabled }

    suspend fun setWebdavLastSyncTime(time: Long) =
        dataStore.edit { it[KEY_WEBDAV_LAST_SYNC_TIME] = time }

    /* ---------------- 局域网设备同步 ---------------- */

    suspend fun setLanSyncAlias(alias: String) =
        dataStore.edit { it[KEY_LAN_SYNC_ALIAS] = alias.trim() }

    suspend fun setLanSyncFingerprint(fingerprint: String) =
        dataStore.edit { it[KEY_LAN_SYNC_FINGERPRINT] = fingerprint.trim() }

    suspend fun setLanSyncPin(pin: String) =
        dataStore.edit { it[KEY_LAN_SYNC_PIN] = pin.trim() }

    /** 接收开关（默认开）；改动后由 [com.dpmusic.app.core.lansync.LanSyncManager] 的收集协程即时启停接收端 */
    suspend fun setLanSyncReceiveEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_LAN_SYNC_RECEIVE_ENABLED] = enabled }

    /** 协议端口；越界值一律夹回合法范围，避免把服务绑到特权端口 */
    suspend fun setLanSyncPort(port: Int) =
        dataStore.edit { it[KEY_LAN_SYNC_PORT] = port.coerceIn(MIN_LAN_SYNC_PORT, MAX_LAN_SYNC_PORT) }

    suspend fun setLanSyncLastSyncTime(time: Long) =
        dataStore.edit { it[KEY_LAN_SYNC_LAST_SYNC_TIME] = time }

    suspend fun setDesktopLyricEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_ENABLED] = enabled }

    suspend fun setDesktopLyricPreset(id: String) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_PRESET] = id }

    suspend fun setDesktopLyricFontSize(size: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_FONT_SIZE] = size }

    suspend fun setDesktopLyricLetterSpacing(spacing: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_LETTER_SPACING] = spacing }

    suspend fun setDesktopLyricOpacity(alpha: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OPACITY] = alpha }

    suspend fun setDesktopLyricBackground(mode: String) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND] = mode }

    suspend fun setDesktopLyricBackgroundColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND_COLOR] = color }

    suspend fun setDesktopLyricBackgroundAlpha(alpha: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA] = alpha }

    suspend fun setDesktopLyricCorner(radius: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_CORNER] = radius }

    suspend fun setDesktopLyricTextColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_TEXT_COLOR] = color }

    suspend fun setDesktopLyricHighlightColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR] = color }

    suspend fun setDesktopLyricStrokeWidth(width: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_STROKE_WIDTH] = width }

    suspend fun setDesktopLyricStrokeColor(color: Int) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_STROKE_COLOR] = color }

    suspend fun setDesktopLyricShadow(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHADOW] = enabled }

    suspend fun setDesktopLyricVerbatim(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_VERBATIM] = enabled }

    suspend fun setDesktopLyricShowTranslation(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHOW_TRANSLATION] = enabled }

    suspend fun setDesktopLyricShowNextLine(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE] = enabled }

    suspend fun setDesktopLyricControls(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_CONTROLS] = enabled }

    suspend fun setDesktopLyricLocked(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_LOCKED] = enabled }

    suspend fun setDesktopLyricTouchThrough(enabled: Boolean) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_TOUCH_THROUGH] = enabled }

    suspend fun setDesktopLyricOffsetX(offset: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OFFSET_X] = offset }

    suspend fun setDesktopLyricOffsetY(offset: Float) =
        dataStore.edit { it[KEY_DESKTOP_LYRIC_OFFSET_Y] = offset }

    // ---- 音频 DSP（v1.2.0）----

    suspend fun setBitPerfectEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_BIT_PERFECT_ENABLED] = enabled }

    suspend fun setVisualizerEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_VISUALIZER_ENABLED] = enabled }

    suspend fun setVisualizerMode(mode: String) =
        dataStore.edit { it[KEY_VISUALIZER_MODE] = mode }

    /** 酷狗概念版音源：启用开关 */
    suspend fun setKgLiteEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_KGLITE_ENABLED] = enabled }

    /** 酷狗概念版音源：强制优先开关 */
    suspend fun setKgLiteForce(enabled: Boolean) =
        dataStore.edit { it[KEY_KGLITE_FORCE] = enabled }

    /** 酷狗概念版：每日自动签到领 VIP 开关 */
    suspend fun setKgLiteAutoClaim(enabled: Boolean) =
        dataStore.edit { it[KEY_KGLITE_AUTO_CLAIM] = enabled }

    /** 记录上次自动签到日期（yyyy-MM-dd），避免同日重复执行 */
    suspend fun setKgLiteLastClaimDate(date: String) =
        dataStore.edit { it[KEY_KGLITE_LAST_CLAIM] = date }

    /** 汽水音乐音源：启用开关 */
    suspend fun setQishuiEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_QISHUI_ENABLED] = enabled }

    /** 汽水 relay 中转地址（留空 = 关闭 relay，退回匿名通道） */
    suspend fun setQishuiRelayUrl(url: String) =
        dataStore.edit { it[KEY_QISHUI_RELAY_URL] = url.trim() }

    /** 汽水 relay 设备密钥（HMAC 签名用） */
    suspend fun setQishuiRelaySecret(secret: String) =
        dataStore.edit { it[KEY_QISHUI_RELAY_SECRET] = secret.trim() }

    /** 汽水：优先真无损（lossless，FLAC，单曲 ~28MB） */
    suspend fun setQishuiLossless(enabled: Boolean) =
        dataStore.edit { it[KEY_QISHUI_LOSSLESS] = enabled }

    /** 哔哩哔哩音源：启用开关 */
    suspend fun setBiliEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_BILI_ENABLED] = enabled }

    private companion object {
        val KEY_PLATFORM = stringPreferencesKey("default_platform")
        val KEY_QUALITY = stringPreferencesKey("preferred_quality")
        val KEY_QUALITY_AUTO_HIGHEST = booleanPreferencesKey("quality_auto_highest")
        val KEY_LX_API_KEY = stringPreferencesKey("lx_api_key")
        val KEY_MAX_STORAGE_MB = intPreferencesKey("max_storage_mb")
        val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val KEY_LYRIC_SCALE_PORTRAIT = floatPreferencesKey("lyric_scale_portrait")
        val KEY_LYRIC_SCALE_LANDSCAPE = floatPreferencesKey("lyric_scale_landscape")
        val KEY_LYRIC_SPACING_PORTRAIT = floatPreferencesKey("lyric_spacing_portrait")
        val KEY_LYRIC_SPACING_LANDSCAPE = floatPreferencesKey("lyric_spacing_landscape")
        val KEY_VERBATIM_LYRIC = booleanPreferencesKey("verbatim_lyric")
        val KEY_SIMULATED_VERBATIM = booleanPreferencesKey("simulated_verbatim")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_DARK_MODE = intPreferencesKey("dark_mode")
        val KEY_CLIPBOARD_AUTO_READ = booleanPreferencesKey("clipboard_auto_read")
        val KEY_LAST_CLIPBOARD_HANDLED = stringPreferencesKey("last_clipboard_handled")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_GLASS_MODE = booleanPreferencesKey("glass_mode")
        val KEY_COVER_DYNAMIC_COLOR = booleanPreferencesKey("cover_dynamic_color")
        val KEY_COVER_COLOR_STYLE = stringPreferencesKey("cover_color_style")
        val KEY_SOURCE_PRIORITY = stringPreferencesKey("source_priority")
        val KEY_SOURCE_CHAINS = stringPreferencesKey("source_chains_json")
        val KEY_CROSS_PLATFORM_FALLBACK = booleanPreferencesKey("cross_platform_fallback")
        val KEY_SCRIPT_ORDERS = stringPreferencesKey("script_orders_json")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val KEY_SLEEP_TIMER_WAIT_SONG_END = booleanPreferencesKey("sleep_timer_wait_song_end")
        val KEY_LYRIC_S2T = booleanPreferencesKey("lyric_s2t")
        val KEY_CAR_LYRIC = booleanPreferencesKey("car_lyric_enabled")

        /** 「发送整首 LRC」（把整首带时间轴歌词写进 extras） */
        val KEY_CAR_LYRIC_FULL_LRC = booleanPreferencesKey("car_lyric_full_lrc")

        /** 小米超级岛形态（off / lyric / lyric_glow） */
        val KEY_MI_ISLAND_MODE = stringPreferencesKey("mi_island_mode")
        val KEY_LIST_SHOW_ALBUM_NAME = booleanPreferencesKey("list_show_album_name")
        val KEY_LIST_SHOW_DURATION = booleanPreferencesKey("list_show_duration")
        val KEY_LIST_SHOW_COVER = booleanPreferencesKey("list_show_cover")
        val KEY_LIST_SHOW_SOURCE = booleanPreferencesKey("list_show_source")
        val KEY_LIST_SHOW_QUALITY = booleanPreferencesKey("list_show_quality")
        val KEY_DOWNLOAD_WRITE_TAGS = booleanPreferencesKey("download_write_tags")
        val KEY_DOWNLOAD_WRITE_COVER = booleanPreferencesKey("download_write_cover")
        val KEY_DOWNLOAD_EMBED_LYRIC = booleanPreferencesKey("download_embed_lyric")
        val KEY_WEBDAV_ENABLED = booleanPreferencesKey("webdav_enabled")
        val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
        val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val KEY_WEBDAV_PATH = stringPreferencesKey("webdav_path")
        val KEY_WEBDAV_AUTO_SYNC = booleanPreferencesKey("webdav_auto_sync")
        val KEY_WEBDAV_LAST_SYNC_TIME = longPreferencesKey("webdav_last_sync_time")
        val KEY_LAN_SYNC_ALIAS = stringPreferencesKey("lan_sync_alias")
        val KEY_LAN_SYNC_FINGERPRINT = stringPreferencesKey("lan_sync_fingerprint")
        val KEY_LAN_SYNC_PIN = stringPreferencesKey("lan_sync_pin")
        val KEY_LAN_SYNC_RECEIVE_ENABLED = booleanPreferencesKey("lan_sync_receive_enabled")
        val KEY_LAN_SYNC_PORT = intPreferencesKey("lan_sync_port")
        val KEY_LAN_SYNC_LAST_SYNC_TIME = longPreferencesKey("lan_sync_last_sync_time")

        /** 协议端口合法范围（1024 以下为特权端口，绑定可能失败） */
        const val MIN_LAN_SYNC_PORT = 1024
        const val MAX_LAN_SYNC_PORT = 65535
        val KEY_DESKTOP_LYRIC_ENABLED = booleanPreferencesKey("desktop_lyric_enabled")
        val KEY_DESKTOP_LYRIC_PRESET = stringPreferencesKey("desktop_lyric_preset")
        val KEY_DESKTOP_LYRIC_FONT_SIZE = floatPreferencesKey("desktop_lyric_font_size")
        val KEY_DESKTOP_LYRIC_LETTER_SPACING = floatPreferencesKey("desktop_lyric_letter_spacing")
        val KEY_DESKTOP_LYRIC_OPACITY = floatPreferencesKey("desktop_lyric_opacity")
        val KEY_DESKTOP_LYRIC_BACKGROUND = stringPreferencesKey("desktop_lyric_background")
        val KEY_DESKTOP_LYRIC_BACKGROUND_COLOR = intPreferencesKey("desktop_lyric_background_color")
        val KEY_DESKTOP_LYRIC_BACKGROUND_ALPHA = floatPreferencesKey("desktop_lyric_background_alpha")
        val KEY_DESKTOP_LYRIC_CORNER = floatPreferencesKey("desktop_lyric_corner")
        val KEY_DESKTOP_LYRIC_TEXT_COLOR = intPreferencesKey("desktop_lyric_text_color")
        val KEY_DESKTOP_LYRIC_HIGHLIGHT_COLOR = intPreferencesKey("desktop_lyric_highlight_color")
        val KEY_DESKTOP_LYRIC_STROKE_WIDTH = floatPreferencesKey("desktop_lyric_stroke_width")
        val KEY_DESKTOP_LYRIC_STROKE_COLOR = intPreferencesKey("desktop_lyric_stroke_color")
        val KEY_DESKTOP_LYRIC_SHADOW = booleanPreferencesKey("desktop_lyric_shadow")
        val KEY_DESKTOP_LYRIC_VERBATIM = booleanPreferencesKey("desktop_lyric_verbatim")
        val KEY_DESKTOP_LYRIC_SHOW_TRANSLATION = booleanPreferencesKey("desktop_lyric_show_translation")
        val KEY_DESKTOP_LYRIC_SHOW_NEXT_LINE = booleanPreferencesKey("desktop_lyric_show_next_line")
        val KEY_DESKTOP_LYRIC_CONTROLS = booleanPreferencesKey("desktop_lyric_controls")
        val KEY_DESKTOP_LYRIC_LOCKED = booleanPreferencesKey("desktop_lyric_locked")
        val KEY_DESKTOP_LYRIC_TOUCH_THROUGH = booleanPreferencesKey("desktop_lyric_touch_through")
        val KEY_DESKTOP_LYRIC_OFFSET_X = floatPreferencesKey("desktop_lyric_offset_x")
        val KEY_DESKTOP_LYRIC_OFFSET_Y = floatPreferencesKey("desktop_lyric_offset_y")

        // ---- 音频 DSP（v1.2.0）----
        /** Bit-Perfect 独占输出开关（Android 14+；开启时强制旁路均衡器） */
        val KEY_BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")

        /** 播放页示波器开关 */
        val KEY_VISUALIZER_ENABLED = booleanPreferencesKey("visualizer_enabled")

        /** 示波器展示模式：bars / wave / both */
        val KEY_VISUALIZER_MODE = stringPreferencesKey("visualizer_mode")

        // ---- 酷狗概念版音源 ----
        /** 酷狗概念版音源开关（默认关闭） */
        val KEY_KGLITE_ENABLED = booleanPreferencesKey("kglite_enabled")

        /** 酷狗概念版音源强制优先 */
        val KEY_KGLITE_FORCE = booleanPreferencesKey("kglite_force")

        /** 酷狗概念版：每日自动签到领 VIP（默认关闭） */
        val KEY_KGLITE_AUTO_CLAIM = booleanPreferencesKey("kglite_auto_claim")

        /** 上次自动签到日期（yyyy-MM-dd，防同日重复） */
        val KEY_KGLITE_LAST_CLAIM = stringPreferencesKey("kglite_last_claim_date")

        /** 汽水音乐音源：启用开关 */
        val KEY_QISHUI_ENABLED = booleanPreferencesKey("qishui_enabled")
        val KEY_QISHUI_RELAY_URL = stringPreferencesKey("qishui_relay_url")
        val KEY_QISHUI_RELAY_SECRET = stringPreferencesKey("qishui_relay_secret")
        val KEY_QISHUI_LOSSLESS = booleanPreferencesKey("qishui_lossless")
        val KEY_BILI_ENABLED = booleanPreferencesKey("bili_enabled")
    }
}