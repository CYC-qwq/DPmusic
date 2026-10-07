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
> ✅ **本节结论已被 §7.7 修正并跑通（2026-09-30 二次攻坚）**：QQ音乐识曲接口**真实存在、匿名可用、
> 零签名零 Cookie**，`c6.y.qq.com/youtu/humming/search`，请求体为 **Base64(8k PCM)**。
> **已实测命中**（孙燕姿《我不难过》/ 采风乐坊《蓄勢》/ 杨丞琳《雨爱》），已接入 `QqRecognizer.kt`。详见 §7.7。

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

---

## 七·七、QQ音乐「听歌识曲 / 哼唱识曲」深度侦察（2026-09-30）

> 本轮为**客户端 APK 逆向**（上次调研欠缺的深度）：拉取 QQ音乐 Android 官方 APK，jadx 反编译，
> 定位识曲全链路，并**实测**服务端接口。上次 §7.2「无免费接口」的结论**需要修正**。
### 0. 结论表（两个能力分开）

> 🎯 **2026-09-30 二次攻坚补齐了决定性一环**：请求体是 **Base64(PCM) 文本**，不是裸 PCM。
> 之前全部 `not_match` 的唯一原因就是 body 形态错误。**现已实测命中**（3 首真实歌曲 100%）。

| 能力 | 可行性 | 置信度 | 关键证据 |
|---|---|---|---|
| **听歌识曲** | ✅ **已接入**（匿名、零签名、零 Cookie） | `已实测命中` | 《我不难过》→ 孙燕姿/未完成；《蓄勢》→ 采风乐坊；《雨爱(翻唱)》→ 原唱杨丞琳 |
| **哼唱识曲** | ✅ **已接入，与听歌共用同一端点**，靠 URL `fpType` 区分 | `已实测命中` | 客户端有独立 H 模式（含专属表情提示串）；`fpType=4` 即 H 通道 |

**实现**：`core/audio/QqRecognizer.kt`（新，含 `Mode.LISTEN/HUMMING`）+ `RecognitionSheet` 模式切换 UI + 三引擎编排。

### 0.0 听歌 / 哼唱 如何区分（源码级，2026-09-30 追订）

客户端**确有独立的哼唱模式**（不是同一模式的别名）：

```java
// BaseRecognizeActivity
public static final int TAB_MUSIC   = 1;     // 听歌识曲 Tab
public static final int TAB_HUMMING = 2;     // 哼唱识曲 Tab
public static final int FROM_HUMMING = 4;

// RecognizeActivity.suffixOfBgTip()  ← 哼唱 Tab 有专属提示文案数组
private static final Integer[] HUMMING_TIP = {
    R.string.recognizer_encore_tip, R.string.recognizer_quite_tip,
    R.string.recognizer_retry_tip,  R.string.recognizer_history_tip };

// RecognizeActivity#startRecognizeInner （两个 Tab 的分流）
if (getSelectedTab() == TAB_HUMMING) {
    Recognizer.a.D2();   // → E2(false) → e3(3) → o = 3
} else {
    Recognizer.a.E2(z);  // → e3(4) → o = 4
}

// Recognizer.C2(int)   o = C2(入参)
// Recognizer.z.a(sessionid, S0(p), i3)  →  URL: fpType = (i3 + 1)
```

| 客户端 Tab | 内部 `o` | URL `fpType` | 最短时长门槛 |
|---|---|---|---|
| `TAB_MUSIC = 1` 听歌识曲 | 4 | **5** | 15s（`I1`: `i2==2 ? 15`）|
| `TAB_HUMMING = 2` 哼唱识曲 | 3 | **4** | 11s（`I1`: `i2==3 ? 11`）|

> ⚠️ **勘误**：URL 参数 `recognizetype` 由 `S0(mIsBackground)` 决定（后台=1 / 前台=2），
> **与「听歌/哼唱」无关**；两个模式靠 `fpType` 区分。（此前文档把它当作区分依据，已修正。）

### 0.1 决定性证据：请求体 = Base64(PCM)

`com/tencent/qqmusic/recognizekt/Recognizer.java`：

| 位置 | 代码 | 含义 |
|---|---|---|
| `k2()` L1741 | `final byte[] encode = Base64.encode(bArr3);` | 把 **8k PCM 分包**做 Base64 |
| `k2()` L1747 | `rxSubscriber.onCompleted(encode)` | 下游拿到的是 **Base64 文本** |
| `l1()` L1820 | `s1(Unit) → recognizer.j2(C2(o), l)` | `l` = 累积的 **Base64 文本**（非 PCM） |
| `l2()` L1887 | `p.f(1, t, bArr, bArr.length)` | 把 `bArr`(Base64) **落盘为分包文件** |
| `p.e(int,long)` | 读取 `recognize_package_{session}_{index}` | 从磁盘读回 **Base64 分包** |
| `o2()` L2002 | `.setContentByte(bArr)` | **body = Base64 文本**（`bArr` 来自 `p.e`） |

修复前（裸 PCM）→ `subcode:-2 not_match`；修复后（Base64）→ `songlist` 命中。

### 0.2 参数与鲁棒性实测矩阵（8kHz/16bit/mono 真音频）

| 维度 | 取值 | 结果 |
|---|---|---|
| body 形态 | 裸 PCM | ❌ `subcode:-2 not_match` |
| body 形态 | **Base64(PCM)** | ✅ **命中**（`is_humming:yes` + 5 条 `songlist`） |
| 采样率 | 8kHz | ✅ 命中 |
| 采样率 | 16kHz | ❌ `subcode:-6`（**必须 8kHz**） |
| 时长 | 3s / 5s | ❌ `subcode:-1` |
| 时长 | 8s / 10s / 15s | ✅ 命中 |
| `fpType` | **4**（哼唱 H 通道） | ✅ 命中；真音频 5 条，翻唱样本精确回原唱 |
| `fpType` | **5**（听歌 F 通道） | ✅ 命中；1~6 条含翻唱/变体版本 |
| `fpType` | 2 / 3 | ⚠️ 旧值，也能命中，但与客户端 Tab 不对应 |
| `fpType` | 6~13 | ❌ `subcode:-2` |
| `recognizetype` | 1 / 2 | ✅ 均命中且**结果一致** → 与听歌/哼唱**无关**（由 `mIsBackground` 决定）|
| 时长 | 3s / 5s | ❌ `subcode:-1` |
| 时长 | 10s+ | ✅ 命中（fp=4/5 实测）|
| 请求头 | **完全不带** | ✅ 仍命中（**零头依赖**） |
| Cookie / 签名 | —— | **不需要** |

### 0.3 实测命中记录（可复现）

| 样本（8k mono，15s） | 结果 |
|---|---|
| 《我不难过》孙燕姿 FLAC @40s | `8136 / 001fsNdn1zuZnA` → **我不难过 / 孙燕姿 / 未完成**（另 4 个版本） |
| 《蓄勢 ～ GEAR UP ～》采风乐坊 MP3 @30s | `202607674` → **蓄勢 ～ GEAR UP ～ / 采风乐坊 / 太鼓达人原声集** |
| 《雨爱(司凤版)》翻唱 FLAC @50s | → **雨爱 / 杨丞琳**（翻唱 → 原唱，5 条候选） |
**哼唱通道验证边界（诚实标注）**：
- ✅ 已验证：`fpType=4`（H 通道）对**真音频**稳定命中，10s+ 可用；
- ✅ 已验证：客户端确有独立哼唱 Tab（`TAB_HUMMING`、专属提示文案、独立 `o=3`），**非臆测**；
- ⚠️ **未**验证：**真人哼唱**（非乐器/非原曲）在 `fpType=4` 的命中率——
  本轮环境无法真实哼唱采样，此项仍属未知，不应被解读为「哼唱已实测可用」。

### 0.3b 哼唱只走 QQ —— 为何酷狗 / 网易云不参与（2026-09-30 追订）

**结论：哼唱模式收敛为 QQ 单引擎**。酷狗与网易云**没有哼唱 / 旋律检索通道**，喂哼唱音频
只会返回空，并行调用既浪费请求、又在结果区给出误导性的「未匹配」。

| 引擎 | 接口 | 是否支持哼唱 | 依据 |
|---|---|---|---|
| QQ 音乐 | `youtu/humming/search?fpType=4` | ✅ | 服务端返回 `is_humming:"yes"`，H 通道专门识别旋律 |
| 酷狗 | `fingerprint.service/v1/music_trackid_mulit` | ❌ | 参数表只有 `dfid/mid/appid/...`，**无任何哼唱/旋律开关**；原型是原曲指纹库（`KgRecognizer.kt`） |
| 网易云 | `api/music/audio/match` | ❌ | 固定 `algorithmCode=shazam_v2`、`duration=6`，为 Shazam 式录音匹配，**无旋律检索**（`AudioRecognizer.kt`） |

**实现**：`RecognizeMode.isQqOnly`（`RecognitionSheet.kt`）在哼唱模式下把酷狗 / 网易云标记为
`EngineState.Skipped`——既不发请求，也不渲染其分组、不计入「全部未匹配」判定与提示文案。

> 备注：服务端 `is_humming` 只是**响应回显**，并非入参开关；模式区分靠 URL `fpType`（见 §0.0）。


### 0.4 响应格式要点

`songlist[].songname / singername / albumname` 是 **Base64 编码的 UTF-8**（`5oiR5LiN6Zq+6L+H` = 我不难过）；
其它可用字段：`songmid`（可直接接 `QqApi.songDetail`）、`albummid`（封面）、`playtime`（秒）、
`score`（匹配度 0~100）、`offset`（命中位置秒）。响应**不含播放地址**，需另行解析。


### 1. 侦察方法与素材（可复现）

```bash
# 官方 Android APK 直链（y.qq.com/download 页面）
#   注意：dldir1v6.qq.com/.../10162781.apk 是 com.tencent.qqmusicpad（车载版）
#   需要带 sign 的 c.y.qq.com 链接才是手机版 com.tencent.qqmusic
curl -L -o qqphone.apk \
  'https://c.y.qq.com/cgi-bin/file_redirect.fcg?bid=dldir&file=ecosfile_plink%2Fmusic_clntupate%2Fandroid%2Fother%2FQQMusic2005000982.apk&sign=...'
# 实测：com.tencent.qqmusic 20.8.5.8，26 个 dex，未加固（可 strings / jadx / aapt2）
/opt/android-aapt2/acs/bin/aapt2 dump badging qqphone.apk   # package: com.tencent.qqmusic 20.8.5.8
bash jadx/bin/jadx --no-res --no-imports -d out classes5.dex classes7.dex classes20.dex classes21.dex
```

- 手机版含中文串「听歌识曲」（`classes7/22/26/3/4/dex`）、「哼唱」（仅 `classes14` 的**元宝聊天**场景，**非识曲**）。
- **手机版 APK 里没有任何「哼唱识曲」的字符串/类**——但见证据 1，服务端**支持** `is_humming`。
  → 说明「哼唱识曲」入口很可能是**下发/AB 实验**（或走「元宝」AI 链路），而非本地写死。

### 2. 证据 1（反编译，源码级）：接口 URL 拼装

`jadx` 反编译 `com/tencent/qqmusiccommon/appconfig/z.java`：

```java
public static final java.lang.String a;      // 附近赋值：a = "c6.y.qq.com"
...
b0 = new com.tencent.qqmusiccommon.appconfig.l(
        "humming.music.qq.com/sound_print_r/upload_record",   // 正式域名
        "c6.y.qq.com/youtu/sound_print_r/upload_record");     // 兜底域名(WebAPI)
...
// 关键方法 a(String sessionid, int recognizetype, int fpType)
return new com.tencent.qqmusiccommon.appconfig.l(
        a + "/youtu/humming/search?sessionid=" + str
          + "&recognizetype=" + i2
          + "&fpType=" + (i3 + 1)).g(-1);
```

- **端点**：`c6.y.qq.com/youtu/humming/search`（对应 `humming.music.qq.com/youtu/...`）
- **参数**：`sessionid`、`recognizetype`、`fpType`（**注意：URL 里 `fpType = 入参 + 1`**）
- 另有：`c6.y.qq.com/youtu/sound_print_r/upload_record`（上传录音/MIDI）

`com/tencent/qqmusic/recognizekt/Recognizer.java`（识曲主流程）：
- 第 76 行：`private static o B = new i4(8000, 16, 2);` → **8kHz / 16bit / 单声道** 录制
- `Recognizer.a(byte[] pcm, int len)`（录音回调）→ `Recognizer.b.l(pcm,len)`（收集）+ 广播
- 第 1981-2002 行 `prepareRequestArgs`（`o2` 方法）——**真实请求构造**：

```java
StringBuilder sb;             // → Cookie
sb.append("uin=").append(uin)          // 未登录 = "0"
  .append("; ct=").append(QQMusicConfig.b())
  .append("; cv=").append(QQMusicConfig.a())
  .append("; recognizetype=").append(S0(p));   // ⚠️ S0(mIsBackground)，非「听歌/哼唱」

// i2 = 内部 fpType（听歌 Tab=4 / 哼唱 Tab=3），URL 里会 +1
RequestArgs req = new RequestArgs(z.a(valueOf, S0(p), i2))
        .setContentByte(b64Bytes)         // ← 请求体 = Base64(8k PCM)
        .addHeader("Cookie", sb.toString())
        .setPriority(3).setCid(999L);
if (user != null) req.addHeader("AUTHST", ...).addHeader("QQUIN", uin);
req.addHeader("uid", ...);
req.addHeader("SOURCE", "spr_qqmusic_android");   // 静态字段 n = "spr_qqmusic_android"
```

`com/tencent/qqmusic/recognizekt/m4.java`（**响应模型**，gson）：

```java
@SerializedName("ret")        int a;
@SerializedName("code")       int b;
@SerializedName("subcode")    int c;
@SerializedName("fpType")     int d;
@SerializedName("session_id") long e;
@SerializedName("is_humming") String f;   // ←←← 哼唱标记！听歌识曲/哼唱共用同一接口
@SerializedName("youtuinfo")  ArrayList<p7> g;
@SerializedName("songlist")   ArrayList<song> h;
@SerializedName("results")    ArrayList<o4> i;
@SerializedName("songtj")     ArrayList<q4> j;
```

### 3. 证据 2（HTTP 实测）：接口真实、匿名可用

> ✅ **修正（二次攻坚）**：下面的矩阵是「裸 PCM」阶段的记录。**裸 PCM 是错误形态**，
> 正确 body 是 **Base64(PCM)**，修正后**三首歌全部命中**（见 §0.1~0.3）。

```bash
# 正确形态：Base64(8kHz/单声道/16bit PCM) 作为 body；无签名、无 Cookie
base64 -w0 pcm8k.s16le > pcm8k.b64
curl -X POST -H 'Content-Type: application/octet-stream' --data-binary @pcm8k.b64 \
  'https://c6.y.qq.com/youtu/humming/search?sessionid=<13位时间戳>&recognizetype=1&fpType=2'

# 真实响应（HTTP 200）：
{"is_humming":"yes","results":[{"id":"8136,100;...","inlier":63,"offset":"42",...}],
 "ret":0,"session_id":"1790776299668","songlist":[{"songid":8136,"songmid":"001fsNdn1zuZnA",
 "songname":"5oiR5LiN6Zq+6L+H",  ...   /* = 我不难过 */ }]}
```

裸 PCM 阶段的失败矩阵（**保留作为对照**，说明形态错误的表现）：

实测矩阵（均为真实《晴天》片段，8kHz 单声道 s16le）：

| 测试 | 结果 |
|---|---|
| 440Hz 正弦 5s | `ret:0 subcode:-2 not_match` |
| 《晴天》30-45s（15s） | `ret:0 subcode:-2 not_match` |
| 采样率 8k/16k/44.1k/48k | 全部 `subcode:-2`（服务端按固定假设处理，不报采样率错）|
| 加 Cookie/gzip/表单 | `ret:-1 code:-1`（**拒绝**）→ 证明**裸 PCM + 空头才是正确形态** |
| fpType 1/2/3/4 × recognizetype 1/2 | `-2 / -4 / -4 / -4` |
| **分块同 session（3×5s）** | `ret:-1`（**不接受流水块**）|
| `youtu/sound_print_r/upload_record` | `ret:0 subcode:0 message:"upload_midi_ok"` ← 见证据 3 |

### 4. 证据 3（已解决）：`upload_record` → `upload_midi_ok`

```
[已解决] 曾推测「客户端把已提取的指纹作为 body 传给 humming/search」。
二次攻坚反编译确证：**body 是 Base64(8k PCM) 分包**，不是指纹、也不是 MIDI。
`upload_record` 是另一条独立链路（服务端抽 MIDI），**识曲主链路不依赖它**。
```

### 5. 证据 4（判定「非自研」）：腾讯 Chirp 音频指纹 SDK

`lib/arm64-v8a/libfingerprint_jni.so` 导出符号（`nm -D`）：

```
T Java_com_tencent_qqmusic_business_userdata_localmatch_fingerprint_FingerPrintExtractor_extract_1native
T _ZN5AFP_D11GetPeakDataEjjPcjPS0_Pj        # AFP_D::GetPeakData
T _ZN5AFP_D13ReleaseBufferEPPc
T _Z15BufferInputSamplesP13tagChirpAudioPsi # tagChirpAudio  ← 腾讯「Chirp」音频指纹
T _Z19BufferOutputSamplesP13tagChirpAudioPsi
```

- **`tagChirpAudio` + `AFP_D`** = 腾讯自研音频指纹库（Chirp / 原 AR 实为腾讯多媒体实验室）。
- `FingerPrintExtractor.extract_native(int,int,byte[],int)` 是 JNI 入口，**但只在 `localmatch`
  （本地歌曲指纹匹配）里被引用**（`grep -rln extract_native` 仅命中该类自身），
  **与在线识曲路径（走原始 PCM）不是同一条链路**。
- 结论：**QQ音乐识曲是腾讯自研（非 ACRCloud/Shazam），但服务端算法不可见**。

### 6. 证据 5：网页端确无识曲入口（排除「Web 端逆向」）

- `https://y.qq.com/act/recognize/index.html`：**实测 200**，但内容是 12.3 版本的**活动宣传页
  「如何玩转听歌识曲」**（提到「耳机内录」「跨 App 识别」），**不是功能页**（无 camera/mic 逻辑）。
- `y.qq.com/n/ryqq/recognize` → 302 → `/n/ryqq_v2/recognize` → **404 兜底页**（已下线）。
- `i.y.qq.com/n3/other/pages/recognize/index.html` → 302 → `y.qq.com/n3/other/...` → 404。
- 主站 JS 包（`Page.chunk.*.js` / `vendor.chunk.*.js`）grep `recogniz` **零命中**。
- Wayback CDX（y.qq.com 域，2 万条 URL）无 `/recognize` 功能页快照。
- **结论**：识曲是**纯客户端（App）能力**，无 Web 端接口。

### 7. 排查记录（试了什么 / 为什么不通）

| 尝试 | 端点 | 结果 | 判定 |
|---|---|---|---|
| GET 探测 | `lyric.music.qq.com|audio_fingerprint_new`、`fcg_local_match_new` | 200 空体 | 真实存在（本地歌词匹配用），**非在线识曲** |
| POST 裸 PCM | `c6.y.qq.com/youtu/humming/search` | `ret:0 not_match` | **接口可用**，未命中 |
| 指纹字节猜测 | 同上（`extract_native` 产物无法离线复现） | — | 需真实 .so 运行，**未验证** |
| 分块上传 | 同 sessionid 多次 POST | `ret:-1` | **不是流水式** |
| gzip / 表单 / Cookie | 同上 | `ret:-1` | 形态错误 |
| `upload_record` | `c6.y.qq.com/youtu/sound_print_r/upload_record` | `upload_midi_ok` | 服务端抽旋律**成功** |

### 8. 替代方案对比（若坚持要「QQ 曲库」的哼唱/识曲）

| 方案 | 识曲 | 哼唱 | 曲库 | 免费额度 | 接入门槛 | 置信度 |
|---|---|---|---|---|---|---|
| **ACRCloud** | ✅ 1.5 亿+ | ✅ 100 万+（中英日法西葡） | 自有大库 | **14 天免费试用，全 API，无需信用卡**；后续按次计费 | SDK 完善（Android 原生）；商用需授权 | `官方文档已核` |
| 腾讯云·媒体处理 MPS「音乐识别」 | ✅ | 未确认 | — | 需开通 MPS + 服务角色授权（付费） | 云 API 签名（secretId/Key） | `官方文档已核` |
| 腾讯多媒体实验室 api.mediax | — | — | 3.6 万 | 付费 | 异步任务制 | §7.2 旧结论（本轮 `multimedia.tencent.com/api/7-...` 已 **404**，站点无该页）|
| 讯飞 | ✅ | ✅ | — | 付费 | 云 API | `搜索线索`（未逐一实测）|
| **酷狗**（项目已用） | ✅ | ❌ | 酷狗库 | 免费（无 key） | 已接入 | `已实测` |

> ACRCloud 哼唱文档实测要点（`docs.acrcloud.cn/metadata/humming.html`）：
> - 返回字段 `metadata.humming[]`，含 `title/artists/album/score(置信度)/play_offset_ms`；
> - **官方明确「哼唱/抢唱/接歌功能暂未在平台公开使用，需邮件 support@acrcloud.com 开通权限」**；
> - 样例响应：识别《最长的电影》（周杰伦）`score:0.96`。

### 9. 后续路径

1. **已完成**：QQ 音乐 听歌/哼唱 双模式接入 DPmusic（`QqRecognizer.Mode` + `RecognitionSheet` 顶部切换），无需签名/Cookie。
2. **待验证（需真人）**：真人哼唱在 `fpType=4` 的命中率（本轮无真实哼唱样本，未测）。
3. **可选增强**：结果里 `songmid` 可直接接 `QqApi.songDetail` 补全详情；
   但 `songlist` 已含 `albummid/playtime/score`，通常无需二次请求。
4. **不建议**：ACRCloud Humming（QQ 已够用且免费；ACRCloud 哼唱需邮件开通 + 按次计费）。

### 10. 曾有的错误结论（已全部推翻）

- ❌ 「QQ音乐无任何识曲接口」→ **否**，接口真实存在（修正 §7.2）。
- ❌ 「裸传 8k PCM 就能命中」→ **否**，裸 PCM 是错误形态；**必须 Base64(PCM)**。
- ❌ 「匿名请求被服务端降级」→ **否**，根因就是 body 形态错误，与匿名无关。
- ❌ 「哼唱识曲是本地算法」→ **否**，`is_humming` 是**服务端响应字段**。
- ❌ 「`recognizetype` 区分听歌/哼唱」→ **否**，它由 `mIsBackground` 决定；由 **`fpType`** 区分（4=哼唱 / 5=听歌）。
- ⚠️ 「服务端对长度敏感」→ **部分正确**：`< 5s` 返回 `subcode:-1`；源码门槛为 听歌 15s / 哼唱 11s。

### 11. 本轮交付物

| 文件 | 说明 |
|---|---|
| `app/src/main/kotlin/com/dpmusic/app/core/audio/QqRecognizer.kt` | **新**：QQ 识曲封装（`Mode.LISTEN/HUMMING`，Base64(PCM) 直传） |
| `app/src/main/kotlin/com/dpmusic/app/ui/components/RecognitionSheet.kt` | `RecognizeMode` 状态 + `ModeSwitch` 切换 UI + 时长/文案随模式联动 + 三引擎并行 |
| `docs/AUDIO-RECOGNIZE-RESEARCH.md` | §7.2 修正 + §7.7 补齐模式分流证据、勘误 `recognizetype`、哼唱验证边界 |
| `docs/功能总览.txt` | 第四章更新为「听歌/哼歌 切换 + 三引擎」 |

**验收**：`bash tools/build-and-install.sh` → BUILD SUCCESSFUL + 装机启动；接口侧 3 首真歌 100% 命中。
