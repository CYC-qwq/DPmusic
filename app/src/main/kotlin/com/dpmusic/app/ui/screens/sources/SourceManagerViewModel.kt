package com.dpmusic.app.ui.screens.sources

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.defaultSourceChains
import com.dpmusic.app.core.data.defaultScriptOrders
import com.dpmusic.app.core.data.enabledPlatforms
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.ScriptKind
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SourceChain
import com.dpmusic.app.core.model.SourceEngine
import com.dpmusic.app.core.repo.EngineUnavailable
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.script.MusicFreeEngine
import com.dpmusic.app.core.script.MusicFreePluginRepository
import com.dpmusic.app.core.script.PluginEnginePool
import com.dpmusic.app.core.script.PluginImportResult
import com.dpmusic.app.core.script.ScriptEnginePool
import com.dpmusic.app.core.script.ScriptImportResult
import com.dpmusic.app.core.script.SourceTestResult
import com.dpmusic.app.core.script.UserApiEngine
import com.dpmusic.app.core.script.UserApiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源管理页 ViewModel：
 * - 脚本列表 / 激活状态（来自 UserApiRepository）；
 * - 引擎运行状态（来自 UserApiEngine）；
 * - 导入（SAF 文件 / 直链）、启用停用、删除，带操作反馈消息。
 */
class SourceManagerViewModel(
    private val repository: UserApiRepository,
    private val settings: SettingsRepository,
    private val plugins: MusicFreePluginRepository,
    private val music: MusicRepository,
    private val scriptPool: ScriptEnginePool,
    private val pluginPool: PluginEnginePool,
) : ViewModel() {

    val scripts = repository.scripts

    /**
     * 当前**已启用**的平台（供「解析链路 / JS 顺序」编辑器过滤）。
     * 关掉的音源不应出现在这些逐平台配置里 —— 否则用户会为「已关闭」的平台白配一通。
     */
    val enabledPlatforms: StateFlow<List<MusicPlatform>> = settings.settings
        .map { it.enabledPlatforms() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicPlatform.entries)

    /* ---------------- MusicFree 插件 ---------------- */

    val pluginList = plugins.plugins

    /* ---------------- 音源可用性测试 ---------------- */

    /** 测试项状态：null = 未测；true/false = 结果；测试中由 [testing] 标记 */
    private val _testResults = MutableStateFlow<List<SourceTestResult>>(emptyList())
    val testResults = _testResults.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()

    private val _testingItem = MutableStateFlow("")
    val testingItem = _testingItem.asStateFlow()

    /**
     * 逐项跑一遍音源可用性测试：
     * 三大平台直连 API → Key 远端代理 → LX 脚本 → MusicFree 插件。
     *
     * 每项独立计时、独立成败，任何一项失败都不影响后续项；
     * 结果按完成顺序实时回填（UI 上能看到进度逐条点亮）。
     */
    fun runSourceTests() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            _testResults.value = emptyList()
            try {
                // 探测用歌曲：优先用网易云搜索的第一条真实结果，保证测试的是真实解析链路
                _testingItem.value = "准备测试样本…"
                val probe = runCatching {
                    music.api(MusicPlatform.WY).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                }.getOrNull() ?: PROBE_SONG

                suspend fun record(block: suspend () -> SourceTestResult) {
                    _testingItem.value = "测试中…"
                    val result = runCatching { block() }
                        .getOrElse { SourceTestResult("未知音源", false, it.message ?: "测试异常", 0L) }
                    _testResults.value = _testResults.value + result
                }

                record { music.testPlatformApi(MusicPlatform.WY) }
                record { music.testPlatformApi(MusicPlatform.QQ) }
                record { music.testPlatformApi(MusicPlatform.KG) }
                record { music.testKeySource(probe) }
                record { music.testScriptSource(probe) }
                record { music.testPluginSource(probe) }
                // 只测**已启用**的直连音源：关掉的音源测了也无意义（反而显示一片红）
                val enabled = settings.settings.value.enabledPlatforms()
                if (MusicPlatform.QS in enabled) {
                    record { music.testPlatformApi(MusicPlatform.QS) }
                    record {
                        val qsProbe = runCatching {
                            music.api(MusicPlatform.QS).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                        }.getOrNull()
                        if (qsProbe == null) {
                            SourceTestResult("汽水音乐直连", false, "汽水搜索无结果，无法测试", 0L)
                        } else {
                            music.testQishuiSource(qsProbe)
                        }
                    }
                }
                // B 站直连：同理，用 B 站曲目做样本（非 B 站曲目会被 canResolve 拒掉）
                if (MusicPlatform.BB in enabled) {
                    record { music.testPlatformApi(MusicPlatform.BB) }
                    record {
                        val bbProbe = runCatching {
                            music.api(MusicPlatform.BB).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                        }.getOrNull()
                        if (bbProbe == null) {
                            SourceTestResult("哔哩哔哩直连", false, "B 站搜索无结果，无法测试", 0L)
                        } else {
                            music.testBiliSource(bbProbe)
                        }
                    }
                }
            } finally {
                _testingItem.value = ""
                _testing.value = false
            }
        }
    }

    /** 逐平台解析链路（网易云 / QQ / 酷狗 各一条） */
    val sourceChains: StateFlow<List<SourceChain>> = settings.settings
        .map { it.sourceChains }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultSourceChains())

    /* ---------------- JS 顺序与启停（逐平台 × 逐类型） ---------------- */

    /** 顺序配置的**原始快照**（含不在链上的 id 也不管，交给 UI 与可用列表对齐） */
    private val rawOrders: StateFlow<List<ScriptOrder>> = settings.settings
        .map { it.scriptOrders }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultScriptOrders())

    /** 已导入的脚本 id（按导入顺序） */
    private val scriptIds: StateFlow<List<String>> = repository.scripts
        .map { list -> list.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 已导入的插件 id */
    private val pluginIds: StateFlow<List<String>> = plugins.plugins
        .map { list -> list.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * JS 顺序（已与「实际可用的条目」对齐）。
     *
     * 组合三方：原始配置 + 已导入列表 → [ScriptOrder.sanitize] 补齐 / 清理。
     * 之所以在 ViewModel 里再对齐一次（仓库里也对齐过），是因为仓库那边只负责**池**，
     * 而 UI 需要的是「完整可展示的列表」——包括那些还没加载好的项。
     */
    val scriptOrders: StateFlow<List<ScriptOrder>> =
        combine(rawOrders, scriptIds, pluginIds) { orders, scripts, pls ->
            ScriptOrder.sanitize(
                orders,
                mapOf(ScriptKind.SCRIPT to scripts, ScriptKind.PLUGIN to pls),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultScriptOrders())

    /** 脚本池的实时内存占用（字节，键为 scriptId） */
    val scriptMemory: StateFlow<Map<String, Long>> = scriptPool.memoryUsage

    /** 插件池的实时内存占用（字节，键为 pluginId） */
    val pluginMemory: StateFlow<Map<String, Long>> = pluginPool.memoryUsage

    /** 当前驻留（已加载进内存）的脚本 id 集合 —— 「实时占用」提示用 */
    val residentScriptIds: StateFlow<Set<String>> =
        settings.settings
            .map { scriptPool.residentIds() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 当前驻留的插件 id 集合 */
    val residentPluginIds: StateFlow<Set<String>> =
        settings.settings
            .map { pluginPool.residentIds() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 刷新两个池的内存占用（打开页面 / 改动顺序后调用） */
    fun refreshMemoryUsage() {
        scriptPool.refreshMemoryUsage()
        pluginPool.refreshMemoryUsage()
    }

    /** 上移 / 下移某平台链上的一个 JS */
    fun moveScript(order: ScriptOrder, id: String, up: Boolean) {
        viewModelScope.launch { settings.setScriptOrder(order.move(id, up)) }
    }

    /** 启用 / 停用某平台链上的一个 JS（拒绝关掉最后一项时给出提示） */
    fun toggleScript(order: ScriptOrder, id: String) {
        val next = order.toggle(id)
        if (next === order) {
            _message.value = "至少保留一项启用的${order.scriptKind.label}"
            return
        }
        viewModelScope.launch { settings.setScriptOrder(next) }
    }

    /** 某脚本 / 插件的加载状态（UI 显示「未加载 / 加载中 / 就绪 / 失败」） */
    fun scriptStatus(id: String) = repository.statusOf(id)

    fun pluginStatusOf(id: String) = plugins.statusOf(id)

    /**
     * 试听**链上的某一个脚本**（不走链、不兜底）。
     *
     * 用户排完顺序最想知道的是「这一项单独到底行不行」；混进兜底就看不出来了 ——
     * 失败了会被下一个脚本救回来，界面显示成功，用户以为它没问题。
     */
    fun probeScript(platform: MusicPlatform, scriptId: String) {
        val key = "${platform.id}:$scriptId"
        if (_probingEngine.value != null) return
        viewModelScope.launch {
            _probingEngine.value = key
            try {
                val song = music.api(platform).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                val result = if (song == null) {
                    SourceTestResult("脚本", false, "该平台搜不到测试曲目", 0L)
                } else {
                    val started = System.currentTimeMillis()
                    try {
                        val r = music.resolveWithScript(scriptId, song)
                        SourceTestResult("脚本", true, "解析成功 · ${r.url.take(56)}…", System.currentTimeMillis() - started)
                    } catch (e: Exception) {
                        SourceTestResult("脚本", false, e.message ?: "解析失败", System.currentTimeMillis() - started)
                    }
                }
                _engineProbeResults.value = _engineProbeResults.value + (key to result)
            } finally {
                _probingEngine.value = null
            }
        }
    }

    /** 试听**链上的某一个插件**（不走链、不兜底） */
    fun probePlugin(platform: MusicPlatform, pluginId: String) {
        val key = "${platform.id}:$pluginId"
        if (_probingEngine.value != null) return
        viewModelScope.launch {
            _probingEngine.value = key
            try {
                val song = music.api(platform).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                val result = if (song == null) {
                    SourceTestResult("插件", false, "该平台搜不到测试曲目", 0L)
                } else {
                    music.testPluginSource(pluginId, song)
                }
                _engineProbeResults.value = _engineProbeResults.value + (key to result)
            } finally {
                _probingEngine.value = null
            }
        }
    }

    /** 「全部失败 → 跨平台兜底」总开关 */
    val crossPlatformFallback: StateFlow<Boolean> = settings.settings
        .map { it.crossPlatformFallback }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前正在「试听此项」的引擎（跨平台区分：同引擎在不同平台可能一起显示） */
    private val _probingEngine = MutableStateFlow<String?>(null)
    val probingEngine = _probingEngine.asStateFlow()

    /** 单引擎试听结果（键 = "${platformId}:${engineId}"） */
    private val _engineProbeResults = MutableStateFlow<Map<String, SourceTestResult>>(emptyMap())
    val engineProbeResults = _engineProbeResults.asStateFlow()

    private fun probeKey(platform: MusicPlatform, engine: SourceEngine) = "${platform.id}:${engine.id}"

    /** 上移 / 下移某个平台链路里的一个引擎 */
    fun moveEngine(chain: SourceChain, engine: SourceEngine, up: Boolean) {
        viewModelScope.launch { settings.setSourceChain(chain.move(engine, up)) }
    }

    /**
     * 启用 / 停用链路里的一个引擎。
     *
     * 若这是最后一项仍启用的引擎（[SourceChain.toggle] 会拒绝），提示用户 ——
     * 否则界面看起来「点了没反应」，用户不知道发生了什么。
     */
    fun toggleEngine(chain: SourceChain, engine: SourceEngine) {
        val next = chain.toggle(engine)
        if (next === chain) {
            _message.value = "至少保留一项启用的音源"
            return
        }
        viewModelScope.launch { settings.setSourceChain(next) }
    }

    /** 恢复某平台的出厂链路 */
    fun resetChain(platform: MusicPlatform) {
        viewModelScope.launch {
            settings.resetSourceChain(platform)
            _message.value = "已恢复 ${platform.label} 的默认链路"
        }
    }

    /** 「全部失败 → 跨平台兜底」总开关 */
    fun setCrossPlatformFallback(enabled: Boolean) {
        viewModelScope.launch { settings.setCrossPlatformFallback(enabled) }
    }

    /**
     * 只测**一个引擎**（不走链路、不兜底）。
     *
     * 取一首该平台的真实歌曲（搜索探针词）来跑，这样结果反映的是「当下这条链上的
     * 这一项，对一首普通歌到底行不行」，而不是抽象的能力判断。
     */
    fun probeEngine(platform: MusicPlatform, engine: SourceEngine) {
        val key = probeKey(platform, engine)
        if (_probingEngine.value != null) return
        viewModelScope.launch {
            _probingEngine.value = key
            try {
                val song = music.api(platform).searchSongs(PROBE_KEYWORD, 1, 1).firstOrNull()
                val result = if (song == null) {
                    SourceTestResult(engine.label, false, "该平台搜不到测试曲目，无法测试", 0L)
                } else {
                    val started = System.currentTimeMillis()
                    try {
                        val resolved = music.resolveWithSingleEngine(engine, song)
                        SourceTestResult(
                            engine.label, true,
                            "解析成功 · ${resolved.url.take(56)}…",
                            System.currentTimeMillis() - started,
                        )
                    } catch (e: EngineUnavailable) {
                        SourceTestResult(engine.label, false, "当前不可用：${e.message}", 0L)
                    } catch (e: Exception) {
                        SourceTestResult(
                            engine.label, false, e.message ?: "解析失败",
                            System.currentTimeMillis() - started,
                        )
                    }
                }
                _engineProbeResults.value = _engineProbeResults.value + (key to result)
            } finally {
                _probingEngine.value = null
            }
        }
    }

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /**
     * 从 SAF 多选文件批量导入脚本。
     *
     * 逐个读取后交给仓库批量导入：内容重复的自动跳过，最后统一汇报
     * 「已导入 N 个 · 跳过重复 M 个 · 失败 K 个」，避免用户点一次弹一次。
     */
    fun importFromUris(context: Context, uris: List<Uri>) {
        if (_importing.value || uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val texts = withContext(Dispatchers.IO) { uris.map { readText(context, it) } }
                val batch = repository.importScriptsBatch(texts)
                _message.value = batch.summary("脚本")
            } catch (e: Exception) {
                _message.value = "读取文件失败：${e.message ?: "未知错误"}"
            } finally {
                _importing.value = false
            }
        }
    }

    /** 从链接下载并导入脚本 */
    fun importFromUrl(url: String) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                when (val result = repository.importFromUrl(url)) {
                    is ScriptImportResult.Success -> _message.value = "已导入：${result.script.name}"
                    is ScriptImportResult.Duplicate -> _message.value = "已存在相同脚本，跳过：${result.existing.name}"
                    is ScriptImportResult.Error -> _message.value = result.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    /** 删除脚本（顺序里的项会随之下线；仓库负责清理） */
    fun remove(id: String) {
        viewModelScope.launch {
            repository.remove(id)
            _message.value = "已删除"
        }
    }

    /* ---------------- MusicFree 插件操作 ---------------- */

    /**
     * 从 SAF 多选文件批量导入插件。
     *
     * 逐个读取（同时带上文件名，供插件无 `platform` 字段时兜底命名）后交给仓库批量导入，
     * 内容重复的自动跳过，最后统一汇报汇总结果。
     */
    fun importPluginsFromUris(context: Context, uris: List<Uri>) {
        if (_importing.value || uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val items = withContext(Dispatchers.IO) {
                    uris.map { readText(context, it) to fileNameOf(it) }
                }
                val batch = plugins.importPluginsBatch(items)
                _message.value = batch.summary("插件")
            } catch (e: Exception) {
                _message.value = "读取文件失败：${e.message ?: "未知错误"}"
            } finally {
                _importing.value = false
            }
        }
    }

    /** 读取 SAF 文件为 UTF-8 文本（读不到时返回空串，由仓库给出「内容为空」提示） */
    private suspend fun readText(context: Context, uri: Uri): String =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
        }

    /** 从 SAF uri 取不带扩展名的文件名（用于插件兜底命名） */
    private fun fileNameOf(uri: Uri): String =
        uri.lastPathSegment.orEmpty().substringBeforeLast('.').substringAfterLast('/')

    /** 从链接导入插件 */
    fun importPluginFromUrl(url: String) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                when (val result = plugins.importFromUrl(url)) {
                    is PluginImportResult.Success -> _message.value = "已导入插件：${result.plugin.name}"
                    is PluginImportResult.Duplicate -> _message.value = "已存在相同插件，跳过：${result.existing.name}"
                    is PluginImportResult.Error -> _message.value = result.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    /** 启用 / 停用插件 */
    /** 删除插件（顺序里的项会随之下线；仓库负责清理） */
    fun removePlugin(id: String) {
        viewModelScope.launch {
            plugins.remove(id)
            _message.value = "已删除插件"
        }
    }

    /** 读取插件的用户变量（打开设置弹窗时用） */
    suspend fun pluginVariables(id: String): Map<String, String> = plugins.userVariables(id)

    /** 保存插件的用户变量（如 Cookie）；激活插件会立即重新挂载 */
    fun savePluginVariables(id: String, variables: Map<String, String>) {
        viewModelScope.launch {
            plugins.saveUserVariables(id, variables)
            _message.value = "已保存插件配置"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        /** 探测样本关键词（网易云搜索第一条作为测试样本） */
        const val PROBE_KEYWORD = "晴天"

        /** 搜索不可用时的兜底样本（保证解析链路测试仍可执行） */
        val PROBE_SONG = Song(
            id = "186016",
            platform = MusicPlatform.WY,
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L,
        )
    }
}
