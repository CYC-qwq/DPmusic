# 上游项目调研（B 站 / YouTube Music 取流）

> 目的：在实现/维护 B 站与 YT Music 音源前，逐个研读一批**成熟同类项目**，弄清「它们的取流
> 链路到底怎么做」。本文是调研记录 + 对本项目的决策依据。
>
> ⚠️ **现状说明（2026-10）**：YouTube Music 音源**已从本项目移除**（见 §3.2）。本文档**保留**，
> 因为它同时覆盖 **B 站**（仍在用）的同类项目印证，以及一份「**若将来重启 YT，路怎么走**」的
> 完整技术结论。B 站实现见 `PLATFORM-BILIBILI.md`。

调研对象（7 个）：

| # | 项目 | 定位 | 与本项目的关系 |
| --- | --- | --- | --- |
| 1 | `bromothymolb/bilibili-api-zoku` | Python B 站 API 库（bilibili-api 的续作） | B 站**接口/风控**权威参考 |
| 2 | `AprDeci/bili-music` | Flutter 的 B 站音乐客户端 | B 站**音源接入范式**（与我们的映射一致） |
| 3 | `lovegaoshi/azusa-player-mobile` | RN 的「B 站音频播放器」 | B 站 + YT **取流 + PoToken(WebView)** 参考 |
| 4 | `yuliskov/SmartTube` | Android TV 的 YouTube 客户端 | YT **取流**事实标准（DASH + poToken） |
| 5 | `maxrave-dev/SimpMusic` | Android/Desktop 的 YT Music 客户端 | YT Music **全链路**；明言受益于 SmartTube |
| 6 | `MetrolistGroup/Metrolist` | Android 的 YT Music 客户端（fork 自 InnerTune/OuterTune） | YT Music 的 **innertube + 取流 + 登录** |
| 7 | `sponsor.ajay.app` | SponsorBlock（公开 API） | YT 视频**分段元数据**（可零成本增强） |

---

## 1. 结论速览

### 1.1 Bilibili —— 「直连」是成熟且稳定的做法

- **`bilibili-api-zoku`** 证实：B 站接口库的核心难点就是**风控**——`buvid3` Cookie（缺则
  **HTTP 412**）与 **WBI 签名**（`w_rid`/`wts`，缺则 `code:-403`）。它还建议用
  `curl_cffi` 的 TLS/JA3 伪装进一步规避风控。
- **`bili-music`** 证实：B 站音乐客户端的通用范式就是「**视频 → 歌曲**」（搜视频、UP 主当歌手、
  标题清洗）——与本项目 `PLATFORM-BILIBILI.md` 的映射完全一致。
- **`azusa-player-mobile`** 同样直连 B 站，且额外支持：**登录态**（点赞/三连/收藏夹同步）、
  歌单订阅、MusicFree 插件、AList。
- **本项目结论**：**直连 B 站是对的**。当前实现（buvid3 + WBI + DASH 音频 + Referer）与这些
  项目同构，已被真机验证可用。

### 1.2 YouTube Music —— 分两层：**免 PoToken 客户端** vs **WebView 铸造 PoToken**

所有 YT 客户端（SmartTube / SimpMusic / Metrolist / azusa）都指向同一套办法：

1. **首选「免 PoToken / 直连 URL」客户端**（`ANDROID_VR`、`VISIONOS`、`IOS`、`TV`…），或
2. **对 Web 客户端（WEB_REMIX）用 WebView 跑 BotGuard，铸造真正的 PoToken**，
   再做 **signatureCipher（sig + n）反混淆**拿到可播 URL。

关键事实（来自 `zemer-cipher`，被 `Metrolist`/`SimpMusic` 直接引用）：

> *「几乎所有库失败的地方，是它们只对**静态/长命令**做解密，而对 `n`、`sig` 要求
> **真正执行** YouTube 的混淆 JS——而这需要运行时的 DOM/JS 环境。所有客户端最终都用
> **真实浏览器的 QJSEngine/WebView** 来做这件事。」*

**决定性实验（本项目实测）**：在**裸 Node 沙箱**里跑 BotGuard，只能拿到
`GenerateIT` 的**降级 token**（`websafeFallbackToken`，108 字符），拿它去取流**仍然 403**。
`zemer-cipher` 的做法是：把 `po_token.html` 载入 **Android WebView**，用**真的浏览器环境**
执行 BotGuard 解释器 + 程序，`createPoTokenMinter` 产出**真 minter**，`obtainPoToken`
返回 **110–128 字节**的 token。

> **结论**：真 PoToken **必须有浏览器/WebView 环境**。这就是本项目此前失败（403）的根因。

### 1.3 SponsorBlock —— 便宜且合法的增强

`sponsor.ajay.app` 是开源的**众包分段 API**（隐私友好的前缀 hash 查询），用来跳过赞助/片头/
片尾，也支持「高光片段」与非音乐部分跳过。SmartTube 与 SimpMusic 都集成了它。
对我们的价值：给 YT 曲目加「跳过非音乐片段 / 高光」是**零风险**的（数据源公开、非取流）。

---

## 2. 逐项目要点

### 2.1 `bilibili-api-zoku`（Python）

- 全面覆盖视频/音频/直播/动态/专栏/用户/番剧；BV↔AV 互转；直播弹幕 WS；字幕/弹幕导出。
- **风控**：默认支持 `aiohttp`/`httpx`/`curl_cffi`，可 `select_client("curl_cffi")` 并设
  `request_settings.set("impersonate", "chrome131")` —— 伪装 TLS/JA3 指纹。
- **412** 的解释：请求过快 → IP 临时封禁；用代理绕过。
- 明确「爬虫模块，B 站接口一变就可能失效，务必用最新版」。

> 对 B 站的启发：我们的 `WbiSigner` 与 buvid 会话思路与其一致；**未来若遇更严风控，
> 可考虑引入 TLS 指纹伪装**（但当前匿名直连已够用）。

### 2.2 `bili-music`（Flutter）

- 功能：播放、搜索、同步收藏夹、元信息匹配、外部歌单导入、动态取色、多平台。
- 声明「基于公开资料开发，无破解/逆向」。
- 印证：B 站音乐客户端的**映射范式**（视频=歌曲、UP=歌手）是业界通行做法。

### 2.3 `azusa-player-mobile`（React Native）

- **`android/app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenWebView.kt`**：
  **Android WebView + `po_token.html` 跑 BotGuard** 生成 PoToken（与 `zemer-cipher` 同源思路）。
- B 站侧：`src/components/explore/Bilibili.tsx`、`src/components/login/bilibili/*`、`grpc/bilibili`
  —— **登录态**（点赞/三连/收藏夹）。
- 内挂 ffmpeg、MusicFree 插件、AList、歌曲缓存。
- 许可证：AGPLv3（**注意**：若参考其代码需评估 GPL 传染）。

### 2.4 `SmartTube`（Android TV）

- Android TV 的 YouTube 客户端，**不支持手机/平板**（TV 优化）。
- 功能：SponsorBlock、8K、60fps、HDR、直播聊天、免 Google 服务。
- README 有一则安全公告（构建环境曾被污染、公钥可能泄露、建议轮换连接码）——
  提醒我们**第三方 APK 的供应链风险**。
- **SimpMusic 明言**：「Special thanks to SmartTube，它帮我**取到 YouTube Music 的 streaming URL**」。

> 它取流的核心即：**DASH 流 + poToken + 免 poToken 客户端回退**。

### 2.5 `SimpMusic`（Android/Desktop）

- 「hidden API of YouTube Music + some tricks」；感谢 InnerTune（数据）与 SmartTube（取流）。
- 集成 **SponsorBlock** 与 **Return YouTube Dislike**；歌词来自 SimpMusic Lyrics / LRCLIB /
  Spotify（需登录） / YouTube Transcript；多账号；Last.fm；Android Auto。
- 说明：**YT Music 播放出错是常态**（依赖上游），不是稳定 API。

### 2.6 `Metrolist`（Android，InnerTune/OuterTune 血统）

- `innertube/` 模块 = 自研 InnerTube 客户端（`InnerTube.kt`、`YouTube.kt`…）。
- README 致谢里点名 **`zemer-cipher`（YouTube cipher 反混淆 + PoToken 生成）**。
- 区域限制：**无 VPN/代理则不可用**（与我们在 `PLATFORM-YOUTUBE-MUSIC.md §3` 的实测一致）。

### 2.7 `ZemerTeam/zemer-cipher`（**最关键**，独立 Android 库）

> Metrolist / SimpMusic 都引用它。它是把「YT 取流」拆解最清楚的一份实现。

**它解决什么**

- **signatureCipher 反混淆**：WebView 里执行 player JS，导出 `window._cipherSigFunc`，解 `s`。
- **n 参数变换**：同样在 WebView 里，从 player JS 运行时发现 n-function，做变换以避免限流/403。
- **PoToken（BotGuard）**：WebView + `po_token.html`，产出真 token。
- **远程可更新的 player 配置**（`player_configs.json`）：YouTube 频繁轮换 player，
  配置按 `6h TTL + ETag` 从仓库 `master` 拉取，**未知 player 解密失败时强刷**——
  「推一条配置到 master」就等于**线上部署**，无需发版。

**配置 schema（`player_configs.json`）**

```json
"445213fb": { "sig": "mP(4,155,INPUT)", "nClass": "Yx", "sts": 20613, "aliases": ["d62bd338"] }
```

- key = player JS URL 里的 8 位 hex 哈希；`aliases` = 前 10000 字节 md5 的回退哈希；
- `sig` = 签名反混淆调用（锁定 `name(int,int,INPUT)` 形态）；`nClass` = n 变换 IIFE 的 URL 类；
- `sts` = 该 player 的 `signatureTimestamp`。

**关键工程细节（含血泪教训）**

- **验证的唯一真值**：解出的地址让 CDN 返回 **HTTP 206**——「多组常量都能"解出"签名，
  但只有一组会被接受」。
- **`signatureTimestamp` 必须与实际解密用的 player JS 一致**：A/B 灰度期间，别的来源
  （如 NewPipe 自带的 player 抓取）可能落到**不同 player 世代**，「用 A 铸的签名 + 用 B 解」→ CDN **403**。
- `onStreamRejected()`：WEB_REMIX 的 403 是「算错但没抛异常」的签名唯一可观测信号 →
  重新拉配置表，epoch 变了就重建 WebView 恢复。
- **进程/内存**：WebView 会被系统 OOM 杀掉；用 `RendererRecoveryPolicy` 在短暂退避窗口内
  跳过重建（避免在注定失败时卡住播放），下一首再试。
- **安全**：`PlayerConfigParser` 是校验边界，所有值用正则锁定，**远程数据不能把任意 JS 注入
  cipher WebView**；非法条目跳过、非法文件整体拒绝、设备保留上一份好配置。

> **对本项目的意义**：`zemer-cipher` 是**现成的 Android 库**（`com.zemer:cipher`，GPL-3.0），
> 若 DPmusic 将来要上「直连 YT 取流」，它是最省事的落点——可直接依赖，或移植其
> `PoTokenWebView` + `CipherWebView` + `PlayerConfigStore` 三件套。

---

## 3. 本项目该如何落地（决策更新）

结合本轮调研与前期实测（详见 `PLATFORM-YOUTUBE-MUSIC.md §2`）：

### 3.1 Bilibili —— 维持「直连」，已达标

- 与 `bilibili-api-zoku` / `bili-music` / `azusa` 同构；已被真机验证。
- 可选增强（按需）：① 登录态（收藏夹/三连，参考 azusa）；② 引入 TLS 指纹伪装以抗更严风控。

### 3.2 YouTube Music —— **已移除**（2026-10）

前几轮「匿名不可行 / SABR / 登录也无效 / 需换干净 IP」的判断，**根因都是没在浏览器环境里铸造
真 PoToken**。调研后，技术结论修正为：

| 方案 | 说明 | 成本 |
| --- | --- | --- |
| A. 免 PoToken 客户端（`ANDROID_VR`/`VISIONOS`） | 某些网络下能直接出可播 URL；但**被要求 PoToken 的 IP 会吃 `Sign in to confirm you're not a bot`**（本项目实测） | 低 |
| B. **WebView 铸造 PoToken + cipher 反混淆**（zemer-cipher 路线） | 对 `WEB_REMIX` 也能通——这才是 Metrolist/SimpMusic 能播的原因 | 中（移植/依赖 + 维护 player 配置） |
| C. 只做元数据 / 移除 | 不承担取流维护成本 | 0 |

> **本项目决定：移除 YouTube Music 音源（方案 C）。** 理由：`B` 虽可行，但需长期依赖 WebView
> 跑 BotGuard + 跟随 YouTube 的 player 轮换维护配置（且 `zemer-cipher` 为 GPL-3.0，引入需评估
> 许可）；在「B 站音源已可用」的前提下，**收益与维护代价不成比例**。
>
> **若将来重启 YT**：直接照 `B` 走——引入 `zemer-cipher`（`com.zemer:cipher`）或按其思路自实现
> `PoTokenWebView` + `CipherWebView` + `PlayerConfigStore`。

**YT 实测要点（历史，仍有效）**

- `player` 请求缺 `playbackContext.contentPlaybackContext`（`vis/splay/lactMilliseconds/signatureTimestamp`）
  → `UNPLAYABLE`/`LOGIN_REQUIRED`；补齐 → `OK`，且下发的是 **`signatureCipher`（非 SABR）**。
- `signatureCipher` 的 `s`/`n` 在**裸 Node 求值器**里能解，但拼出的 `googlevideo` 地址**一律 403**。
- 生成并注入 **BotGuard PoToken** 后**仍 403** —— 因为裸沙箱只得 `GenerateIT` 的**降级 token**
  （`websafeFallbackToken`，108 字符），而真 token 需浏览器环境（**110–128 字节**）。
- **登录态**（真实账号 cookie + `SAPISIDHASH`）**仍 403**；`@distube/ytdl-core` 报
  `Failed to find any playable formats`；**权威工具 `yt-dlp`** 报 `The page needs to be reloaded.`。
- 免 PoToken 客户端 `VISIONOS`(id=101)/`ANDROID_VR`(id=28) 在本网络出口被直接拒绝：
  **`Sign in to confirm you're not a bot`** —— 该 IP 被要求 PoToken。

### 3.3 SponsorBlock —— 可选增强（低风险）

若要提升 YT 体验，可接 SponsorBlock 公开 API 做「跳过片头/赞助、跳高光」。数据源公开、
与取流无关，风险最低。**注意**：本项目当前**不实现、也不建议实现**「YouTube 广告跳过」
（那属于规避平台变现机制，与 `PLATFORM-YOUTUBE-MUSIC.md §2.3` 讨论的 BotGuard 绕行一脉，
有法律与合规风险）；SponsorBlock 仅用于**创作者本人标记的非广告分段**。

---

## 4. 附：本项目相关实测证据索引

| 现象 | 出处 |
| --- | --- |
| B 站 412（缺 buvid）/ -403（缺 WBI） | `PLATFORM-BILIBILI.md §3` |
| B 站流 403（缺 Referer） | `PLATFORM-BILIBILI.md §5` |
| YT 各类 403 / `Sign in to confirm you're not a bot` / PoToken 结论 | 本文 §3.2（「YT 实测要点」） |
| 裸 Node 沙箱 BotGuard 只得降级 token → 取流仍 403 | 本文 §1.2（决定性实验） |
| 建议路线：WebView 铸造真 PoToken | 本文 §3.2 |
