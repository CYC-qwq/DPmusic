# 三平台逐字歌词研究报告（网易云 YRC / QQ QRC / 酷狗 KRC）

> 研究时间：2026-09
> 状态：三平台**全部完成「获取 → 解密 → 解析」全链路实测**
> 结论：三平台均有可用的逐字（卡拉OK级）歌词方案，DPmusic 可用**纯 Kotlin** 实现（无第三方依赖）

## 一、结论速览

| 平台 | 逐字格式 | 获取方式 | 加密 | 实测 |
|---|---|---|---|---|
| 网易云音乐 | **YRC** | `api/song/lyric/v1`（**普通接口即可，无需加密**） | 无（明文返回） | ✅ 乌梅子酱 / 孤勇者 / 诀爱·尽 |
| QQ音乐 | **QRC** | `lyric_download.fcg` / `musicu.fcg` | 自定义 DES 三重 EDE + zlib | ✅ 晴天（含翻译/罗马音：アイドル） |
| 酷狗音乐 | **KRC** | `krcs` 搜索 + `lyrics.kugou.com` 下载 | `krc1` + XOR + zlib | ✅ 晴天 |

**三格式对比（字级时间语法各不相同）**：

| 维度 | 网易云 YRC | QQ QRC | 酷狗 KRC |
|---|---|---|---|
| 行 | `[行开始,行时长]` | `[行开始,行时长]` | `[行开始,行时长]` |
| 字 | `(绝对开始,时长,0)字` | `字(绝对开始,时长)` | `<相对行首偏移,时长,0>字` |
| 时间基准 | 绝对 | 绝对 | **相对行首** |
| 翻译/罗马音 | `ytlyric`/`yromalrc`（覆盖极少） | `trans`/`roma`（覆盖好，含 kana 注音） | 内置 `[language:]` 段 |
| 实测覆盖率 | 部分歌曲（3/11） | 较广 | 较广 |

## 二、网易云 YRC

### 2.1 获取（普通接口，已实测）
```
GET https://music.163.com/api/song/lyric/v1?id={songId}&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0
Header: Referer: https://music.163.com/
```
- 或老接口：`GET /api/song/lyric?id={id}&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1`（同样返回 yrc）
- 响应字段：`yrc.lyric`（逐字原文）、`ytlyric.lyric`（逐字翻译）、`yromalrc.lyric`（逐字罗马音）、`lrc`（行级，回退用）
- ⚠️ **不需要 EAPI 加密**（实测对比：普通接口与 eapi 结果一致；eapi 细节见附录 A，备用）

### 2.2 格式样例（乌梅子酱）
```
{"t":0,"c":[...]}  ← 头部元数据为 JSON 行（解析时跳过）
[25580,3200](25580,360,0)背(25940,360,0)靠(26300,290,0)在(26590,670,0)树(27260,380,0)枝(27640,1140,0)上
```
- 行首：`[行开始ms,行时长ms]`
- 每字：`(绝对开始ms,时长ms,0)文字`（第三位恒为 0）
- 解析：`^\[(\d+),(\d+)\]` + `\((\d+),(\d+),\d+\)([^(]*)`

### 2.3 覆盖率注意
实测 11 首：乌梅子酱 / 孤勇者 / 诀爱（LIVE）**有** YRC；晴天 / 起风了 / 爱如火 / 向云端 / 雪 Distance / 罗刹海市 / 悬溺 / 小美满 / 跳楼机 / アイドル / Ditto **无** → **必须有行级 LRC 回退**。

## 三、QQ音乐 QRC

### 3.1 获取
**A. 旧接口（需 songid）**
```
GET https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg?version=15&miniversion=82&lrctype=4&musicid={songid}
Header: Referer: https://y.qq.com/portal/player.html
```
返回 XML：`<content>` 主歌词（hex 密文）、`<contentts>` 翻译、`<contentroma>` 罗马音

**B. musicu 接口（推荐，需 songMID）**
```
POST https://u.y.qq.com/cgi-bin/musicu.fcg
{"comm":{"ct":11,"cv":"1003006","v":"1003006","uin":"0"},
 "req":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo",
   "param":{"songMID":"{mid}","songID":0,"format":"json","qrc":1,"trans":1,"roma":1,"crypt":1,"lrc_t":0,"type":0}}}
```
响应 `req.data.lyric / trans / roma`（均为 hex 密文）

### 3.2 解密（自定义 DES 三重 EDE + zlib）
```
hex → bytes
 → 自定义DES解密（KEY1 = "!@#)(NHL"）
 → 自定义DES加密（KEY2 = "123ZXC!@"）
 → 自定义DES解密（KEY3 = "!@#)(*$%"）
 → zlib 解压 → 明文
```
- ⚠️ **不是标准 DES**！密钥为三个字符串的前 8 字节；算法是位序变体（已实测：标准 DES/3DES 直接解会得到乱码）
- 参考实现：`TLittlePrince/qrcDecrypt`（Python）、`WXRIW/QQMusicDecoder`（C#）、lx-music-api-server `pyqdes`（C++）

### 3.3 格式样例（晴天，解密后为 XML 包裹）
```
<QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[ti:晴天]
[2250,2250]词(2250,450)：(2700,450)周(3150,450)杰(3600,450)伦(4050,450)
```
- 行首：`[行开始ms,行时长ms]`；每字：`文字(绝对开始ms,时长ms)`
- trans/roma 同格式（アイドル 实测：主歌词 15.3KB / 翻译 4.6KB / 罗马音 14.2KB，含 `[kana:]` 注音）

## 四、酷狗 KRC

### 4.1 获取
```
① 搜索：
GET https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword={关键词}&duration={时长ms}&hash=
→ candidates[].{id, accesskey}
② 下载：
GET https://lyrics.kugou.com/download?ver=1&client=pc&id={id}&accesskey={key}&fmt=krc&charset=utf8
→ {"content":"<base64>"}
```

### 4.2 解密（XOR + zlib）
```
base64 解码 → 校验前 4 字节 "krc1"
→ 剩余字节 XOR（16 字节密钥循环）
→ zlib 解压 → 明文
密钥（hex）：40 47 61 77 5E 32 74 47 51 36 31 2D CE D2 6E 69
```

### 4.3 格式样例（晴天）
```
[offset:0]
[0,2045]<0,255,0>周<255,256,0>杰<511,256,0>伦<767,255,0> <1022,256,0>-...
[2045,1279]<0,256,0>词<256,256,0>：<512,256,0>周<768,255,0>杰...
```
- 行首：`[行开始ms,行时长ms]`；每字：`<相对行首偏移ms,时长ms,0>文字`
- 部分文件含 `[language:xxx]` 翻译段

## 五、DPmusic 集成可行性

**全部可用纯 Kotlin 实现（无第三方依赖）**：

| 平台 | 要点 | 难度 |
|---|---|---|
| 网易云 | 普通 HTTP + 正则解析（现有 `WyApi`/`Http` 层直接复用） | ★ |
| 酷狗 | Base64 + XOR + `java.util.zip.Inflater` | ★ |
| QQ | **自定义 DES 位运算直译**（约 150 行，纯整数/位操作，从 C#/Python 参考实现直译）+ `Inflater` | ★★★ |

**统一数据模型建议**：
```kotlin
data class LyricWord(val text: String, val startMs: Long, val durationMs: Long)
data class LyricLine(val startMs: Long, val durationMs: Long, val words: List<LyricWord>)
```
- 渲染：卡拉OK式逐字高亮（`currentMs ∈ [word.startMs, word.startMs + duration)` 的字高亮）
- 回退：无逐字时用现有行级 LRC

## 六、风险
- 均为非公开接口，存在变更/风控可能
- 网易云 YRC 覆盖有限（老歌/外文歌常无）→ 行级回退必须
- QQ 自定义 DES 移植必须逐位照搬参考实现（勿用标准 DES 替代）
- 移动端注意 Referer / UA 头

## 七、落地记录（DPmusic 实现）

> 状态：三平台逐字歌词已全部落地——**保留行级 LRC 回退 + 设置开关控制渲染**。

**新增文件（`core/lyric/`）**

| 文件 | 职责 |
|---|---|
| `QrcDecoder.kt` | QQ 自定义 DES 三重 EDE 解密 + zlib（常量与全部位运算方法已与验证版 Java 参考实现逐项比对通过） |
| `YrcParser.kt` | 网易云 YRC 解析（字块 `(绝对开始,时长,0)文字`；JSON 元数据行自动跳过） |
| `QrcParser.kt` | QQ QRC 解析（字块 `文字(绝对开始,时长)`；XML `LyricContent` 提取；翻译按 ±400ms 合并） |
| `KrcParser.kt` | 酷狗 KRC 解密（`krc1` + 16 字节 XOR + zlib）与解析（字块 `<相对偏移,时长,0>文字` → 绝对时间；`[language:]` 段按行号对齐翻译） |

**数据模型**：`LyricWord(text, startMs, durationMs)`；`LyricLine.words`（默认空）；`SongLyrics.hasWordTiming`——全部带默认值，完全向后兼容。

**接口链（三平台统一「逐字优先 → 行级回退」）**

| 平台 | 逐字获取 | 回退 |
|---|---|---|
| 网易云 | `api/song/lyric/v1`（`yv=1&ytv=1&yrv=1`，响应含 `yrc`） | `lrc` + `tlyric` |
| QQ | `musicu.fcg GetPlayLyricInfo`（hex 密文 → `QrcDecoder` → QRC；`songID:0` 可省略） | `fcg_query_lyric_new.fcg` |
| 酷狗 | `krcs.kugou.com/search`（**关键词用「歌名-歌手」，空格会导致 0 结果**）→ `lyrics.kugou.com/download?fmt=krc` | 同候选 id 下载 `fmt=lrc` |

**渲染（`LyricsView.kt`）**
- 设置开关：`AppSettings.verbatimLyric`（默认开；设置页「歌词」卡片可关）。
- 开关开 且 当前行有逐字数据：FlowRow 按字排布（自动换行居中）；已唱 = primary，未唱 = onSurfaceVariant，**正在唱 = 左→右渐变填充**（`Brush.horizontalGradient`，填充比例 `(pos - start) / duration`）。
- 正在唱的字轻微弹起（scale 1.08 阻尼弹簧）。
- 播放进度 ticker 为 500ms → 用 `animateFloatAsState + tween(500ms, Linear)` 平滑插值，填充连续无跳步。
- 无逐字数据 / 开关关闭：完全保持原有整行渲染（颜色 / 缩放 / 透明度三重阻尼过渡）。

## 附录 A：网易云 EAPI 备用方案（已验证）
- 接口：`POST https://interface3.music.163.com/eapi/song/lyric/v1`
- 加密：`digest = md5("nobody" + path + "use" + json + "md5forencrypt")`；
  `data = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest`；
  `params = hexUpper(AES-128-ECB(data, key="e82ckenh8dichen8"))`
- header 字段同步进 Cookie；`path = /api/song/lyric/v1`
- 实测：与普通接口返回一致（含 yrc）；仅当普通接口被限制时启用

## 附录 B：实测存档（/tmp/lyric）
- `yrc_1997438791.json` / `yrc_1901371647.json`（网易云 YRC 原始响应）
- `qq_qrc_decrypted.txt`（QQ 晴天 QRC 明文）
- `qq2_lyric/trans/roma_hex.txt` + 解密结果（アイドル 三件套）
- `kg_krc_decrypted.txt`（酷狗 KRC 明文）
