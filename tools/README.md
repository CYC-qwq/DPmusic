# DPmusic 一键构建 & 安装

在**设备本机**完成「编译 → 校验 → 安装 → 启动」，不需要电脑、不需要 Android Studio。

---

## 一、为什么是两个脚本

设备上的运行环境被分成两半，**权限不互通**，所以必须两段：

| | Ubuntu（proot）终端 | Android shell（Shizuku / adb / root） |
|---|---|---|
| 能做什么 | 跑 Gradle / AGP / Kotlin / D8，**构建 APK** | `pm install`、`am start`、访问 `/data/local/tmp` |
| 不能做什么 | 没有系统权限，`/system` 和 `/data/local/tmp` 都碰不到 | 没有 JDK / SDK，构建不了 |
| 脚本 | `build-and-install.sh` | `install-on-device.sh` |

> 另外 `system_server` **读不了 `/storage`（FUSE）**，所以 `pm install /storage/.../x.apk`
> 会直接报 `avc: denied ... tcontext=u:object_r:fuse:s0` + `Can't open file`。
> 安装脚本会自动先 `cp` 到 `/data/local/tmp/` 再装 —— 这是必需的中间步骤。

---

## 二、用法

### 第 1 步：构建（Ubuntu 终端）

```bash
cd /storage/emulated/0/AndroidIDEProjects/DPmusic
bash tools/build-and-install.sh
```

> ⚠️ `/storage` 是 FUSE，文件**没有可执行位**，必须用 `bash xxx.sh`，不能 `./xxx.sh`。

常用参数：

| 参数 | 作用 |
|---|---|
| `--clean` | 先 `clean` 再全量构建（改过依赖 / 换过 SDK 时用） |
| `--release` | 构建 `assembleRelease`（默认无签名，见下文） |
| `--no-verify` | 跳过 apksigner / badging 校验 |
| `--no-deploy` | 只构建，不投递到共享目录 |
| `--aapt2 <path>` | 手动指定 aapt2 |
| `--proj <dir>` | 指定别的工程目录 |

脚本会自动：环境自检 → 选 aarch64 aapt2（含 daemon 自检）→ 构建 → 校验
（sha256 / badging / 签名证书）→ 投递到共享目录 → 生成安装脚本。

**产物投递位置**：`/storage/emulated/0/Download/DPmusic-build/`
- `latest.apk` —— 始终指向最新构建
- `DPmusic-debug-<时间戳>.apk` —— 历史包（只保留最近 5 个）
- `install-on-device.sh` —— 安装脚本副本

### 第 2 步：安装（Android 侧）

在 Operit 里用 `super_admin:shell` 执行：

```sh
sh /storage/emulated/0/Download/DPmusic-build/install-on-device.sh
```

脚本会自动：定位 APK → 搬到 `/data/local/tmp` → `pm install -r -t` → 校验 → `am start` → 打印 PID。

其它用法：

```sh
sh install-on-device.sh --no-launch      # 只装不启动
sh install-on-device.sh --uninstall      # 卸载
sh install-on-device.sh /path/to/x.apk   # 装指定的 APK
```

---

## 三、aarch64 上的关键点

**整条 AGP 链路里只有 `aapt2` 是原生二进制**，其余（Kotlin 编译器、Compose 插件、
D8/R8、apksigner、zipflinger）都是 JVM 程序。所以「能不能在 arm64 上构建」= 「能不能让 aapt2 跑起来」。

- SDK 里的 `build-tools/*/aapt2` 和 `zipalign` 是 **x86_64**，在 arm64 上直接 `Illegal instruction`。
- 用 Android Code Studio 的**静态 aarch64 aapt2**（`libaapt2.so`，静态 ELF）即可，位于
  `/opt/android-aapt2/acs/bin/aapt2`。
- 必须通过 `-Pandroid.aapt2FromMavenOverride=<path>` 传给 AGP，且**路径必须以 `aapt2` 结尾**，
  否则报 `Custom AAPT2 location does not point to an AAPT2 executable`。

同理，`/opt/android-sdk/platform-tools/adb` 也是 x86_64 二进制，在设备上执行会 `Illegal instruction`
—— 所以**不要用 adb 安装**，直接用 `pm install`。

---

## 四、排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `BUILD FAILED` + `Daemon startup failed` | AGP 用了 x86_64 aapt2 | 确认 `/opt/android-aapt2/acs/bin/aapt2` 存在；或 `--aapt2` 指定 |
| `RES_TABLE_TYPE_TYPE entry offsets overlap` | aapt2 太老（如 debian 的 2.19-debian） | 换静态版 aapt2 |
| 构建中途 `RC=130` 被中断 | 外层等待超时发了 SIGINT | 脚本已用 `setsid` 让 gradle 脱离会话；直接重跑即可（增量很快） |
| `pm install` → `avc: denied ... fuse` | system_server 读不了 /storage | 用 `install-on-device.sh`（自动搬到 /data/local/tmp） |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 与已装版本签名不同 | `sh install-on-device.sh --uninstall` 后重装 |
| 安装后进程不存活 | 启动即崩溃 | `logcat -d -t 300 \| grep -E 'AndroidRuntime\|dpmusic'` |
| `setlocale: cannot change locale` | proot 里只有 `C.UTF-8` | 脚本已自动回退，可忽略 |

---

## 五、Release 包说明

`--release` 产出的 APK **默认未签名**（工程没有配 `signingConfig`），无法直接安装。两种做法：

```bash
# A) 用 AGP 注入签名（实测有效）
bash tools/build-and-install.sh --release   # 先构建
# 或构建时注入：
cd /storage/emulated/0/AndroidIDEProjects/DPmusic
bash ./gradlew assembleRelease \
  -Pandroid.aapt2FromMavenOverride=/opt/android-aapt2/acs/bin/aapt2 \
  -Pandroid.injected.signing.store.file=/opt/keys/my-release.jks \
  -Pandroid.injected.signing.store.password=*** \
  -Pandroid.injected.signing.key.alias=mykey \
  -Pandroid.injected.signing.key.password=***

# B) 事后签名
apksigner sign --ks /opt/keys/my-release.jks --out signed.apk app-release-unsigned.apk
```

> 注意：**APK 里只有签名证书（公钥），没有私钥**。debug keystore 是 AGP 在构建机上自动生成的
> （口令 `android`，别名 `androiddebugkey`），不同环境各有一份、互不相同 —— 覆盖安装必须用同一把 key。
