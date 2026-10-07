package com.dpmusic.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.lansync.LanDevice
import com.dpmusic.app.core.lansync.LanSendResult
import com.dpmusic.app.core.lansync.LanSyncManager
import com.dpmusic.app.core.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放流转的 ViewModel（主页「流转」Sheet 作用域）。
 *
 * 与同步页的局域网区块共用同一个 [LanSyncManager]（因此共用发现结果与身份），
 * 但**意图不同**：这里只管「把当前播放的队列与进度推给对方接着播」，
 * 不涉及任何设置 / 歌单的覆盖式同步，也不启动接收端。
 *
 * ## 为什么不启动接收端
 *
 * 流转是**单向推送**：本机是发送方，需要的是对端在监听。本机开不开接收端
 * 与本次流转无关，开了只会白占端口。接收端仍由同步页的开关控制（见 SyncViewModel）。
 *
 * ## 为什么不做二次确认
 *
 * 流转不覆盖对端的任何持久化数据（纯播放指令），且随时可被对端自己点播打断，
 * 因此点击设备即推，不做「不可撤销」的确认框 —— 那是给破坏性操作准备的。
 */
class PlaybackCastViewModel(
    private val lan: LanSyncManager,
    private val player: PlayerConnection,
) : ViewModel() {

    /** 已发现的设备（转发自 LanSyncManager，与同步页共享） */
    val devices: StateFlow<List<LanDevice>> = lan.devices

    /** 待流转内容：当前播放曲目与队列，用于 Sheet 里预览「要推什么」 */
    val nowPlaying = player.nowPlaying
    val queue = player.queue

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 正在推送的目标设备 host（null = 空闲），用于禁用重复点击并显示进度 */
    private val _castingHost = MutableStateFlow<String?>(null)
    val castingHost: StateFlow<String?> = _castingHost.asStateFlow()

    /** Sheet 打开：清掉上次的过期结果并扫描一次。 */
    fun onEnter() {
        _message.value = null
        scan()
    }

    /** Sheet 关闭：清空发现结果，避免过期设备留在下次打开时。 */
    fun onLeave() {
        lan.clearDevices()
        _castingHost.value = null
    }

    /** 扫描局域网设备（组播优先，无结果时自动兜底扫网段）。 */
    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val found = lan.scan()
                _message.value = when {
                    found.isNotEmpty() -> "发现 ${found.size} 台设备"
                    // 给出可执行的下一步，而不是只说「没找到」
                    else -> "未发现设备。请确认对端已打开 DPmusic 并在同一 Wi-Fi 下"
                }
            } catch (e: Exception) {
                _message.value = "扫描失败：${e.message ?: "未知错误"}"
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * 把当前播放队列与进度流转到 [device]。
     *
     * 队列为空（没播过任何东西）时直接拒绝：让用户去看一条「没有内容」的网络往返
     * 毫无意义，而且这不是错误，只是还没开始听。
     */
    fun castTo(device: LanDevice) {
        if (_castingHost.value != null) return
        if (queue.value.songs.isEmpty()) {
            _message.value = "还没有播放内容，先播放一首歌再流转"
            return
        }
        viewModelScope.launch {
            _castingHost.value = device.host
            _message.value = "正在流转到 ${device.displayName}…"
            try {
                _message.value = when (val result = lan.sendPlayback(device)) {
                    is LanSendResult.Success -> result.detail
                    is LanSendResult.Failed -> "失败：${result.reason}"
                }
            } finally {
                _castingHost.value = null
            }
        }
    }
}
