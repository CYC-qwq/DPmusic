# soda-relay

让 Android 音乐播放器（DPmusic）播放汽水音乐曲目的**家用机取链 + 公网中转**方案。

汽水音乐没有公开播放 API。本项目借用一个**已登录的官方 Windows 客户端**去解析曲目，从中取出可直接下载的 CDN 直链与 CENC 解密密钥，再通过一台公网中转交给手机。

> **仅用于个人学习研究。** 会员曲目能播是因为用户自己已在官方客户端登录并付费，代码只读取该客户端自己解析出的结果，不破解、不绕过任何付费机制。详见 [ARCHITECTURE.md §8](ARCHITECTURE.md)。

---

## 它是怎么工作的

```
┌─────────┐   HTTP(JSON)   ┌──────────┐   WebSocket   ┌──────────────┐
│ Android │ ─────────────▶ │  公网中转  │ ◀──────────── │ 家用 Windows │
│ DPmusic │                │  1C1G     │   常连接       │  汽水客户端   │
└────┬────┘                └──────────┘               └──────────────┘
     │                                                       │
     │        音频字节：CDN 直连（支持 Range 拖动）             │
     └───────────────────────────────────────────────────────┘
```

1. 手机请求中转 → 中转通过常连的 WebSocket 转发给家用电脑
2. 家用电脑用 `luna:///playing?track_id=...` 让客户端播放该曲
3. 从客户端进程内存读出**完整音质阶梯**（每档的 CDN 直链 + CENC 密钥）
4. 校验时长与官方一致（确保是完整歌曲，而非试听片段）
5. 暂停客户端（避免家用电脑外放），把链接和密钥返回给手机
6. 手机**直连 CDN** 下载，本地解密后播放 —— 中转不承担音频带宽

**中转只传 JSON，不碰音频**，所以 1 核 1G 也够用。

---

## 特性

| | |
|---|---|
| **六档音质** | `lossless`(FLAC ~1004kbps) / `hi_res`(~325kbps) / `spatial` / `highest` / `higher` / `medium` |
| **完整播放** | 时长校验保证是整曲；会员曲目可用 |
| **中转零依赖** | 只用 Python 标准库（含手写 RFC6455 WebSocket），无需 pip |
| **手机直连** | 音频走 CDN，中转带宽为零 |
| **自动暂停** | 取链后按应用暂停客户端，不影响其他播放器 |
| **登录态安全** | 内置守卫，防止客户端"安全模式"弹窗清掉登录 |

---

## 目录结构

```
relay/
  relay.py                 中转服务（仅标准库，部署到公网服务器）
  relay.env.example        环境变量模板
  soda-relay.service       systemd unit
pc/
  soda_daemon.py           家用守护进程（WebSocket 客户端 + 取链 + 缓存）
  soda_pause.py            按应用暂停客户端播放
  smtc.ps1                 soda_pause 调用的 PowerShell 脚本
  run_daemon.bat           启动器
  cenc_decrypt.py          CENC 解密参考实现（含完整 box 解析）
  requirements.txt
  sodalib/
    soda_fetch.py          阶梯提取（Frida 读客户端内存）
    soda_guard.py          "安全模式"弹窗守卫
    soda_reuse_proven.py   deeplink 启动原语
    health.py              客户端健康检查
secrets.example.json       密钥模板（真实文件不入库）
ARCHITECTURE.md            链路文档：时序、协议、加解密、实测数据
```

---

## 快速开始

### 1. 公网中转（Debian 12 / 1C1G 足够）

```bash
mkdir -p /opt/soda-relay /etc/soda-relay
cp relay/relay.py /opt/soda-relay/

# 两个密钥必须不同：前者给家用电脑，后者给设备
python3 -c "import secrets;print('SODA_SHARED_SECRET='+secrets.token_hex(32))"  > /etc/soda-relay/relay.env
python3 -c "import secrets;print('DEVICE_SECRET='+secrets.token_hex(32))"      >> /etc/soda-relay/relay.env
chmod 600 /etc/soda-relay/relay.env

cp relay/soda-relay.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now soda-relay

curl -s http://127.0.0.1:8080/v1/health     # {"ok":true,...}
```

### 2. 家用电脑（Windows，需已登录的汽水音乐客户端）

```cmd
pip install -r pc\requirements.txt
pip install frida pywin32

set RELAY_AGENT_URL=ws://<你的服务器>:8765/agent
set SODA_SHARED_SECRET=<与服务器 relay.env 一致>

pc\run_daemon.bat
```

### 3. 调用

```bash
# 签名：HMAC_SHA256(DEVICE_SECRET, "device.<unix秒>.<路径>")
TS=$(date +%s)
SIG=$(python3 -c "
import hmac,hashlib,sys
print(hmac.new(b'<DEVICE_SECRET>', ('device.$TS./v1/play').encode(), hashlib.sha256).hexdigest())")

curl -s -H "X-Ts: $TS" -H "X-Sig: $SIG" \
  "http://<服务器>:8080/v1/play?track_id=7673136752508962852&quality=hi_res"
```

响应里的 `url` 可直接交给播放器（支持 Range），但**音频是 CENC 加密的**，必须配合 `play_auth` 解密才能出声 —— 见 [ARCHITECTURE.md §4.3](ARCHITECTURE.md)。

---

## 接口速查

| 路由 | 说明 |
|---|---|
| `GET /v1/health` | 免鉴权健康检查 |
| `GET /v1/play?track_id=&quality=` | 取直链 + `play_auth` + `duration_s` + `is_full_length` |
| `GET /v1/audio?track_id=&quality=` | 代理音频字节（回退路径，支持 `Range`） |

**播放前必须检查 `is_full_length` 为 `true`**，否则可能是试听片段或别的曲目。

错误码：`400 bad_request` / `401 unauthorized` / `502 no_tier`（可重试）/ `503 agent_unavailable`。

---

## 环境变量

**中转**：`SODA_SHARED_SECRET`、`DEVICE_SECRET`、`HTTP_PORT`(8080)、`AGENT_PORT`(8765)、`BIND`

**家用电脑**：`RELAY_AGENT_URL`、`SODA_SHARED_SECRET`、`SODA_HOME`、`SODA_EXE`、`SODA_CACHE`、`SODA_NO_PAUSE`

---

## 安全须知

1. **不要提交 `secrets.json`。** 已在 `.gitignore` 中，请确认未 `git add -f`。
2. **两个密钥必须不同。** `SODA_SHARED_SECRET` 泄露 = 他人可冒充家用电脑；`DEVICE_SECRET` 泄露 = 他人可消耗你的取链配额。
3. **`8765` 只对该家用电脑开放**，不要暴露到公网。
4. **目前是明文 HTTP。** 生产环境请加 TLS（需要域名）。
5. **`/v1/audio` 有约 2.5% 偶发 502**，客户端必须实现重试；主路径 `/v1/play` + CDN 直连不受影响。

---

## 已知限制

- **单曲串行**：只有一台客户端，同时只能解析一首，并发请求排队。
- **需要常开的 Windows 机器**，且客户端保持登录。
- **取链较慢**：首次约 20 s，复用客户端约 10 s，缓存命中约 2 s。
- **约 10 秒外放**：客户端必须先真正播放才会解析出阶梯，取链过程中会有短暂声音（见 ARCHITECTURE §5.3）。

---

## 实测数据

| 项 | 值 |
|---|---|
| 链接有效期 | > 18.6 小时 |
| CDN 绑定 IP？ | 否 |
| Range 支持 | 完整（含 `bytes=-1024`） |
| 拉取速度 | 30.1 MB/s |
| 解密验证 | 6/6 档位 ffmpeg 零错误 |
| 中转内存 | ~22 MB |

---

## License

MIT，见 [LICENSE](LICENSE)。
