# DPmusic 架构文档

> 工业级全设备自适应音乐播放器 · Kotlin + Jetpack Compose + Media3
> 包名 `com.dpmusic.app` · compileSdk 36 / minSdk 23 / targetSdk 34 · JVM 17

---

## 1. 技术栈总览

| 维度 | 选型 | 版本 |
| --- | --- | --- |
| 语言 / 构建 | Kotlin + AGP + Gradle 9 | Kotlin 2.1.0 / AGP 8.13.0 |
| UI | Jetpack Compose（BOM）+ Material 3 + Extended Icons | 2025.10.01 |
| 播放引擎 | Jetpack Media3（ExoPlayer + MediaSessionService，含 HLS） | 1.11.1 |
| 音频 DSP | Media3 `AudioProcessor` 链（自研 RBJ 参数均衡 + FFT 频谱抽取） | 随 Media3 |
| 导航 | Navigation Compose（类型安全序列化路由） | 2.9.5 |
| 网络 | OkHttp（统一连接池 / 重试） | 4.12.0 |
| 图片 | Coil 3（复用 OkHttp） | 3.3.0 |
| 持久化 | DataStore Preferences | 1.1.7 |
| 取色 | Palette（封面主色 → 流光背景） | 1.0.0 |
| 自适应 | material3-window-size-class 断点 | 随 BOM |

无 Hilt / 无注解处理器：依赖注入采用 `AppContainer` 手工容器 + `AppViewModelFactory` 集中装配，零 kapt 开销。

---

## 2. 工程结构

```
app/src/main/kotlin/com/dpmusic/app/
├── DPmusicApp.kt                 # Application：容器初始化 + Coil 装配 + 存储自动清理
├── AppContainer.kt               # 进程级单例容器（懒加载）
├── AppViewModelFactory.kt        # 全局 ViewModel 工厂
├── MainActivity.kt               # 唯一 Activity：Edge-to-Edge / 主题 / 权限 / 连接服务
├── core/
│   ├── ClipboardLinkInbox.kt     # 剪贴板链接收件箱（切回前台检测）
│   ├── ImportInbox.kt            # 外部 JSON 导入收件箱
│   ├── model/                    # 统一数据模型（跨平台归一化）
│   │   ├── MusicPlatform.kt      #   平台枚举 wy/qq/kg + fromId
│   │   ├── Song.kt               #   歌曲（stableKey = "平台:ID"）
│   │   ├── Playlist.kt / Rank.kt #   歌单 / 榜单
│   │   ├── Lyric.kt              #   歌词行（含翻译）
│   │   ├── PlayQuality.kt        #   音质档位 + chainFor() 降档链
│   │   ├── SourcePriority.kt     #   解析优先级（KEY_FIRST 默认）
│   │   ├── RecentPlay.kt         #   最近播放（带进度）
│   │   └── NcmChat.kt / NcmContent.kt / NcmTogether.kt / Comments.kt …
│   ├── net/
│   │   ├── HttpClient.kt         #   统一 OkHttp（3 次指数退避重试）
│   │   ├── JsonUtils.kt          #   JSONP 剥离 + JsonElement 访问助手
│   │   ├── PlatformApi.kt        #   平台 API 抽象接口
│   │   ├── WyApi / QqApi / KgApi / NcmApi / NcmEapi   # 平台实现（全实测校准）
│   │   ├── LxResolver.kt         #   远端代理 Key 解析：URL 缓存 + 熔断 + 降档链
│   │   ├── SongLinkParser.kt     #   歌曲链接识别
│   │   └── PlaylistLinkParser.kt #   歌单链接识别
│   ├── script/                   # 双脚本运行时（互不干扰）
│   │   ├── UserApiEngine.kt      #   LX Music 脚本引擎（QuickJS）
│   │   ├── ScriptMusicResolver.kt#   LX 脚本解析接入层
│   │   ├── MusicFreeEngine.kt    #   MusicFree 插件引擎（QuickJS + cheerio 兼容层）
│   │   ├── MusicFreeResolver.kt  #   插件解析接入层（含请求头登记）
│   │   ├── MusicFreePluginRepository.kt
│   │   └── AES.kt / RSA.kt       #   脚本侧加解密辅助
│   ├── lyric/                    # LrcParser / QrcParser / YrcParser / KrcParser
│   │   ├── LyricsHub.kt          #   歌词中心（请求取消 / 缓存）
│   │   ├── SimulatedVerbatim.kt  #   模拟逐字
│   │   └── DesktopLyric*.kt      #   桌面歌词（样式 / 视图 / 服务）
│   ├── audio/                    # 听歌识曲 + 音频 DSP 链
│   │   ├── AudioFingerprintEngine.kt / KgRecognizer.kt / AudioRecognizer.kt
│   │   ├── AudioSampler.kt / AudioPlaybackCapturer.kt / CaptureForegroundService.kt
│   │   ├── AudioEffectsManager.kt #   音效门面（平台音效 → Media3 DSP 后端）
│   │   ├── DspEngine.kt           #   音频链宿主 + DspRenderersFactory
│   │   ├── BitPerfectController.kt#   USB 独占输出控制器（Android 14+）
│   │   └── dsp/                   #   Biquad / ParametricEqProcessor / SpectrumAudioProcessor
│   ├── util/                     # AppLogger / StorageManager / CircuitBreaker / CoverPalette / Formatters
│   ├── data/                     # DataStore 仓库群（15 个：设置 / 收藏 / 歌单 / 历史 /
│   │                             #   不喜欢 / 搜索历史 / 统计 / 会话 / 账号 / 红心同步 /
│   │                             #   均衡器 / 私信 …）
│   ├── download/                 # 下载队列 / 下载器 / 路径 / 元数据写入
│   ├── sync/                     # WebDAV 客户端 / 同步管理器 / 白名单
│   ├── together/                 # 一起听会话 / 邀请监听 / 邀请解析
│   ├── widget/                   # 桌面小组件 Provider / 快照更新器
│   ├── repo/MusicRepository.kt   # 仓库门面：搜索/歌单/榜单/歌词/解析/跨平台兜底
│   └── playback/
│       ├── MusicService.kt       # MediaSessionService（后台保活 / 通知 / 锁屏）
│       ├── PlayerConnection.kt   # UI ⇄ 服务唯一桥接（懒解析队列 / 自愈 / 防抖）
│       ├── PlaybackState.kt      # NowPlaying / QueueSnapshot / PlayerCommand
│       ├── PlaybackHeaderStore.kt# 音源请求头（插件返回的 header 注入播放器）
│       ├── NcmFmController.kt    # 私人 FM
│       └── SleepTimerController.kt
└── ui/
    ├── theme/                    # Color / Type / Shape / Theme / Glass / ThemePalettes / LayoutInsets
    ├── navigation/Routes.kt      # @Serializable 类型安全路由
    ├── shell/
    │   ├── AppNavHost.kt         # 导航图 + 五大主页面 NavItem
    │   └── DPmusicShell.kt       # 自适应主外壳（导航 + Mini 条 + 全屏播放器 + 队列 + 流光底）
    ├── player/PlayerViewModel.kt # 歌词状态机 + 封面取色 + 收藏态
    ├── motion/DPMotion.kt        # 动效令牌：时长 / 缓动 / 弹簧 / 交错延迟
    ├── util/Haptics.kt           # 触觉反馈：tick / click / confirm / reject / gestureEnd
    ├── components/               # 42 个通用组件（含 Skeleton 骨架屏、Glass 玻璃面板、
    │                             #   类玻璃材质内核、分享 / 私信 / 评论 / 识曲 …）
    └── screens/                  # 17 个子包（home / search / rank / playlist / mine / settings /
                                  #   sources / chat / together / download / ncm / qq / favorites /
                                  #   recent / artist / album / logs）
```

---

## 3. 音源层（全部经终端实测校准，非臆造）

### 3.1 平台能力矩阵

| 能力 | 网易云（wy） | QQ 音乐（tx） | 酷狗（kg） |
| --- | --- | --- | --- |
| 搜索 | `/api/cloudsearch/pc` ✅ | `client_search_cp?new_json=1` ✅ | `mobilecdn /api/v3/search/song` ✅ |
| 关键词歌单搜索 | `/api/cloudsearch/pc` ✅ | ⚠️ 匿名态已失效 → 降级分类浏览 | `special/song` ✅ |
| 歌单详情 | `/api/v6/playlist/detail` + `/api/v3/song/detail`（500/批）✅ | `musicu` → `srfDissInfo.aiDissInfo` ✅ | `special/song` 分页 100/页 ✅ |
| 榜单列表 | 官方榜 ✅ | `musicToplist.ToplistInfoServer/GetAll` ✅ | `rank/list` ✅ |
| 榜单歌曲 | `/api/v6/playlist/detail` ✅ | `fcg_v8_toplist_cp.fcg` ✅ | `rank/song`（rankid=8888）✅ |
| 歌词 | `lrc` + `tlyric` 翻译 ✅ | 歌词接口 ✅ | 两步链：`search → download`（base64 LRC）✅ |
| 封面 | `al.picUrl` ✅ | `y.qq.com/.../T002R500x500M000{albumMid}.jpg` ✅ | ⚠️ `play/getdata` 失效（err_code 20010）→ 改用 `trans_param.union_cover` 的 `{size}` 占位替换 ✅ |

### 3.2 关键字段映射（归一化入口）

- **网易云**：`id / name / ar[].name / al.picUrl / dt(毫秒)`
- **QQ**：`mid / title / singer[].name / album.mid / interval(秒)`
- **酷狗**：`hash / songname / singername / trans_param.union_cover / duration(秒)`；榜单/歌单无 `singername` 时从 `filename`（"歌手 - 歌名"）兜底解析

### 3.3 音源解析层（播放入口）

播放 URL 由**三条并行链路**提供，全部收敛到 `MusicRepository.resolveForPlayback`：

| 链路 | 实现 | 说明 |
|---|---|---|
| 远端代理 Key | `LxResolver` | `GET https://source.shiqianjiang.cn/api/music/url?source=&songId=&quality=`，Header `X-API-Key`；**Key 不内置**，从设置读取 |
| LX Music 脚本 | `UserApiEngine` + `ScriptMusicResolver` | QuickJS 沙箱执行用户导入的 LX 脚本 |
| MusicFree 插件 | `MusicFreeEngine` + `MusicFreeResolver` | QuickJS + cheerio 兼容层；插件返回的请求头登记进 `PlaybackHeaderStore` |

**优先级链**（`SourcePriority`，默认 `KEY_FIRST`）：

```
Key 优先  → 代理 → 失败回退脚本      ← 默认
脚本优先  → 脚本 → 失败回退代理
仅 Key    → 代理（忽略脚本）
仅脚本    → 脚本（平台不支持则明确报错，不回退）
```

两者都失败 → MusicFree 插件兜底 → 仍失败则跨平台换源（见下）。

**远端代理细节**：

- 平台映射：`wy→wy / qq→tx / kg→kg`；`songId` 优先级 `hash → songmid → id`
- **URL 缓存**：LRU + 8 分钟 TTL（CDN 链接时效保护）
- **熔断器**：连续 4 次失败 → 30s 冷却（半开探测）
- **降档链**：解析失败自动逐级降档（如 `flac → 320k → 128k`）；403/429 为终止语义，直接抛出
- **跨平台兜底**：`MusicRepository` 在同平台解析彻底失败后，用「双字符组 Jaccard 相似度」（标题 > 0.72 且歌手 > 0.5）到其它平台匹配同名曲目再解析

### 3.4 MusicFree 插件运行时

插件协议（`module.exports = { platform, search, getMediaSource, getLyric, ... }`）跑在独立 QuickJS 上下文，
适配层见 `assets/script/musicfree-preload.js`：

- **宿主桥**：网络请求经 `__mf_native_call__('request', ...)` 走 OkHttp；加解密由宿主提供（md5 / sha1 / sha256 / aes）
- **模块兼容层**：内置 `axios / qs / he / crypto-js / dayjs / cheerio`（cheerio 为纯 JS 实现，1450 行，覆盖选择器 / 伪类 / DOM 读写 / 容错解析）
- **别名**：`node-fetch / request / request-promise / superagent` → `axios`
- **离线回归**：`node tools/tests/cheerio-compat.test.js`（54 项断言）、`node tools/tests/plugin-mount.test.js <plugin.js>`

详见 [`docs/MUSICFREE-PLUGIN.md`](MUSICFREE-PLUGIN.md)。

### 3.5 歌曲评论（含楼中楼回复）

入口：`MusicRepository.comments(song, page, limit)` → `PlatformApi.comments(songId, page, limit)` → `ui/components/CommentsSheet.kt`。

| 平台 | 评论列表 | 楼中楼回复 |
| --- | --- | --- |
| 网易云 | ✅ `/api/v1/resource/comments/R_SO_4_{id}` | ⚠️ `beReplied` 可用（是被回复的那一条，最多 1 项，**无 time 字段**） |
| QQ 音乐 | ✅ `fcg_global_comment_h5.fcg`（**`topid` 必须传数字 songId**） | ❌ 接口不提供（评论项无 `commentcount`/`replylist`；`cmd=9` 是热评流不是楼中楼） |
| 酷狗 | ❌ 需签名，未实现 | ❌ |

**QQ 的坑（已修）**：`topid` 传 MID 会**静默返回空**（`code=0` 但 `commenttotal=0`，`allow_comment=1` 看不出异常）。
修复：`resolveNumericSongId()` 把 MID 转数字 id（复用已有 `songIdsByMids(cookie, mids)`，传空 Cookie 匿名可调）。
实测 `0039MnYb0qxYhV → 97773` 后 `total=230665` 正常返回。

**UI**：回复入口显示「查看 N 条回复」，**默认折叠**；展开态用 `remember(c.id)` 按评论 id 记忆，滚动不串位；
折叠时 `AnimatedVisibility` 不组合子树（零开销）。详见 [`docs/COMMENTS.md`](COMMENTS.md)。

### 3.6 酷狗概念版音源（第四条解析通道）

**背景**：酷狗公开 Web 接口只能拿到普通音质；概念版 App（`com.kugou.android.lite`）的
`/v5/url` 接口在**匿名无登录**状态下即可取到**免费歌完整全曲**，普通接口做不到。故把该链路
作为 Key / 脚本 / 插件之外的第四条通道接入。

| 文件 | 职责 |
| --- | --- |
| `core/net/KgSign.kt` | 协议常量 + 签名算法（`md5(salt + 排序k=v + body + salt)`、tracker `key`、登录 `paramsKey`） |
| `core/net/KgLiteApi.kt` | `/v5/url` 取地址（降档 + 试听兜底）、`/user/detail` 登录校验 |
| `core/net/KgLiteLoginApi.kt` | 手机号验证码登录（下发短信 + `login_by_verifycode` + `secu_params` 解密取 token） |
| `core/net/KgLiteCrypto.kt` | 登录封套加密原语（AES-256-CBC 种子派生 + RSA/NoPadding 裸加密） |
| `core/net/KgLiteResolver.kt` | 播放链路接入层（LRU + 8 分钟 TTL） |
| `core/net/KgLiteClaimApi.kt` | 每日领 VIP（听歌 / 广告 / 任务查询，含幂等错误码处理） |
| `core/data/KgLiteClaimService.kt` | 自动签到调度（启动延迟 8s、`yyyy-MM-dd` 同日去重） |
| `core/data/KgLiteRepository.kt` | token / mid / 资料持久化（**仅本机**） |

**⚠️ 取地址参数陷阱（`album_id` / `album_audio_id`，本轮修复的播放失败根因）**：

搜索接口 `mobilecdn/api/v3/search/song` 同时返回 `album_id`、`audio_id`、`album_audio_id` 三个**不同**字段
（例：晴天 → 966846 / 20505418 / 32100650）。`/v5/url` 只认 **`album_audio_id`**。
若把 `audio_id` 当 `album_audio_id` 传（早期实现即如此），参数与 hash **错配** → 请求被污染：

| 歌 | 传 `(album_id, audio_id→aaid)` 错误组合 | 传 `(album_id, album_audio_id)` 修复后 |
| --- | --- | --- |
| 富士山下（正式版） | `status=0 / error_code=35104`，**试听轮同样失败** | `status=2`（版权受限）→ 试听轮 `status=1` ✅ |
| 富士山下(2024版) | `status=3`，**试听轮同样失败** | **`status=1` + 全曲 URL** ✅ |
| 稻香 | `status=0 / 35104`，试听轮失败 | 试听轮 `status=1` ✅ |
| 晴天（蓝心羽，免费） | `status=1`（碰巧可用） | `status=1` ✅ |

实测还确认：`album_id` 传错**不影响**结果；但 `album_audio_id` 传**非 0 且不属于该曲**的值
（或把 `album_id` 与不匹配的 `album_audio_id` 混搭）会误判受限。故实现为
**只从 `extra["album_audio_id"]` 取值，缺失就传 0**（传 0 等价于省略，实测免费歌全曲可得）。

**关键协议点（均实测校准）**：

- **两个盐，不可混用**：老接口（`hot_tab` 等）用 `OIlwieks28dk2k092lksi2UIkp`；
  `/v5/url` 用配置盐 `LnT6xpN3khm36zse0QzvmgTZ3waWdRSA`。
  （已用前者复现 `KgApi.kt` 里写死的 `ee44edb9…`，证明算法骨架正确。）
- **两个签名缺一不可**：`key = md5(hash + SECRET + appid + mid + userid)` + 标准 `signature`，
  且 `signature` 必须在放入 `key` **之后**计算（顺序敏感）。缺任一报 `20006 err signature`。
- **`x-router: trackercdn.kugou.com`** 是路由关键（同名 gateway 域名靠它分发）。
- **`dfid` 用 `"-"` 即可**：实测随机 hex 串会被判 `status=0`（6/6 失败），而 `"-"` 稳定可用 →
  **无需设备注册**（省掉 RSA/AES/二进制解密与注册风控整条链路）。
- **`IsFreePart` 语义**：`0` = 求全曲；`1` = 允许 60s 试听片段（任何歌都返回）。
- **`token` 必须随请求带上（本轮修复的「付费歌只有 60s」根因）**：登录态不是只给 `key`/`userid` 用的，
  `/v5/url` 本身也要带 `token`。实测（2026-10-01，已登录账号）：

  | 档位 | 无 `token` | 有 `token` |
  | --- | --- | --- |
  | 128 | `status=2`（受限） | `status=1` 全曲 3753KB |
  | 320 | `status=2` | `status=1` 全曲 9382KB |
  | **flac** | `status=2` | **`status=1` 全曲 26MB**（下载验证 `audio/flac` + magic `fLaC`） |
  | high | `status=2` | `status=1` 全曲 |

  结论订正：早期文档写「付费歌全曲需 VIP」不够准确 —— **只要带上已登录账号的 `token` 即可取全曲**，
  无需账号本身是 VIP（概念版免费送 VIP 的活动正是为此）。这也解释了「官方概念版 App 能放全曲、
  DPmusic 只能试听」的现象：不是账号权限差异，而是我们的请求漏了 `token` 参数。
- **音质**：免费歌 128 可全曲；付费歌带 `token` 后 128/320/flac/high 均可全曲；无 `token` 时全部 `status=2` → 自动降档/试听兜底。

**登录（手机号 + 验证码）**：概念版验证码登录**不需要人机验证**（`20028` 仅密码登录触发），
故提供手机号验证码登录入口。关键实测校准：

- **登录 query 的 `mid` 必须非空** —— 为空字符串时服务端返回 `20006 err signature`
  （极易误判为签名错，实为封套缺 mid）。mid 首次启动随机生成并持久化。
- 返回码语义：`20020` 验证码过期 / `20021` 验证码错误 / `20006` mid 异常 —— 均**协议已通**。
- `params = AES({mobile, code})`，种子随机 16 位；`pk = RSA({clienttime_ms, key: 种子})`；
  `key = md5(appid + appkey + clientver + clienttime_ms)`（**毫秒**）。
- 登录成功后用同一「种子」解密 `data.secu_params` 取 token，再经 `/user/detail` 复核。
- ⚠️ **下发短信接口禁用自动重试**（`Http.postJson(retryOnFailure = false)`），避免超时重试重复发送。

**接入位置**：`MusicRepository.resolveWithFallback()` 的四级链 ——
概念版「强制优先」→ Key/脚本 → 概念版兜底 → MusicFree 插件。
开关见设置页「酷狗概念版音源」卡片（`kglite_enabled` / `kglite_force`）。

**默认值（本轮调整为开箱可播）**：

| 设置项 | 默认 | 原因 |
| --- | --- | --- |
| `defaultPlatform`（默认平台） | **酷狗 KG**（原为网易云） | 概念版仅对 KG 曲库生效（`canResolve` 要求 `platform == KG`）；默认选网易云会导致「命中了概念版通道却全程不适用」 |
| `kglite_enabled` | **true** | 匿名即可取免费歌全曲，是本应用「开箱可播」的基础通道（Key 需自备、脚本/插件需导入，均非默认可用） |
| `kglite_force` | **true** | 优先概念版可**避免先撞上空 Key** 产生误导性报错「未配置音源 Key」，失败仍自动回退 |

> ⚠️ 默认值只在 `prefs` **无该键**时生效。设备上若曾手动改过平台/开关，旧值不会被新默认覆盖
> （符合预期，不擅自改写用户显式选择）。

**「一听就提示输入 Key」的根因（已定位）**：`lx_api_key` 为空 + `source_priority = key_first` 时，
解析链第一步走 Key 通道，`LxResolver` 抛 `未配置音源 Key：请在「设置 → 音频偏好」中填写`。
两个叠加因素：① 概念版默认关闭 → 兜底也不生效；② 默认平台网易云 → 概念版本就对该曲不适用。
上述默认值调整 + `kglite_force` 后，**酷狗曲库歌曲**直接由概念版接管，不再触达空 Key 通道。

**每日签到领 VIP（`kglite_auto_claim`，默认关闭）**：登录态下调用
`/youth/v1/free_package/get_vip_task`（任务查询）+ `/youth/v2/report/listen_song`（听歌奖励）
+ `/youth/v1/ad/play_report`（广告奖励）。幂等错误码视为成功态：`130012` 听歌今日已领、
`30002` 广告次数用尽。调度：启动后延迟 8s 执行一次，以 `yyyy-MM-dd` 记录 `kglite_last_claim_date`
做同日去重（**失败也记录日期**，避免反复重试触发风控）。

**真实设备端到端验证（2026-10-01，用设备登录态复刻协议）**：

| 接口 | 实测响应 | 判定 |
| --- | --- | --- |
| `/v5/url`（免费歌，有 `x-router`） | `status:1` + 真实 `http://fs.youthandroid.kugou.com/...mp3`（含 `backupUrl`） | ✅ 取地址链路通 |
| `/youth/v1/free_package/get_vip_task` | `{"status":1,"error_code":0,"data":{"task_status":0}}` | ✅ 登录态有效 |
| `/youth/v2/report/listen_song` | `{"status":0,"error_code":130012}`（今日已领） | ✅ 幂等码已被代码覆盖 |
| `/youth/v1/ad/play_report` | `{"status":1,"data":{"remain_vip_hour":18,"done":2,"remain":6,"award_vip_hour":3}}` | ✅ **真实领取 +3h VIP 成功** |

> 注：`/user/detail` 在纯 curl 复现时返回 502 —— 该接口依赖更强的设备上下文，
> 但**登录态有效性已由 `get_vip_task` 的 `status:1` 反证**，不影响 App 内链路。

**合规边界**：仅代发一次短信并校验用户**主动输入**的验证码；不代持密码。
自动签到属**账号自动化操作**，已在 UI 加明确风险警告（可能违反酷狗用户协议、触发风控/封号），
**默认关闭**、需用户主动开启、风险自负。
登录态（token / userid / mid）仅明文存本机、仅用于取播放地址与签到。

**登录分支验证进展（本轮更新）**：设备上已完成一次真实手机号验证码登录并落盘
（`kglite_token` 64 位、`kglite_mid` 31 位、`kglite_profile_json` 含 `userId`），
且该 token 经服务端接口验证有效（见上表 `get_vip_task status:1`）→ **登录成功分支已跑通**。
仍存在的边界：`secu_params` 的**多形态解码**（JSON / 明文串）为兼容性写法，
不同版本服务端返回形态尚未穷举，异常时会回退明文 `token`/`t1` 字段。

---

## 4. 播放层（引擎与 UI 生命周期完全解耦）

### 4.1 服务侧：`MusicService : MediaSessionService`

- 全权接管后台保活、媒体通知、锁屏控制、耳机按键
- ExoPlayer 配置：`handleAudioFocus = true`、`setHandleAudioBecomingNoisy(true)`、`setWakeMode(C.WAKE_MODE_NETWORK)`
- 声明 `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限

### 4.2 桥接侧：`PlayerConnection`（UI 与服务之间唯一通道）

四大核心机制：

1. **懒解析队列**：入队时仅「当前曲 + 下一曲」持有真实 URL，其余为 `Uri.EMPTY` 占位媒体项；播放推进到占位项时实时解析并 `replaceMediaItem`（避免批量预解析拖慢起播）
2. **错误自愈**：`onPlayerError → 强制重解析（含跨平台兜底）→ 重试`；同一首连续失败 2 次 → 提示并自动跳过
3. **切歌防抖**：350ms 内重复的上一首/下一首指令直接忽略
4. **历史回写**：切歌 / 暂停时记录播放进度，供「最近播放」进度胶囊展示

UI 反向控制全部经由 `PlayerConnection`：`playQueue / togglePlayPause / next / previous / seekTo / setQuality / playSongNext / toggleFavorite / skipToQueueIndex / setRepeatMode / toggleShuffle`。

### 4.3 命令流（单向事件）

`PlayerConnection.commands: MutableSharedFlow<PlayerCommand>` 向上层抛出：

- `OpenPlayerSheet` → 外壳展开全屏播放器
- `ShowQueueSheet` → 外壳弹出播放队列抽屉
- `ShowMessage(text)` → 外壳 Snackbar 提示

### 4.4 音频处理链（DSP 后端，Media3 AudioProcessor）

平台音效（`android.media.audiofx.Equalizer / BassBoost / Virtualizer`）已**彻底移除**——`Virtualizer` 在多数机型已失效，且平台音效会抢占 AudioTrack 会话、与 Bit-Perfect 独占互斥。改用 Media3 `AudioProcessor` 链，挂在 ExoPlayer 的 AudioSink 内，处理「解码后 → 写入 AudioTrack 前」的 PCM：

```
解码 PCM ─▶ ParametricEqProcessor ─▶ SpectrumAudioProcessor ─▶ Sonic ─▶ SilenceSkipping ─▶ AudioTrack
            (10 段参数均衡 + 立体声宽度)  (FFT 频谱抽取，只读透传)
```

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `Biquad` | `core/audio/dsp/Biquad.kt` | RBJ 公式双二阶滤波器（Peaking / LowShelf / HighShelf），Direct Form I，FloatArray 预分配零分配 |
| `ParametricEqProcessor` | `core/audio/dsp/ParametricEqProcessor.kt` | 10 段参数均衡链：preamp → 低架 → 8 段峰形 → 高架 → 软限幅；支持 16-bit / float PCM |
| `SpectrumAudioProcessor` | `core/audio/dsp/SpectrumAudioProcessor.kt` | 1024 点 radix-2 FFT + Blackman-Harris 窗；输出 48 根频谱柱 + 1024 点波形；**只读不改纯透传**（不破坏 bit-perfect） |
| `DspEngine` | `core/audio/DspEngine.kt` | 音频链宿主；`DspRenderersFactory` 重写 `DefaultRenderersFactory.buildAudioSink` 注入 `DefaultAudioSink.DefaultAudioProcessorChain(eq, spectrum)`（Sonic / SilenceSkipping 由 Media3 自动追加） |
| `AudioEffectsManager` | `core/audio/AudioEffectsManager.kt` | 对外 API 门面（`bandLevelsMb` / `bassStrength` / `surroundStrength` / `presets`），把持久化状态翻译成 DSP 参数 |

**关键设计（均为踩坑后修正，勿回退）**：

- **`isActive()` 恒为 true**：Media3 只在 `configure` 阶段按 `isActive()` 过滤处理器，编入后再改返回值**不会**移出链。开关因此由处理器内部 `@Volatile eqEnabled` 决定（关闭时纯透传），不能靠 `isActive()`。
- **`onFlush()` 必须用新签名**：`onFlush(StreamMetadata)` 已取代无参 `onFlush()`（后者 deprecated）。
- **多声道安全降级**：输入 `channelCount > 2` 时 `onConfigure` 返回 `AudioFormat.NOT_SET`，避免 5.1 被当 2 声道处理导致串音。
- **预增益防削顶**：`preampFor()` 按频段峰值自动衰减（上限 -12dB）。
- **立体声宽度**：M/S 扩展替代已死的 `Virtualizer`，系数 1.0~2.0（`1 + surroundStrength/1000`）。
- **频段表按频率单调递增**（80Hz 低架 / 1k~16k 峰形 / 16k 高架），否则 UI 响应曲线会错乱。

**示波器数据侧可调项**（集中在 `SpectrumAudioProcessor`）：

| 常量 | 值 | 约束 |
| --- | --- | --- |
| `PUBLISH_HZ` | 100 | **必须 > 屏幕刷新率 60Hz**，否则 UI 隔帧跳变（一卡一卡） |
| `DECAY` | 0.912f | 落峰 ≈ 500ms，**与发布频率解耦**，改 `PUBLISH_HZ` 需同步改 |
| `WAVE_SIZE` | 1024 | 逐帧写入不抽点，等效采样率 = 源采样率（抽点会让高频混叠） |
| `BAR_COUNT` | 48 | 对数分桶 20Hz~20kHz |
| `FLOOR_DB` | -84f | 频谱动态下限 |

波形起点用**过零上升沿触发**（全缓冲从新到旧扫描最后一次上升沿），保证每帧起点相位一致、不左右乱跑。

### 4.5 USB Bit-Perfect 独占输出（Android 14+）

`core/audio/BitPerfectController.kt`：通过 `AudioManager.setPreferredMixerAttributes` 为外接 USB DAC 申请 `MIXER_BEHAVIOR_BIT_PERFECT` 混音属性，绕过系统混音器直通输出。

- **支持判定**：遍历 `getSupportedMixerAttributes(device)` 找 `mixerBehavior == MIXER_BEHAVIOR_BIT_PERFECT`（**不存在** `isBitPerfectPlaybackSupported` 这个方法）
- **API 签名**（已逐个核实，勿按臆想写）：
  - `setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes): Boolean`（**3 参**）
  - `clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo): Boolean`（**2 参**）
  - `AudioMixerAttributes.Builder(AudioFormat)`（**构造器传格式**，无 `setFormat`）+ `.setMixerBehavior(int)`
- **设备优先级**：`USB_DEVICE → USB_HEADSET → USB_ACCESSORY → WIRED_HEADPHONES → WIRED_HEADSET → BLUETOOTH_A2DP`
- **三个必做配套**：
  1. **强制旁路全部 DSP** —— `AudioEffectsManager.applyEnabled()` 中 `enabled && !bitPerfectBypass`，并在设置卡片与均衡器面板**两处**明示「独占期间 EQ 不可用」
  2. **参数防抖缓存** —— `appliedKey = "${device.id}|${sampleRate}|${channelCount}"`，命中则跳过重复调用，避免 DAC 反复断流爆音
  3. **生命周期** —— `AudioDeviceCallback` 监听拔插 + `MusicService.onDestroy` 调 `release()` 归还控制权
- 真实解码格式由 `AnalyticsListener.onAudioInputFormatChanged` 上报，用于匹配 `AudioMixerAttributes` 的采样率
- 无外接设备时状态为「未检测到外接音频设备（插上 USB DAC 后自动启用）」，不影响正常播放

### 4.6 新增设置项（`SettingsRepository`）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `bit_perfect_enabled` | Boolean | `false` | USB Bit-Perfect 独占输出开关 |
| `visualizer_enabled` | Boolean | `false` | 播放页示波器开关 |
| `visualizer_mode` | String | `"both"` | 示波器展示内容：`bars` / `wave` / `both` |

三者均进 `SyncScopes.SETTINGS_KEYS` 白名单（纯偏好，无敏感信息），由 `AppContainer.startAudioDspSync()` 统一分发到三个子系统。

---

## 5. 数据与状态（MVI / MVVM）

```
UI (Compose) ──intent──▶ ViewModel ──suspend──▶ Repository ──▶ PlatformApi / LxResolver
     ▲                                                              │
     └────── StateFlow<UiState> ◀─── StateFlow ◀───────────────────┘

播放控制旁路：UI ──▶ PlayerConnection ──▶ MediaController ──▶ MusicService(ExoPlayer)
                    ▲                                              │
                    └────── StateFlow<NowPlaying / QueueSnapshot> ◀─┘
```

- 页面状态：每页独立 `ViewModel`（`StateFlow` 单向流出），错误统一 `error: StateFlow<String?>`
- 播放状态：`PlayerConnection.nowPlaying / queue` 为全局单一数据源，页面与外壳共同订阅
- 持久化（DataStore Preferences，`core/data` 共 15 个仓库）：
  - `SettingsRepository`：默认平台 / 音质 / 动态取色 / 深色三态 / 音源 Key / 解析优先级 / 玻璃开关 / **USB 独占开关 / 示波器开关 / 示波器模式** …
  - `FavoritesRepository`：收藏（`stableKey` 去重）
  - `HistoryRepository`：最近播放 + 进度（按 `stableKey` 回写）
  - `UserPlaylistRepository`：本地歌单 + 链接歌单（含定时更新）
  - `SearchHistoryRepository` / `DislikeRepository` / `ListeningStatsStore` / `EqualizerRepository`
  - `PlaybackSessionStore`：播放会话（队列 + 索引 + 进度，冷启动恢复）
  - `NcmRepository` / `QqRepository`：账号 Cookie 与登录态；`NcmSyncService` / `QqSyncService`：红心双向同步
  - `NcmChatRepository`：私信会话与消息

---

## 6. 全设备自适应体系

### 6.1 断点策略（WindowSizeClass）

| 断点 | 设备形态 | 导航 | 页面布局 | 播放形态 |
| --- | --- | --- | --- | --- |
| Compact | 竖屏手机 | 底部 `NavigationBar` | 单列卡片流 | 底部 Mini 条 |
| Medium / Expanded | 平板 / 折叠屏 / 横屏 | 侧边 `NavigationRail` | Master-Detail 双栏 | 底部 Mini 条 + 右栏 `NowPlayingPanel` |

- `MainActivity` 全量声明 `configChanges`（旋转不重建），`calculateWindowSizeClass` 在组合内实时响应断点切换
- **导航形态平滑变形演进**：NavigationRail 以 `expandHorizontally / shrinkHorizontally` 横向展开收起，底部 NavigationBar 以 `expandVertically / shrinkVertically` 纵向收展，两态互切时内容区宽度连续阻尼过渡；底栏整体由 `animateContentSize` 驱动（Mini 条出现 / 消失同样平滑）
- `LookaheadScope` 提供前瞻坐标域；`SharedTransitionLayout` 承载跨组件共享元素
- **滚动偏移锚定保持**：所有列表 / 网格滚动状态在屏幕层外提（`rememberLazyListState / rememberLazyGridState / rememberLazyStaggeredGridState`），旋转与单双栏形态切换时滚动位置不丢失
- 搜索/收藏/最近页：右栏为「即时播放浮层」；榜单/歌单页：右栏为联动详情面板（榜单网格在宽屏下为 `Adaptive` 3~4 列动态网格）
- 详情页（榜单详情 / 歌单详情）在宽屏下自动切换「左固定封面 / 右列表」分栏

### 6.2 播放器双形态

- **竖屏**：大黑胶封面（旋转 + 呼吸）→ 歌曲信息 → 96dp 迷你歌词窗口（点击展开全屏歌词）→ 示波器（可选）→ 控制条
- **横屏**：黄金分割——左 0.618 黑胶 + 流光背景，右 1.0 全尺寸歌词 + 控制台（示波器置左栏控制行下方）
- 判定：`maxWidth > maxHeight * 1.15f`（运行时约束，而非设备类别）
- **示波器融入式呈现**（`SpectrumVisualizer`）：不使用 `Surface` / 圆角卡片，直接以 `Canvas` 绘于页面流光底——频谱柱为主色→tertiary 竖向渐变 + 全圆角，波形为「极淡中线 + 下方 0.16→0.02 渐变填充 + 外发光 + 主色→tertiary→主色横向渐变实线」，与玻璃风格的沉浸感一致
- **流畅性双保险**：数据侧 100Hz 发布（> 60Hz 屏幕，每 vsync 必有新帧）+ UI 侧频谱柱逐帧缓动（`SMOOTH_BARS = 0.30f`）；波形**不做逐点插值**（相位随触发点整体平移，插值会产生重影），连续性靠数据率 + 过零触发保证

### 6.3 Mini ⇄ 全屏流体形变 + 共享元素

- 单一 `Animatable<Float> progress(0..1)` 驱动：布局级位移 `(1-p) × 高度`（布局坐标参与共享元素计算）、顶部圆角 `28dp × (1-p)` 收敛
- Mini 条与全屏页是**同一实体**的两个状态：Mini 条上拉手势直接驱动进度（跟手），松手按速度/位置吸附（弹簧 `Spring.DampingRatioLowBouncy + Spring.StiffnessLow`）
- Mini 条随进度做 `scale 1→0.96 / alpha 1→0` 的连续变换，消除状态切换的断裂感
- **SharedTransitionLayout 共享元素**：封面在 Mini 条与全屏播放器之间无缝飞入飞出（`sharedElementWithCallerManagedVisibility` + 同一 `SharedContentState`，可见性由 `sheetVisible` 驱动）

### 6.4 预测性返回手势

- `PredictiveBackHandler`（activity-compose）：播放器展开时，系统返回手势「跟手」折叠播放器（`progress = 1 - backEvent.progress`）；手势取消弹回，手势提交后完整收起
- 播放器收起时返回手势自动交还导航栈（Manifest 已开启 `enableOnBackInvokedCallback`）

---

## 7. 主题与动效体系

- **动态取色**：Android 12+ 动态色彩（设置可关）；不可用时回退至内置手调色板
- **深色三态**：跟随系统 / 浅色 / 深色
- **M3 形态语言**：大圆角卡片（ExtraLarge）、全圆角胶囊（FilterChips / Pill Button）、分段按钮（`SingleChoiceSegmentedButtonRow`，我的页两 Tab 切换）
- **组件库（43 个）**：基础类 `pressScale` / `staggeredEntrance` / `CoverArt`（模糊衬底）/ `PlatformBadge` / `PlatformChips` / `StateViews` / `Skeleton`（骨架屏）/ `DpTopAppBar` / `ListDisplay` / `ListFilterBar`；列表类 `SongRow`（含 `SwipeableSongRow`）/ `PlaylistRow` / `SongSelection`；播放类 `MiniPlayerBar` / `NowPlayingPanel` / `PlayerSheet` / `QueueSheet` / `VinylDisc` / `LyricsView` / `Visualizer`（示波器，`SpectrumVisualizer` + `VisualizerMode`，**无卡片背景**直接绘于流光底）；弹层类 `CommentsSheet` / `EqualizerSheet` / `SleepTimerSheet` / `PlaybackSpeedSheet` / `RecognitionSheet` / `SimilarSongsSheet` / `DislikeManagerSheet` / `DesktopLyricSheet` / `DownloadSheet` / `DownloadBall` / `SongShareSheet` / `ShareToNcmFriendDialog` / `NcmFriendPickerDialog` / `AddToPlaylistDialog` / `AddToPlaylistHost` / `ClipboardLinkDialog` / `WebLoginDialog` / `SearchField` / `SearchModeToggle` / `SearchTopBar` / `ListeningStatsCard` / `AccountPlaylistMiniCard`；材质类 `Glass`（GlassSurface / GlassBackdrop）/ `LiquidGlass`（类玻璃材质内核）
- 视觉亮点：封面 Palette 主色 → 黑胶辉光流光；榜单瀑布流卡片悬浮微光；歌单详情视差折叠头部；最近播放时间轴节点；收藏页滑动操作胶囊

### 7.1 动效与交互反馈体系（Motion & Feedback）

「每一次操作都要有反馈」是这套 UI 的硬约束，分四层落地：

**① 动效令牌（`ui/motion/DPMotion.kt`）** —— 全局唯一动效参数来源，杜绝魔法数字：

| 档位 | 时长 | 典型场景 |
| --- | --- | --- |
| `Instant` / `Fast` | 90 / 140 ms | 按压回弹、图标切换、涟漪 |
| `Medium` | 220 ms | 内容淡入淡出、状态切换 |
| `Slow` / `Emphasized` | 320 / 420 ms | 卡片展开、页面级过渡 |
| `Layout` | 500 ms | 共享元素、导航形态演进、抽屉 |

缓动分方向：**进入**用 `Decelerate`（快起慢停，显轻快）、**退出**用 `Accelerate`（慢起快走，让位新内容）、
**手势跟手**用弹簧（`snappy` / `bouncy` / `gentle` / `morph`）。
交错入场延迟由 `staggerDelayMillis()` 统一封顶（最多 320ms），避免长列表后段元素"迟到"。

**② 触觉反馈（`ui/util/Haptics.kt`）** —— `rememberDpHaptics()` 提供六个语义级别：

| 方法 | 语义 | 已接入位置 |
| --- | --- | --- |
| `tick()` | 极轻：状态切换 | 循环模式、随机播放、取消收藏 |
| `click()` | 轻：普通点击 | 歌曲行、Mini 条播放/下一首、播放器全部按键 |
| `longPress()` | 长按 | 歌曲行长按进入多选 |
| `confirm()` | 确认：成功 | 收藏成功、滑动「下一首播放」 |
| `reject()` | 警告：破坏性 | 滑动移除收藏 |
| `gestureEnd()` | 手势落位 | 进度条拖动松手 |

遵循「克制而有意」：只在状态真正改变时触发；系统关闭触觉时自动静默；API 30 以下自动降级到等价旧常量。

**③ 骨架屏（`ui/components/Skeleton.kt`）** —— 用内容形状的占位替代转圈，让用户预判布局、感知等待更短：

- `ShimmerBox` 扫光用**单一** `rememberInfiniteTransition` 驱动（长列表不爆开销），
  并以 `drawBehind + size.width` 计算真实像素宽度（用比例值当 Offset 是无效的）；
- `SongRowSkeleton` / `SongListSkeleton` 与 `SongRow` 排版严格对齐，加载完成**零跳动**；
- `CardSkeleton` / `CardGridSkeleton` 用于榜单网格与歌单卡片流；
- 已覆盖 11 处加载态：每日推荐、QQ 推荐、歌单列表（云/QQ）、歌单详情、搜索（歌曲/歌单）、榜单（网格 + 歌曲）、歌手详情、专辑详情。

**④ 列表增删动画** —— 收藏 / 最近播放 / 本地歌单 / 歌单详情接入 `Modifier.animateItem()`：
移除项淡出、其余项平滑补位。与 `staggeredEntrance` 共存时禁用 `fadeInSpec`
（入场交给交错动画），避免两套 alpha 动画叠加导致入场"发虚"。

---

## 8. 构建与运行

```bash
# AndroidIDE 直接打开工程目录即可；命令行构建：
./gradlew :app:assembleDebug
```

- 音源为在线接口（需网络）；LX 代理 Key **不内置**，由用户在「设置 → 音源服务」填写（见 §3.3）
- 媒体通知：Android 13+ 首次启动会申请 `POST_NOTIFICATIONS`
- 无需任何本地配置文件；`usesCleartextTraffic=true` 已开启（部分 CDN 为 http）

---

## 9. 已知限制与降级策略

| 限制 | 降级策略（已实现） |
| --- | --- |
| QQ 关键词歌单搜索匿名态失效 | 自动降级分类浏览 `fcg_get_diss_by_tag`（categoryId=10000000&sortId=5） |
| 酷狗封面接口 `play/getdata` 失效 | 使用 `trans_param.union_cover` 的 `{size}` 占位替换 |
| LX 代理 403/429 | 终止降档尝试，Snackbar 明示失败原因 |
| 某平台音源整体不可用 | 跨平台相似度匹配兜底 → 仍失败则自动跳过并提示 |
| 音质档位 UI 枚举 5 档 | master/atmos 档位在链中可用，UI 循环仅暴露 5 档以保稳定 |
| Bit-Perfect 需 Android 14+ 且依赖设备支持 | 低版本 / 不支持时 `supported=false`，开关置灰并给出原因；普通播放不受影响 |
| 无外接 USB DAC 时 Bit-Perfect 无法激活 | 状态显示「未检测到外接音频设备（插上 USB DAC 后自动启用）」，插拔自动感知 |
| Bit-Perfect 与均衡器互斥 | 独占期间强制旁路全部 DSP，设置卡片与均衡器面板两处明示 |
| 输入为多声道（> 2ch）时参数均衡不适用 | `onConfigure` 返回 `NOT_SET` 安全降级为纯透传，避免串音 |

## 10. 扩展指南

- **新增音源平台**：实现 `PlatformApi` 接口 → 注册进 `AppContainer.musicRepository` 的 `apis` 映射 → `MusicPlatform` 枚举加一项 → 远端代理侧确认 `source` 映射
- **新增音源引擎**：参考 `ScriptMusicResolver` / `MusicFreeResolver` 实现接入层 → 在 `MusicRepository.resolveForPlayback` 的兜底链中挂载 → 如需用户可选，扩展 `SourcePriority`
- **新增脚本模块**（MusicFree 插件 require）：见 [`docs/MUSICFREE-PLUGIN.md`](MUSICFREE-PLUGIN.md) §4（新增 JS 兼容层 → `MusicFreeEngine.doLoad` 求值 → preload 的 `modules` 表注册 → 补离线测试）
- **新增页面**：`Routes.kt` 加 `@Serializable` 路由 → `AppNavHost` 注册 composable → 如需导航项则加入 `mainNavItems`
- **调整断点行为**：所有页面以 `windowSizeClass.widthSizeClass == WindowSizeClass.Compact` 为唯一分叉条件，改一处即可全局生效
