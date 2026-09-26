# 音源管理页：收纳式信息架构

> 文件：`ui/screens/sources/SourceManagerScreen.kt`
> 目标：**一屏之内看清状态，需要时才展开细节** —— 解决原先「一长串平铺卡片、找东西要滑很久」的问题。

## 1. 设计目标

| 问题（改前） | 方案（改后） |
| --- | --- |
| 引擎状态 / 导入 / 脚本列表 / 插件 / 优先级 / 测试 / 说明 **7 张卡全部平铺**，滚动很长 | **总览常驻 + 4 个可收纳分组** |
| 想改「优先级」要滑到页面底部 | 分组头**摘要行**直接显示「当前：X」，点开即改 |
| 想跑可用性测试要滑到专门一张卡 | 测试入口**收进总览卡**，结果就地展开 |
| 空态占用整屏 `EmptyState`，把真正有用的东西顶走 | 紧凑空态 `SectionEmptyHint`（一行标题 + 一行副标题） |

## 2. 页面骨架（LazyColumn，`spacedBy(10.dp)`）

```
① item  "overview"   SourceOverviewCard              ← 常驻，永不收纳
② item  "lx"         Column { SectionHeader + CollapseSection { … } }   ← LX 音源脚本（默认展开）
③ item  "plugin"     Column { SectionHeader + CollapseSection { … } }   ← MusicFree 插件（默认收起）
④ item  "priority"   Column { SectionHeader + CollapseSection { … } }   ← 解析优先级（默认收起）
⑤ item  "tips"       Column { SectionHeader + CollapseSection { … } }   ← 使用说明（默认收起）
```

各分组内容：
- `lx` → `SectionImportRow` + （`SectionEmptyHint` | `ScriptCard × N`）
- `plugin` → `SectionImportRow` + （`SectionEmptyHint` | `PluginCard × N`）
- `priority` → `PriorityOptions`；`tips` → `TipsContent`

> ⚠️ **分组头与内容必须在同一个 `item` 里。** 早期实现是 `item("lx_header")` + `if (lxExpanded) { item("lx_import") … }`，
> 有两个问题：① 展开/收起是硬切换，没有过渡；② 若把内容做成常驻的第二个 item 以支持动画，
> LazyColumn 的 `spacedBy(10.dp)` 会在收起状态给 0 高度 item 也留出间距 → 分组之间多出 10dp 空隙。
> 合并成一个 item 后，间距由 `CollapseSection` 内部的 `spacing` 承担，收起时随内容一起消失。

**收纳状态**用 `rememberSaveable` 记住（旋屏 / 返回再进不丢）：

| 状态 | 默认 | 理由 |
| --- | --- | --- |
| `lxExpanded` | `true` | 最常用（导入 / 启用脚本），不该多一步 |
| `pluginExpanded` | `false` | 兜底能力，用得少 |
| `priorityExpanded` | `false` | 低频设置；摘要行已显示当前值 |
| `tipsExpanded` | `false` | 一次性阅读 |
| `testExpanded` | `false` | 点「可用性测试」时自动置 `true` |

### 2.1 展开/收起动画（`CollapseSection`）

`AnimatedVisibility` 封装，统一 4 处收纳分组 + 总览卡的测试明细：

| 方向 | 高度 | 透明度 | 说明 |
| --- | --- | --- | --- |
| 展开 | `expandVertically` `tween(260ms, FastOutSlowInEasing)`，`expandFrom = Top` | `fadeIn(200ms, delay 60ms)` | 先撑开再显影，避免内容「贴边弹出」 |
| 收起 | `shrinkVertically` `tween(200ms, FastOutSlowInEasing)`，`shrinkTowards = Top` | `fadeOut(120ms)` | 收起比展开快 —— 干脆，不拖沓 |

- 内容用 `Modifier.staggeredEntrance(index)` 错峰入场（每项 +45ms，超过 12 项不再延迟），复用 `ui/components/Common.kt` 的既有工具，与收藏 / 榜单页手感一致；
- 收起后 `AnimatedVisibility` **不再组合子树**，零额外开销；
- 代价：分组内容不再是 Lazy 项（`forEachIndexed` 全量组合）。脚本 / 插件是用户手动导入的小集合，可接受；若将来出现「一次导入上百个脚本」的场景，需要改回 `items()` + `Modifier.animateItem()`。

页内动画的分工：**页面级**过渡由 `AppNavHost` 的全局 `enterTransition` 负责（详情页右侧滑入 + 淡入），本页不重复配置。

## 3. 组件

| 组件 | 职责 | 关键点 |
| --- | --- | --- |
| `SourceOverviewCard` | 状态灯 + 标题 + `MiniPill(优先级)` + 状态描述 + 3 个 `MiniStat` + 测试按钮 + 结果明细 | `GlassSurface(strong = true)`；测试结果就地展开，不再单独占卡 |
| `MiniPill` / `MiniStat` | 小标签 / 小统计块 | 单行、`maxLines = 1`、超长省略 |
| `SectionHeader` | 分组头 + 收纳开关 | **整行可点**；箭头 `animateFloatAsState`（展开 `0f` / 收起 `-90f`）；摘要 `maxLines = 1` |
| `CollapseSection` | 收纳内容容器 | `AnimatedVisibility` 高度 + 透明度联动；顶部间距在动画内部（收起不留空隙）；`spacing` 可调（分组 10dp / 测试明细 4dp） |
| `SectionImportRow` | 分组内导入行 | hint + 「选择文件」+「从链接导入」+ 导入中进度 |
| `SectionEmptyHint` | 紧凑空态 | 图标 + 标题 + 副标题，高度远小于原 `EmptyState` |
| `PriorityOptions` | 优先级单选 | `SourcePriority.entries` + RadioButton + 说明 |
| `TipsContent` | 使用说明正文 | 标题由分组头承担，正文只有 3 条 `TipLine` |

**总览卡是唯一 `strong = true` 的面板**（面纱 0.64）—— 它是信息密度最高的一块，其余分组头走默认薄玻璃。见 `docs/GLASS-UI.md` §2.4。

## 4. 与旧实现的差异（清理清单）

| 旧组件 | 处置 |
| --- | --- |
| `EngineStatusCard` | 并入 `SourceOverviewCard` |
| `PriorityCard` | 拆为 `SectionHeader` + `PriorityOptions` |
| `ImportCard` | 拆为 `SectionImportRow`（脚本 / 插件各一处） |
| `SourceTestCard` | 整块删除，并入总览卡（`SourceTestRow` 保留为明细行） |
| `TipsCard` | 改名 `TipsContent`（去掉内部标题） |
| `PluginSectionHeader` / `PluginImportCard` / `PluginEmptyHint` | 删除（已被 `SectionHeader` / `SectionImportRow` / `SectionEmptyHint` 取代） |

## 5. 排查记录（本轮踩到的两个坑）

1. **新增组件的 import 全部缺失** —— 12 个（`GlassSurface`、`ImageVector`、`animateFloatAsState`、`rotate`、`HorizontalDivider`、`Box`、`rememberSaveable`、`Icons.Outlined.Code/SwapVert/Info/ExpandMore/ExpandLess`）。重构时新写的组件不会自动补 import，**改完必须过一遍 import 清单再构建**。
2. **一次 edit 把 `deletePluginTarget` 对话框的闭合括号吃掉了**（骨架末尾少了 `}, ) } }` 四行），同时 `PluginEmptyHint` 删除时残留了两个孤立 `}`。这类错误编译器会报，但更快的定位方式是：**改完先扫一遍「每个 `@Composable` 函数是否成对闭合」**，再构建。

## 6. 验收要点

- [ ] 进页面第一眼：总览卡显示状态灯 / 优先级 / 三个统计，无需滚动；
- [ ] 点分组头整行可展开/收起，**内容高度平滑过渡（不是硬切换）**，箭头同步旋转，状态在旋屏后保持；
- [ ] 收起时分组之间**没有多余空隙**（收起态相邻分组头的间距 = 展开态分组头间距）；
- [ ] 展开后卡片是**错峰上浮淡入**（约 45ms/项），不是整块同时出现；
- [ ] LX 分组默认展开且能看到导入行与脚本列表；
- [ ] 总览卡点「可用性测试」→ 按钮变「测试中」→ 结果**平滑展开**并逐行淡入，显示 `X / N 项可用`；
- [ ] 空态（无脚本 / 无插件）是紧凑一行，不占整屏；
- [ ] 底部留白正确（内容能滚到底部玻璃栏下面，末项仍可点击）—— 见 `LocalBottomBarInset`。

## 7. 解析优先级：默认「Key 优先」

**安装后（或从未设置过时）默认走 Key 优先**：先用远端代理 Key 音源，失败再回退自定义脚本。

| # | 位置 | 值 |
| --- | --- | --- |
| 1 | `core/model/SourcePriority.kt` — `fromId` 的 `?:` 回落 | `KEY_FIRST` |
| 2 | `core/data/SettingsRepository.kt` — `AppSettings.sourcePriority` 默认值 | `KEY_FIRST` |
| 3 | `core/repo/MusicRepository.kt` — `priorityProvider` 默认 lambda | `KEY_FIRST` |
| 4 | `ui/screens/sources/SourceManagerViewModel.kt` — `stateIn` 初始值 | `KEY_FIRST` |

同时把 `KEY_FIRST` **移到枚举第一位** —— 它既决定「未设置时的默认」，也决定设置页单选列表的顺序（默认项排第一，视觉一致）。

**解析顺序**（`MusicRepository.resolveWithKeyOrScript`）：

```
仅 Key   → 代理（忽略脚本）
仅脚本   → 脚本（平台不支持则明确报错，不回退）
Key 优先 → 代理 → 失败回退脚本      ← 默认
脚本优先 → 脚本 → 失败回退代理
```

**已装设备不受影响**：DataStore 里已存的值优先于默认值；若想验证默认值，需清除应用数据（会丢失收藏 / 歌单，不建议）。

> ⚠️ `source_priority` 在 `core/sync/SyncScopes.kt` 的同步白名单里。若开过 WebDAV 云同步，云端存的旧值（如 `script_first`）同步回来会覆盖本机默认 —— 此时到「音源管理 → 解析优先级」手动选一次即可。
