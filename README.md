# DPmusic

代码编写：deepseek-flash
Gemini 也提供了些许帮助
其中代码还有借鉴了许多的开源项目，如 lx music 等

> **三平台聚合音乐播放器** —— Android / Kotlin · Jetpack Compose · Media3
>
> 一次搜索同时覆盖 **网易云音乐 / QQ 音乐 / 酷狗音乐**，统一完成播放、歌词、下载、
> 数据同步与桌面体验；内置逐字歌词、听歌识曲、一起听、私信聊天、音效均衡器、
> 桌面歌词悬浮窗与液态玻璃外观。

| 项目 | 值 |
|---|---|
| 包名 | `com.dpmusic.app` |
| 语言 | Kotlin 2.1.0（100% Kotlin，无 Java 源码） |
| UI | Jetpack Compose + Material 3（液态玻璃 + 动态取色） |
| 播放底座 | AndroidX Media3 1.11.1（ExoPlayer + MediaSessionService） |
| 版本 | versionCode 2 / versionName 1.1.0 |
| SDK | minSdk 23 · targetSdk 34 · compileSdk 36 |
| 代码规模 | 202 个 Kotlin 文件 · 约 45,900 行 + 约 2,600 行 JS |
| 构建 | AGP 8.13.0 · Gradle Wrapper（仓库自带） |

---

## 目录

- [功能总览](#功能总览)
- [技术栈](#技术栈)
- [构建与运行](#构建与运行)
- [工程结构](#工程结构)
- [数据与隐私](#数据与隐私)
- [延伸文档](#延伸文档)

---

## 功能总览

### 一、音源与内容（三平台聚合）

| 能力 | 说明 |
|---|---|
| 歌曲搜索 | 三平台并行搜索，平台切换 + 分页加载 + 输入联想 |
| 歌单搜索 | 关键词搜歌单（QQ 匿名态自动降级为分类浏览） |
| 榜单 | 三平台官方热榜浏览 + 榜单详情 |
| 歌曲链接解析 | 粘贴官方分享链接 → 自动识别平台并定位歌曲 |
| 歌单链接导入 | 三平台歌单链接 → 保存为「链接歌单」（可刷新 / 定时更新） |
| 歌手 / 专辑详情 | 热门歌曲、全部专辑、专辑曲目 |
| 相似歌曲 | 以当前歌曲为种子推荐 |
| 评论 | 歌曲评论分页浏览 |
| 每日推荐 / 私人 FM | 网易云登录后的个性化内容（私人 FM 支持自动续杯） |

### 二、音源引擎（三套并行，可配优先级）

播放地址由三条独立链路提供，互不干扰：

| 引擎 | 运行时 | 说明 |
|---|---|---|
| **远端代理 Key** | OkHttp | `source.shiqianjiang.cn` 解析服务；**Key 不内置**，需在「设置 → 音源服务」自行填写 |
| **LX Music 自定义脚本** | QuickJS 沙箱 | 导入 LX Music 脚本，本地执行解密 / 取源逻辑 |
| **MusicFree 插件** | QuickJS + cheerio 兼容层 | 导入 MusicFree 插件；内置 `axios / qs / he / crypto-js / dayjs / cheerio` 模块适配 |

**解析优先级**（设置 → 音源管理 → 解析优先级）：

```
Key 优先  → 代理 → 失败回退脚本      ← 默认
脚本优先  → 脚本 → 失败回退代理
仅 Key    → 代理（忽略脚本）
仅脚本    → 脚本（平台不支持则明确报错，不回退）
```

三者全部失败后，再按「标题 + 歌手」相似度到另外两个平台换源兜底。

### 三、播放（Media3 / ExoPlayer）

- **后台保活**：通知栏 / 锁屏 / 耳机按键控制；音频焦点自动处理来电与其他应用抢占；拔耳机自动暂停；弱网网络唤醒锁
- **播放控制**：播放暂停 / 上下首 / 进度拖拽（时间气泡）/ 循环（关·列表·单曲）/ 随机 / 队列跳播
- **播放队列**：面板内搜索过滤、点击跳播、当前曲高亮、整队列加入歌单
- **懒解析队列**：仅当前曲与下一曲持有真实地址，其余为占位媒体项；推进到占位项时实时解析替换（避免批量预解析拖慢起播）
- **音质体系**：5 档可选（128K / 320K / FLAC / 24bit / Hi-Res）+ **失败自动逐级降档链** + **8 分钟 URL 缓存** + **熔断器**
- **跨平台兜底**：同平台彻底失败后，按「标题 + 歌手」相似度到另外两个平台换源
- **错误自愈**：失败自动重解析重试；同一首连续失败 2 次则提示并自动跳过
- **连切优化**：切歌指令合并窗口（350ms 防抖）+ 序号取代，连按期间不预解析
- **续播**：主页「继续收听」+ 播放会话持久化（队列 + 索引 + 进度，冷启动可恢复）
- **音效均衡器**：Equalizer + BassBoost + Virtualizer，7 种中文预设（正常 / 流行 / 摇滚 / 爵士 / 古典 / 人声 / 重低音）+ 自定义频段 + 实时响应曲线，会话变更自动重挂
- **播放速度**：0.5x ~ 2.0x
- **定时退出**：倒计时暂停 + 「播完当前歌曲后停止」模式

### 四、歌词

- **逐字歌词（卡拉 OK）**：三平台全支持 —— QQ QRC（自定义 DES 解密）、网易云 YRC、酷狗 KRC；无逐字数据自动回退行级
- **预测式平滑**：本地时钟推进 + 偏差对齐，填充与人声精确同步
- **行级动效**：颜色 / 缩放 / 透明度阻尼过渡 + 翻译行
- **交互**：点击行 seek、自动滚动（拖动暂停 / 松手 4s 恢复）、全屏歌词模式切换
- **样式调节**：字号 / 行距（横竖屏分别记忆持久化）
- **繁简转换**：一键歌词简繁切换
- **模拟逐字**：无逐字数据的歌曲也可按行内时长估算逐字进度

### 五、桌面歌词（悬浮窗）

- **系统级悬浮窗**（`TYPE_APPLICATION_OVERLAY`），显示在其他应用之上，前台服务保活
- **6 套预设**：流光 / 黑胶 / 霓虹 / 玻璃 / 墨韵 / 糖果
- **数十项自定义**：字号、字距、不透明度、背景样式与颜色、圆角、文字色、高亮色、描边、阴影、逐字开关、翻译行、下一行、控制条、锁定、触摸穿透、位置记忆…
- **胶囊自适应宽度**：贴合文字宽度、可水平拖动；垂直方向可拖到屏幕任意高度（含顶部）
- **交互**：拖动移动（位置自动记忆）· 单击显示迷你控制条（上一首 / 播放暂停 / 下一首 / 关闭，3s 自动收起）· 锁定位置 · 触摸穿透
- 实现为**纯 Canvas 自绘**（不引入 Compose 到悬浮窗，零新依赖、更省电）

### 六、听歌识曲

- **三种拾音方式**：麦克风（环境音）/ 系统播放捕获（内部音频，Android 10+ MediaProjection）/ 本地音频文件（SAF）
- **双引擎并行**：酷狗 PCM 指纹直传 + 网易云 afp 指纹（WebView + wasm）
- 采集 10 秒 → 候选列表 → 播放 / 收藏 / 加歌单 / 找原唱
- 音频**仅内存处理，不落盘**

### 七、下载

- 下载面板：任务队列（进度 / 速度实时刷新）+ 暂停 / 恢复 / 取消；App 重启后中断任务标记为「已暂停」可手动恢复
- 音质选择（解析失败自动降档并提示）；支持跨平台换源
- 可选元数据增强：写入 **ID3/FLAC 标签**、嵌入**封面**、嵌入**歌词（含翻译行）**
- 下载路径自定义：可写性校验 + 权限引导（存储权限 / 所有文件访问权限）+ 恢复默认
- 全局下载悬浮球（下载中显示进度）

### 八、分享

- **歌曲分享**：三种组合 —— 官方链接 / 音源直链（实时解析）/ 两者都带 → 系统分享面板
- **歌单分享**：JSON 备份文件（系统分享）/ 源链接分享 / 复制链接 / 导出文件（SAF）
- **分享给网易云好友**：直接发送到网易云私信会话

### 九、歌单与数据

- 本地歌单：创建 / 重命名 / 删除 / 增删歌曲 / 批量操作 / 排序
- **链接歌单**：从链接更新 + 定时更新（关闭 / 每天 / 每 3 天 / 每周，打开歌单页自动检查）
- 收藏（红心）、播放历史、最近播放（时间轴分组 + 进度胶囊）、搜索历史
- **不喜欢 / 屏蔽规则**：按歌曲 / 歌手 / 关键词屏蔽，命中自动跳过
- **听歌统计**：本次播放时长 + 近 7 日每日时长柱状图
- 网易云 / QQ 音乐账号登录（网页登录，自动获取登录态），支持**红心双向自动同步**
- 数据导入导出（JSON 备份，支持「分享到 DPmusic」直接导入）

### 十、一起听

- 基于网易云「一起听」房间：创建 / 加入房间、房间信息与成员列表
- 实时同步播放（播放 / 暂停 / 切歌 / 进度）、房间内聊天
- **邀请监听**：剪贴板与官方私信卡片邀请自动识别，切回应用即提示加入

### 十一、私信聊天

- **网易云私信**：会话列表（含未读数与最近一条消息）+ 会话记录（向上翻页）+ 发送
- **可发送内容**：文本 / 歌曲 / 歌单 / 专辑
- **与官方 App 双向互通**：官方客户端用户可直接收到并回复
- 好友选择器 + 分享面板直达

### 十二、数据同步（WebDAV）

- 备份 / 恢复两类数据：**「设置与音源」**、**「歌单与数据」**
- **白名单机制**：只同步显式列出的键；账号 Cookie、音源 Key、WebDAV 密码等敏感项**永不参与同步**
- 支持自动同步 + 上次同步时间展示

### 十三、桌面与系统集成

- **桌面小组件**：当前歌曲（标题 / 歌手 / 封面）+ 上一首 / 播放暂停 / 下一首；冷启动按钮经「连接等待」可正常控制；封面异步降采样；快照持久化（进程被杀后恢复）
- **剪贴板自动读取**（可选）：从其他应用切回时识别歌曲 / 歌单 / 一起听链接并询问处理（去重、长度限制、仅含链接才触发）
- **外部导入**：从文件管理器 / 其他应用「用 DPmusic 打开或分享」JSON 备份文件自动导入
- 运行日志页：内存环形缓冲 + 全局未捕获异常捕获 + 一键导出分享
- **存储与缓存**：上限可选（256MB / 512MB / 1GB / 2GB / 不限制），超限**自动清理**（临时文件 → 图片缓存 LRU → 整缓存目录），进入设置页先清理再统计

### 十四、界面与主题

- **液态玻璃外观**（设置 → 外观 → 玻璃风格）：真实模糊 + **边缘折射（lens）** + **AGSL 边缘高光** + 鲜艳度，对齐 AndroidLiquidGlass 的效果量级（自研实现，不引第三方库）；Android 12 以下自动降级为半透明 + 流光底
- **内容从玻璃下穿过**：底栏 / Mini 条采样真实页面内容，而非装饰性底色
- **Material 3 动态取色**（Android 12+ 取系统壁纸色），未开启时可用内置调色板（多套）
- **自适应布局**：手机（底部导航）/ 折叠屏与平板（侧边导航栏）随 WindowSizeClass 实时切换，导航形态「变形演进」平滑过渡；单栏 ⇄ Master-Detail 双栏
- **丝滑动效**：标签切换 fadeThrough、详情页推进过渡、共享元素（封面）转场、预测式返回手势跟手折叠播放器
- **列表显示开关**：专辑名 / 时长 / 封面 / 来源标识可逐项开关
- 沉浸式 Edge-to-Edge；旋转与深色切换不重建 Activity，滚动位置保持

### 十五、动效与反馈体系

「每一次操作都要有反馈」是这套 UI 的硬约束，分四层落地：

| 层 | 内容 |
|---|---|
| **动效令牌** | `DPMotion` 统一时长（90 / 140 / 220 / 320 / 420 / 500ms）与缓动（进入 Decelerate、退出 Accelerate、手势弹簧），交错入场延迟封顶 320ms |
| **触觉反馈** | `Haptics` 六个语义级别：`tick / click / longPress / confirm / reject / gestureEnd`；系统关闭触觉时静默，API 30 以下自动降级 |
| **骨架屏** | 用内容形状占位替代转圈（`ShimmerBox` 单动画驱动），与真实行排版严格对齐、加载完成零跳动；已覆盖 11 处加载态 |
| **列表增删动画** | `Modifier.animateItem()` 接入收藏 / 最近播放 / 歌单等，移除淡出、其余平滑补位 |

---

## 技术栈

| 领域 | 选型 | 版本 |
|---|---|---|
| 构建 | AGP / Gradle Wrapper | 8.13.0 |
| 语言 | Kotlin | 2.1.0 |
| UI | Compose BOM | 2025.10.01 |
| 导航 | Navigation Compose（类型安全路由） | 2.9.5 |
| 播放 | Media3（exoplayer / session / common / datasource-okhttp / exoplayer-hls） | 1.11.1 |
| 网络 | OkHttp | 4.12.0 |
| 图片 | Coil 3（复用 OkHttp 连接池） | 3.3.0 |
| 持久化 | DataStore Preferences | 1.1.7 |
| 序列化 | kotlinx.serialization JSON | 1.8.0 |
| 协程 | kotlinx.coroutines | 1.10.2 |
| 取色 | androidx.palette | 1.0.0 |
| 脚本引擎 | QuickJS（`wang.harlon.quickjs:wrapper-android`） | 2.4.0 |
| 图标 | material-icons-extended | 1.7.8 |

> 无 Hilt / 无注解处理器：依赖注入采用 `AppContainer` 手工容器 + `AppViewModelFactory` 集中装配，零 kapt 开销。

---

## 构建与运行

### 环境要求

- JDK 17
- Android SDK：**compileSdk 36**（需 Android SDK Platform 36 与 Build-Tools）
- Gradle：使用仓库自带 Wrapper（`./gradlew`）

### 步骤

```bash
# 1. 配置 SDK 路径（IDE 首次打开会自动写入）
#    local.properties:
#    sdk.dir=/path/to/Android/Sdk

# 2. 构建 Debug APK
./gradlew :app:assembleDebug

# 3. 产物位置
#    app/build/outputs/apk/debug/app-debug.apk

# 4. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 设备本机构建（可选）

仓库自带 `tools/` 脚本，可在**手机本机**完成「编译 → 校验 → 安装 → 启动」：

```bash
bash tools/build-and-install.sh            # 构建（Ubuntu 终端）
sh tools/install-on-device.sh              # 安装（Android shell / Shizuku）
```

详见 [`tools/README.md`](tools/README.md)。

### 首次使用要点

| 事项 | 说明 |
|---|---|
| 音源解析 | 播放地址由「音源解析层」提供：① 远端音源代理服务（需在 **设置 → 音源服务** 填写 Key）；② 自定义 LX 音源脚本；③ MusicFree 插件。优先级可在设置中调整 |
| 账号登录 | 网易云 / QQ 音乐需在设置中**网页登录**后才能使用个性化功能（每日推荐、私人 FM、红心同步、一起听、私信、评论） |
| 桌面歌词 | 首次需在 **设置 → 桌面歌词** 授予「显示在其他应用上层」权限 |
| 下载目录 | 默认 `Music/DPmusic`；Android 11+ 需授予「所有文件访问权限」才能写入公共目录 |
| 数据同步 | 需自备 WebDAV 服务地址与账号（可选功能） |

---

## 工程结构

```
DPmusic/
├── build.gradle                    根构建脚本
├── settings.gradle.kts             工程与仓库配置
├── gradle.properties               Gradle / Kotlin 编译参数
├── gradle/
│   ├── libs.versions.toml          版本目录（所有依赖集中声明）
│   └── wrapper/                    Gradle Wrapper
├── gradlew / gradlew.bat
├── local.properties                SDK 路径（本机配置，不纳入版本控制）
├── .gitignore
├── tools/                          设备本机构建 / 安装 / 测试脚本
│   ├── build-and-install.sh        构建 + 校验 + 投递
│   ├── install-on-device.sh        安装 + 启动
│   ├── extract_plugins.py          从设备提取插件源码
│   └── tests/                      离线回归测试（cheerio / 插件挂载）
├── docs/                           项目文档
│   ├── ARCHITECTURE.md             架构说明
│   ├── CHANGELOG.md                迭代记录（v1.0 批次）
│   ├── GLASS-UI.md                 液态玻璃设计规范
│   ├── MUSICFREE-PLUGIN.md         MusicFree 插件运行时
│   ├── SOURCE-MANAGER.md           音源管理页信息架构
│   ├── STORAGE.md                  存储与缓存策略
│   ├── CODE-REVIEW.md              代码质量排查报告
│   ├── AUDIO-RECOGNIZE-RESEARCH.md 听歌识曲调研
│   ├── LYRICS-RESEARCH.md          歌词格式调研
│   └── 功能总览.txt                 功能清单
└── app/
    ├── build.gradle                模块构建脚本（依赖声明）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── audiofp/index.html              听歌识曲指纹页（WebView + wasm）
        │   ├── script/user-api-preload.js      LX 脚本预置环境
        │   ├── script/musicfree-preload.js     MusicFree 插件适配层
        │   └── script/musicfree-cheerio.js     cheerio 兼容层（纯 JS）
        ├── res/                                资源（图标 / 布局 / 颜色 / 字符串）
        └── kotlin/com/dpmusic/app/             Kotlin 源码（见下表）
```

### 源码分包

| 包 | 文件数 | 职责 |
|---|---|---|
| `app`（根） | 4 | `DPmusicApp` 应用入口 · `MainActivity` 唯一 Activity · `AppContainer` 手工 DI 容器 · `AppViewModelFactory` |
| `core/model` | 15 | 统一数据模型（`Song` / `MusicPlatform` / `PlayQuality` / `Lyric` / `Playlist` / `SourcePriority` / `NcmChat` …） |
| `core/net` | 12 | 三平台 API（`WyApi` / `QqApi` / `KgApi` / `NcmApi`）· 统一 HTTP 层 · eapi 加密 · 音源解析 · 链接解析 · JSON 工具 |
| `core/repo` | 1 | `MusicRepository`：聚合平台 API 与音源解析，实现「降档 + 脚本/Key 优先级 + 插件兜底 + 跨平台换源」容错链 |
| `core/playback` | 6 | `MusicService`（MediaSessionService）· `PlayerConnection`（UI ↔ 服务唯一桥接）· 定时退出 · 私人 FM · 播放状态 · 请求头存储 |
| `core/lyric` | 10 | LRC / QRC / YRC / KRC 解析 · 繁简转换 · 模拟逐字 · 歌词中心 · 桌面歌词（样式 / 视图 / 服务） |
| `core/audio` | 8 | 听歌识曲（指纹引擎 + 采样 + 识别）· 音效均衡器 · 播放捕获 · 前台捕获服务 |
| `core/data` | 15 | DataStore 仓库群：设置 · 收藏 · 歌单 · 历史 · 不喜欢 · 搜索历史 · 听歌统计 · 会话 · 账号 · 红心同步 · 均衡器 · 私信 |
| `core/download` | 5 | 下载任务队列 · 单曲下载器 · 路径管理 · 元数据写入（标签 / 封面 / 歌词） |
| `core/script` | 8 | 双脚本引擎（LX 脚本 + MusicFree 插件）· 插件仓库 · 解析器 · AES / RSA 辅助 |
| `core/sync` | 3 | WebDAV 客户端 · 同步管理器 · 同步白名单 |
| `core/together` | 3 | 一起听会话（房间 / 同步 / 聊天）· 邀请监听 · 邀请解析 |
| `core/widget` | 2 | 桌面小组件 Provider · 快照更新器 |
| `core/util` | 6 | 日志与崩溃捕获 · 存储与缓存管理 · 封面取色 · 熔断器 · 格式化 · 繁简转换 |
| `core`（根） | 2 | 剪贴板链接收件箱 · 导入收件箱 |
| `ui/shell` | 2 | `DPmusicShell` 自适应外壳 · `AppNavHost` 导航图 |
| `ui/navigation` | 1 | 类型安全路由定义 |
| `ui/screens/*` | 17 个子包 | 各业务页面（主页 / 搜索 / 榜单 / 歌单 / 我的 / 设置 / 一起听 / 私信 / 下载 / 音源管理 / 日志 / 歌手 / 专辑 / 最近播放 / 收藏 / 网易云 / QQ 音乐…） |
| `ui/components` | 42 | 可复用组件（播放面板 · 歌词视图 · 均衡器 · 定时退出 · 识曲 · 桌面歌词设置 · 下载 · 评论 · 分享 · 玻璃面板 · 骨架屏…） |
| `ui/motion` | 1 | 动效令牌（时长 / 缓动 / 弹簧 / 交错延迟） |
| `ui/player` | 2 | 播放页 ViewModel · 下载 ViewModel |
| `ui/theme` | 8 | Material 3 主题 · 动态取色 · 调色板 · 玻璃令牌 · 布局 insets · 字体 · 形状 |
| `ui/util` | 2 | 自适应断点工具 · 触觉反馈 |

---

## 数据与隐私

### 本地存储

| 数据 | 位置 | 说明 |
|---|---|---|
| 应用设置 / 账号 Cookie / 歌单 / 收藏 / 历史 / 统计 | `DataStore`（应用私有目录） | 仅本机可读 |
| 下载任务列表 | 应用私有目录 | 仅任务元信息 |
| 图片磁盘缓存 | 应用缓存目录 | 上限可在设置中选择（256MB / 512MB / 1GB / 2GB / 不限制），超限自动按最久未用逐出 |
| 下载的音频文件 | 用户指定目录（默认 `Music/DPmusic`） | 用户可见 |
| 运行日志 | 内存环形缓冲 | 可手动导出，不自动上传 |

### 隐私原则

- **不上传任何用户数据**：账号 Cookie 仅保存在本机，用于直连接口，不写入日志、不发送第三方
- **听歌识曲音频仅内存处理**，不落盘
- **音源 Key 不内置**：仓库中不含任何有效的音源服务 Key，需用户自行配置
- **同步白名单**：WebDAV 同步只覆盖显式列出的键，账号 Cookie / 音源 Key / WebDAV 密码永不参与
- **无统计 SDK / 无广告 SDK / 无第三方埋点**

### 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 网络请求与状态判断 |
| `WAKE_LOCK` | 弱网 / 锁屏持续缓冲 |
| `FOREGROUND_SERVICE`（+ `MEDIA_PLAYBACK` / `MEDIA_PROJECTION` / `SPECIAL_USE`） | 播放保活 / 系统内录捕获 / 桌面歌词悬浮窗 |
| `POST_NOTIFICATIONS` | 媒体通知（Android 13+，拒绝不影响前台播放） |
| `SYSTEM_ALERT_WINDOW` | 桌面歌词悬浮窗 |
| `RECORD_AUDIO` | 听歌识曲采集环境音（仅识别时使用） |
| `WRITE_EXTERNAL_STORAGE`（≤ Android 10）/ `MANAGE_EXTERNAL_STORAGE` | 下载到公共音乐目录 |

> 本项目为**本地客户端**：所有音源内容均来自公开接口与用户自行配置的解析服务，
> 请遵守各平台服务条款，仅将下载功能用于个人合法用途。

---

## 延伸文档

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) —— 分层架构、核心数据流、线程模型、自适应体系、扩展点
- [`docs/GLASS-UI.md`](docs/GLASS-UI.md) —— 液态玻璃设计规范（分层、令牌、降级矩阵、调参指南）
- [`docs/MUSICFREE-PLUGIN.md`](docs/MUSICFREE-PLUGIN.md) —— MusicFree 插件运行时与模块兼容层
- [`docs/SOURCE-MANAGER.md`](docs/SOURCE-MANAGER.md) —— 音源管理页信息架构与解析优先级
- [`docs/STORAGE.md`](docs/STORAGE.md) —— 存储与缓存上限、自动清理、构建纪律
- [`docs/CODE-REVIEW.md`](docs/CODE-REVIEW.md) —— 代码质量排查报告（v1.1.0）
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) —— 迭代记录（v1.0 时期的批次记录）
- [`tools/README.md`](tools/README.md) —— 设备本机构建 / 安装脚本说明
