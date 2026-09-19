# DPmusic 架构说明

本文描述当前代码库的整体架构、核心数据流、线程模型与扩展方式。
所有描述均对应 `app/src/main/kotlin/com/dpmusic/app/` 下的实际实现（184 个 Kotlin 文件 / 约 39,500 行）。

---

## 一、分层总览

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层（Jetpack Compose）                                     │
│  ui/shell（自适应外壳 + 导航图） · ui/screens/*（页面）          │
│  ui/components（可复用组件） · ui/theme（M3 + 毛玻璃 + 调色板）  │
└───────────────┬──────────────────────────────────────────────┘
                │  StateFlow / collectAsStateWithLifecycle
┌───────────────▼──────────────────────────────────────────────┐
│  ViewModel 层                                                 │
│  ui/**/XxxViewModel（页面状态与交互编排）                        │
│  统一由 AppViewModelFactory 注入依赖                            │
└───────────────┬──────────────────────────────────────────────┘
                │  领域能力调用
┌───────────────▼──────────────────────────────────────────────┐
│  领域层（core）                                                │
│  repo/MusicRepository        音源聚合与容错解析                  │
│  playback/PlayerConnection   UI ↔ 播放服务唯一桥接               │
│  lyric/LyricsHub             进程级歌词中心                      │
│  download/DownloadTaskStore  下载队列                           │
│  sync/SyncManager            WebDAV 备份恢复                    │
│  together/TogetherSession    一起听房间                         │
│  audio/AudioEffectsManager   音效均衡器                          │
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│  数据与基础设施层                                              │
│  net/（三平台 API · HTTP 层 · 音源解析 · 脚本引擎）              │
│  data/（DataStore 仓库群 · 57 项设置）                          │
│  playback/MusicService（Media3 MediaSessionService）           │
│  util/（日志 · 存储 · 熔断器 · 取色）                            │
└──────────────────────────────────────────────────────────────┘
```

**依赖方向单向向下**：UI → ViewModel → core 领域层 → net/data。`core` 层不引用任何 `ui` 类型。

---

## 二、应用启动链路

```
DPmusicApp.onCreate()
 ├─ AppLogger.installCrashHandler()          安装全局未捕获异常捕获
 ├─ AppContainer.init(this)                  构建手工 DI 容器（懒加载单例）
 └─ SingletonImageLoader.setSafe { ... }     安装 Coil 全局图片加载器（复用 OkHttp + 磁盘缓存上限）

MainActivity.onCreate()
 ├─ enableEdgeToEdge()                       沉浸式
 ├─ AppContainer.player.connect()            连接 MediaSessionService（幂等，可安全重入）
 ├─ handleOpenIntent(intent)                 外部「打开 / 分享」JSON → ImportInbox
 ├─ 申请 POST_NOTIFICATIONS（Android 13+）
 └─ setContent { DPmusicTheme { ... } }
      ├─ 桌面歌词开关变化 → DesktopLyricService.sync(context, enabled)
      ├─ 深色模式（三态：跟随系统 / 强制浅色 / 强制深色）→ 系统栏图标同步
      ├─ 毛玻璃共享层 rememberGraphicsLayer() → LocalGlassBlur
      ├─ 列表显示开关 → LocalListDisplayOptions
      └─ DPmusicShell(windowSizeClass)
```

`onWindowFocusChanged(true)` 时额外做两件事：
1. `checkClipboard()` —— 剪贴板自动读取（可关闭）：仅处理含链接、长度 ≤ 4000、且与上次处理不同的文本；
   按「一起听邀请 → 歌曲链接 → 歌单链接」优先级投入 `ClipboardLinkInbox`。
2. `AppContainer.togetherInviteWatcher.checkNow()` —— 一起听私信邀请检查（内部节流）。

---

## 三、依赖注入（AppContainer）

项目**不使用 Hilt / KSP**，改用 `object AppContainer` 手工装配，全部单例懒加载，零注解处理开销：

| 类别 | 成员 |
|---|---|
| 上下文 | `appContext` |
| 存储 | `settings` · `favorites` · `userPlaylists` · `history` · `dislike` · `searchHistory` · `stats` · `session` · `equalizer` |
| 账号 | `ncm` · `qq` · `ncmSync` · `qqSync` |
| 网络 | `wyApi` · `qqApi` · `kgApi` · `lxResolver` · `scriptResolver` · `userApiEngine` · `userApiRepo` |
| 播放 | `player`（`PlayerConnection`）· `sleepTimer` · `ncmFm` |
| 业务 | `musicRepository` · `downloads` · `sync` · `together` · `togetherInviteWatcher` · `widgetUpdater` |

ViewModel 统一经 `AppViewModelFactory` 从容器取依赖，页面不直接触碰单例。

---

## 四、播放子系统

### 4.1 服务端：`core/playback/MusicService`

继承 `MediaSessionService`，**ExoPlayer 完全由服务持有**，与 UI 生命周期解耦：

- `handleAudioFocus = true`：来电 / 其他应用抢占自动处理
- `setHandleAudioBecomingNoisy(true)`：拔耳机自动暂停
- 网络唤醒锁：锁屏弱网持续缓冲
- `DefaultMediaNotificationProvider`：定制小图标 + 中文频道名
- `Player.Listener.onAudioSessionIdChanged` → `AudioEffectsManager.attach(sessionId)`（均衡器随会话重挂）
- `DefaultLoadControl` 按「流媒体 / 本地文件」两套参数（弱网缓冲调优：流媒体更长缓冲与回退，本地文件短缓冲快速起播）

### 4.2 客户端桥接：`core/playback/PlayerConnection`（676 行）

UI 与服务的**唯一**通道（`MediaController` + `SessionToken`），关键机制：

| 机制 | 说明 |
|---|---|
| **懒解析队列** | 仅当前曲与下一曲持有真实 URL，其余为占位媒体项；推进到占位项时实时解析并 `replaceMediaItem`（避免批量预解析拖慢起播） |
| **错误自愈** | `onPlayerError` → 强制重解析（含跨平台兜底）→ 重试；同一首连续失败 2 次则提示并自动跳过 |
| **状态出口** | `NowPlaying` StateFlow（曲目 / 进度 / 播放态 / 循环模式 / 队列）单向流出 |
| **命令入口** | `PlayerCommand` SharedFlow（播放 / 暂停 / 切歌 / seek / 模式切换 / 提示消息） |
| **会话持久化** | 队列 + 当前索引 + 进度写入 `PlaybackSessionStore`，供主页「继续收听」冷启动恢复 |
| **副作用联动** | 播放即写历史、红心状态同步收藏、命中屏蔽规则自动跳过 |

### 4.3 容错解析链：`core/repo/MusicRepository`

```
resolve(song, quality)
  ├─ 按 SourcePriority 选择「自定义脚本」或「Key 音源」作为首选
  ├─ 首选解析（内部已含音质降档链：如 flac → 320k → 128k）
  │    └─ 每档校验返回地址是否「真实媒体文件」（拒绝空壳地址以继续降档）
  └─ 全部失败 → 到另外两个平台按「标题 + 歌手」相似度换源
       └─ 成功后返回 ResolvedPlayback（可能已替换为其它平台的曲目，UI 提示已切换音源）
```

配套组件：

- **降档链** `PlayQuality.chainFor(platform)`：按平台给出可用档位序列（各平台支持档位不同）
- **URL 缓存**：解析结果缓存 8 分钟（`URL_TTL_MS`），避免频繁解析
- **熔断器** `core/util/CircuitBreaker`：连续失败 4 次 → 冷却 30s（期间拒绝请求），冷却后放行单个探测请求
- **HTTP 层** `core/net/Http`：OkHttp 单例 + 统一 UA + 15/20/20s 超时 + 3 次指数退避重试

### 4.4 定时退出与私人 FM

- `SleepTimerController`：倒计时到点自动暂停；支持「播完当前歌曲后停止」（到点等当前曲自然结束或用户切歌）；状态经 StateFlow 供菜单 / 面板展示
- `NcmFmController`：拉取一批 FM 歌曲替换队列并打开播放页；监听队列接近队尾自动「续杯」；队列被外部替换则自动停止；命中屏蔽规则自动跳过

---

## 五、音源解析子系统

播放地址有两条来源，由设置 `sourcePriority` 决策（脚本优先 / Key 优先 / 仅脚本 / 仅 Key）：

### 5.1 自定义音源脚本（`core/script`）

- `UserApiEngine`（585 行）：**QuickJS** 沙箱执行 LX Music 自定义音源脚本
  - 独立 `HandlerThread` 承载 JS 运行时，避免阻塞主线程
  - 提供脚本所需的网络 / 加密 / 工具 API（`Http`、Base64、MD5、AES、RSA、URL 编解码…）
  - 脚本回调与 Kotlin 侧经 `CompletableDeferred` 对接，带超时
  - 引擎状态经 `ScriptEngineStatus`（Idle / Loading / Ready / Failed）单向流出
- `UserApiRepository`：脚本元信息（列表 / 激活项 / 内容）持久化到设置；`SourceManagerScreen` 提供导入 / 启用 / 删除
- `ScriptMusicResolver`：对上层提供与远端代理一致的解析入口
- `assets/script/user-api-preload.js`：脚本运行环境预置

### 5.2 远端音源代理（`core/net/LxResolver`）

- 统一 `GET {API_BASE}/url?source=&songId=&quality=`，请求头携带 `X-API-Key`（用户在设置中填写，**不内置**）
- 响应状态映射为语义化异常：403 → `AuthFailureException`（Key 失效）、429 → `RateLimitedException`、其它 → `ResolveException`
- 地址有效性校验：拒绝空串、非 http(s)、仅域名 / 根路径的空壳地址（触发继续降档）
- 代理地址为常量（`API_BASE`），可替换为自建服务

### 5.3 三平台 API（`core/net`）

| 文件 | 说明 |
|---|---|
| `WyApi` / `NcmApi` / `NcmEapi` | 网易云：搜索 / 歌单 / 榜单 / 详情 / 评论 / 每日推荐 / 私人 FM / 一起听；`NcmEapi` 实现 eapi 参数加密（AES + 摘要） |
| `QqApi`（581 行） | QQ 音乐：搜索 / 歌单 / 榜单 / 详情 / 推荐 / 歌词（QRC）；Cookie 校验（`uin` + `qqmusic_key`） |
| `KgApi` | 酷狗：搜索 / 歌单 / 榜单 / 详情 / 歌词（KRC）/ 封面兜底（`union_cover`） |
| `PlatformApi` | 三平台统一接口抽象（`MusicRepository` 按平台分发） |
| `SongLinkParser` / `PlaylistLinkParser` | 官方分享链接解析（歌曲 / 歌单） |
| `JsonUtils` | `kotlinx.serialization` 之上的安全取值扩展（`str` / `long` / `objOrNull` / `arrOrNull`…） |

---

## 六、歌词子系统

### 6.1 进程级歌词中心：`core/lyric/LyricsHub`

```
LyricsHub.attach(player, musicRepository)   由 DesktopLyricService / 播放页调用（幂等）
   ├─ 跟随当前曲目加载歌词（独立于播放页生命周期）
   ├─ 空结果不入缓存 + 自动重试一次
   └─ 播放页可复用同一份结果（避免重复请求）
```

### 6.2 解析器

| 文件 | 格式 |
|---|---|
| `LrcParser` | 标准 LRC（行级 + 翻译行合并） |
| `QrcParser` + `QrcDecoder` | QQ 音乐 QRC（含**自定义 DES 解密**）→ 逐字时间轴 |
| `YrcParser` | 网易云 YRC → 逐字时间轴 |
| `KrcParser` | 酷狗 KRC → 逐字时间轴 |
| `ChineseS2T` | 繁简转换（歌词一键切换） |
| `SimulatedVerbatim` | 无逐字数据时，按行内时长估算逐字进度 |

统一产出 `SongLyrics`（`lines: List<LyricLine>`，每行含 `translation` 与可选 `words: List<LyricWord>`）。

### 6.3 渲染

- **播放页歌词** `ui/components/LyricsView`（556 行）：逐字卡拉 OK 渐变、行级颜色 / 缩放 / 透明度阻尼过渡、点击行 seek、自动滚动（拖动暂停 / 松手 4s 恢复）
- **桌面歌词**（见下节）：独立纯 Canvas 渲染

### 6.4 桌面歌词（悬浮窗）

| 文件 | 职责 |
|---|---|
| `DesktopLyricStyle`（275 行） | 6 套预设（流光 / 黑胶 / 霓虹 / 玻璃 / 墨韵 / 糖果）+ 样式解析（0 = 跟随预设 / 主题色） |
| `DesktopLyricView`（802 行） | **纯 Canvas 自绘**：逐字渐变、描边叠层、阴影、动态高度、胶囊自适应宽度、拖动与单击控制条（几何图标自绘） |
| `DesktopLyricService`（432 行） | 前台服务（`specialUse`）+ `WindowManager` 悬浮窗 + 三路数据流（播放状态 / 歌词中心 / 设置）+ 位置持久化 + 触摸穿透 |

**关键实现约定（避免踩坑）：**

1. **测量与绘制分离**：`baseFontMetrics()` 用独立 `measurePaint` 计算字体度量，
   `contentHeight()` 与 `onDraw` 都读它；绘制时的字号缩放（`fitTextSize`）**只返回字号、不改画笔**
   —— 否则窗口高度会在「绘制前 / 绘制后」之间震荡，表现为拖动时强烈抖动
2. **首行基线** = `paddingV - fm.ascent`（而非 `+ fm.descent`）—— 字体框上下各留 `paddingV`，文字垂直居中
3. **窗口参数**：`WRAP_CONTENT`（宽度由内容测量决定）+ `gravity = BOTTOM or CENTER_HORIZONTAL`；
   此 gravity 下 `params.y` 表示「窗口底边距屏幕底边距离，**向上为正**」，纵向位移需取反
4. **同帧合并**：`scheduleWindowUpdate()` 用 `postOnAnimation` 把同一帧内多次 MOVE 合并为一次
   `updateViewLayout`；拖动中 `clampPosition()` 直接 return（不被 500ms 数据推送夹紧）
5. 位置以 Float 累积（`posX/posY`），拖动结束写回设置；未启用「不限边界」窗口标志，防止窗口丢失

---

## 七、数据层（`core/data`）

统一使用 **DataStore Preferences**（`appDataStore`），按领域拆分为多个仓库：

| 仓库 | 内容 |
|---|---|
| `SettingsRepository`（433 行） | **57 项设置**（字段 / 映射 / setter / key 四件套）：平台与音质、音源服务、存储、下载、歌词、外观、列表显示、WebDAV、桌面歌词… |
| `FavoritesRepository` / `UserPlaylistRepository` | 收藏与本地歌单（JSON 序列化） |
| `HistoryRepository` / `SearchHistoryRepository` | 播放历史 / 搜索历史 |
| `DislikeRepository` | 屏蔽规则（歌曲 / 歌手 / 关键词） |
| `ListeningStatsStore` | 听歌统计（按日时长与曲数） |
| `PlaybackSessionStore` | 播放会话（队列 + 索引 + 进度） |
| `NcmRepository` / `QqRepository` | 账号 Cookie 与资料缓存 |
| `NcmSyncService` / `QqSyncService` | 红心双向自动同步（批量 + 风控保护） |
| `EqualizerRepository` | 均衡器设置（预设 / 手动频段 / 低音 / 环绕） |

> 账号 Cookie 仅存本机，用于直连接口，不写日志、不上传第三方。

---

## 八、同步子系统（`core/sync`）

- `SyncManager`（232 行）：两类备份文件 —— **「设置与音源」**、**「歌单与数据」**
  - 覆盖范围由 `SyncScopes` **白名单**决定：只同步显式列出的键（含前缀匹配，如脚本内容 `user_script_content_*`）
  - **账号 Cookie / 音源 Key / WebDAV 配置（含密码）永不参与同步**
  - 支持手动备份 / 恢复、自动同步（监听变化 + 节流）、上次同步时间记录
- `WebDavClient`：基础 WebDAV 操作（目录创建 / 上传 / 下载）
- 偏好项以 `PrefEntry(type, value)` 形式序列化，恢复时按类型还原键值

---

## 九、下载子系统（`core/download`）

| 文件 | 职责 |
|---|---|
| `DownloadTaskStore`（407 行） | 进程级任务队列：**串行执行**、进度 / 速度实时刷新、暂停 / 恢复 / 取消；任务列表持久化到 `download_tasks.json`（节流保存）；重启后中断任务标记「已暂停」 |
| `SongDownloader` | 单曲下载：音质降档、跨平台换源、写盘 |
| `DownloadPaths` | 默认目录 `Music/DPmusic`（外部存储不可用时退回应用专属目录）+ 可写性校验 + 权限引导 |
| `AudioMetadataWriter`（398 行） | 元数据增强：**ID3 / FLAC 标签**、**封面嵌入**、**歌词嵌入（含翻译行）** |
| `DownloadTask` | 任务模型（状态机） |

UI 侧：`DownloadManagerScreen`（任务 + 历史）、`DownloadSheet`（下载选项）、`DownloadBall`（全局进度悬浮球）。

---

## 十、听歌识曲子系统（`core/audio`）

```
拾音方式（AudioPickSource）
 ├─ MIC     麦克风：采集 10 秒环境音
 ├─ SYSTEM  系统播放捕获：MediaProjection（Android 10+），由 CaptureForegroundService 承载前台服务
 └─ FILE    本地音频文件：SAF 选择 → AudioFileDecoder 解码为单声道 PCM

    ↓ AudioSampler（重采样 + float → int16 LE）

双引擎并行（RecognitionSheet 状态机同时驱动两个 EngineState）
 ├─ KgRecognizer   酷狗 PCM 指纹直传（MD5 签名）
 └─ AudioRecognizer 网易云 afp 指纹（AudioFingerprintEngine = WebView + wasm，
                    页面资源为 assets/audiofp/index.html）

    ↓ 候选列表 → 播放 / 收藏 / 加歌单 / 找原唱
```

音频**仅内存处理，不落盘**。

---

## 十一、UI 架构

### 11.1 外壳与导航

- `ui/shell/DPmusicShell`（616 行）：`WindowSizeClass` 驱动 ——
  Compact 用 `NavigationBar`（底部），Medium / Expanded 用 `NavigationRail`（侧边，矮窗口精简 header）；
  统一承载迷你播放条、播放面板宿主、下载悬浮球、玻璃背景层、剪贴板链接弹窗
- 五大主页面：**主页 / 搜索 / 榜单 / 歌单 / 我的**
- `ui/shell/AppNavHost`（345 行）：**类型安全路由**（`@Serializable` 路由对象），
  标签切换用 M3 `fadeThrough`（无方向感），详情页推进用方向化过渡，封面共享元素转场
- `ui/navigation/Routes.kt`：全部路由定义（主页 / 搜索 / 榜单 / 榜单详情 / 平台歌单 / 本地歌单 /
  歌手 / 专辑 / 最近播放 / 每日推荐 / 我的歌单 / 推荐 / 设置 / 日志 / 音源管理 / 下载管理 / 数据同步 / 一起听）

### 11.2 主题与视觉

- `DPmusicTheme(darkTheme, dynamicColor, themeColor, glass)`：Material 3 + **动态取色**（Android 12+）+ 内置调色板
- **毛玻璃**：`GlassBackdrop` 写入共享 `GraphicsLayer`，玻璃面板采样做真实模糊；`LocalGlassBlur` 向下提供；
  设置中可整体开关（`glassMode`）
- 设计约定：**半透明面板不使用 `shadowElevation`**（会破坏通透感），改用描边 / 高光分层

### 11.3 组件与状态

- `ui/components`（37 个）：`PlayerSheet`（1837 行，播放页与三点菜单）、`RecognitionSheet`（1058 行）、
  `DesktopLyricSheet`（728 行）、`LyricsView`、`EqualizerSheet`、`SleepTimerSheet`、`PlaybackSpeedSheet`、
  `QueueSheet`、`CommentsSheet`、`SimilarSongsSheet`、`SongShareSheet`、`DownloadSheet`、
  `ListeningStatsCard`、`DislikeManagerSheet`、`SongRow`、`VinylDisc`、`Glass`…
- 状态一律经 ViewModel 的 `StateFlow` 暴露，UI 用 `collectAsStateWithLifecycle()` 订阅
- 跨页面共享的小状态用 `CompositionLocal`（`LocalGlassBlur`、`LocalListDisplayOptions`）

---

## 十二、线程模型与性能约定

| 线程 | 承载 |
|---|---|
| 主线程 | Compose UI · `PlayerConnection`（`Dispatchers.Main.immediate`）· `MusicService` 持有 ExoPlayer · 桌面歌词服务 |
| `Dispatchers.IO` | DataStore 读写 · 网络请求 · 下载与写盘 · 图片缓存 |
| 专用 `HandlerThread` | QuickJS 音源脚本引擎 |

**性能约定：**

- `onDraw` 中**零分配**：Paint / Path / RectF / FontMetrics 全部复用字段
- 歌词视图仅在**内容变化**时 `requestLayout()`，其余帧只 `invalidate()`
- 悬浮窗更新按帧合并（`postOnAnimation`），避免同帧多次 IPC
- 高频写入（设置滑块）采用**松手落盘**，避免每帧写 DataStore
- 图片统一走 Coil（复用 OkHttp 连接池 + 磁盘 LRU 上限）

---

## 十三、扩展点

| 想做什么 | 改哪里 |
|---|---|
| **新增平台** | `core/model/MusicPlatform`（枚举 + 品牌色 + LX 代号）→ 实现 `PlatformApi` → `AppContainer` 注册 → 链接解析器补规则 |
| **新增设置项** | `SettingsRepository` 四件套（字段 / 读取映射 / setter / key）；若需同步，再评估加入 `SyncScopes` |
| **新增页面** | `ui/navigation/Routes.kt` 加 `@Serializable` 路由 → `ui/screens/<域>/` 建 Screen + ViewModel → `AppNavHost` 注册 |
| **新增桌面歌词预设** | `core/lyric/DesktopLyricStyle` 的预设列表 + 样式解析分支 |
| **新增音效预设** | `core/audio/AudioEffectsManager.presets`（5 锚点电平，按对数频率插值到实际频段数） |
| **新增歌词格式** | `core/lyric/` 加解析器 → `LyricsHub` 挂载到加载链路 |
| **换音源代理** | `core/net/LxResolver` 的 `API_BASE` 常量（或改用自定义脚本，无需代理） |
| **换下载默认目录** | `core/download/DownloadPaths.defaultPath()` |

---

## 十四、已知取舍（有意为之）

| 取舍 | 原因 |
|---|---|
| 手工 DI（`AppContainer`）替代 Hilt | 零注解处理开销，编译更快，依赖图一目了然 |
| 悬浮窗用纯 Canvas 而非 Compose | 无 Activity 宿主，避免 Compose 生命周期桥与组合树开销；更省电、零新依赖 |
| 未采用 Media3 预加载（`DefaultPreloadManager`） | 其要求「播放器与预加载同处同一非主线程 Looper」，与当前「`MediaSessionService` 主线程持有播放器」架构冲突；收益（起播快数百毫秒）已被「懒解析队列 + 下一首预解析」覆盖，改造风险不成比例 |
| 同步使用白名单而非黑名单 | 新增敏感设置（Cookie / Key / 密码）默认不会被同步，安全性优先 |
| 类型安全路由（`@Serializable`） | 编译期校验参数，替代字符串拼接路由 |
