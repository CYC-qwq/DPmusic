# DPmusic 毛玻璃外观（Glassmorphism）设计规范

> 叠加式外观模式 · 默认关闭（设置 → 外观 → **玻璃风格**）
> 与动态取色 / 主题色板 / 深色三态完全兼容

## 1. 定位与原则

- **叠加而非替换**：毛玻璃只是叠加在现有调色体系之上的「材质层」，所有既有外观设置原样保留。
- **克制**：列表文字直接置于流光底上；面板只用于「容器」（卡片 / 栏 / 弹层），避免满屏玻璃损伤可读性。
- **性能优先**：不做逐面板实时模糊；真实模糊仅用于全局流光底的封面层（静态成像）。
- **优雅降级**：Android 12 以下自动退化为「半透明 + 高光描边 + 流光底」，观感一致、零风险。

## 2. 视觉分层（由底到顶）

| # | 层 | 说明 |
|---|---|---|
| 1 | 基础底色 | surfaceContainerLowest 兜底（页面背景透明后由它承接） |
| 2 | 封面模糊层 | 当前歌曲封面 72dp 模糊（仅 Android 12+；静态成像） |
| 3 | 暗化罩 | 纵向渐变，保证正文可读性 |
| 4 | 漂移光球 ×3 | 跟随歌曲调色板 / 主题色，26s / 34s 缓慢漂移 |
| 5 | 边缘渐隐 | 顶 / 底轻微压暗，提升状态栏与底栏层次 |
| 6 | 页面内容 | Scaffold 背景透明，自然透出流光底 |
| 7 | 玻璃面板 | 真实模糊 + **边缘折射** + **边缘高光（AGSL）** + 半透明 + 斜向光泽（卡片 / 栏 / Mini 条） |
| 8 | 弹层 / 播放器 | 强透明面板（底部抽屉）；播放器保持既有沉浸式设计 |

## 2.1 Liquid Glass（液态玻璃，v5 起）

对齐参考实现 [`io.github.kyant0:backdrop`](https://github.com/Kyant0/AndroidLiquidGlass)（AndroidLiquidGlass，Apache-2.0）的效果与量级，
**不引入该库**（其 1.0.6 需要 Kotlin 2.3、2.0.x 需要 compileSdk 37，本项目 Kotlin 2.1 / compileSdk 36），
在 `ui/components/LiquidGlass.kt` 内自行实现：

| 效果 | 实现 | 门槛 |
|---|---|---|
| 真实模糊 | `Modifier.blur(4.dp)`（库示例 `blur(4.dp)`） | API31+ |
| **边缘折射（lens）** | 圆角矩形 SDF 的 `circleMap` 位移剖面：把 16dp 折射带按「位移量等分」切成 8 环，逐环以不同缩放重绘共享背景层并裁剪到该环 | API31+（纯 Compose 绘制） |
| **边缘高光（rim light）** | 库 `DefaultHighlightShaderString` 同款 AGSL：SDF 梯度 · 45° 法线的 `\|dot\|^falloff` 强度场，沿轮廓加法混合（`BlendMode.Plus`）描边 | API33+ |
| 鲜艳度 | `ColorMatrix.setToSaturation(1.5f)`（库 `vibrancy()`），作用于整块底色采样 | API31+ |

**参数（与库文档示例同值）**：`refractionHeight = 16.dp`、`refractionAmount = 32.dp`、`blur = 4.dp`、
`rimWidth = 0.5.dp`、`rimColor = White α0.5`、`rimAngle = 45°`、`rimFalloff = 1`、`ringCount = 8`（集中在 `LiquidLens`）。

**为什么不用库的 `RenderEffect.createRuntimeShaderEffect(shader, "content")` 写法**（本机实测，Android 16 / Compose 1.9）：
该路径下折射着色器的**输入着色器 `content` 读不到内容**（`content.eval()` 恒为黑），且行为随 Compose 图层结构
（`compositingStrategy=Offscreen`、`drawLayer` 位置、坐标空间）漂移；探针实测 4 种 RenderEffect 链结构（仅折射 /
折射+色彩 / 折射+模糊 / 折射+模糊+色彩）结果完全一致且均无效。改用「同心环 + 逐环缩放重绘」后，
纯 Compose 机制（`drawLayer` + `clipPath(Difference/Intersect)` + `scale`）稳定复刻同一位移剖面，且两端连续无接缝。

**两个坐标空间陷阱（踩坑记录）**：
1. `clipPath` 使用**面板局部坐标**，必须放在 `translate(-position)` **之外**；否则裁剪区落在屏幕左上角、与面板不相交 → 整环被裁掉；
2. `saveLayer`（颜色滤镜）的 bounds 必须是**当前绘制坐标系**下的区域 —— 在 `translate` 之后调用时需传 root 坐标下的面板矩形，
   否则保存层范围错误、绘制被裁掉。
   为控制开销，逐环重绘**不套** `saveLayer`（每环一个面板大小的离屏缓冲代价过高），鲜艳度只作用于整块底色。
## 2.2 玻璃「后面有东西可透」（v6 引入，v9 落地为真实布局）

参考实现的通透感来自**内容从玻璃下面经过**。DPmusic 原先的页面内容被 Scaffold 内边距顶开（底栏留位），
玻璃下面只有装饰性流光底，所以面板看起来像纯色卡片。最终实现（v9）：

1. **外壳不再给内容做底部内边距**：`DPmusicShell` 的内容区只 `consumeWindowInsets(padding)`，
   内容一直铺到屏幕底部，**从玻璃栏下面穿过**；
2. **`LocalBottomBarInset`**（`ui/theme/LayoutInsets.kt`）：外壳按实测高度提供「Mini 条 + 底部导航栏」总高
   （`if (isCompact) miniBarHeight + navBarHeight else miniBarHeight`，含系统导航栏 inset）；
3. **各页面列表自行留白**：`contentPadding = PaddingValues(bottom = 原值 + LocalBottomBarInset.current)`
   —— 保证最后一项能滚到玻璃栏**上方**可点击，同时中途内容会从玻璃下面经过。
   已覆盖 23 个页面文件（含 `verticalScroll` 的 3 处，用 `.verticalScroll(...).padding(bottom = ...)`）；
   抽屉 / 对话框（`*Sheet.kt` / `*Dialog.kt`）**不加**，它们不在底栏之下；
4. **内容层**：内容区录制「流光底 + 页面内容」到 `contentLayer`，底栏 / Mini 条 / 覆盖层的
   `LocalGlassBlur` 指向它，因此玻璃采样到的是真实内容。

> ⚠️ **inset 只能加一次，且只能加在「真正滚到屏幕底部」的那一层。**
> 如果同一个滚动区里还有区块排在它下面（典型：搜索页空闲态的「搜索历史 + 热搜榜」），
> 给上面的区块也加 inset，就会在它下方留下一整块**死空白**（≈ mini 条 + 导航栏 ≈ 140dp）。
> 正确做法：把同屏内的多个区块合并进**一个**滚动容器，inset 只加在最外层一次
> —— 这样它只在内容滚到尽头时才可见，中途内容照样从玻璃下穿过。
> 实例：`SearchScreen` 的 `SearchIdlePanels`（v1.1.0 修复；此前历史词条下方多出 ~140dp 空白，
> 把热搜榜压到屏幕下半部分）。同屏两个相邻的 `verticalScroll` 还有第二个隐患：
> 上方区块内容变长时会按剩余高度抢占空间，把下方区块挤成 0 高。

> 踩坑记录：`PaddingValues(vertical = X, bottom = Y)` / `PaddingValues(16.dp, bottom = Y)` 会命中
> `start/top/end/bottom` 重载，语义与预期不同（`vertical` 与 `bottom` 混用直接编译不过），
> 必须展开成 `start/top/end/bottom` 全具名形式。

## 2.3 播放页 / 覆盖层（v7 起，v8 修正为不透明）

- **播放页（L0｜不透明层）**：歌词是长时间阅读场景，**不做透明玻璃**。
  根容器为不透明 `Surface(surfaceContainerLowest)` + 一层**封面调色板染色的不透明竖向渐变**
  （`lerp(base, palette[i], 0.34/0.21/0.12)`，深色 0.26 档），既保留专辑氛围又保证对比度。
- **覆盖层采样源**：`DPmusicShell` 用 `CompositionLocalProvider(LocalGlassBlur provides contentLayer)`
  包住覆盖层（播放页 / 队列 / 相似歌曲 / 下载球 / 加入歌单），
  若将来覆盖层使用 `GlassSurface`，透出的将是真实页面内容而不是装饰性流光底。
- `GlassSurface(strong = true)` 保留为「强调档」参数（大面纱，供需要可读性的浮层使用）。

## 2.4 玻璃分层规范（设计基线）

| 层级 | 场景 | 配方 |
| --- | --- | --- |
| **L0 不透明** | 播放页、歌词、长文阅读 | 不透明底 + 调色板染色渐变，**不使用玻璃** |
| **L1 薄玻璃** | 底部导航栏 / Mini 条 / 侧边导航 | 模糊 4dp + 边缘折射 16dp/32dp + 边缘高光 + 面纱 0.36 |
| **L2 中玻璃** | 卡片 / 面板 / 列表容器 | 同 L1（`glassPanelColor(base)`） |
| **L3 厚玻璃** | 底部抽屉 / 对话框（scrim 之上）、**搜索框 / 输入框**（要可读性） | 面纱 0.64（`glassPanelColor(base, strong = true)`） |
| **边缘** | 所有玻璃 | API33+ AGSL 边缘高光（45°、`\|dot\|^falloff`、加法混合）；低版本 1dp 描边 |

> 原则：**信息密度高 / 停留时间长 → 越不透明**；**导航与装饰 → 越透明**。
> 所有面板底色必须走 `glassPanelColor(...)`，不要直接使用 `surfaceContainer*`，
> 否则玻璃模式下会出现「不透明面板」与「玻璃面板」混杂（v8 已把 18 个文件统一）。

> ⚠️ **列表行同样不能留不透明兜底（v1.1.0 修复）。**
> `SwipeableSongRow` 为了盖住 `SwipeToDismissBox` 的滑动揭示背景，给 `SongRow` 加过一层
> `Modifier.background(colorScheme.surface)`；`SongRow` 自身背景是 `Color.Transparent`，
> 所以**这一层是该页面唯一的不透明面** —— 玻璃模式下它把整片流光底盖掉，
> 「我的 → 我的喜欢」整列变成一块死黑（实测：列表区像素恒为 `#141218` 一类的 surface 值、
> 全区域零变化，而列表**上方**的流光底亮度正常 → 可断定是「被盖住」而非「背景本来就黑」）。
> 修法：玻璃模式下行底不加任何底色，揭示背景改为**只在拖过阈值时绘制**
> （`SwipeBackground` 在 `Settled` 时直接 `return`），顺带消除了「小幅拖动先闪错误颜色」。
> 排查同类问题的通用判据：**某区域像素在整片区域内完全恒定** → 一定有不透明层盖住了流光底。

## 3. 设计令牌（ui/theme/Glass.kt → GlassTokens）

| 令牌 | 深色 | 浅色 | 说明 |
|---|---|---|---|
| panelAlpha | 0.46 | 0.50 | 普通面板透明度（真实模糊后更透） |
| strongAlpha | 0.82 | 0.86 | 弹层面板透明度 |
| borderColor | 白 18% | 白 90% | 边缘高光描边 |
| sheenColor | 白 7% | 白 20% | 斜向光泽 |
| glowAlpha | 0.30 | 0.50 | 光球强度（颜色经饱和度增强） |
| panelWhiteBlend | 0 | 0.72 | 浅色面板向白偏移（「亮玻璃」层次） |
| rimColor | 白 50% | 白 75% | 边缘高光（AGSL 加法混合描边） |
| panelBlurRadius | 4dp | 4dp | 面板真实模糊半径（Android 12+，取库示例量级） |

## 4. 组件覆盖（v1）

- 全局流光底（外壳层，含封面模糊）
- 底部导航栏 / 侧边导航栏（半透明容器）
- Mini 播放条（GlassSurface）
- 首页卡片：继续收听 / 快捷卡
- 设置卡片（统一 GlassSurface）
- 7 个底部弹层：播放队列 / 下载 / 分享 / 均衡器 / 听歌识曲 / 播放器音质 / 歌单操作

## 5. 降级矩阵

| 环境 | 效果 |
|---|---|
| Android 13+ | 封面真实模糊层 + 面板（模糊 + 边缘折射 + AGSL 边缘高光）+ 鲜艳度 + 流光底 |
| Android 12 | 面板（模糊 + 边缘折射，无 AGSL 边缘高光）+ 流光底 |
| Android 12 以下 | 纯流光底 + 半透明面板（自动跳过模糊 / 折射 / 高光） |
| 毛玻璃关闭 | 全部回退经典外观（零视觉变化） |

## 6. 文件索引

| 文件 | 职责 |
|---|---|
| `ui/theme/Glass.kt` | 令牌 / LocalGlass / glassPanelColor |
| `ui/components/Glass.kt` | GlassSurface / GlassBackdrop / GlassBlurSample（采样 + 模糊 + 折射环 + 边缘高光） |
| `ui/components/LiquidGlass.kt` | Liquid Glass 内核：AGSL 边缘高光、折射环、cornerRadiiOf、LiquidLens 参数 |
| `ui/theme/Theme.kt` | 玻璃模式接入（透明背景 + 令牌注入） |
| `ui/shell/DPmusicShell.kt` | 流光底挂载 + 导航栏玻璃化 |
| `ui/components/MiniPlayerBar.kt` | Mini 条玻璃化 |
| `ui/screens/home/HomeScreen.kt` | 首页卡片玻璃化 |
| `ui/screens/settings/SettingsScreen.kt` | 设置卡片玻璃化 + 开关 |
| 各 Sheet 文件 | 弹层玻璃化 |

## 7. 调参指南

- 全部参数集中在 `GlassTokens`（单点调参）：
  - 想更「透」：降低 panelAlpha / 提高 borderColor 对比；
  - 想更「亮」：提高 glowAlpha（光球已跟随歌曲调色板自动换色）；
  - 弹层可读性不足：提高 strongAlpha 至 0.9+。
- 封面模糊半径：`GlassBackdrop` 内 `blur(28.dp)` / 透明度 `alpha(0.48f)`。
- v3 调优（截图像素分析）：移除面板投影（半透明下投影会从边缘透出脏影）、光球饱和度增强 + 半径加大、浅色白偏移 0.72。
- v4 调优（iOS 控制中心方向）：真实背景模糊落地（共享背景层 + 逐面板 48dp RenderEffect）、面板更透（0.50/0.46）。
- v5 调优（Liquid Glass）：模糊半径 48dp → **4dp**（重模糊会抹掉折射细节）；新增边缘折射（16dp / 32dp）与
  AGSL 边缘高光，均匀描边由高光替代（`border = null`）。
  - 折射太夸张 → 调小 `LiquidLens.refractionAmount`（默认 32dp）；
  - 折射范围太宽 → 调小 `LiquidLens.refractionHeight`（默认 16dp，上限为面板最小圆角半径）；
  - 折射出现台阶 → 提高 `LiquidLens.ringCount`（默认 8）或加大 `panelBlurRadius`；
  - 边缘高光太亮 / 太弱 → 调 `GlassTokens.rimColor`（默认深色白 50%）。

## 8. 路线图（可迭代）

- [x] 真实背景模糊（GraphicsLayer 记录 + 逐面板 RenderEffect）
- [x] Liquid Glass：边缘折射（lens）+ AGSL 边缘高光 + 鲜艳度（对齐 AndroidLiquidGlass 效果与量级）
- [ ] 采样对象升级：底栏 / Mini 条改为折射「真实页面内容」（需让页面内容延伸到栏下方）
- [ ] 更多组件玻璃化（搜索框 / 筛选芯片 / 播放器控制卡）
- [ ] 开关切换的透明度渐入过渡动画
- [ ] 低端机性能开关（关闭光球漂移动画 / 降低折射环数量）