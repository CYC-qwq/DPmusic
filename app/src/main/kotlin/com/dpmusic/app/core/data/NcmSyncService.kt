package com.dpmusic.app.core.data

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.net.NcmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 网易云红心自动双向同步（需登录 Cookie）：
 * - 拉取：云端「我喜欢的音乐」→ 本地收藏（按 stableKey 去重合并）；
 * - 推送：本地收藏的网易云歌曲 → 云端红心（仅差量，批量提交）；
 * - 触发：启动延迟自动 + 运行期每 30 分钟定期 + 登录后 / 设置页手动；
 * - 删除不做同步（仅并集增补，避免误删）。
 */
class NcmSyncService(
    private val api: NcmApi,
    private val ncm: NcmRepository,
    private val favorites: FavoritesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(NcmSyncState())
    val state: StateFlow<NcmSyncState> = _state.asStateFlow()

    init {
        // 初值：上次同步时间（持久化恢复）
        scope.launch {
            _state.update { it.copy(lastSyncAt = ncm.lastLikesSyncAt.value) }
        }
        // 启动延迟自动同步（避开启动高峰；未登录静默跳过）
        scope.launch {
            delay(STARTUP_DELAY_MS)
            sync(auto = true)
        }
        // 运行期间定期自动同步
        scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                sync(auto = true)
            }
        }
    }

    /** 手动同步（设置页按钮） */
    fun syncNow() {
        scope.launch { sync(auto = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun sync(auto: Boolean) {
        if (_state.value.syncing) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            if (!auto) _state.update { it.copy(message = "请先在设置中登录网易云账号") }
            return
        }
        // 自动同步节流：距上次成功同步不足间隔则跳过（手动不受限）
        val now = System.currentTimeMillis()
        val lastAt = ncm.lastLikesSyncAt.value
        if (auto && lastAt > 0 && now - lastAt < AUTO_MIN_INTERVAL_MS) return

        _state.update { it.copy(syncing = true) }
        val result = runCatching { doSync(cookie) }
        val message = result.fold(
            onSuccess = { r ->
                ncm.setLastLikesSyncAt(System.currentTimeMillis())
                buildString {
                    append("同步完成：拉取 ").append(r.pulled).append(" 首 · 推送 ").append(r.pushed).append(" 首")
                    if (r.failed > 0) append("（").append(r.failed).append(" 首推送失败）")
                }
            },
            onFailure = { "同步失败：${it.message ?: "网络异常"}" },
        )
        _state.update {
            it.copy(
                syncing = false,
                lastSyncAt = ncm.lastLikesSyncAt.value,
                message = message,
            )
        }
    }

    private suspend fun doSync(cookie: String): SyncResult {
        val uid = ncm.profile.value?.userId ?: throw IllegalStateException("账号资料缺失，请重新登录")
        // 1. 定位「我喜欢的音乐」歌单
        val likedPid = api.userPlaylists(cookie, uid).firstOrNull { it.special }?.id
            ?: throw IllegalStateException("未找到「我喜欢的音乐」歌单")
        // 2. 拉取云端红心全量（权威 id 集合 + 歌曲详情）
        val cloud = api.likedSongs(cookie, likedPid)
        // 3. 本地网易云歌曲
        val localWy = favorites.favorites.value.filter { it.platform == MusicPlatform.WY }
        val localIds = localWy.mapNotNull { it.id.toLongOrNull() }.toSet()
        // 4. 拉取方向：云端有、本地无 → 合并进本地
        val toPull = cloud.songs.filter { it.id.toLongOrNull() !in localIds }
        val pulled = favorites.addAll(toPull)
        // 5. 推送方向：本地有、云端无 → 云端红心（批量接口：避免 radio/like 连续调用触发风控 405）
        val toPush = localWy.filter { it.id.toLongOrNull() !in cloud.ids }
        val pushIds = toPush.mapNotNull { it.id.toLongOrNull() }
        var pushed = 0
        var failed = 0
        for (batch in pushIds.chunked(PUSH_BATCH_SIZE)) {
            val ok = runCatching { api.likeSongs(cookie, likedPid, batch) }.getOrDefault(false)
            if (ok) pushed += batch.size else failed += batch.size
            delay(PUSH_BATCH_INTERVAL_MS)
        }
        return SyncResult(pulled = pulled, pushed = pushed, failed = failed)
    }

    private data class SyncResult(val pulled: Int, val pushed: Int, val failed: Int = 0)

    private companion object {
        /** 启动后延迟自动同步（毫秒） */
        const val STARTUP_DELAY_MS = 8_000L

        /** 运行期间定期同步间隔（毫秒） */
        const val PERIODIC_INTERVAL_MS = 30 * 60 * 1000L

        /** 自动同步最小间隔（毫秒；手动同步不受限） */
        const val AUTO_MIN_INTERVAL_MS = 10 * 60 * 1000L

        /** 推送分批大小（manipulate/tracks 单次请求的歌曲数上限） */
        const val PUSH_BATCH_SIZE = 100

        /** 推送批间间隔（毫秒） */
        const val PUSH_BATCH_INTERVAL_MS = 500L
    }
}

/** 红心同步状态（UI 订阅） */
data class NcmSyncState(
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0L,
    val message: String? = null,
)
