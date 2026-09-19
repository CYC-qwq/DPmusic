package com.dpmusic.app.ui.screens.playlist

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.AutoUpdateMode
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.core.model.UserPlaylist
import com.dpmusic.app.core.net.PlaylistLinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本地歌单管理 ViewModel：
 * - 创建 / 重命名 / 删除；
 * - 导入（SAF 文件读取）/ 导出（SAF 文件写入）；
 * - 歌单内歌曲播放与移除。
 */
class UserPlaylistViewModel : ViewModel() {

    private val repository = AppContainer.userPlaylists
    private val music = AppContainer.musicRepository
    private val player = AppContainer.player
    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi
    private val qq = AppContainer.qq
    private val qqApi = AppContainer.qqApi

    val playlists = repository.playlists

    val nowPlaying = player.nowPlaying

    // 账号歌单（填写 Cookie 后自动同步展示在歌单页）
    private val _ncmAccountPlaylists = MutableStateFlow<List<NcmPlaylist>>(emptyList())
    val ncmAccountPlaylists = _ncmAccountPlaylists.asStateFlow()
    private val _ncmAccountLoading = MutableStateFlow(false)
    val ncmAccountLoading = _ncmAccountLoading.asStateFlow()
    private val _qqAccountPlaylists = MutableStateFlow<List<QqPlaylist>>(emptyList())
    val qqAccountPlaylists = _qqAccountPlaylists.asStateFlow()
    private val _qqAccountLoading = MutableStateFlow(false)
    val qqAccountLoading = _qqAccountLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** 正在从链接更新的歌单 ID（null = 无） */
    private val _updatingId = MutableStateFlow<String?>(null)
    val updatingId = _updatingId.asStateFlow()

    private var autoCheckRunning = false

    init {
        // 监听登录态：填写 / 更新 Cookie 后自动拉取账号歌单；退出登录则清空
        viewModelScope.launch {
            ncm.cookie.collect { cookie ->
                if (cookie.isNotBlank()) {
                    loadNcmAccountPlaylists()
                } else {
                    _ncmAccountPlaylists.value = emptyList()
                }
            }
        }
        viewModelScope.launch {
            qq.cookie.collect { cookie ->
                if (cookie.isNotBlank()) {
                    loadQqAccountPlaylists()
                } else {
                    _qqAccountPlaylists.value = emptyList()
                }
            }
        }
    }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.create(trimmed) }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.rename(id, trimmed) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun removeSong(playlistId: String, songKey: String) {
        viewModelScope.launch { repository.removeSong(playlistId, songKey) }
    }

    /** 多选批量移除 */
    fun removeSongs(playlistId: String, songKeys: Set<String>) {
        if (songKeys.isEmpty()) return
        viewModelScope.launch {
            val removed = repository.removeSongs(playlistId, songKeys)
            _message.value = "已从歌单移除 $removed 首"
        }
    }

    /** 导入：从 SAF uri 读取备份 JSON 并合并 */
    fun importFrom(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().decodeToString() }
                    ?: error("无法读取文件")
                repository.importJson(raw).getOrThrow()
            }.onSuccess { count ->
                _message.value = "已导入 $count 个歌单"
            }.onFailure { e ->
                _message.value = e.message ?: "导入失败"
            }
        }
    }

    /** 导出：将全部歌单写入 SAF uri */
    fun exportTo(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = repository.exportJson()
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(json.toByteArray()) }
                    ?: error("无法写入文件")
            }.onSuccess {
                _message.value = "歌单已导出"
            }.onFailure { e ->
                _message.value = e.message ?: "导出失败"
            }
        }
    }

    /** 链接导入：自动匹配平台（网易云 / QQ音乐 / 酷狗）→ 解析歌单 → 创建本地歌单 */
    fun importFromLink(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val candidates = PlaylistLinkParser.parseCandidates(text)
                if (candidates.isEmpty()) {
                    _message.value = "无法识别链接：请粘贴网易云 / QQ音乐 / 酷狗歌单分享链接"
                    return@launch
                }
                val urlInText = Regex("https?://\\S+").find(text)?.value
                    ?.trimEnd('，', '。', '、', '；', '）', ')', '”', '"')
                var lastError: String? = null
                for (candidate in candidates) {
                    val attempt = runCatching {
                        val songs = music.playlistSongs(candidate.platform, candidate.playlistId)
                        if (songs.isEmpty()) error("歌单不存在或没有歌曲")
                        val meta = runCatching {
                            music.playlistMeta(candidate.platform, candidate.playlistId)
                        }.getOrNull()
                        val name = meta?.name?.takeIf { it.isNotBlank() } ?: "${candidate.platform.label}歌单"
                        repository.createWithSongs(
                            name = name,
                            songs = songs,
                            sourceLink = urlInText ?: canonicalLink(candidate.platform, candidate.playlistId),
                            sourcePlatformId = candidate.platform.id,
                            sourcePlaylistId = candidate.playlistId,
                        )
                        name to songs.size
                    }
                    if (attempt.isSuccess) {
                        val (name, count) = attempt.getOrThrow()
                        _message.value = "已导入「$name」共 $count 首"
                        return@launch
                    } else {
                        lastError = attempt.exceptionOrNull()?.message
                    }
                }
                _message.value = "解析失败：${lastError ?: "歌单不存在或网络异常"}"
            } finally {
                _importing.value = false
            }
        }
    }

    /** 从来源链接更新歌单（手动触发） */
    fun updatePlaylist(id: String) {
        if (_updatingId.value != null) return
        val playlist = playlists.value.firstOrNull { it.id == id } ?: return
        val link = playlist.sourceLink
        if (link.isNullOrBlank()) {
            _message.value = "该歌单不是从链接导入的，无法更新"
            return
        }
        viewModelScope.launch {
            _updatingId.value = id
            try {
                val count = refreshFromSource(playlist, link)
                _message.value = "已更新「${playlist.name}」：$count 首"
            } catch (e: Exception) {
                _message.value = "更新失败：${e.message ?: "网络异常"}"
            } finally {
                _updatingId.value = null
            }
        }
    }

    /** 设置定时更新频率 */
    fun setAutoUpdate(id: String, mode: AutoUpdateMode) {
        viewModelScope.launch {
            repository.setAutoUpdate(id, mode)
            _message.value = if (mode == AutoUpdateMode.OFF) {
                "已关闭定时更新"
            } else {
                "已开启定时更新：${mode.label}"
            }
        }
    }

    /** 打开歌单页时检查：到期的定时更新歌单静默刷新 */
    fun checkAutoUpdates() {
        if (autoCheckRunning) return
        val now = System.currentTimeMillis()
        val due = playlists.value.filter { pl ->
            pl.isLinked && pl.autoUpdate != AutoUpdateMode.OFF &&
                now - pl.lastUpdatedAt >= pl.autoUpdate.intervalMs
        }
        if (due.isEmpty()) return
        autoCheckRunning = true
        viewModelScope.launch {
            try {
                var updated = 0
                for (pl in due) {
                    val ok = runCatching { refreshFromSource(pl, pl.sourceLink.orEmpty()) }.isSuccess
                    if (ok) updated++
                }
                if (updated > 0) _message.value = "已自动更新 $updated 个歌单"
            } finally {
                autoCheckRunning = false
            }
        }
    }

    /** 导出单个歌单到 SAF uri */
    fun exportSingleTo(context: Context, uri: Uri, playlist: UserPlaylist) {
        viewModelScope.launch {
            runCatching {
                val json = repository.exportSingleJson(playlist)
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(json.toByteArray()) }
                    ?: error("无法写入文件")
            }.onSuccess {
                _message.value = "已导出「${playlist.name}」"
            }.onFailure { e ->
                _message.value = e.message ?: "导出失败"
            }
        }
    }

    /** 生成单个歌单的备份 JSON（分享文件用） */
    fun exportJsonOf(playlist: UserPlaylist): String = repository.exportSingleJson(playlist)

    /** 外部提示（如「链接已复制」） */
    fun postMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    /* ---------------- 账号歌单同步（网易云 / QQ） ---------------- */

    /** 拉取网易云「我的歌单」（需登录 Cookie + 账号资料） */
    fun loadNcmAccountPlaylists() {
        if (_ncmAccountLoading.value) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        val uid = ncm.profile.value?.userId ?: return
        viewModelScope.launch {
            _ncmAccountLoading.value = true
            runCatching { ncmApi.userPlaylists(cookie, uid) }
                .onSuccess { _ncmAccountPlaylists.value = it }
            _ncmAccountLoading.value = false
        }
    }

    /** 拉取 QQ「我的歌单」（需登录 Cookie） */
    fun loadQqAccountPlaylists() {
        if (_qqAccountLoading.value) return
        val cookie = qq.cookie.value
        if (cookie.isBlank()) return
        viewModelScope.launch {
            _qqAccountLoading.value = true
            runCatching { qqApi.userPlaylists(cookie) }
                .onSuccess { _qqAccountPlaylists.value = it }
            _qqAccountLoading.value = false
        }
    }

    /** 进入歌单页兜底同步：已登录但尚未加载时拉取 */
    fun syncAccounts() {
        if (ncm.cookie.value.isNotBlank() && _ncmAccountPlaylists.value.isEmpty()) {
            loadNcmAccountPlaylists()
        }
        if (qq.cookie.value.isNotBlank() && _qqAccountPlaylists.value.isEmpty()) {
            loadQqAccountPlaylists()
        }
    }

    /** 构造账号歌单备份 JSON（分享 / 导出用；联网拉取歌曲后生成） */
    fun buildAccountPlaylistJson(
        platform: MusicPlatform,
        playlistId: String,
        name: String,
        onReady: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val songs = runCatching { music.playlistSongs(platform, playlistId) }.getOrNull()
            if (songs.isNullOrEmpty()) {
                _message.value = "歌单为空或加载失败"
                onReady(null)
                return@launch
            }
            val json = runCatching {
                repository.exportSingleJson(
                    UserPlaylist(
                        id = "account-${platform.id}-$playlistId",
                        name = name,
                        songs = songs,
                        createdAt = System.currentTimeMillis(),
                        sourceLink = canonicalLink(platform, playlistId),
                        sourcePlatformId = platform.id,
                        sourcePlaylistId = playlistId,
                    ),
                )
            }.getOrNull()
            if (json == null) _message.value = "导出准备失败"
            onReady(json)
        }
    }

    /** 将账号歌单 JSON 写入 SAF uri（导出） */
    fun writeAccountJsonTo(context: Context, uri: Uri, json: String, name: String) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("无法写入文件")
            }.onSuccess { _message.value = "已导出「$name」" }
                .onFailure { e -> _message.value = e.message ?: "导出失败" }
        }
    }

    /* ---------------- 内部：从链接刷新 ---------------- */

    private suspend fun refreshFromSource(playlist: UserPlaylist, link: String): Int {
        val candidates = PlaylistLinkParser.parseCandidates(link)
        if (candidates.isEmpty()) error("无法识别来源链接")
        var lastError: Exception? = null
        for (candidate in candidates) {
            val attempt = runCatching {
                val songs = music.playlistSongs(candidate.platform, candidate.playlistId)
                if (songs.isEmpty()) error("歌单不存在或没有歌曲")
                repository.replaceSongs(playlist.id, songs)
                songs.size
            }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull() as? Exception
        }
        throw lastError ?: IllegalStateException("歌单不存在或网络异常")
    }

    private fun canonicalLink(platform: MusicPlatform, playlistId: String): String = when (platform) {
        MusicPlatform.WY -> "https://music.163.com/playlist?id=$playlistId"
        MusicPlatform.QQ -> "https://y.qq.com/n/ryqq/playlist/$playlistId"
        MusicPlatform.KG -> "https://www.kugou.com/yy/special/single/$playlistId.html"
    }

    /* ---------------- 歌单内播放 ---------------- */

    fun playSong(playlist: UserPlaylist, index: Int) {
        val list = playlist.songs
        if (index in list.indices) player.playQueue(list, index)
    }

    /** 按 stableKey 定位播放（过滤列表用） */
    fun playSong(playlist: UserPlaylist, songKey: String) {
        val index = playlist.songs.indexOfFirst { it.stableKey == songKey }
        if (index >= 0) player.playQueue(playlist.songs, index)
    }

    fun playAll(playlist: UserPlaylist) {
        if (playlist.songs.isNotEmpty()) player.playQueue(playlist.songs, 0)
    }
}