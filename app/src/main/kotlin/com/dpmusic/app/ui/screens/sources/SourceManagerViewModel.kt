package com.dpmusic.app.ui.screens.sources

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.script.ScriptImportResult
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
) : ViewModel() {

    val scripts = repository.scripts
    val activeId = repository.activeId
    val engineStatus = engine.status

    /** 音源解析优先级（脚本 vs Key 代理） */
    val sourcePriority = settings.settings
        .map { it.sourcePriority }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourcePriority.SCRIPT_FIRST)

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

    fun consumeMessage() {
        _message.value = null
    }
}
