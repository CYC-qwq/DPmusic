# 网易云「听歌识曲」研究笔记

> 研究时间：2025-09 | 参考项目：[akinazuki/NeteaseCloudMusic-Audio-Recognize](https://github.com/akinazuki/NeteaseCloudMusic-Audio-Recognize)（npm 包 `ncm-audio-recognize` v1.4.0）
> 结论：**全链路实测可用（3/3 匹配成功）**，可作为 DPmusic「听歌识曲」功能的实现基础。

## 一、完整管线

```
录音（48kHz 单声道）
  │
  ▼  取一段 6 秒窗口（from 偏移），每 6 个采样点取 1 个（48k → 8k 降采样）
Float32 PCM（48000 个样本 = 6 秒 @8kHz）
  │
  ▼  afp.wasm 的 ExtractQueryFP()（Emscripten Embind 导出的 C++ 函数）
指纹字节流（int8，约 1200 字节）
  │
  ▼  标准 Base64 编码
encoded 字符串（约 1600 字符）
  │
  ▼  HTTP POST（application/x-www-form-urlencoded）
https://interface.music.163.com/api/music/audio/match
  sessionId=441df692-afea-4a54-8aff-f5f20fd34f12（固定值，可用）
  algorithmCode=shazam_v2
  duration=6
  rawdata={encoded}
  times=2
  decrypt=1
  │
  ▼
data.result[] → { startTime, song{ id, name, artists[], album{name,picUrl}, duration, ... } }
```

## 二、实测结果（2025-09 实测，全部通过）

| 测试素材 | 窗口 | 结果 |
|---|---|---|
| 晴天 - 周杰伦（mp3） | 4s-10s | 2 条（翻唱版） |
| 晴天 - 周杰伦（mp3） | 30s-36s | 2 条，含**原唱 Live 版**（cid 185956） |
| 我不难过 - 孙燕姿（DPmusic 下载的 FLAC） | 30s-36s | **3 条全部原唱**（cid 287398 / 287171 / 32712816） |

- 编码耗时：约 80ms（桌面 Node，含 WASM 首次加载）
- 无需登录 / Cookie；接口在国内网络直连可用（VPN 开启时测试亦通过）
- 返回的 `song` 为**完整网易云歌曲对象**，可直接映射为 DPmusic 的 `Song` 模型：
  - `id` → cid（网易云歌曲 ID，可直接走现有 WY 播放解析链路）
  - `name` → title；`artists[].name` → artist；`album.name` → album；`album.picUrl` → coverUrl；`duration` → durationMs

## 三、关键结论

1. **指纹算法（afp.wasm）必须原样运行**，不能重写：
   - 指纹需与网易服务端数据库 bit 级一致，任何重实现都不可行；
   - `ExtractQueryFP` 是 Emscripten **Embind** 导出的 C++ 函数（`std::string` 参数 + embind 调用约定），依赖 wasm 内部自带的 embind 运行时（含 zlib + C++ 运行时），wasm 仅 231KB；
   - wasm 导出被混淆为单字母（B~M），友好名（ExtractQueryFP）由 JS 胶水层（sandbox.bundle.cjs，88KB）在运行时挂载。

2. **无客户端加密**：`rawdata` 就是指纹的普通 Base64，直接表单提交即可。

3. **sessionId 固定值可用**（无需每会话生成）。

4. 识别出的都是「候选列表」（含翻唱 / Live 版），由客户端展示让用户选择。

## 四、Android 集成方案（推荐：WebView 承载指纹引擎）

| 方案 | 可行性 | 说明 |
|---|---|---|
| **WebView 跑原始 bundle + wasm** | ★★★★★ | 100% 保真；系统组件零依赖；需做 Node 依赖 shim 与资产加载 |
| Chicory 等纯 JVM WASM 运行时 | ★★ | embind 运行时依赖 JS 胶水，手工复刻成本极高 |
| wasm2c + NDK 编译为 .so | ★★ | embind 调用约定处理复杂，工程量大 |
| Kotlin 重写算法 | ✗ | 无法保证与服务端 bit 级一致 |

**WebView 方案要点：**

1. 资产：`assets/audiofp/` 下放 `afp.wasm` + 改造版 `sandbox.bundle.cjs` + `index.html`；
   - bundle 顶部 `require('fs'|'path'|'crypto')` 需以 shim 包装（浏览器分支实际不用它们）；
   - wasm 通过 WebViewAssetLoader（`https://appassets.androidplatform.net/...`）提供，避免 file:// fetch 限制。
2. 桥接：`@JavascriptInterface` 传入 Float32 PCM（Base64 或直接数组），JS 调 `Encode()` 回传 Base64 指纹。
3. 录音：`AudioRecord`（48kHz 单声道）→ 抗混叠低通（8 阶 Butterworth @3.6kHz）→ 每 6 点取 1（8kHz）→ 峰值归一化；
   - 酷狗上传全量 10 秒；网易云取前 6 秒窗口（对齐官方管线）。
4. 请求：OkHttp POST 表单（现有 `Http` 层扩展一个 `postForm` 即可）。
5. 结果：映射为 `Song(platform = WY)` → 走现有 `MusicRepository.resolveForPlayback` 播放 / 收藏 / 加歌单。

**复用现有设施：**
- `Http`（OkHttp 单例，含重试）→ 加 `postForm`
- `Song` 模型 / 播放器 / 收藏 / 加歌单对话框
- 设置页可加开关：听歌识曲入口（首页或搜索页）

## 五、风险与注意点

- 接口为**非公开接口**，存在随时变更 / 风控的可能（实测当前正常；需做好失败提示与降级）；
- WebView 需开启 JS 并允许网络；首次加载 wasm 约百毫秒级；
- 录音需要 `RECORD_AUDIO` 运行时权限（首次点击时申请）；
- `afp.wasm` 与 bundle 来自开源项目（ISC 许可），可随应用分发。

## 六、集成落地（已完成）

### 资产（`app/src/main/assets/audiofp/`）

- `index.html`（398KB，wasm 内联，由 `/tmp/ncm/build_assets.py` 生成）：
  - 补丁 ①：wasm 内联（`Q = "inline://afp.wasm"` + base64 解码注入），绕开 file:// fetch 限制；
  - 补丁 ②：`toNCMBuffer` 支持 `preDecimated`（Kotlin 侧预降采样 48k→8k，JS 桥传输量减少 6 倍）；
  - 桥接层：`window.__fpEncode(reqId, pcmB64)` → `AndroidBridge.onResult(reqId, encoded, error)`；加载完成回调 `onReady()`。
  - 一致性验证：补丁版 vs 官方版指纹输出**逐字节一致**（`IDENTICAL: true`），且补丁输出 E2E 匹配成功。

### Kotlin 实现

| 文件 | 职责 |
|---|---|
| `core/audio/AudioSampler.kt` | AudioRecord 48kHz 单声道录音 → 预降采样 8kHz Float32（内存处理不落盘） |
| `core/audio/AudioFingerprintEngine.kt` | WebView 封装（@JavascriptInterface 桥 + Float32→Base64 小端） |
| `core/audio/AudioRecognizer.kt` | 匹配接口封装（POST 表单 → Candidate 列表 → Song(WY)） |
| `ui/components/RecognitionSheet.kt` | 识别 Sheet（状态机：Idle/Recording/Working/Results/Failed）+ RecognitionViewModel |
| `core/net/HttpClient.kt` | 新增 `postForm`（x-www-form-urlencoded） |
| `ui/screens/home/HomeScreen.kt` | 顶栏麦克风入口（Sheet 挂载于主页） |
| `AndroidManifest.xml` | `RECORD_AUDIO` 权限 |
| `app/proguard-rules.pro` | `@JavascriptInterface` keep 规则 |

### 交互流程

顶栏 🎤 → Sheet「选择拾音方式」（麦克风 / 系统播放 / 音频文件）→ 采集 10s（环形进度 + 音量脉动）→ 双引擎并行匹配（酷狗置顶）
→ 候选列表（点击播放 / 长按加歌单 / 收藏）→ 失败可重试；首次使用申请麦克风权限。

### 找原唱（版本搜索）

- 识别结果行展示**版本标签**：`翻唱`（originCoverType=2/3）/ `现场版`（标题或专辑含 Live/现场）；
- 每行 🔍 按钮 → 以**清洗后的纯歌名**（去掉 `(Live)`、`（原唱xx）`、`[伴奏]` 等括号内容）为关键词搜索网易云；
- 搜索视图支持**改词重搜**（键盘「搜索」键触发），结果行标注 `原唱`（type=1）/ `翻唱` / `Cover`，优先选原唱播放；
- 搜索接口 `cloudsearch/pc` 直接返回 `originCoverType` 字段，无需二次详情请求；
- **三平台可切换**（复用 `PlatformChips` 组件）：网易云（带原唱标记）/ QQ音乐 / 酷狗；QQ/KG 走 `MusicRepository.searchSongs` 统一接口，无原唱标记但曲库互补；
- 实测：搜「我不难过」三平台首条均为孙燕姿原版；部分歌曲（如周杰伦版权曲）网易云搜索可能无原版上架，可切 QQ音乐搜。

## 七、酷狗 & QQ音乐「听歌识曲」调研

> 调研时间：2026-09（实测已跑通酷狗全链路）

### 7.1 酷狗：✅ 有免费接口（实测通过，比网易云更简单）

**来源**：`MakcRe/KuGouMusicApi`（2026-06 新增「听歌识曲」模块 `module/audio_match.js` + 前端页 `public/audio_match.html`）

**管线**（与网易云完全不同——直接上传音频，无需指纹算法）：

```
录音 → 10 秒 8kHz 单声道 Int16 PCM（Float32 × 32768）
→ MD5 签名（盐 + 排序参数 + PCM 二进制 + 盐）
→ POST https://gateway.kugou.com/fingerprint.service/v1/music_trackid_mulit
   （content-type: application/octet-stream，UA: KuGou/11490 (Android)）
→ data[] 候选列表
```

**签名算法**：`MD5(salt + sortedParams + pcmBuffer + salt)`，salt=`OIlwieks28dk2k092lksi2UIkp`
（参数按 key 字母序拼接 `key=value`；二进制直接参与哈希）

**请求参数**：`dfid='-'`、`mid`（md5(随机UUID)的十六进制转十进制大整数）、`uuid='-'`、`appid=1005`、`clientver=20489`、`clienttime`（秒）、`fpid=Date.now()`、`area_code=1`、`include_unpublish=1`、`useid=0`、`multi_result=1`、`signature`

**返回字段（关键）**：
| 字段 | 说明 |
|---|---|
| `songname` / `singername` | 歌名 / 歌手 |
| **`is_original`** | **"1"=原唱、"2"=翻唱**（比网易云更直接！） |
| `dist` | 距离（越小越相似，0.000 = 完美匹配） |
| `hash_320` / `hash_flac` / `hash_high` | 播放哈希（32位hex，可直接作为 `Song.id` 走现有 KgApi 链路） |
| `timelength_320` / `_flac` | 时长（毫秒） |
| `union_cover` | 封面（`{size}` 占位替换） |
| `album[0].albumname` | 专辑名 |

**实测结果（3/3 成功）**：
| 样本 | 结果 |
|---|---|
| 晴天 前奏 0-10s | **[1] 周杰伦 原版（is_original:1, dist:0.000）** 🎯 |
| 我不难过 30-40s | [1] 孙燕姿 原版（is_original:1） |
| 晴天 30-40s | 翻唱版若干（该段原版未命中） |

**集成评估**：难度**低**（Http 二进制 POST + MD5 签名，无需 WebView/wasm），hash 与现有 `KgApi.songDetail` 完全兼容。

### 7.2 QQ音乐：❌ 无免费接口

- 客户端「听歌识曲」未公开逆向（无类似 KuGouMusicApi 的识曲模块）；
- 腾讯多媒体实验室「听歌识曲」为**商业 API**（`api.mediax.tencent.com`，异步任务制、自有曲库仅 3.6 万+、需 secretId/secretKey 付费）；
- QQ音乐开放平台（music_developer）不提供识曲能力。

### 7.3 商业替代方案（如需 QQ 系能力）

| 服务 | 曲库 | 说明 |
|---|---|---|
| **ACRCloud** | 1亿+ | 免费试用，SDK 完善（iOS/Android/Java/Python）；网易云、KKBOX、咪咕、唱吧均为其客户 |
| 讯飞 | — | song-recognition / music_recognition API，付费 |
| 腾讯多媒体实验室 | 3.6万 | 异步任务制，付费 |

**结论**：免费可集成方案仅酷狗（实测可用）；QQ音乐无免费识曲接口。

### 7.4 双引擎集成（已完成）

**一次录音（6 秒 48kHz）→ 降采样 8kHz → 双路并行识别**：

| 引擎 | 链路 | 展示 |
|---|---|---|
| **酷狗（主力）** | Int16 PCM 直传 + MD5 签名 | 结果列表**置顶** |
| 网易云 | afp.wasm 指纹 → 匹配接口 | 紧随其后 |

- UI：结果按引擎分组（酷狗组在前），每组独立状态（识别中 / N 条 / 未匹配 / 识别失败）；
- 版本标签：酷狗 `is_original` →「原唱 / 翻唱」；网易云 `originCoverType` →「原唱 / 翻唱 / 现场版」；
- 验证：Kotlin 逻辑经**逐行等价 Java 程序**实测（HTTP 200、周杰伦原版 dist 0.000）；
- 实现文件：`core/audio/KgRecognizer.kt`（新）、`Http.postBinary`（新）、`AudioSampler.floatToInt16Le`（新）、`RecognitionSheet` 双引擎状态机。

### 7.5 识别精度优化（2026-09）

针对真机「偶发未匹配」：降采样方式、录音幅度两轮假设经实测排除后，定位到**无滤波降采样在嘈杂环境的高频混叠**（4kHz 以上噪声折叠进 0~4kHz 识别频段）。

| 优化 | 实现 | 实测（真实接口） |
|---|---|---|
| 抗混叠低通 | 8 阶 Butterworth @3.6kHz（4 级 biquad，降采样前） | 5kHz+ 强噪：旧管线双引擎均未匹配 → 新管线**均命中**（酷狗 dist 0.000） |
| 峰值归一化 | 输出归一化到 0.95（静音保护） | 弱信号量化更充分 |
| 时长对齐官方 | 酷狗 10 秒 / 网易云前 6 秒窗口 | 6~10 秒均实测可用 |
| 未匹配引导 | 双引擎无结果时提示「调大音量 / 靠近音源」 | — |

回归验证：干净音频新旧管线均命中（无回归）；7dB 粉噪均命中（网易云 3 条 vs 旧 2 条）。

### 7.6 多拾音途径（2026-09）

| 途径 | 实现 | 说明 |
|---|---|---|
| 🎤 麦克风 | `AudioRecord(MIC)` | 识别周围环境音（现有链路） |
| 📱 系统播放 | `MediaProjection` + `AudioPlaybackCapture`（Android 10+） | 捕获手机内部媒体音频；Android 14+ 需 mediaProjection 前台服务（`CaptureForegroundService`）；立体声 → 下混单声道 |
| 📁 音频文件 | SAF（`OpenDocument`）+ `MediaCodec` 解码 | ≤60 秒流式解码 → 挑 RMS 最大 10 秒窗口 → 重采样 48kHz |

- 三条链路统一走 `AudioSampler.process48k`（抗混叠低通 → 降采样 → 归一化）→ 双引擎；
- 「再试一次」沿用最近一次拾音方式；双引擎均无结果时提示换拾音方式；
- 涉及文件：`AudioPlaybackCapturer.kt`、`AudioFileDecoder.kt`、`CaptureForegroundService.kt`（新）。
