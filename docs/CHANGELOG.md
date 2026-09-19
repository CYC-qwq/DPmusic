# DPmusic 迭代记录

按批次记录功能开发与修复。每个批次均以「目标 → 改动 → 验证」三段式记录。

> 说明：本文件记录自 **A/B 组功能移植**起的批次（C39 ~ C51）。
> 更早期的基线建设（三平台 API、播放底座、歌词解析、UI 骨架等）已并入当前架构，详见 `ARCHITECTURE.md`。

---

## A/B 组功能移植（13 项）

### A 组 · 6 项快赢

| # | 功能 | 批次 | 说明 |
|---|---|---|---|
| 1 | 播放速度 0.5x ~ 2.0x | C39 | `PlaybackSpeedSheet` + `PlaybackParameters`，速度持久化 |
| 2 | 定时退出（睡眠定时） | C39 | `SleepTimerController`，支持「播完当前歌曲后停止」 |
| 3 | 不喜欢 / 屏蔽列表 | C40 | `DislikeRepository`（歌曲 / 歌手 / 关键词三类规则）+ 命中自动跳过 |
| 4 | 热搜榜 | C40 | 三平台热搜入口 |
| 5 | 歌词繁简转换（S2T） | C40 | `ChineseS2T`，播放页与桌面歌词共用 |
| 6 | 列表显示开关 | C40 | 专辑名 / 时长 / 封面 / 来源逐项开关，经 `LocalListDisplayOptions` 全局生效 |

### B 组 · 7 项中等工程

| # | 功能 | 批次 | 说明 |
|---|---|---|---|
| 7 | 歌手详情页 | C41 | 热门歌曲 + 全部专辑 + 分页 |
| 8 | 专辑详情页 | C41 | 专辑信息 + 曲目列表 |
| 9 | 相似歌曲 | C41 | 以当前歌曲为种子推荐 |
| 10 | 下载管理页 | C42 | 任务队列（串行 / 进度 / 暂停 / 恢复 / 取消）+ 历史记录 + 任务持久化 |
| 11 | 下载增强 | C42 | 元数据写入：ID3 / FLAC 标签、封面嵌入、歌词嵌入（含翻译行） |
| 12 | WebDAV 同步 | C43 | 备份 / 恢复「设置与音源」「歌单与数据」，白名单机制 + 自动同步 |
| 13 | 桌面小组件 | C44 | 当前歌曲 + 播放控制（RemoteViews）+ 快照持久化 + 冷启动连接等待 |

---

## C45 · Media3 升级与弱网调优

**目标**：播放底座升级到 Media3 1.11.1，并针对弱网提升缓冲表现。

**改动**

- `gradle/libs.versions.toml`：`media3 = "1.11.1"`，新增 `media3-exoplayer-hls`
- `app/build.gradle`：`minSdk 21 → 23`，新增 HLS 依赖（m3u8 音源支持）
- `core/playback/MusicService`：`DefaultLoadControl` 弱网缓冲调优
  （最小缓冲 30s / 最大 60s / 起播 1.5s / 断流恢复 3s / 回退缓冲 30s + 关键帧保留）
- 1.11.1 API 变更适配：`setBackBufferDurationMs` + `setRetainBackBufferFromKeyframe`
  已合并为 `setBackBuffer(int, boolean)`

**兼容性核验**：media3 五模块为纯 Java 产物（无 Kotlin 元数据）；`kotlin-stdlib 2.2.10`
非 strict 元数据可被 Kotlin 2.1.0 读取；15 个导入类与全部方法签名逐一验证通过。

---

## C46 · 双缓冲调优（保留）与预加载接入（回退）

**目标**：进一步优化起播与切换体验。

**保留交付**：`DefaultLoadControl` 拆分为两套参数 ——
「流媒体 30s / 60s / 1.5s / 3s」与「本地文件 1s / 15s / 1s / 1s」，本地文件起播更快。

**回退项**：Media3 `DefaultPreloadManager` 预加载

- 接入后运行期崩溃；崩溃点定位为 `setPreloadLooper` 禁止主线程 Looper
- 更深层原因：`PreloadMediaSource` 要求「播放器与预加载处于同一非主线程 Looper」
  （官方 `buildExoPlayer` 通过共享专用线程实现，即整个播放器需在后台线程）
- 当前架构（`MediaSessionService` 主线程持有播放器）不满足该前提；
  收益（下一曲起播快数百毫秒）已被「懒解析队列 + 下一首预解析」覆盖，全线程改造风险不成比例
- **结论：回退**。若未来重做，需按官方线程模型专项改造

---

## C47 · 桌面歌词 + 三点菜单二级分组收纳

**目标**：新增悬浮歌词功能（高自定义、多预设、优雅美观），并整理播放页三点菜单。

**新增 5 个文件（约 2,150 行）**

| 文件 | 行数 | 职责 |
|---|---|---|
| `core/lyric/LyricsHub.kt` | 116 | 进程级歌词中心：独立跟随播放器加载 / 缓存（空结果不入缓存 + 自动重试一次），播放页可回填复用 |
| `core/lyric/DesktopLyricStyle.kt` | 276 | 6 套预设（流光 / 黑胶 / 霓虹 / 玻璃 / 墨韵 / 糖果）+ 样式解析（0 = 跟随预设 / 主题色） |
| `core/lyric/DesktopLyricView.kt` | 707 | 纯 Canvas 悬浮视图：逐字卡拉 OK 渐变、本地时钟平滑推进、描边叠层、阴影、动态高度、拖动 + 单击控制条 |
| `core/lyric/DesktopLyricService.kt` | 318 | 前台服务（`specialUse`）+ `WindowManager` 悬浮窗 + 三路数据流 + 位置持久化 + 触摸穿透 |
| `ui/components/DesktopLyricSheet.kt` | 729 | 设置面板：总开关 + 权限引导 + 实时预览 + 预设卡片 + 22 项自定义 |

**其他改动**

- `SettingsRepository` 新增 **22 项设置**（22 字段 / 映射 / setter / key）
- 接线：`AndroidManifest`（`SYSTEM_ALERT_WINDOW` + `FOREGROUND_SERVICE_SPECIAL_USE` + service 声明）、
  `MainActivity`（开关同步服务）、`SettingsScreen`（桌面歌词卡片 ×2 形态）
- 三点菜单二级收纳：快捷区 3 项（播放队列 / 私人 FM / 添加到歌单）+ 3 个手风琴分组
  （音质与播放 / 歌曲详情 / 更多），原 15 项零丢失 + 新增「桌面歌词」入口

**验证**：约 230 项静态检查全过；结构扫描 184 文件平衡；APK 实测 5 个新类与 6 套预设名均在包内。

---

## C48 · 悬浮歌词拖动方向修复

**问题**：拖动方向相反（往上拖却往下跑），且「拖不动」。

**根因（两个 bug 叠加）**

1. 窗口 `gravity = BOTTOM` 时，`params.y` 表示「窗口底边距屏幕底边的距离，**向上为正**」；
   原实现写 `y + dy` → 手指上移（`dy < 0`）→ y 减小 → 窗口反而下移
2. `render()` 由播放位置推送（500ms tick）触发，每次都无条件用设置中的旧偏移重新布局 →
   刚拖到就被拽回原位（表现为「拖不动」）

**修复（1 文件，+89 / −15）**

- 纵向取反 `posY -= dy`，并用 Float 累积位置（消除 < 1px 丢精度）
- 拖动中（`dragging`）+ 设置值未变化（`lastAppliedOffset` 比较）时跳过位置同步
- 范围夹紧：`y ∈ [0, 屏高 − 窗高 − 顶部余量]`
- 移除「不限边界」窗口标志（防止窗口被拖出屏幕后无法找回）；默认位置距底 26dp

---

## C49 · 悬浮歌词胶囊自适应宽度

**目标**：从「横贯整屏的宽条」改为「胶囊贴合文字宽度」——更精致，且水平拖动真正可用。

**改动（2 文件，+110 / −13）**

- `DesktopLyricView`：`onMeasure` 按内容测宽 —— 当前行（逐字 / 整行）、下一行、翻译行、控制条取最大值
  + 左右内边距，夹在 `[168dp, 屏宽 − 24dp]`；新增 `measurePaint` + `measureTextWidth` /
  `measureWordsWidth` / `desiredContentWidth`；行变化时 `requestLayout()` 让宽度跟随歌词
- `DesktopLyricService`：窗口宽度 `MATCH_PARENT → WRAP_CONTENT`；新增 `horizontalRangePx()`
  与 `clampPosition()`（宽度变化后重新夹紧，保证胶囊完整留在屏内）

**体验提升**：窗口两侧不再是空白遮挡层（旁边的应用区域可直接点击）；水平拖动可用。

---

## C50 · 悬浮歌词拖动体验三修（跟手 / 去抖 / 可拖到顶）

**问题**：① 不好拖 ② 拖动时强烈抖动 ③ 拖动高度受限，到不了屏幕顶部。

**根因（三处）**

1. **强烈抖动 —— 测量被绘制污染**：`fitText(text, available, paint)` 在文本过宽时会临时改动
   `textPaint.textSize`；而 `contentHeight()`（决定窗口高度）与 `onDraw` 又都读 `textPaint.fontMetrics`
   → 窗口高度在「绘制后」与「绘制前」之间反复变化 → 尺寸震荡
2. **限高 —— 顶部安全区**：`maxUp = 屏高 − 窗高 − topSafePx()`，而 `topSafePx()` = 状态栏高度 + 12dp
   → 胶囊最多只能拖到状态栏下沿
3. **不好拖 —— 每帧多次 IPC + 拖动中被夹紧**：每次 `ACTION_MOVE` 都直接 `updateViewLayout`；
   且 `clampPosition()` 会被 500ms 一次的播放数据推送触发，在手指移动中把窗口拽一下

**修复（2 文件，+76 / −39）**

- `DesktopLyricView`（+40 / −19）
  - 新增 `fontMetricsBuffer` + `baseFontMetrics()`：字体度量一律走独立 `measurePaint` 计算，
    `contentHeight()` / `onDraw` 改读基准度量 → **高度恒定，抖动根除**
  - `fitText(...)` → `fitTextSize(...): Float`：**只返回字号、不改动绘制画笔**（3 处调用）
  - 纵向内边距 11 → 14dp
- `DesktopLyricService`（+36 / −20）
  - `topSafePx()`（状态栏 + 12dp）→ `topGapPx()` = 4dp：**可一直拖到屏幕顶部**
  - 新增 `pendingWindowUpdate` + `scheduleWindowUpdate()`（`postOnAnimation` 合并同帧更新）+
    `applyWindowPosition()` → 每帧最多一次 `updateWindowLayout`，跟手且不抖
  - `clampPosition()` 加 `if (dragging) return`；`persistPosition()` 走 `applyWindowPosition()`
  - 水平范围由「半差 − 56dp」放宽为「半差 + 24dp」；移除失效常量 `MIN_VISIBLE_DP`

**验证**：52 项静态检查全过（含旧代码清除、括号 / 行数结构、repr 逐字节、diff 生成）；
结构扫描 184 文件平衡。

---

## C51 · 悬浮歌词垂直居中（文字贴顶修复）

**问题**：水平居中正常，但文字太贴胶囊上边界（纵向未居中）。

**根因（首行基线取值错误）**

- `onDraw` 首行基线写成 `var cursor = paddingV + fm.descent`
- 字体度量中 **ascent 在基线上方（负值，|ascent| ≈ 0.928 × 字号）**、
  **descent 在基线下方（正值，≈ 0.244 × 字号）**
- 用 `descent` 当「顶部留白」时，真实上方留白 = `paddingV − (|ascent| − 字形高)`
  ≈ `14 − (0.928 − 0.88) × 22` = **0.0dp**（默认 22sp）→ 字形顶部正好压在胶囊上边界
- 而下留白 = **34.4dp** → 上下严重不对称（≈ 0 : 34dp），视觉即「字贴顶」

**修复（`DesktopLyricView`，+4 / −1）**

- 基线改为 `var cursor = paddingV - fm.ascent` → 字体框（ascent..descent）恰好上下各留 `paddingV`
- 高度公式与行高**均未改动**，仅基线取值修正 → 无布局回归风险
- 几何核算：上留白 **0.0 → 15.1dp**，下留白 34.4 → 19.4dp（默认 22sp / paddingV 14dp）
- 占位文案、下一行、翻译行共用同一 cursor 递推 → 全部同步下移到正确位置

---

## 改动流程约定（本项目实践）

为保证批量改动可追溯、可回滚，本项目采用如下流程：

1. **先定位、后改动**：改动前检索并阅读相关代码与上下文，确认根因再动手
2. **两阶段脚本**：批量改动写为 Python 脚本，先 `check` 全量校验锚点（出现次数、新文本是否已存在），
   通过后才 `write` 写入
3. **先备份**：写入前把待改文件复制为 `*.cXX.bak` 存档
4. **静态验证**：写入后跑验证脚本（新代码存在性 + 旧代码清除 + 括号 / 行数结构 + 关键片段逐字节核对）
5. **生成 diff**：输出 unified diff 逐行审查，确认无意外改动
6. **结构扫描**：全量扫描 Kotlin 文件数与括号平衡，确认无文件损坏
7. **统一构建测试**：批次完成后由开发者统一构建验证（不在改动过程中逐次编译）

> 经验教训（已内化到上述流程）：
> 锚点必须取精确字节（换行、缩进、同行后续字符都算）；
> 半透明面板禁用 `shadowElevation`；
> 涉及播放核心线程模型的改动需先做前置可行性验证；
> 验证脚本的预期值必须与实际设计一致（否则会产生假失败）。
