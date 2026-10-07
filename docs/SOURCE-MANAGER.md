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

## 5. 多选导入与自动去重

### 5.1 多选

脚本 / 插件的文件选择均使用 `ActivityResultContracts.OpenMultipleDocuments()`（原为 `OpenDocument()` 单选）：

| 项 | 值 |
| --- | --- |
| 触发 | `SectionImportRow.onPickFile` → `launcher.launch(arrayOf("application/javascript","text/javascript","text/plain","*/*"))` |
| 回调 | `uris: List<Uri>`（取消时为空列表，直接 return） |
| 入口 | `vm.importFromUris(context, uris)` / `vm.importPluginsFromUris(context, uris)` |

> 注意 `OpenMultipleDocuments` 的回调签名是 `(List<Uri>) -> Unit`，**不是** `(Uri?) -> Unit`；
> 从单选改多选时若忘了改 lambda 参数类型，编译器会报类型不匹配。

一次选多个文件时**只弹一次汇总 Snackbar**（不再逐文件弹），格式：

```
已导入 3 个脚本，跳过重复 2 个，失败 1 个：未找到脚本信息注释块（脚本需以块注释开头）
```

汇总文案由 `BatchImportResult.summary(noun)` 统一生成；失败仅 1 条时会把原因直接带出来，省得用户翻日志。

### 5.2 自动去重（内容指纹）

**去重依据是内容指纹，不是名字。** 名字不可靠：MusicFree 插件的 `name` 取自 `platform` 字段，
不同作者写的插件常同名（都叫「网易云」），按名字去重会误删用户真正想要的插件。

指纹实现见 `core/script/SourceFingerprint.kt`：

| 步骤 | 规则 |
| --- | --- |
| 1. 归一化 | `\r\n` / `\r` → `\n`；每行 `trimEnd()`；掐掉首尾空行 |
| 2. 摘要 | 归一化内容的 `SHA-256`，取**前 16 字节** → 32 位 hex |

**判定语义（刻意保守）**：

| 场景 | 判定 | 理由 |
| --- | --- | --- |
| 同一文件重复选中 / 同批次选了副本 | **重复 → 跳过** | 归一化后完全一致 |
| 仅换行符 / 行尾空格 / 首尾空行不同 | **重复 → 跳过** | 这些是编辑器差异，不是内容差异 |
| 改版本号 / 改逻辑 / 改注释文字 | **不重复 → 并存** | 用户可能确实要保留多版本 |
| 不同作者的同名插件（内容不同） | **不重复 → 并存** | 避免误杀 |

**老数据兼容**：`UserScript` / `MusicFreePlugin` 新增 `fingerprint` 字段（默认 `""`）。
升级前导入的条目该字段为空，读取时由 `fingerprintOf()` **现场按内容补算**，
因此「升级前导入的脚本」也能被新导入的同内容文件识别为重复 —— 不需要数据迁移脚本。
（`AppJson` 配了 `ignoreUnknownKeys = true`，所以反向也安全：旧版本读新数据不会崩。）

**结果类型**：`ScriptImportResult` / `PluginImportResult` 新增 `Duplicate(existing)` 分支。

> ⚠️ 给 sealed interface 加分支后，**所有 `when` 都要补分支**，否则报
> `'when' expression must be exhaustive`。本轮就漏了 `importFromUrl` / `importPluginFromUrl` 两处，
> 编译时才暴露。项目里这两组结果只在 `SourceManagerViewModel` 使用（共 4 处 `when`）。

## 6. 排查记录（本轮踩到的两个坑）

1. **新增组件的 import 全部缺失** —— 12 个（`GlassSurface`、`ImageVector`、`animateFloatAsState`、`rotate`、`HorizontalDivider`、`Box`、`rememberSaveable`、`Icons.Outlined.Code/SwapVert/Info/ExpandMore/ExpandLess`）。重构时新写的组件不会自动补 import，**改完必须过一遍 import 清单再构建**。
2. **一次 edit 把 `deletePluginTarget` 对话框的闭合括号吃掉了**（骨架末尾少了 `}, ) } }` 四行），同时 `PluginEmptyHint` 删除时残留了两个孤立 `}`。这类错误编译器会报，但更快的定位方式是：**改完先扫一遍「每个 `@Composable` 函数是否成对闭合」**，再构建。

## 7. 验收要点

- [ ] 进页面第一眼：总览卡显示状态灯 / 优先级 / 三个统计，无需滚动；
- [ ] 点分组头整行可展开/收起，**内容高度平滑过渡（不是硬切换）**，箭头同步旋转，状态在旋屏后保持；
- [ ] 收起时分组之间**没有多余空隙**（收起态相邻分组头的间距 = 展开态分组头间距）；
- [ ] 展开后卡片是**错峰上浮淡入**（约 45ms/项），不是整块同时出现；
- [ ] LX 分组默认展开且能看到导入行与脚本列表；
- [ ] 总览卡点「可用性测试」→ 按钮变「测试中」→ 结果**平滑展开**并逐行淡入，显示 `X / N 项可用`；
- [ ] 空态（无脚本 / 无插件）是紧凑一行，不占整屏；
- [ ] 底部留白正确（内容能滚到底部玻璃栏下面，末项仍可点击）—— 见 `LocalBottomBarInset`；
- [ ] 点「选择 JS 文件 / 选择 JS 插件」可**一次选中多个文件**（系统文件选择器支持多选）；
- [ ] 多选后**只弹一次**汇总 Snackbar，形如「已导入 3 个脚本，跳过重复 2 个」；
- [ ] 重复导入同一个文件 → 提示「已存在相同脚本，跳过：XXX」，列表**不新增条目**；
- [ ] 同一批次里选了同一文件的多个副本 → 只进 1 条，其余计入「跳过重复」；
- [ ] 内容有实质改动的不同版本 → 正常并存（不被误判为重复）。

## 8. 解析链路：逐平台自定义（取代旧「全局优先级」）

### 8.1 为什么改

旧版只有**一个全局开关**（Key 优先 / 脚本优先 / 仅脚本 / 仅 Key），无法表达：
- 「酷狗先用概念版，网易云先用脚本」这类**分平台**差异；
- 三/四个音源**任意排序**；
- MusicFree 插件参与排序（旧版插件被**硬编码**钉在链路最后）。

现在每个平台一条**独立链路**：有序引擎列表 + 逐项启停。

### 8.2 数据模型

`core/model/SourceEngine.kt` — 链路上的一个环节：

| 引擎 | id | 物理解析器 | 平台约束 |
| --- | --- | --- | --- |
| Key 代理 | `key` | `LxResolver` | 全平台 |
| LX 脚本 | `script` | `ScriptMusicResolver` | 全平台（脚本不支持时跳过） |
| MusicFree 插件 | `plugin` | `MusicFreeResolver` | 全平台（插件不覆盖时跳过） |
| 酷狗概念版 | `kglite` | `KgLiteResolver` | **仅酷狗**（`SourceEngine.supports`） |

`core/model/SourceChain.kt` — 单平台链路：

```json
{ "platformId": "wy", "engines": ["script","key","plugin"], "disabled": ["plugin"] }
```

| 语义 | 说明 |
| --- | --- |
| `engines` | **顺序即尝试顺序**（从上到下） |
| `disabled` | 关掉的项**仍留在 `engines` 原位**（重开即恢复原位置），只是解析时跳过 |
| `activeEngines()` | 生效链 = 顺序 ∩ 未被禁用 ∩ 该平台支持 |
| 不允许全关 | `toggle` 拒绝关掉最后一项（返回 `this`），UI 侧提示「至少保留一项」 |

**默认链有意与枚举声明顺序不同**：酷狗概念版排第一 —— 它是唯一「不配置任何东西就能播」的通道，
排前可避免先撞上「未配置 Key」的空转与误导报错（与旧版 `kgLiteForce` 默认开启一致）。

### 8.3 解析流程（`MusicRepository.resolveWithFallback`）

```
汽水曲目 → 直连（不在链路内，汽水无其他通道）
        ↓ 否则
按 song.platform 取链路 → 依次尝试 activeEngines()
        ├─ EngineUnavailable（未配置/平台不支持）→ 静默跳过，不算失败
        └─ 其他异常 → 记日志，落到下一项
        ↓ 全部失败
跨平台兜底（可关）→ 到其他平台找同名曲替换
```

> ⚠️ **`EngineUnavailable` 是关键区分**。没配 Key、没导入插件时，若当成普通失败，
> 用户会看到一串「Key 解析失败」的误导日志 —— 其实只是他没配。现在这类直接静默跳过。

### 8.4 旧设置迁移

| 旧 `source_priority` | 新链路 |
| --- | --- |
| `key_first`（默认） | 各平台默认链（酷狗概念版优先） |
| `script_first` | 脚本提到最前，其余保持默认相对顺序 |
| `key_only` | 仅保留 Key |
| `script_only` | 仅保留脚本 |

迁移**惰性发生**：`SourceChain.sanitize(stored, legacy)` 在读取设置时执行 ——
没存过 `source_chains_json` 就用 `legacy` 生成，存过则以链路为准。
旧键 `source_priority` **保留不删**（回滚安全），`setSourcePriority` 已 `@Deprecated`。

**健壮性**（都有单测）：非法 JSON → 当作没存过；非法引擎 id → 丢弃；
应用升级新增引擎 → 追加到末尾（不会因为老数据里没有就永远用不上）；
平台不支持的引擎（概念版混进网易云）→ 过滤掉。

### 8.5 UI（`SourceChainEditor`）

放在「音源管理 → 解析链路」，默认收起。交互要点：

1. **平台分页**（FilterChip：网易云 / QQ / 酷狗）：一屏只看一条链；被改过的平台带小圆点。
2. **顺序即优先级**：每行左侧有序号，右侧 `↑↓`；到顶/到底自动**禁用变灰**（不是「点了没反应」）。
3. **逐项开关**：关掉的项留在原位；最后一项不可关。
4. **每项可单独试听**：点「试听此项」跑一次**只走该引擎、不兜底**的真实解析（`resolveWithSingleEngine`），
   结果就地显示 ✓/✗ —— 用户排完顺序最大的疑问是「这么排真的行吗」，就地验证。
5. **不支持的引擎不下架、只置灰**：显示「仅酷狗曲库可用」，避免用户以为没这功能。
6. **底部**：跨平台兜底总开关 + 恢复本平台默认。

汽水音乐**不出现在此页**（无 Key/脚本/概念版通道，只有开关控制的直连）。

### 8.6 云同步

`source_chains_json` / `cross_platform_fallback` 已加入 `SyncScopes.SETTINGS_KEYS`。

> ⚠️ 老设备同步回来的 `source_priority` 不再驱动解析。若用户之前选过「仅脚本」，
> 云端旧值仍在（迁移依据），但一旦本机写入过 `source_chains_json`，就以链路为准。

### 8.7 已知边界

- **DSP / 缓存不受影响**：链路只改「去哪儿取地址」，不改变已解析地址的复用（各 resolver 自带 LRU）。
- **改动链路后不会自动重解析当前曲目**：需切歌或手动刷新（避免改配置时打断正在播放的歌）。
- 恢复默认是**逐平台**的，没有「一键全部恢复」—— 三个平台各点一次即可，低频操作。

## 9. 验收要点

- [ ] 三个平台 Tab 切换正常，被改过的平台显示小圆点；
- [ ] `↑↓` 改变顺序后，**实际播放确实按新顺序尝试**（可用「试听此项」交叉验证）；
- [ ] 关掉某项后该项留在原位、重新打开位置不变；
- [ ] 只剩一项时无法再关（提示「至少保留一项启用的音源」）；
- [ ] 网易云 / QQ 下「酷狗概念版」置灰显示「仅酷狗曲库可用」；
- [ ] 酷狗 Tab 下概念版默认排第一；
- [ ] 旧版选过「仅脚本」的设备升级后，链路确实只剩脚本；
- [ ] 关掉跨平台兜底后，本平台解析失败会**直接报错**、不再换成别的平台的歌。

## 10. 细分 JS 优先级：逐平台「脚本 / 插件」顺序

### 10.1 为什么还要再分一层

§8 的链路里，`script` 与 `plugin` 各只是**一个环节**。但用户往往导入了**多个** LX 脚本、
**多个** MusicFree 插件，同样需要「A 失败落到 B」的细分排序。这一层就补上它。

### 10.2 引擎从「单激活」改成「多实例池」

原来 `UserApiEngine` / `MusicFreeEngine` 都是**单 QuickJS 上下文 + 单激活脚本**：

```
UserApiEngine   单上下文，单 currentScriptId
ScriptEngineStatus.Ready(scriptId, …)   只装「那一个」激活脚本的能力
```

要「A 失败自动落到 B」，**B 必须此刻也在内存里、且已上报过自身平台能力** —— 单上下文做不到。
于是每个 JS 一个独立引擎实例，由池统一管理：

| 池 | 文件 | 职责 |
| --- | --- | --- |
| `ScriptEnginePool` | `core/script/ScriptEnginePool.kt` | 多脚本实例；`reconcile` 幂等对齐；`queryMemoryUsage` |
| `PluginEnginePool` | `core/script/PluginEnginePool.kt` | 多插件实例；同构；另有 `unload`（用户变量变更后重挂） |

`reconcile(wanted)`：**缺的加载、多的卸载**，重复调用不重复加载。
只卸载「不再被任何平台启用」的 JS —— 在某平台临时关掉、别的平台仍启用时无需重新加载（重载 JS 明显更慢）。

两个引擎各自抽出一个**最小契约**（`ScriptEngine` / `PluginEngine`）：加载、销毁、状态、读内存、下发请求。
目的是让池与解析器能在**普通 JVM 单测**里用假引擎驱动（真实引擎依赖 QuickJS + `HandlerThread`，只能在设备上跑）。

### 10.3 数据模型

`core/model/ScriptOrder.kt` —— **平台 × 类型 × 有序列表**：

```json
{ "platformId": "wy", "kind": "script",
  "refs": [ {"id":"a","enabled":true}, {"id":"b","enabled":false} ] }
```

| 概念 | 说明 |
| --- | --- |
| `ScriptRef` | 链上一项：`id` + `enabled` |
| `ScriptKind` | `script`（LX 脚本） / `plugin`（MusicFree 插件） |
| `ScriptOrder.move` | 上下移动（越界为空操作） |
| `ScriptOrder.toggle` | 启停；**拒绝关掉最后一项启用的**（返回 `this`，UI 提示） |
| `ScriptOrder.reconcile` | 新导入项追加到末尾并默认启用；已删除项移除；保持既有顺序 |
| `ScriptOrder.sanitize` | 补齐缺失的「平台 × 类型」组合、清非法项、对齐可用项 |

* 链上**只有一项**（或空链）时也允许，空链是正常的初始状态。

**驻留规则**（`residentIds`）：一个 JS 只要被**任一平台**启用就要驻留。
若写成「所有平台都启用才驻留」，用户在网易云临时关掉一个脚本、切到 QQ 又要重载一遍，说不通。

### 10.4 解析流程

`ScriptMusicResolver` / `MusicFreeResolver` 按**用户为该平台排的顺序**依次尝试：

```
取该平台该类型的 enabledIds（顺序即尝试顺序）
  → 过滤「未驻留 / 引擎未就绪 / 未声明该平台能力」
  → 逐个尝试：每个脚本内部仍走音质降档链（如 flac → 320k → 128k）
  → 首个成功即返回；都失败抛出带平台信息的 ResolveException
```

试听（配置页「试听此项」）走 `resolveWithScript` / `resolveWithPlugin`：**不走链、不兜底** ——
用户最想知道的就是「这一项单独到底行不行」，混进兜底会被下一个脚本救回来、界面显示成功，看不出问题。

### 10.5 内存：不设硬上限，改为实时提示

多实例的代价是内存。池**不设硬上限**（要自由度），而是把**真实占用**透出来（`queryMemoryUsage` → `memoryUsage`），
UI 每行显示该项占用、总览卡显示合计，超过 **64MB** 时给出警示。

### 10.6 UI（`ScriptOrderEditor`，脚本 / 插件共用）

与 §8.5 的链路编辑器**同一交互模型**（平台分页、序号 + `↑↓`、逐项开关、恢复默认）：
- 每行显示**该项内存占用**；
- 每行可「试听此项」（不走链、不兜底）；
- 总览卡显示合计占用，> 64 MB 警示。

### 10.7 持久化与云同步

设置键 `script_orders_json`；`ScriptOrder.sanitize` 惰性补齐 / 清理。
对外暴露给 UI 的列表在 ViewModel 里**再对齐一次** —— 仓库那边只负责**池**，
而 UI 需要的是「完整可展示的列表」（含尚未加载好的项）。

### 10.8 单测

| 测试 | 覆盖 |
| --- | --- |
| `ScriptOrderTest`（19） | 顺序、启停、`move` 不重复不丢项（回归）、`reconcile` 幂等、`sanitize`、驻留规则、kind 隔离、JSON 往返 |
| `ScriptEnginePoolTest`（12） | 加载 / 卸载 / 幂等 / 能力判定 / 内存汇总 / `destroyAll` / 重挂是新实例 |
| `PluginEnginePoolTest`（12） | 同上 + 平台覆盖判定（「网易云」↔「网易云音乐」/ 代号 / 未声明不拦）+ `unload` → 重挂 |
| `ScriptMusicResolverOrderTest`（9） | 落到下一项、顺序即优先级、交换顺序生效、档位降档、停用/不支持静默跳过、`resolveWithScript` 不兜底 |

> ⚠️ 本地单测的 `android.jar` 是 mockable jar，`org.json.*` 全是空实现
> （`JSONObject.getJSONObject` 恒返回 null），走 JSON 的解析逻辑会误判失败。
> 已在 `app/build.gradle` 加 `testImplementation("org.json:json:20240303")` 提供真实实现。
> `android.util.LruCache` 同样是空实现 —— 因此**不测缓存命中**（测出来的是环境假象）。

### 10.9 验收要点

- [ ] 导入多个 LX 脚本后，能在每个平台分别排序、逐项启停；
- [ ] 排在前的脚本失败时，**确实落到下一个**（可把第一个的排序调换验证结果变化）；
- [ ] 关掉某平台的某个脚本，别的平台该脚本**仍然可用**（驻留不受影响）；
- [ ] 每行显示该项内存占用，总览卡显示合计；开很多时 > 64MB 有警示；
- [ ] 「试听此项」只反映该项自身成败（不兜底）；
- [ ] 不能把某平台该类型的最后一项关掉（提示「至少保留一项启用的…」）。
