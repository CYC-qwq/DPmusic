package com.dpmusic.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.data.QqRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.QqApi
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.download.DownloadPaths
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.util.CoverPalette
import com.dpmusic.app.core.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel：
 * - 音频偏好（默认平台 / 默认音质）持久化到 DataStore；
 * - 外观（M3 动态取色 / 深色模式三态）；
 * - 缓存管理（封面图片缓存 + 音源解析缓存），带操作反馈消息。
 */
class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val player: PlayerConnection,
    private val context: Context,
    private val ncm: NcmRepository,
    private val ncmApi: NcmApi,
    private val qq: QqRepository,
    private val qqApi: QqApi,
) : ViewModel() {

    val settings = settingsRepo.settings

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage = _cacheMessage.asStateFlow()

    /** 存储占用快照（封面/音源图片缓存 + 临时文件） */
    private val _storageUsage = MutableStateFlow(StorageManager.Usage())
    val storageUsage = _storageUsage.asStateFlow()

    init {
        refreshStorageUsage()
    }

    /** 刷新存储占用（IO 线程统计） */
    fun refreshStorageUsage() {
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) { StorageManager.usage(context) }
            _storageUsage.value = usage
        }
    }

    /** 设置图片缓存上限：持久化 + 立即重建缓存（按 LRU 收缩到新上限内） */
    fun setMaxStorageMb(mb: Int) {
        viewModelScope.launch {
            settingsRepo.setMaxStorageMb(mb)
            withContext(Dispatchers.IO) { StorageManager.applyImageCap(context, mb) }
            delay(300)
            refreshStorageUsage()
            _cacheMessage.value = "已应用新的存储上限：${StorageManager.capLabel(mb)}"
        }
    }

    /** 智能清理：临时文件优先 → 图片缓存按最久未用收缩到上限内 */
    fun smartClean() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { StorageManager.smartClean(context) }
            refreshStorageUsage()
            val freed = StorageManager.formatBytes(result.freedBytes)
            _cacheMessage.value = when {
                result.actions.isEmpty() -> "存储占用正常，无需清理"
                result.freedBytes > 0 -> "智能清理完成：释放 $freed（${result.actions.joinToString("、")}）"
                else -> "已执行：${result.actions.joinToString("、")}"
            }
        }
    }

    fun setPlatform(platform: MusicPlatform) {
        viewModelScope.launch { settingsRepo.setDefaultPlatform(platform) }
    }

    fun setQuality(quality: PlayQuality) {
        viewModelScope.launch { settingsRepo.setQuality(quality) }
    }

    private var apiKeySaveJob: Job? = null

    /** 音源 Key：防抖写入（输入停顿 300ms 后落盘） */
    fun setLxApiKey(key: String) {
        apiKeySaveJob?.cancel()
        apiKeySaveJob = viewModelScope.launch {
            delay(300)
            settingsRepo.setLxApiKey(key)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }
    }

    fun setDarkMode(mode: Int) {
        viewModelScope.launch { settingsRepo.setDarkMode(mode) }
    }

    fun setClipboardAutoRead(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setClipboardAutoRead(enabled) }
    }

    fun setVerbatimLyric(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setVerbatimLyric(enabled) }
    }

    fun setSimulatedVerbatim(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSimulatedVerbatim(enabled) }
    }

    /** 歌词简转繁显示 */
    fun setLyricS2T(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setLyricS2T(enabled) }
    }

    fun setListShowSource(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowSource(enabled) }
    }

    fun setDownloadWriteTags(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDownloadWriteTags(enabled) }
    }

    fun setDownloadWriteCover(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDownloadWriteCover(enabled) }
    }

    fun setDownloadEmbedLyric(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDownloadEmbedLyric(enabled) }
    }

    fun setListShowAlbumName(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowAlbumName(enabled) }
    }

    fun setListShowDuration(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowDuration(enabled) }
    }

    fun setListShowCover(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowCover(enabled) }
    }

    /** 选择主题色：应用所选配色，并自动关闭动态取色（保证选择立即生效） */
    fun setThemeColor(id: String) {
        viewModelScope.launch {
            settingsRepo.setThemeColor(id)
            settingsRepo.setDynamicColor(false)
        }
    }

    fun setGlassMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setGlassMode(enabled) }
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
                CoverPalette.clear()
            }
            refreshStorageUsage()
            _cacheMessage.value = "封面缓存已清理"
        }
    }

    fun clearResolveCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { AppContainer.musicRepository.clearResolveCache() }
            }
            _cacheMessage.value = "音源解析缓存已清理"
        }
    }

    /* ---------------- 下载路径 ---------------- */

    private val _downloadPathCheck = MutableStateFlow<DownloadPaths.Check?>(null)
    val downloadPathCheck = _downloadPathCheck.asStateFlow()

    /** 校验下载路径；可写时保存并提示，不可写时给出原因（UI 引导授权 / 重新填写） */
    fun saveDownloadPathIfValid(path: String) {
        viewModelScope.launch {
            _downloadPathCheck.value = null
            val check = withContext(Dispatchers.IO) {
                DownloadPaths.check(context, DownloadPaths.resolve(context, path))
            }
            _downloadPathCheck.value = check
            if (check is DownloadPaths.Check.Ok) {
                settingsRepo.setDownloadDir(path.trim())
                _cacheMessage.value = "下载路径已更新：${DownloadPaths.resolve(context, path).absolutePath}"
            }
        }
    }

    fun consumeDownloadPathCheck() {
        _downloadPathCheck.value = null
    }

    fun consumeMessage() {
        _cacheMessage.value = null
    }

    /* ---------------- 网易云音乐（一起听账号） ---------------- */

    val ncmProfile = ncm.profile

    /** 红心同步状态（自动双向同步） */
    val ncmSyncState = AppContainer.ncmSync.state

    private val _ncmLogin = MutableStateFlow(NcmLoginUi())
    val ncmLogin = _ncmLogin.asStateFlow()

    /** 手动填写 Cookie：先校验再保存 */
    fun saveNcmCookie(raw: String) {
        val cookie = raw.trim()
        if (cookie.isBlank()) {
            _ncmLogin.update { it.copy(message = "请先粘贴 Cookie") }
            return
        }
        viewModelScope.launch {
            _ncmLogin.update { it.copy(busy = true, message = null, success = false) }
            val result = runCatching { ncmApi.loginStatus(cookie) }
            val profile = result.getOrNull()
            when {
                result.isFailure -> _ncmLogin.update {
                    it.copy(busy = false, message = "网络异常：${result.exceptionOrNull()?.message ?: "请稍后重试"}")
                }

                profile == null -> _ncmLogin.update {
                    it.copy(busy = false, message = "Cookie 无效或已过期，请重新获取")
                }

                else -> {
                    ncm.saveCookie(cookie, profile)
                    // 登录成功 → 立即触发一次红心同步（自动双向）
                    AppContainer.ncmSync.syncNow()
                    _ncmLogin.update { it.copy(busy = false, success = true, message = "登录成功：${profile.nickname}") }
                }
            }
        }
    }

    /** 退出网易云登录（清除本地 Cookie） */
    fun clearNcmCookie() {
        viewModelScope.launch {
            ncm.clearCookie()
            _ncmLogin.value = NcmLoginUi(message = "已退出网易云登录")
        }
    }

    fun consumeNcmLoginMessage() {
        _ncmLogin.update { it.copy(message = null, success = false) }
    }

    /** 手动触发红心同步 */
    fun syncNcmLikes() = AppContainer.ncmSync.syncNow()

    /** 消费红心同步提示 */
    fun consumeNcmSyncMessage() = AppContainer.ncmSync.consumeMessage()

    /* ---------------- QQ 音乐（账号 / 红心同步） ---------------- */

    val qqProfile = qq.profile

    /** 红心同步状态（自动双向同步） */
    val qqSyncState = AppContainer.qqSync.state

    private val _qqLogin = MutableStateFlow(QqLoginUi())
    val qqLogin = _qqLogin.asStateFlow()

    /** 手动填写 Cookie：先校验再保存 */
    fun saveQqCookie(raw: String) {
        val cookie = raw.trim()
        if (cookie.isBlank()) {
            _qqLogin.update { it.copy(message = "请先粘贴 Cookie") }
            return
        }
        viewModelScope.launch {
            _qqLogin.update { it.copy(busy = true, message = null, success = false) }
            val result = runCatching { qqApi.loginStatus(cookie) }
            val profile = result.getOrNull()
            when {
                result.isFailure -> _qqLogin.update {
                    it.copy(busy = false, message = "网络异常：${result.exceptionOrNull()?.message ?: "请稍后重试"}")
                }
                profile == null -> _qqLogin.update {
                    it.copy(busy = false, message = "Cookie 无效或已过期，请重新获取")
                }
                else -> {
                    qq.saveCookie(cookie, profile)
                    // 登录成功 → 立即触发一次红心同步（自动双向）
                    AppContainer.qqSync.syncNow()
                    _qqLogin.update { it.copy(busy = false, success = true, message = "登录成功：${profile.nickname}") }
                }
            }
        }
    }

    /** 退出 QQ 音乐登录（清除本地 Cookie） */
    fun clearQqCookie() {
        viewModelScope.launch {
            qq.clearCookie()
            _qqLogin.value = QqLoginUi(message = "已退出 QQ 音乐登录")
        }
    }

    fun consumeQqLoginMessage() {
        _qqLogin.update { it.copy(message = null, success = false) }
    }

    /** 手动触发红心同步 */
    fun syncQqLikes() = AppContainer.qqSync.syncNow()

    /** 消费红心同步提示 */
    fun consumeQqSyncMessage() = AppContainer.qqSync.consumeMessage()
}

/** 网易云登录 UI 状态（设置页「网易云音乐」卡片 / 弹窗） */
data class NcmLoginUi(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)

/** QQ 音乐登录 UI 状态（设置页「QQ 音乐」卡片 / 弹窗） */
data class QqLoginUi(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)

