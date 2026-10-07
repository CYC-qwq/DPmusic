# 贡献指南

感谢关注 DPmusic。本项目是一个纯客户端的聚合音乐播放器，欢迎提交问题与改进。

## 提交 Issue 前

请先确认：

1. **已查阅文档** —— [`README.md`](README.md) 与 [`docs/`](docs/) 覆盖了绝大多数使用问题。
2. **说明环境** —— 机型、Android 版本、安装的是哪个变体（`withsdk` / `nosdk`）、应用版本号。
3. **附上日志** —— 应用内「设置 → 日志」可导出；崩溃请附 `logcat` 中 `FATAL EXCEPTION` 段落。
4. **不要贴敏感信息** —— 提交日志前请**自行抹掉**账号 Cookie、音源 Key、WebDAV 密码、汽水中转密钥等。

> ⚠️ **不要在本仓库的 Issue / PR 中粘贴任何真实凭据**。密钥一旦公开即视为泄漏，请立即更换。

## 开发环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | Platform 36（`compileSdk 36`） |
| Gradle | 使用仓库自带 Wrapper（`./gradlew`），勿用系统 Gradle |

首次打开需配置 SDK 路径（`local.properties`，**不纳入版本控制**）：

```properties
sdk.dir=/path/to/Android/Sdk
```

## 构建与测试

```bash
# 单元测试（协议编解码 / 服务端路由 / CENC 解密等，纯 JVM）
./gradlew :app:test

# Debug 包
./gradlew :app:assembleDebug

# 发布变体（见 README「发布变体」）
./gradlew :app:assembleWithsdkRelease
./gradlew :app:assembleNosdkRelease
```

**提交前请确保 `./gradlew :app:test` 通过。** 涉及音源解析、协议解析、解密的改动，请一并补充或更新对应单测。

## 代码约定

- **语言 / 风格**：100% Kotlin，沿用现有代码的注释风格（中文注释，说明「为什么」而非「做了什么」）。
- **依赖注入**：使用既有的 `AppContainer` 手工容器，**不引入 Hilt / kapt / ksp**（保持零注解处理开销）。
- **网络层**：走统一的 `Http` / `HttpClient`，不要在业务代码里直接 `new OkHttpClient()`。
- **缓存**：新增缓存请使用 `BoundedCache`（**上限必填**），不要写无界 `mutableMapOf`——这是历史上出现过的内存泄漏来源。
- **资源释放**：注册监听器 / 持有 `Context` 时注意生命周期；系统内存压力请接入 `MemoryPressureCenter`。
- **R8**：新增依赖若使用反射 / JNI / `@JavascriptInterface`，请在 `app/proguard-rules.pro` 补 keep 规则。

## 关于音源与合规

- 本仓库**不含**任何有效的音源服务 Key、账号 Cookie 或中转密钥，请勿在 PR 中加入。
- 请勿提交用于绕过平台付费 / 权益校验的代码。
- 涉及第三方接口的实现，请遵守对应平台的服务条款。

## Pull Request

1. 从 `main` 切出分支，命名建议：`fix/xxx`、`feat/xxx`。
2. 保持单个 PR 聚焦一件事；大改动请先开 Issue 讨论。
3. PR 描述写清：**改动动机 → 具体改动 → 验证方式**（附测试结果或截图）。
4. 确保未提交构建产物、签名文件、`local.properties` 与任何凭据。
