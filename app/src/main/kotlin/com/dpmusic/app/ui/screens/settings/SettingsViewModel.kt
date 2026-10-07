package com.dpmusic.app.ui.screens.settings

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.data.BiliRepository
import com.dpmusic.app.core.data.KgLiteRepository
import com.dpmusic.app.core.data.QqRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.KgLiteProfile
import com.dpmusic.app.core.net.BiliPlatformApi
import com.dpmusic.app.core.net.KgLiteApi
import com.dpmusic.app.core.net.KgLiteLoginApi
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.QqApi
import com.dpmusic.app.core.net.bool
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.download.DownloadPaths
import com.dpmusic.app.core.miisland.MiIslandMode
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.util.CoverPalette
import com.dpmusic.app.core.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val kgLite: KgLiteRepository,
    private val kgLiteApi: KgLiteApi,
    private val kgLiteLoginApi: KgLiteLoginApi,
    private val bili: BiliRepository,
    private val biliApi: BiliPlatformApi,
) : ViewModel() {

    val settings = settingsRepo.settings

    /* ---------------- 哔哩哔哩账号（可选登录） ---------------- */

    /** 当前 B 站账号（含会员态）；未登录为 null */
    val biliAccount = bili.account

    private val _biliLogin = MutableStateFlow(BiliLoginUi())
    val biliLogin = _biliLogin.asStateFlow()

    /** 校验 Cookie → 保存登录态；成功后刷新账号资料（会员态决定音质上限） */
    fun saveBiliCookie(raw: String) {
        val cookie = raw.trim()
        if (cookie.isBlank()) {
            _biliLogin.update { it.copy(message = "请先粘贴 Cookie") }
            return
        }
        viewModelScope.launch {
            _biliLogin.update { it.copy(busy = true, message = null, success = false) }
            val result = runCatching { biliApi.account(cookie) }
            val account = result.getOrNull()
            when {
                result.isFailure -> _biliLogin.update {
                    it.copy(busy = false, message = "网络异常：${result.exceptionOrNull()?.message ?: "请稍后重试"}")
                }
                account == null -> _biliLogin.update {
                    it.copy(busy = false, message = "Cookie 无效或已过期，请重新获取")
                }
                else -> {
                    bili.saveLogin(cookie, account)
                    _biliLogin.update {
                        it.copy(busy = false, success = true, message = "登录成功：${account.summary}")
                    }
                }
            }
        }
    }

    /** 退出 B 站登录（清除本地 Cookie 与资料） */
    fun clearBiliLogin() {
        viewModelScope.launch {
            bili.clearLogin()
            _biliLogin.value = BiliLoginUi(message = "已退出哔哩哔哩登录")
        }
    }

    /** 手动刷新账号资料（会员态变化后，如刚开大会员） */
    fun refreshBiliAccount() {
        viewModelScope.launch {
            _biliLogin.update { it.copy(busy = true, message = null) }
            AppContainer.refreshBiliAccount()
            val acc = bili.account.value
            _biliLogin.update {
                it.copy(busy = false, message = if (acc != null) "已刷新：${acc.summary}" else "未登录")
            }
        }
    }

    fun consumeBiliMessage() {
        _biliLogin.update { it.copy(message = null, success = false) }
    }

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage = _cacheMessage.asStateFlow()

    /** 存储占用快照（封面/音源图片缓存 + 临时文件） */
    private val _storageUsage = MutableStateFlow(StorageManager.Usage())
    val storageUsage = _storageUsage.asStateFlow()

    init {
        refreshStorageUsage()
    }

    /** 刷新存储占用（IO 线程统计）；统计前先做一次「超限自动清理」，保证展示值就是清理后的真实占用 */
    fun refreshStorageUsage() {
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) {
                StorageManager.autoCleanIfNeeded(context)
                StorageManager.usage(context)
            }
            _storageUsage.value = usage
        }
    }

    /** 设置图片缓存上限：持久化 + 立即重建缓存（按 LRU 收缩到新上限内）+ 立即做一次超限自动清理 */
    fun setMaxStorageMb(mb: Int) {
        viewModelScope.launch {
            settingsRepo.setMaxStorageMb(mb)
            val cleaned = withContext(Dispatchers.IO) {
                StorageManager.applyImageCap(context, mb)
                StorageManager.autoCleanIfNeeded(context)
            }
            delay(300)
            refreshStorageUsage()
            _cacheMessage.value = when {
                cleaned != null && cleaned.freedBytes > 0 ->
                    "上限已设为 ${StorageManager.capLabel(mb)}，已自动清理并释放 " +
                        StorageManager.formatBytes(cleaned.freedBytes)
                else -> "上限已设为 ${StorageManager.capLabel(mb)}，超出后会自动清理"
            }
        }
    }

    /** 手动立即清理（自动清理之外的兜底入口）：临时文件优先 → 图片缓存按最久未用收缩到上限内 */
    fun smartClean() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { StorageManager.smartClean(context) }
            refreshStorageUsage()
            val freed = StorageManager.formatBytes(result.freedBytes)
            _cacheMessage.value = when {
                result.actions.isEmpty() -> "存储占用正常，无需清理"
                result.freedBytes > 0 -> "清理完成：释放 $freed（${result.actions.joinToString("、")}）"
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

    /** 车载 / 蓝牙歌词（把当前歌词行写进媒体元数据标题 + lyricInfo extras） */
    fun setCarLyricEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setCarLyricEnabled(enabled) }
    }

    /** 「发送整首 LRC」（额外把整首带时间轴的歌词写进 extras） */
    fun setCarLyricFullLrc(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setCarLyricFullLrc(enabled) }
    }

    /** 小米超级岛形态（HyperOS；关闭 / 歌词 / 发光歌词） */
    fun setMiIslandMode(mode: MiIslandMode) {
        viewModelScope.launch { settingsRepo.setMiIslandMode(mode) }
    }

    fun setListShowSource(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowSource(enabled) }
    }

    fun setListShowQuality(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setListShowQuality(enabled) }
    }

    fun setQualityAutoHighest(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setQualityAutoHighest(enabled) }
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

    /** 播放页封面动态取色开关（仅影响全屏播放页，不动全局主题） */
    fun setCoverDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setCoverDynamicColor(enabled) }
    }

    /** 播放页封面动态取色：切换配色风格 */
    fun setCoverColorStyle(id: String) {
        viewModelScope.launch { settingsRepo.setCoverColorStyle(id) }
    }

    // ---- 音频 DSP（v1.2.0）----

    fun setBitPerfectEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBitPerfectEnabled(enabled) }
    }

    fun setVisualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setVisualizerEnabled(enabled) }
    }

    fun setVisualizerMode(mode: String) {
        viewModelScope.launch { settingsRepo.setVisualizerMode(mode) }
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

    /** 红心同步状态（单向：云端 → 本地） */
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
                    // 登录成功 → 立即拉取一次云端红心（单向）
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
            // 同时清空 WebView 的 Cookie 容器：否则「快速登录」会立刻用旧票据登回上一个账号
            clearWebLoginCookies()
            _ncmLogin.value = NcmLoginUi(message = "已退出网易云登录")
        }
    }

    /**
     * 清空 WebView 的 Cookie 容器。
     *
     * 网页登录的票据存在系统 WebView 的 Cookie 库里（与应用的 DataStore 相互独立），
     * 退出登录时必须一并清掉，否则用户换账号时会「秒登回旧号」。
     */
    private fun clearWebLoginCookies() {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
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

    /* ---------------- 酷狗概念版（音源 / 账号） ---------------- */

    /** 概念版账号资料（未登录为 null） */
    val kgLiteProfile: StateFlow<KgLiteProfile?> = kgLite.profile

    /** 概念版签到结果（自动/手动领取的最近一次结果） */
    val kgLiteClaimResult = AppContainer.kgLiteClaim.lastResult

    private val _kgLiteUi = MutableStateFlow(KgLiteLoginUi())
    val kgLiteUi: StateFlow<KgLiteLoginUi> = _kgLiteUi.asStateFlow()

    /** 验证码重发倒计时 Job */
    private var countdownJob: Job? = null

    fun setKgLiteEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setKgLiteEnabled(enabled) }
    }

    fun setKgLiteForce(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setKgLiteForce(enabled) }
    }

    /** 每日自动签到开关 */
    fun setKgLiteAutoClaim(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setKgLiteAutoClaim(enabled) }
    }

    /** 汽水音乐音源启用开关 */
    fun setQishuiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setQishuiEnabled(enabled) }
    }

    /* ---------------- 汽水 relay（家里电脑取链通道） ---------------- */

    private var relayUrlSaveJob: Job? = null
    private var relaySecretSaveJob: Job? = null

    /** relay 中转地址：防抖写入 */
    fun setQishuiRelayUrl(url: String) {
        relayUrlSaveJob?.cancel()
        relayUrlSaveJob = viewModelScope.launch {
            delay(300)
            settingsRepo.setQishuiRelayUrl(url)
        }
    }

    /** relay 设备密钥：防抖写入 */
    fun setQishuiRelaySecret(secret: String) {
        relaySecretSaveJob?.cancel()
        relaySecretSaveJob = viewModelScope.launch {
            delay(300)
            settingsRepo.setQishuiRelaySecret(secret)
        }
    }

    /** 汽水：优先真无损（lossless/FLAC）开关 */
    fun setQishuiLossless(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setQishuiLossless(enabled) }
    }

    private val _qishuiRelayCheck = MutableStateFlow(QishuiRelayCheckUi())
    val qishuiRelayCheck = _qishuiRelayCheck.asStateFlow()

    fun consumeQishuiRelayCheck() {
        _qishuiRelayCheck.value = QishuiRelayCheckUi()
    }

    /** 「测试连接」：调 `/v1/health`（协议 §3.1，免鉴权），区分「中转可达」与「家机在线」 */
    fun checkQishuiRelay() {
        viewModelScope.launch {
            _qishuiRelayCheck.value = QishuiRelayCheckUi(busy = true)
            val api = AppContainer.qishuiRelayApi
            if (!api.isConfigured()) {
                _qishuiRelayCheck.value = QishuiRelayCheckUi(
                    message = "请先填写中转地址与设备密钥", success = false,
                )
                return@launch
            }
            val json = withContext(Dispatchers.IO) { api.health() }
            if (json == null) {
                _qishuiRelayCheck.value = QishuiRelayCheckUi(
                    message = "无法连接中转，请检查地址与网络", success = false,
                )
                return@launch
            }
            val connected = json.bool("agent_connected") == true
            _qishuiRelayCheck.value = QishuiRelayCheckUi(
                message = if (connected) {
                    "链路正常 · 家中设备在线"
                } else {
                    "中转可达，但家中设备离线（取链会返回 503）"
                },
                success = connected,
            )
        }
    }

    /** 哔哩哔哩音源：启用开关（关闭后不在平台选择 / 榜单 / 兜底中出现） */
    fun setBiliEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBiliEnabled(enabled) }
    }

    /** 立即领取（设置页按钮） */
    fun claimKgLiteVipNow() = AppContainer.kgLiteClaim.claimNow()

    /** 消费签到结果提示 */
    fun consumeKgLiteClaimResult() = AppContainer.kgLiteClaim.consumeResult()

    /**
     * 手机号验证码登录 —— 第 1 步：下发短信验证码。
     * 仅转发用户主动填写的手机号，符合「不做登录自动化」的合规边界。
     */
    fun sendKgLiteSmsCode(mobile: String) {
        if (_kgLiteUi.value.smsSending) return
        viewModelScope.launch {
            _kgLiteUi.update { it.copy(smsSending = true, message = null, success = false) }
            val result = runCatching { kgLiteLoginApi.sendSmsCode(mobile) }
            val r = result.getOrNull()
            _kgLiteUi.update {
                when {
                    result.isFailure -> it.copy(smsSending = false, message = "网络异常：${result.exceptionOrNull()?.message ?: "请稍后重试"}")
                    r == null -> it.copy(smsSending = false, message = "验证码下发失败")
                    r.ok -> it.copy(smsSending = false, smsSent = true, countdown = SMS_COOLDOWN_SEC, message = r.message)
                    else -> it.copy(smsSending = false, message = r.message)
                }
            }
            if (r?.ok == true) startKgLiteCountdown()
        }
    }

    /** 第 2 步：用手机号 + 验证码登录，成功后用 `/user/detail` 复核并持久化 */
    fun loginKgLiteByCode(mobile: String, code: String) {
        viewModelScope.launch {
            _kgLiteUi.update { it.copy(busy = true, message = null, success = false) }
            val result = runCatching { kgLiteLoginApi.loginByCode(mobile, code) }
            val r = result.getOrNull()
            when {
                result.isFailure -> _kgLiteUi.update {
                    it.copy(busy = false, message = "网络异常：${result.exceptionOrNull()?.message ?: "请稍后重试"}")
                }
                r == null || !r.ok -> _kgLiteUi.update {
                    it.copy(busy = false, message = r?.message ?: "登录失败，请重试")
                }
                else -> {
                    val status = runCatching { kgLiteApi.loginStatus(r.token, r.userId) }.getOrNull()
                    kgLite.saveLogin(
                        r.token,
                        KgLiteProfile(
                            userId = status?.userId?.takeIf { it.isNotBlank() } ?: r.userId,
                            nickname = status?.nickname?.takeIf { it.isNotBlank() } ?: r.nickname,
                            vipType = status?.vipType ?: r.vipType,
                            mid = kgLite.mid.value,
                        ),
                    )
                    val vipTag = if ((status?.vipType ?: r.vipType) > 0) " · VIP" else ""
                    val name = status?.nickname?.takeIf { it.isNotBlank() } ?: r.nickname
                    _kgLiteUi.update { it.copy(busy = false, success = true, message = "登录成功：$name$vipTag") }
                }
            }
        }
    }

    /** 验证码重发倒计时（每秒递减，归零后可重发） */
    private fun startKgLiteCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_kgLiteUi.value.countdown > 0) {
                delay(1000)
                _kgLiteUi.update { it.copy(countdown = (it.countdown - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** 退出概念版登录（清除本地 token / 资料） */
    fun clearKgLiteLogin() {
        viewModelScope.launch {
            countdownJob?.cancel()
            kgLite.clearLogin()
            _kgLiteUi.value = KgLiteLoginUi(message = "已退出酷狗概念版登录")
        }
    }

    fun consumeKgLiteMessage() {
        _kgLiteUi.update { it.copy(message = null, success = false) }
    }
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

/** 酷狗概念版登录 UI 状态（设置页「酷狗概念版」卡片 / 弹窗） */
data class KgLiteLoginUi(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
    /** 短信已下发（展示验证码输入框与倒计时） */
    val smsSent: Boolean = false,
    /** 下发短信中 */
    val smsSending: Boolean = false,
    /** 重发倒计时（秒） */
    val countdown: Int = 0,
)

/** 哔哩哔哩登录 UI 状��（设置页「哔哩哔哩」卡片 / 弹窗） */
data class BiliLoginUi(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)
/** 汽水 relay 连通性自检 UI 状态（设置页「汽水 relay」卡片） */
data class QishuiRelayCheckUi(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false,
)

/** 短信重发冷却（秒） */
private const val SMS_COOLDOWN_SEC = 60

