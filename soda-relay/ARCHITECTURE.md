# 链路文档

从 Android 设备的"播放"动作，到音频字节进入播放器。每一步都注明**实际实现的代码位置**与**实测数值**；凡是推断而非实测的，单独标注。

---

## 1. 需求与约束

**目标**：让 DPmusic（Android）播放汽水音乐的曲目，包括会员曲目，且能完整播放。

**约束**：

| 约束 | 后果 |
|---|---|
| 汽水没有开放的播放 API | 只能借用**已登录的官方客户端**自己解析 |
| 客户端只跑 Windows | 需要一台常开的家用 Windows 电脑 |
| 家庭宽带无公网 IP | 需要一台公网中转 |
| 手机需要直连音频（省中转带宽） | 中转只传 JSON，音频走 CDN 直连 |
| 音频是 CENC 加密的 | 设备必须拿到密钥并本地解密 |

**拓扑**（两层，中转不碰音频）:

```
┌─────────┐  HTTPS/HTTP   ┌──────────┐   WebSocket   ┌──────────────┐
│ Android │ ───────────▶  │  中转服务器 │ ◀───────────  │ 家用 Windows │
│ DPmusic │   (JSON)      │  1C1G     │   (常连)      │   客户端      │
└────┬────┘               └──────────┘               └──────────────┘
     │                                                       │
     │           音频字节（CDN 直连，可 Range）                 │
     └───────────────────────────────────────────────────────┘
                     https://*.douyinvod.com/...
```

关键设计：**中转只做"请求转发 + 音频代理回退"**，不参与解析。解析发生在家里电脑，因为那里才有登录态。

---

## 2. 组件

| 组件 | 文件 | 部署位置 | 依赖 |
|---|---|---|---|
| 中转（relay） | `relay/relay.py` | 公网服务器 `/opt/soda-relay/` | **仅标准库**（含手写 RFC6455 WebSocket） |
| 家用守护进程（daemon） | `pc/soda_daemon.py` | 家用 Windows | `websockets` |
| 阶梯提取 | `pc/sodalib/soda_fetch.py` | 同上 | `frida`（附着客户端内存） |
| 安全模式守卫 | `pc/sodalib/soda_guard.py` | 同上 | `pywin32`（窗口操作） |
| 播放暂停 | `pc/soda_pause.py` + `pc/smtc.ps1` | 同上 | PowerShell（Windows 自带） |
| CENC 解密参考实现 | `pc/cenc_decrypt.py` | 仅参考，不参与运行 | `cryptography`, `imageio-ffmpeg` |

> 中转**零依赖**是刻意的：目标服务器（Debian 12, 1C1G）没有 pip，装不了包。

---

## 3. 调用链

### 3.1 一次播放的完整时序

```
Android               中转                  家用 daemon            汽水客户端
   │                   │                       │                     │
   │ 1. GET /v1/play   │                       │                     │
   │──────────────────▶│                       │                     │
   │                   │ 2. WS {"type":"play"}  │                     │
   │                   │──────────────────────▶│                     │
   │                   │                       │ 3. 需要新链时：       │
   │                   │                       │    luna:///playing   │
   │                   │                       │────────────────────▶│
   │                   │                       │                     │ 4. 用自身
   │                   │                       │                     │    登录态解析
   │                   │                       │ 5. 从进程内存读阶梯    │
   │                   │                       │◀────────────────────│
   │                   │                       │ 6. 时长校验           │
   │                   │                       │ 7. 暂停客户端          │
   │                   │ 8. WS {"type":"result"}│                     │
   │                   │◀──────────────────────│                     │
   │ 9. JSON(url, play_auth, ...)              │                     │
   │◀──────────────────│                       │                     │
   │                                                               │
   │ 10. 直连 CDN 拉字节 ──────────────────────────────────────────▶│
   │ 11. 本地解密 → 播放器                                          │
```

### 3.2 为什么必须用客户端

汽水的接口是**fail-closed** 的：客户端 3.8.0 用 Electron 36 / Chromium 136，请求带签名；服务器还会做版本风控。匿名调用得到的响应是 `HTTP 200 + Content-Type: application/json + Content-Length: 0` —— 看着成功，实际是空的。

所以链路必须是"**让官方客户端自己去请求**"，我们再从它的内存里取出结果。这也是唯一能拿到会员曲目完整时长的方式（实测匿名接口码率上限约 `br=251`，且永远拿不到 lossless）。

---

## 4. 接口

### 4.1 中转 HTTP 接口

基址 `http://<HOST>:<HTTP_PORT>`，默认端口 `8080`（`BIND`、`HTTP_PORT`、`AGENT_PORT` 均为环境变量）。

| 路由 | 用途 |
|---|---|
| `GET /v1/health` | 免鉴权。返回 `{"ok":true,"agent_connected":bool,...}` |
| `GET /v1/play?track_id=&quality=` | 取播放链接与解密密钥 |
| `GET /v1/audio?track_id=&quality=` | 代理音频字节（回退路径，支持 `Range`） |

**鉴权**（`/v1/health` 除外）：

```
X-Ts:  <unix 秒>
X-Sig: HMAC_SHA256(DEVICE_SECRET, "device.<X-Ts>.<path>")  的 hex
```

`path` 是**不含查询串**的路径（如 `/v1/play`）。时间戳窗口 ±300 秒；比较用 `hmac.compare_digest`（常量时间）。

**`quality` 白名单**（`ALLOWED_QUALITIES`）：

| 档位 | 编码 | 码率 | 单曲约 |
|---|---|---|---|
| `lossless` | FLAC | ~1004 kbps | 28 MB |
| `hi_res`（默认） | AAC | ~325 kbps | 9 MB |
| `spatial` | AAC | ~325 kbps | 9 MB |
| `highest` | AAC | ~260 kbps | 7 MB |
| `higher` | AAC | ~132 kbps | 4 MB |
| `medium` | AAC | ~68 kbps | 2 MB |

**`/v1/play` 成功响应**：

```json
{
  "ok": true,
  "url": "https://...douyinvod.com/.../?br=...",
  "quality": "hi_res",
  "codec": "aac",
  "duration_s": 228.0,
  "catalogue_s": 228.0,
  "is_full_length": true,
  "play_auth": "<base64，CENC 密钥 blob>",
  "encrypted": true,
  "encryption": "cenc-aes-ctr",
  "cache": "hit",
  "took_ms": 17700,
  "expires_hint_s": 43200
}
```

| 字段 | 含义 |
|---|---|
| `is_full_length` | **必须为 `true` 才播放**。表示解码时长与官方时长一致，即完整歌曲而非试听片段 |
| `play_auth` | CENC 密钥 blob，**与同一响应的 `url` 严格配对** |
| `encrypted` / `encryption` | `true` / `"cenc-aes-ctr"` |
| `cache` | `hit`（快，~0–2 s）/ `miss`（现场解析，~10–33 s） |
| `expires_hint_s` | 建议缓存秒数；实测链接存活 > 18.6 小时 |

**`/v1/audio` 响应头**（不破坏 `Range` 语义，密钥放头部）：

| 头 | 值 |
|---|---|
| `X-Encrypted` | `1` / `0` |
| `X-Encryption` | `cenc-aes-ctr` |
| `X-Play-Auth` | 与该 url 配对的密钥 blob |

**错误码**：

| HTTP | `code` | 含义 |
|---|---|---|
| 400 | `bad_request` | 参数错误或 `quality` 不在白名单 |
| 401 | `unauthorized` | 签名或时间戳不符 |
| 502 | `no_tier` | 本次没解析出档位（可重试一次） |
| 503 | `agent_unavailable` | 家用电脑离线 |

`/v1/health` 与所有错误响应**都不含 `play_auth`**。

### 4.2 中转↔daemon 的 WebSocket

路径 `/agent`。中转侧是**单槽位**：后连上的 daemon 会顶掉前一个，所以家用电脑只能跑一个 daemon 实例（`pc/soda_daemon.py` 用回环端口 `47653` 做了单实例锁）。

握手（daemon → 中转）：

```json
{"type":"hello","ver":1,"node":"home-pc","ts":<unix>,
 "sig":HMAC_SHA256(SODA_SHARED_SECRET,"hello.<ts>"),
 "capabilities":{"qualities":[...],"lossless":true,"proxy_audio":true,"cache_ttl_s":43200}}
```

请求（中转 → daemon）与响应：

```json
{"type":"play","id":<int>,"track_id":"...","quality":"hi_res","allow_restart":true}
{"type":"result","id":<int>,"ok":true,"url":"...","play_auth":"...","duration_s":...,...}
```

另有 `{"type":"ping"}` → `{"type":"pong"}`。

### 4.3 音频加密（CENC）

**所有档位都是 CENC AES-CTR 加密**（`stsd` 为 `enca`，含 `senc`），不解析就无声。

**密钥派生**（`pc/cenc_decrypt.py: key_from_spade_a()`）：

```
b          = base64_decode(play_auth)
paddingLen = (b[0] ^ b[1] ^ b[2]) - 48
inner      = b[1 : len(b) - paddingLen]
buff       = [0xFA, 0x55] + inner
out[i]     = (inner[i] ^ buff[i]) - popcount(i) - 21      # <0 时循环 +255，取低 8 位
skip       = base36(out[0])                                # '0'-'9'→0-9, 'a'-'z'→10-35
end        = 1 + (len(b) - paddingLen - 2) - skip
key        = hex_decode(out[1 : end])                      # 16 字节
```

**样本解密**（`pc/cenc_decrypt.py: decrypt()`）：

```
stsz = 每个 sample 的字节长度
senc = 每个 sample 的 IV（本流 8 字节；tenc 内为 01 08）
逐 sample: AES-CTR(key, iv) XOR 密文           # senc_flags=0，无 subsample
最后：stsd 的 'enca' 改回 frma 的真实编码
```

**三条必须遵守的规则**：

1. **`play_auth` 与 `url` 必须来自同一次响应。** 每个档位有各自的 `kid` 和密钥，错配会解出噪声。
2. **不要用音量判断解密是否成功**，要看解码错误数。实测错配密钥时 `mean_volume` 仍显示正常值。
3. **relabel 必须用真实 `frma`**：

   | 档位 | `frma` |
   |---|---|
   | `medium`/`higher`/`highest`/`hi_res`/`spatial` | `mp4a`（AAC） |
   | `lossless` | **`fLaC`（FLAC）** |

   写死 `mp4a` 会让 FLAC 流被当作 AAC，产生数千个解码错误。

**定位 `frma` 的坑**：`enca` 是 sample entry，其音频头长度是 **28 字节**（QuickTime SoundDescription v0），不是 ISO 的 20 字节。差 8 字节就永远找不到。**建议按 `frma` 签名扫描**，不要走 box 树。

**实测验证**（6 档，用 ffmpeg 裁判）：

| 档位 | frma | 字节数 | 加密文件 rc | 解密后 rc | 解密后错误 | 3 MB 前缀 |
|---|---|---|---|---|---|---|
| `lossless` | fLaC | 28,665,504 | 69 | 0 | 0 | 可播 |
| `hi_res` | mp4a | 9,154,134 | −22 | 0 | 0 | 可播 |
| `spatial` | mp4a | 9,241,517 | −22 | 0 | 0 | 可播 |
| `highest` | mp4a | 7,416,908 | −22 | 0 | 0 | 可播 |
| `higher` | mp4a | 3,767,846 | −22 | 0 | 0 | 可播 |
| `medium` | mp4a | 1,943,824 | −22 | 0 | 0 | 可播 |

`rc != 0` 表示加密文件确实无法直接解码；`rc = 0` 且错误为 0 表示解密完全正确。**前缀可播** ⇒ 可以边下边解流式播放，不必等整首下载完。

---

## 5. 家用电脑侧

### 5.1 解析流程

```
1. 检查客户端健康；不健康则冷启动
2. luna:///playing?track_id=<id>&media_type=track     # 三个斜杠，走 second-instance
3. 等约 10 秒（客户端写阶梯）
4. Frida 附着主进程（含 main.node 的那个），扫内存取完整阶梯
   每个档位形如：
     "main_url": "...", "video_meta": {"quality":"hi_res",...},
     "encrypt_info": {"encrypt":true,"kid":"...","spade_a":"...","encryption_method":"cenc-aes-ctr"}
5. 时长校验：解码时长必须等于官方时长（±2.5 s），否则丢给下一候选
6. 暂停客户端（见 5.3）
```

**为什么"时长校验"是唯一判据**：客户端会预加载后续曲目，内存里同时存在多首曲目的阶梯。只有内容本身（解码时长 vs 官方时长）能区分归属。

**取链耗时**（实测）：冷启动基线 21.4 s/首（17.4–29.2）；复用客户端约 9–10 s/首；自适应策略 12.7–16.2 s/首。

### 5.2 安全模式守卫（必须保留）

客户端在 **10 分钟内启动 4 次**会弹"异常诊断"对话框，按钮为 `['进入安全模式', '忽略']`，**`defaultId` 是 0，即"进入安全模式"** —— 它执行 `cleanDirectorySync(userData)` + 重启，会**清掉登录态**。

守卫（`pc/sodalib/soda_guard.py`）在每次启动客户端前清理 `%TEMP%\SodaMusic_Launch_Records` 里的历史记录，把计数压在阈值以下；对话框若已出现，**按句柄点"忽略"，绝不发送回车**。

> 这条是硬性要求。批量取链会频繁启动客户端，一旦触发且按下默认按钮，登录态就没了，需要人工重新登录。

### 5.3 取链后自动暂停

**需求**：取到链接后不想让家用电脑外放音乐。

**实现**：用 Windows SMTC（`Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager`）**按应用**暂停。客户端会注册自己的媒体会话（名称为"汽水音乐"），可单独暂停。

**为什么不虚拟媒体键**：媒体键会送给"当前活跃"的会话，可能误暂停用户其他播放器。SMTC 能按 `SourceAppUserModelId` 精确指定。

**实测结论**（这些是采用该方案的前提）：

| 前提 | 实验 | 结果 |
|---|---|---|
| 暂停不破坏已提取的阶梯 | 暂停后重新提取 | 5 档 url **一字不变**（+5s、+10s） |
| 暂停不杀死客户端 | 403 秒轮询 | 7 进程不变，状态稳定 `Paused` |
| 暂停后能取下一首 | 暂停 → 新 deeplink → 提取 | 5 档全部成功 |
| 定向、不误伤其他应用 | SMTC 按会话名匹配 | 仅命中目标会话 |

**时序约束**：暂停必须在**提取成功之后**。客户端要真正开始播放才会把阶梯写进内存，提前暂停会拿不到阶梯。所以链路是：

```
fire deeplink → 播放中（可能外放约 10 s）→ 提取阶梯 → 暂停
```

**已知副作用**：那约 10 秒仍会出声。如需完全静音，可在播放前把**客户端自身音量**调 0（不影响系统音量）—— 本项目未实现。

**实现细节**：`pc/soda_pause.py` 调用 `pc/smtc.ps1`。**必须是 .ps1 文件，不能用内联 `-Command`** —— 会话名是中文，内联传递时非 ASCII 会被破坏，导致匹配失败且静默无动作。现在用 hex 传入、hex 返回。另外状态**不是同步更新**的：控制调用返回成功后再读一次仍是旧值，必须轮询确认。

**关闭开关**：环境变量 `SODA_NO_PAUSE=1`。失败不影响取链（暂停失败只记日志）。

### 5.4 客户端配置

`pc/sodalib/soda_fetch.py` 通过环境变量定位客户端：

| 变量 | 默认 |
|---|---|
| `SODA_HOME` | `%LOCALAPPDATA%\Programs\Soda Music\3.8.0` |
| `SODA_EXE` | `%SODA_HOME%\SodaMusic.exe` |
| `SODA_CACHE` | `%APPDATA%\SodaMusic\LunaCacheV2` |

**必须使用官方安装目录。** 解包后的副本只起 2 个进程且窗口异常。

---

## 6. 部署

### 6.1 中转（Debian 12，1C1G）

```bash
# 1. 放代码
mkdir -p /opt/soda-relay && cp relay/relay.py /opt/soda-relay/

# 2. 配置（生成两个独立密钥）
mkdir -p /etc/soda-relay
python3 -c "import secrets;print('SODA_SHARED_SECRET='+secrets.token_hex(32))" > /etc/soda-relay/relay.env
python3 -c "import secrets;print('DEVICE_SECRET='+secrets.token_hex(32))" >> /etc/soda-relay/relay.env
chmod 600 /etc/soda-relay/relay.env

# 3. systemd
cp relay/soda-relay.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now soda-relay

# 4. 验证
curl -s http://127.0.0.1:8080/v1/health
```

> **`SODA_SHARED_SECRET` 与 `DEVICE_SECRET` 必须是两个不同的值**，且分发给不同角色：前者只给家用电脑，后者只给设备。

**资源占用实测**：RSS ~22 MB，2 个监听端口（`8080` 数据、`8765` agent）。

### 6.2 家用电脑（Windows）

```cmd
pip install -r pc\requirements.txt
pip install frida pywin32          REM 客户端操作所需，非 pip 冷启动环境可省去调试功能

REM 配置
set RELAY_AGENT_URL=ws://<服务器>:8765/agent
set SODA_SHARED_SECRET=<与服务器一致>

pc\run_daemon.bat
```

前置条件：Windows + **已登录**的汽水音乐客户端，且保持开机。

### 6.3 防火墙

| 端口 | 用途 | 建议 |
|---|---|---|
| `8080` | 设备 HTTP | 对设备网段开放 |
| `8765` | daemon WebSocket | **仅允许家用电脑出口连接**；不要暴露 |

> 若 daemon 从公网连 `8765`，该端口必须可达。生产环境建议加 TLS 与源 IP 限制。

---

## 7. 实测性能与限制

| 项 | 实测值 |
|---|---|
| 首次取链（冷启动） | 21.4 s（17.4–29.2） |
| 复用客户端取链 | 9–10 s |
| 自适应策略 | 12.7–16.2 s |
| 缓存命中（12 h 内同曲同档位） | ~0–2 s |
| 链接有效期 | **> 18.6 小时**（实测仍返回 206） |
| CDN 是否绑定 IP | **否**（服务器侧 curl/python 均 206） |
| `Range` 支持 | **完整**（`0-1023`、`1000000-1000255`、`5000000-`、`-1024` 均 206） |
| 拉取速度 | 30.1 MB/s（241 Mbps） |
| 并发 | **单曲串行**。只有一台客户端，同时只能解析一首 |

**已知问题**：

1. **`/v1/audio` 偶发 502（约 2.5%）**，响应体为空。中继侧日志显示它成功写了 502，服务器回环 40/40 干净，公网路径 38/40 —— 故障在网络路径（IDC IP + 非标准端口 + 明文 HTTP）。缓解措施：响应单次写出、CDN 失败快速超时（最大延迟从 47.5 s 降到 7.9 s）。**设备端必须实现重试**。根治需要 443 + TLS。
2. **明文 HTTP**。上 HTTPS 需要一个域名。
3. **客户端被反复启动会影响稳定性**；`MAX_REUSE_BEFORE_RESTART = 3` 是实测的拐点（连续切 3–4 首后开始退化）。

---

## 8. 合规声明

本项目**仅用于个人学习研究**：

- 不破解、不绕过任何付费机制 —— 会员曲目之所以能播，是因为**用户自己在官方客户端里已经登录并付费**，代码只是读取该客户端自己解析出的结果。
- 不伪造任何凭据，不实现登录流程。
- 不在仓库中保存任何账号信息或密钥。
- 音频的解密密钥来自客户端自身返回的数据，用于在用户自己拥有的设备上播放。

**请勿用于商业用途或分发受版权保护的内容。** 使用者需自行承担遵守当地法律与服务条款的责任。
