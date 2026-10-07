# 哔哩哔哩音源（直连通道）

> 相关文件：`core/net/BiliApi.kt`（接口 + 会话/签名）、`core/net/BiliText.kt`（标题清洗）、
> `core/net/WbiSigner.kt`（WBI 签名）、`core/net/BiliResolver.kt`（取流）、
> `core/net/BiliPlatformApi.kt`（`PlatformApi` 实现）

## 1. 定性：这是**视频站**，不是音乐站

B 站没有「歌曲」实体，只有用户上传的视频。因此接入时做了三处明确的取舍：

| 概念 | 本项目映射 | 说明 |
| --- | --- | --- |
| 歌曲 | 一个**视频**（`bvid`） | 搜歌 = 搜含该歌的视频 | 
| 歌手 | 视频**UP 主** | 标题里的「歌手」不可靠，UP 主是最接近的字段 |
| 歌名 | 清洗后的**标题** | 见 §4 |

**后果**：搜索结果里会出现翻唱 / live / 剪辑，且同一首歌有多个视频。这是 B 站作为音源
的固有特性，不是实现缺陷。

> 📄 同类项目（`bili-music` / `azusa-player-mobile` / `bilibili-api-zoku`）的调研印证了这一范式，
> 详见 [`RESEARCH-UPSTREAM-PROJECTS.md`](RESEARCH-UPSTREAM-PROJECTS.md)。

## 2. 能力矩阵（实测 2026-10）

| 能力 | 状态 | 备注 |
| --- | --- | --- |
| 搜索 | ✅ 匿名 | 需 WBI 签名 + `buvid3` Cookie |
| 单曲详情 | ✅ 匿名 | `wbi/view` |
| 播放地址 | ✅ 匿名 | DASH 音频轨；**必须带 Referer** |
| 音频档位 | ⚠️ 仅 AAC | `30280`≈192k / `30232`≈132k / `30216`≈64k；会员 FLAC 匿名不可得 |
| 歌词 | ❌ | B 站无 LRC（CC 字幕语义不同），`lyrics()` 返回空文档 |
| 榜单 | ✅ 匿名 | **音乐区排行**（`ranking/v2?rid=3`，实测 96 条，含 `bvid`+`cid` 可直接播） |
| 歌单搜索 | ❌ | 收藏夹需登录态，未接入 |
| 评论 | ❌ | 接口未接入 |

## 3. 风控与签名（**最容易踩坑的部分**）

两个独立机制，缺一不可：

1. **`buvid3` Cookie** —— 不带的请求一律 **HTTP 412**（Precondition Failed）。
   冷启动流程：访问 `www.bilibili.com` 取 `Set-Cookie` → `finger/spi` 补 `buvid4`。
2. **WBI 签名**（`w_rid` + `wts`）—— `wbi/search`、`wbi/view` 等接口要求，否则 `code: -403`。

签名算法见 `WbiSigner.kt`：`mixinKey = (imgKey+subKey)` 按官方混入表重排取前 32 字符；
`w_rid = md5(排序拼接串 + mixinKey)`。密钥每日轮换，按 6 小时 TTL 缓存，遇 -403 自动强刷重试。

> ⚠️ 这里**每个细节都不能想当然**（过滤 `!'()*`、按键名升序、拼接后取前 32）。
> `WbiSignerTest` 的期望值来自**独立 Python 实现**——先用 Python 算出签名请求真实接口
> 确认 `code: 0`，再固化进测试。这样 Kotlin 实现一旦漂移会立刻失败，不会等到线上 -403。

## 4. 标题清洗（`BiliText.cleanTitle`）

搜索返回的 `title` 是脏的，含两类噪声：

- `<em class="keyword">…</em>` 搜索高亮标签（必须剥离，否则标题里混进 HTML）；
- 前后缀修饰：`【Hi-Res无损】`、`(Official MV)`、`（官方MV）`、`【4K】` 等。

清洗规则（单测 `BiliTextTest` 钉住，用了真实抓取的标题做样例）：
剥离 HTML → 解码实体 → 去前缀方括号修饰 → 去后缀圆/方括号修饰。

## 5. 播放：必须带 Referer

B 站音频流（`*.bilivideo.com` / `*.mcdn.bilivideo.cn`）做**防盗链**：不带
`Referer: https://www.bilibili.com/` 直接 403。

由于 Media3 的 `MediaItem` 无法携带请求头，`BiliResolver` 解析成功后把
「地址 → 头」登记进 `PlaybackHeaderStore`，由播放侧在发请求前注入
（与 MusicFree 插件同一机制）。下载路径在 `SongDownloader.refererFor` 同样补了 Referer。

## 6. 接线点清单（新增一个平台要动的地方）

| 文件 | 改动 |
| --- | --- |
| `MusicPlatform.kt` | 新增 `BB("bb", "哔哩哔哩", "B站", "bb", 0xFF00AEEC)` |
| `PlayQuality.kt` | `qualityOrder(BB) = ["320k","128k"]` |
| `SongLinkParser.kt` | 识别 `bilibili.com/video/BV…` |
| `SongDownloader.refererFor` | 补 `https://www.bilibili.com/` |
| `AppContainer.kt` | 注册 `BiliPlatformApi()` / `BiliResolver()` |
| `MusicRepository.kt` | `biliResolver` 作为**直连通道**（同汽水）+ `testBiliSource` |
| `SourceManagerViewModel.kt` | 音源自检列表加一项 |

**UI 无需改动**：`MusicPlatform.entries` 驱动的平台选择（`PlatformChips` / 设置页音源平台 /
首页榜单）自动带上 B 站。

> 注：`PlatformChips` 已改为按 **`AppSettings.enabledPlatforms()`**（用户开关）过滤，
> 榜单页另按 `toplistPlatforms()`（开关 × 是否有榜单内容）过滤。

## 7. 已知边界

- **跨平台兜底会兜到 B 站视频**：网易云某歌解析失败时，兜底搜索可能在 B 站匹配到
  **翻唱 / live 视频**（标题相似度 > 0.72 即认定同曲）。这是「跨平台找同名曲」的既有语义；
  不接受可在「音源管理 → 解析链路」关掉跨平台兜底。
- **榜单只有音乐区一个**：`ranking/v2` 实测**仅 `rid=3`（音乐区）可用**（96 条）；
  其余分区（28/29/30/31…）返回 `-400`（非排行分区），故不接。且该接口**必须带 buvid + Referer**，
  裸请求会被风控拦成 `-352` —— 本应用复用 `BiliApi` 会话态，故正常可用。
  首页榜单分区会出现「音乐区排行」。
- **`av` 号链接不支持**：只识别 `BV` 号；`av` 号需先转 BV，未实现（可用搜索代替）。

## 8. 验收要点

- [ ] 搜索页平台切成「哔哩哔哩」，搜「晴天 周杰伦」能出结果，标题已无 `<em>` 标签；
- [ ] 点一首能正常播放（鉴权靠 Referer，播放中不应 403）；
- [ ] 粘贴 `https://www.bilibili.com/video/BV…` 能导入并播放；
- [ ] 「音源管理 → 音源可用性测试」里有「哔哩哔哩直连」一项，且通过；
- [ ] 下载一首 B 站曲目能成功（验证下载侧 Referer）；
- [ ] 歌词区为空但不报错。
