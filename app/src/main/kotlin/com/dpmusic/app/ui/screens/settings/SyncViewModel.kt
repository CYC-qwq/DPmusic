package com.dpmusic.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 数据同步页 ViewModel：WebDAV 配置 + 手动同步操作 */
class SyncViewModel(
    private val settingsRepo: SettingsRepository,
    private val sync: SyncManager,
) : ViewModel() {

    val settings = settingsRepo.settings

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun testConnection() = launchOp {
        sync.testConnection()
        "连接成功"
    }

    fun uploadSettings() = launchOp {
        sync.uploadSettings()
        "「设置与音源」已上传到云端"
    }

    fun downloadSettings() = launchOp {
        if (sync.downloadSettings()) "「设置与音源」已从云端恢复" else "云端未找到设置文件"
    }

    fun uploadLists() = launchOp {
        sync.uploadLists()
        "「歌单与数据」已上传到云端"
    }

    fun downloadLists() = launchOp {
        if (sync.downloadLists()) "「歌单与数据」已从云端恢复" else "云端未找到歌单文件"
    }

    fun setWebdavEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setWebdavEnabled(enabled)
    }

    fun setWebdavUrl(url: String) = viewModelScope.launch {
        settingsRepo.setWebdavUrl(url)
    }

    fun setWebdavUsername(name: String) = viewModelScope.launch {
        settingsRepo.setWebdavUsername(name)
    }

    fun setWebdavPassword(password: String) = viewModelScope.launch {
        settingsRepo.setWebdavPassword(password)
    }

    fun setWebdavPath(path: String) = viewModelScope.launch {
        settingsRepo.setWebdavPath(path)
    }

    fun setWebdavAutoSync(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setWebdavAutoSync(enabled)
    }

    private fun launchOp(block: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                _message.value = block()
            } catch (e: Exception) {
                _message.value = "失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }
}
