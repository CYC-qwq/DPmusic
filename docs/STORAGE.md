# 存储与缓存：上限 + 自动清理

## 1. 用户可见行为

设置 → 存储与缓存：

| 元素 | 说明 |
| --- | --- |
| 封面 / 音源图片缓存、临时文件 | 当前占用（进入本页时会先做一次超限检查，所以数字是清理后的真实值） |
| **最大可占用** | 256MB / 512MB / 1GB / 2GB / 不限制 —— **选定后，缓存总占用一旦超过该上限就自动清理**，无需手动操作 |
| 状态行 | `当前占用 X / 上限 Y`；超出时高亮为 `已超出上限，将自动清理` |
| 立即清理 | 手动兜底入口（临时文件 + 图片缓存按最久未用收缩） |
| 清空图片缓存 / 清理音源解析缓存 | 保持不变 |

## 2. 实现（`core/util/StorageManager.kt`）

### 触发时机（未超限时零开销）

1. **应用启动**：`DPmusicApp.onCreate` → `StorageManager.startAutoClean(this)`；
2. **设置流**：守护协程 `collect` 设置流 —— DataStore 加载完成 / 上限被改动后立即判断
   （启动瞬间读到的是默认值，只靠首帧会漏判）；
3. **周期**：每 10 分钟一次；
4. **进入设置页**：`SettingsViewModel.refreshStorageUsage()` 先清理再统计。

### 清理步骤（按优先级）

| 步骤 | 动作 |
| --- | --- |
| ① | 临时分享文件（`cache/shared`）全清 —— 完全可再生 |
| ② | 图片缓存（`cache/coil3_disk_cache`）超上限 → 按「最久未用」删除到上限内，并重建加载器让上限持续生效 |
| ③ | 自动清理时若总占用仍超限 → 图片缓存进一步收缩到上限的 80%（`capMb > 0` 才做，避免「不限」被误收缩） |
| ④ | 仍超限（占用来自其它缓存子目录）→ 整缓存目录按最久未用裁剪到上限的 80%，**跳过** `logs` / `Crash Reports` 等诊断目录 |

### 两个关键坑

1. **Coil3 磁盘缓存在打开时不会立即按 `maxSizeBytes` 收缩**（只在后续写入时逐出），
   所以「重建加载器」并不能真正把占用降下来 —— 必须自己按 mtime 删文件（`trimDirByLru` / `trimCacheDir`）。
2. **启动瞬间 `settings.value` 是 DataStore 的默认值**（1024MB），不是用户设置值。
   若只在启动时判断一次，用户设的 256MB 上限会被忽略 → 必须跟随设置流再判断一次。

## 3. 验证记录（真机）

在缓存里放一个 200MB 探针文件，使总占用 378MB > 上限 256MB，重启应用：

```
collect capMb=1024 total=393994527   ← 启动首帧读到默认值，未超限，不动手
cleaned freed=179319182 actions=[其它缓存文件]   ← 读到真实上限 256MB → 自动清理释放 171MB
collect capMb=256  total=214675345   ← 稳定在上限内
```

`du -sk cache`：378MB → 212MB（< 256MB 上限）。

## 4. 构建纪律（重要）

本工程的 Gradle 增量构建**出现过「BUILD SUCCESSFUL 但 `compileDebugKotlin` 未执行（UP-TO-DATE）」**的情况，
导致改动的 Kotlin 代码没有进 APK。

> ⚠️ **不要用 `grep 'Task :app:compileDebugKotlin'` 当判据** —— 实测该脚本的日志里 **Gradle 任务行是不完整记录的**
> （39 个任务实际执行，日志里只能捞到 12 个 `Task :app:*`），Kotlin 任务名经常压根不出现，会误判成「没编译」。

**可靠判据（三项一起看）**：

```bash
# ① 编译错误数（必须为 0）
grep -ac 'e: file' /tmp/dpmusic-build.log

# ② Kotlin 编译器真的跑过 —— 看编译器输出，而不是 Gradle 任务行
#    （有 w: file:///.../xxx.kt 之类的警告行 = 编译器执行过）
grep -a -c 'file:///.*\.kt' /tmp/dpmusic-build.log

# ③ 编译产物时间戳刷新（比 sha 更直接）
ls -l --time-style=+%H:%M:%S app/build/tmp/kotlin-classes/debug/com/dpmusic/app/<改动的类>.class

# ④ 产物 sha 变化
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

**更硬的一招 —— 反汇编验证改动真的进了产物**（零风险、秒级）：

```bash
# JDK 自带 javap 可用；直接看编译出的 class 字节码
javap -c -p -classpath app/build/tmp/kotlin-classes/debug <全限定类名>
javap -c -p -classpath app/build/tmp/kotlin-classes/debug '<外部类名>$Companion'
```

若 ②③④ 任一可疑 → 用 `bash tools/build-and-install.sh --clean` 强制全量构建（约 2m20s，39 tasks 全执行）。
