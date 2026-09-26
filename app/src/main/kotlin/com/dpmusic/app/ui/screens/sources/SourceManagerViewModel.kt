package com.dpmusic.app.ui.screens.sources

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.script.MusicFreeEngine
import com.dpmusic.app.core.script.MusicFreePluginRepository
import com.dpmusic.app.core.script.PluginImportResult
import com.dpmusic.app.core.script.ScriptImportResult
import com.dpmusic.app.core.script.SourceTestResult
import com.dpmusic.app.core.script.UserApiEngine
import com.dpmusic.app.core.script.UserApiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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
    private val engine: UserApiEngine,
    private val settings: SettingsRepository,
    private val plugins: MusicFreePluginRepository,
    private val pluginEngine: MusicFreeEngine,
    private val music: MusicRepository,
) : ViewModel() {

    val scripts = repository.scripts
    val activeId = repository.activeId
    val engineStatus = engine.status

    /* ---------------- MusicFree 插件 ---------------- */

    val pluginList = plugins.plugins
    val activePluginId = plugins.activeId
    val pluginStatus = pluginEngine.status

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
            } finally {
                _testingItem.value = ""
                _testing.value = false
            }
        }
    }

    /** 音源解析优先级（脚本 vs Key 代理） */
    val sourcePriority = settings.settings
        .map { it.sourcePriority }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourcePriority.KEY_FIRST)

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** 从 SAF 文件导入脚本 */
    fun importFromUri(context: Context, uri: Uri) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                }
                when (val result = repository.importScript(text)) {
                    is ScriptImportResult.Success -> _message.value = "已导入：${result.script.name}"
                    is ScriptImportResult.Error -> _message.value = result.message
                }
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
                    is ScriptImportResult.Error -> _message.value = result.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    /** 设置音源解析优先级 */
    fun setSourcePriority(priority: SourcePriority) {
        viewModelScope.launch {
            settings.setSourcePriority(priority)
        }
    }

    /** 启用 / 停用脚本 */
    fun toggleActive(id: String) {
        viewModelScope.launch {
            if (activeId.value == id) {
                repository.deactivate()
                _message.value = "已停用"
            } else {
                repository.setActive(id)
                _message.value = "已启用，正在加载脚本…"
            }
        }
    }

    /** 删除脚本 */
    fun remove(id: String) {
        viewModelScope.launch {
            repository.remove(id)
            _message.value = "已删除"
        }
    }

    /* ---------------- MusicFree 插件操作 ---------------- */

    /** 从 SAF 文件导入插件 */
    fun importPluginFromUri(context: Context, uri: Uri) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                }
                val fileName = uri.lastPathSegment.orEmpty().substringBeforeLast('.').substringAfterLast('/')
                when (val result = plugins.importPlugin(text, fileName)) {
                    is PluginImportResult.Success -> _message.value = "已导入插件：${result.plugin.name}"
                    is PluginImportResult.Error -> _message.value = result.message
                }
            } catch (e: Exception) {
                _message.value = "读取文件失败：${e.message ?: "未知错误"}"
            } finally {
                _importing.value = false
            }
        }
    }

    /** 从链接导入插件 */
    fun importPluginFromUrl(url: String) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                when (val result = plugins.importFromUrl(url)) {
                    is PluginImportResult.Success -> _message.value = "已导入插件：${result.plugin.name}"
                    is PluginImportResult.Error -> _message.value = result.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    /** 启用 / 停用插件 */
    fun togglePlugin(id: String) {
        viewModelScope.launch {
            if (activePluginId.value == id) {
                plugins.deactivate()
                _message.value = "已停用插件"
            } else {
                plugins.setActive(id)
                _message.value = "已启用插件，正在挂载…"
            }
        }
    }

    /** 删除插件 */
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
