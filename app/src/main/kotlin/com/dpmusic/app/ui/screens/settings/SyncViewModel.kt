package com.dpmusic.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.lansync.LanDevice
import com.dpmusic.app.core.lansync.LanSendResult
import com.dpmusic.app.core.lansync.LanSyncEvent
import com.dpmusic.app.core.lansync.LanSyncManager
import com.dpmusic.app.core.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 数据同步页 ViewModel：WebDAV 配置 + 手动同步操作 + 局域网设备同步。
 *
 * 局域网接收端**不再绑在本页生命周期上**：它由设置 `lanSyncReceiveEnabled` 驱动
 * （见 [LanSyncManager.applyReceiveSetting]，进程启动即常驻）。本页只负责
 * 把界面事件接到 [LanSyncManager.setEventSink]，让用户看到传输进度。
 *
 * 早期版本「进页面才开监听、离开就关」是错的：对端在任意时刻扫描都发现不了本机，
 * 用户看到的现象就是「找不到设备」。接收属于**长期状态**而非页面状态。
 */
class SyncViewModel(
    private val settingsRepo: SettingsRepository,
    private val sync: SyncManager,
    private val lan: LanSyncManager,
) : ViewModel() {

    val settings = settingsRepo.settings

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /* ---------------- 局域网设备同步 ---------------- */

    /** 已发现的局域网设备（含 LocalSend 客户端） */
    val lanDevices: StateFlow<List<LanDevice>> = lan.devices

    private val _lanScanning = MutableStateFlow(false)
    val lanScanning: StateFlow<Boolean> = _lanScanning.asStateFlow()

    private val _lanMessage = MutableStateFlow<String?>(null)
    val lanMessage: StateFlow<String?> = _lanMessage.asStateFlow()

    private val _lanServerRunning = MutableStateFlow(false)
    val lanServerRunning: StateFlow<Boolean> = _lanServerRunning.asStateFlow()

    /** 本机在局域网中的地址（供用户手填到对端，组播不可靠时有用） */
    private val _lanLocalAddresses = MutableStateFlow<List<String>>(emptyList())
    val lanLocalAddresses: StateFlow<List<String>> = _lanLocalAddresses.asStateFlow()

    /**
     * 进入同步页：接上事件回调并扫描一次。
     *
     * 接收端的启停不在这里 —— 它由设置驱动、进程内常驻（见类注释）。
     */
    fun onEnterLanSection() {
        _lanLocalAddresses.value = com.dpmusic.app.core.lansync.LanHttpClient.localIpv4Addresses()
        lan.setEventSink { event -> _lanMessage.value = event.describe() }
        _lanServerRunning.value = lan.serverRunning
        scanLanDevices()
    }

    /**
     * 离开同步页：只断开事件回调与发现结果，**不停接收端**。
     *
     * 停了端口对端就再也发现不了本机 —— 这正是先前「找不到设备」的根因。
     */
    fun onLeaveLanSection() {
        lan.setEventSink(null)
        lan.clearDevices()
    }

    /**
     * 开关「允许其他设备向我发送数据」。
     *
     * 落到设置里，由 [LanSyncManager.applyReceiveSetting] 的收集协程即时启停；
     * 失败（端口被占用等）会在 [lanMessage] 里给出原因。
     */
    fun setLanServerEnabled(enabled: Boolean) = viewModelScope.launch {
        _lanMessage.value = null
        settingsRepo.setLanSyncReceiveEnabled(enabled)
        // 启停由 LanSyncManager.applyReceiveSetting 的收集协程统一负责（唯一生命周期所有者，
        // 避免这里与收集协程同时 bind）。写入是异步的，稍等一拍再回读真实状态。
        kotlinx.coroutines.delay(150)
        _lanServerRunning.value = lan.serverRunning
        // 打开开关但实际没能监听 → 给出失败原因（端口被占用等），否则用户会以为已经开着
        if (enabled && !lan.serverRunning) {
            _lanMessage.value = lan.lastStartFailure()
                ?: "接收端未启动，请检查端口是否被占用"
        }
    }

    /** 扫描局域网设备（组播优先，无结果时自动兜底扫网段）。 */
    fun scanLanDevices() {
        if (_lanScanning.value) return
        viewModelScope.launch {
            _lanScanning.value = true
            try {
                val found = lan.scan()
                _lanMessage.value = when {
                    found.isNotEmpty() -> "发现 ${found.size} 台设备"
                    // 给可执行的下一步，而不是只说「没找到」
                    else -> "未发现设备。请确认对端已打开 DPmusic 或 LocalSend 且在同一网络"
                }
            } catch (e: Exception) {
                _lanMessage.value = "扫描失败：${e.message ?: "未知错误"}"
            } finally {
                _lanScanning.value = false
            }
        }
    }

    /** 把本机数据推送到指定设备（覆盖语义，调用方需先弹确认框）。 */
    fun sendToLanDevice(device: LanDevice, includeSettings: Boolean, includeLists: Boolean) {
        viewModelScope.launch {
            _lanMessage.value = "正在发送到 ${device.displayName}…"
            _lanMessage.value = when (val result = lan.sendTo(device, includeSettings, includeLists)) {
                is LanSendResult.Success -> result.detail
                is LanSendResult.Failed -> "失败：${result.reason}"
            }
        }
    }

    fun setLanSyncAlias(alias: String) = viewModelScope.launch {
        settingsRepo.setLanSyncAlias(alias)
        // 别名会随注册应答返回给对端，改完必须让缓存失效，否则界面显示的还是旧名字
        lan.refreshSelfInfo()
    }

    /** PIN：接收端据此校验来者，发送端据此通过对方校验（双向同一字段）。 */
    fun setLanSyncPin(pin: String) = viewModelScope.launch {
        settingsRepo.setLanSyncPin(pin)
    }

    /**
     * 协议端口。非法输入（非数字 / 越界）由仓储夹到 1024–65535，
     * 但**改端口需要重启接收端**才会生效，这里提示用户。
     */
    fun setLanSyncPort(port: String) {
        val value = port.trim().toIntOrNull()
        if (value == null) {
            if (port.isBlank()) _lanMessage.value = null
            return
        }
        viewModelScope.launch {
            settingsRepo.setLanSyncPort(value)
            lan.refreshSelfInfo()
            // 端口变更后的「停旧起新」由收集协程统一负责（唯一生命周期所有者），这里只回读状态。
            kotlinx.coroutines.delay(150)
            _lanServerRunning.value = lan.serverRunning
            _lanMessage.value = when {
                !settings.value.lanSyncReceiveEnabled -> "端口已设为 $value"
                lan.serverRunning -> "端口已切换为 $value"
                else -> lan.lastStartFailure() ?: "端口已改为 $value，但接收端未在监听"
            }
        }
    }

    /* ---------------- WebDAV ---------------- */

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

/** 把接收端事件转成一句可展示的文案（界面不关心事件类型，只关心「发生了什么」）。 */
private fun LanSyncEvent.describe(): String = when (this) {
    is LanSyncEvent.Accepted -> "$from 正在发送：${files.joinToString(" + ")}"
    is LanSyncEvent.Progress -> "接收中…（${bytes / 1024} KB）"
    is LanSyncEvent.Receiving -> "已收齐 ${totalBytes / 1024} KB，正在应用…"
    is LanSyncEvent.Completed -> "已从局域网接收：$summary"
    is LanSyncEvent.Rejected -> "已拒绝：$reason"
    is LanSyncEvent.Failed -> "失败：$reason"
    is LanSyncEvent.Cancelled -> "对端已取消"
}
