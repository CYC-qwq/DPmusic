# 代码质量排查报告（v1.1.0）

> 排查时间：2026-09-26 ｜ 范围：202 个 Kotlin 文件（45,824 行）+ 2,615 行 JS
> 备份：`/sdcard/Download/DPmusic-src-20260926-0417.tar.gz`（801 KB，不含构建产物）

## 1. 排查方法

| 手段 | 覆盖内容 |
| --- | --- |
| 静态扫描 | TODO/FIXME、非空断言 `!!`、`GlobalScope`、`runBlocking`、`Thread.sleep`、裸 `CoroutineScope`、资源未关闭、监听器未注销、硬编码凭据、列表 key |
| 编译警告 | `--clean` 全量构建 → 逐条分类（26 条） |
| 人工审查 | 播放链路、存储清理、下载、HTTP、JS 引擎、启动路径、权限、导航 |
| 边界推演 | 空队列 / 单曲 / 重复歌曲 / 大歌单 / 离线 / 连切 / 权限未授 / 进程重建 |

## 2. 结论

**未发现会导致崩溃或数据损坏的缺陷。** 以下为逐条验证过的设计（附代码依据）：

| 维度 | 结论 | 依据 |
| --- | --- | --- |
| 主线程 IO | 零 | HTTP 全 `suspend + withContext(IO)`；存储统计/清理、小组件取图均切 IO |
| 协程 | 无泄漏 | 无 `GlobalScope`；裸 `CoroutineScope` 均为单例成员（与进程同寿命） |
| 资源关闭 | 规范 | `use{}` 普遍；`RandomAccessFile` 手动 close 时机正确 |
| 列表 key | 安全 | 全用 `stableKey`；`addSongs` 与已有内容双重去重 → 不会 key 冲突 |
| 网络韧性 | 完善 | 超时 + 指数退避重试 + 按平台熔断 + 下载失败自动降档 |
| 连切优化 | 有效 | 合并窗口 + 序号取代 + 连按期间不预解析 + 切歌取消上一个歌词请求 |
| 启动路径 | 轻量 | `onCreate` 仅 4 件事，预热全异步 |
| 权限 | 齐全 | 通知（13+）、存储、悬浮窗均有检查与引导 |

## 3. 本次已修复

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | 缺少 `@OptIn(DelicateCoilApi::class)`（使用内部 API 未声明契约） | `StorageManager.applyImageCap` |
| 2 | `String.format` 未指定 Locale → 本地化区域会输出阿拉伯数字 | `StorageManager.formatBytes` |
| 3 | 未使用的 `import kotlinx.coroutines.runBlocking`（易误解为有阻塞调用） | `PlayerConnection.kt` |
| 4 | **20 处图标未用 `AutoMirrored`** → RTL 语言下不镜像 | 10 个文件 |
| 5 | **release 构建无签名配置** → `--release` 只能出装不上的包 | `app/build.gradle` |
| 6 | `.backup/` 内 17 个文件仍含旧 API Key | 已删除，全项目 0 残留 |

修复后：`e: file` = 0、AutoMirrored 警告归零、debug 与 release 均构建成功且 release 已签名。

## 4. 待处理（含未处理原因）

| 优先级 | 项 | 说明 | 为什么没做 |
| --- | --- | --- | --- |
| P2 | `Virtualizer` API 已废弃（6 处） | Android 15+ 上均衡器的"虚拟环绕"会失效 | 需替代方案（如 Spatializer），属功能级改动，需真机听感验证 |
| P2 | `rememberSwipeToDismissBoxState(confirmValueChange)` 已废弃 | 未来 Compose 升级会失效，滑动删除需迁移到 anchor 方案 | **本机 `input swipe` 注入无效**，无法自动验证滑动体验；滑动删除是高频交互，盲改风险大于收益 |
| P3 | `MANAGE_EXTERNAL_STORAGE` | Google Play 上架会被拒（自用/侧载无碍） | 属产品定位选择 |
| P3 | `NcmEapi.kt` 硬编码 `EAPI_KEY` | 网易云社区公开固定密钥，非私密凭据 | 可接受，建议补注释说明来源 |
| P3 | `WebLoginDialog:198 databaseEnabled`、`DesktopLyricView:741 scaledDensity` | 无害的 Java 层废弃 API | 无替代品 |

## 5. 边界情况核查表

| 场景 | 结论 | 依据 |
| --- | --- | --- |
| 歌单内重复歌曲 | ✅ 不会崩 | `UserPlaylistRepository:108-110` 与已有内容去重 |
| 连切 20 首 | ✅ 只加载 1 首 | `SKIP_COALESCE_MS` 合并窗口 + `preloadNext` 连按期间跳过 |
| 切歌时歌词堆积 | ✅ 取消上一个 | `LyricsHub:76,82` `loadJob?.cancel()` |
| 队列被替换时旧解析结果 | ✅ 丢弃 | `skipSeq` + `queueEpoch` + `slotStillMatches` |
| 解析失败 | ✅ 跨平台兜底 → 连续失败 2 次跳过 | `MusicRepository:104-115` |
| 缓存超限 | ✅ 自动清理（实测 378MB → 212MB） | `StorageManager` |
| 进程被杀后恢复 | ✅ 会话持久化 | `PlaybackSessionStore` |
| 权限未授权 | ✅ 有引导/降级 | `MainActivity` / `DownloadPaths` |
| 弱网长稳 / 超大歌单 / RTL / release 实机 | ⚠️ **未实测** | 需真机长稳测试 |

## 6. 回归测试入口

```bash
# cheerio 兼容层：54 项断言（秒级，无需真机）
node tools/tests/cheerio-compat.test.js

# MusicFree 插件挂载端到端验证（mock 宿主跑真实插件源码）
node tools/tests/plugin-mount.test.js /path/to/plugin.js

# 从设备提取插件源码（⚠️ 导出的 ds_b64.txt 含全部设置，用完即删）
run-as com.dpmusic.app sh -c 'base64 files/datastore/dpmusic_store.preferences_pb' > ds_b64.txt
python3 tools/extract_plugins.py ds_b64.txt ./plugins
```

构建与安装纪律见 `docs/STORAGE.md` §4（含「不要用 `grep 'Task :app:compileDebugKotlin'` 当判据」的踩坑记录）。