package com.dpmusic.app.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.data.HistoryRepository
import com.dpmusic.app.core.data.PlaybackSession
import com.dpmusic.app.core.data.PlaybackSessionStore
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * UI 与 MediaSessionService 之间的唯一桥接层。
 *
 * 关键机制：
 * 1. 懒解析队列：仅当前曲与下一曲持有真实 URL，其余为占位媒体项；
 *    播放推进到占位项时实时解析并 replaceMediaItem（避免批量预解析拖慢起播）。
 * 2. 错误自愈：onPlayerError -> 强制重解析（含跨平台兜底）-> 重试；
 *    同一首连续失败 2 次则提示并自动跳过。
 * 3. 切歌防抖：350ms 内重复的上一首/下一首指令直接忽略。
 * 4. 历史回写：切歌 / 暂停时记录播放进度，供「最近播放」进度胶囊展示。
 * 5. 槽位校验：异步解析完成后仅当目标槽位未变（mediaId 一致）才写回，
 *    防止「解析期间队列被替换 / 插入」造成音频与歌曲信息 / 歌词错位。
 */
@OptIn(UnstableApi::class)
class PlayerConnection(
    private val context: Context,
    private val repository: MusicRepository,
    private val history: HistoryRepository,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val sessionStore: PlaybackSessionStore,
    private val dislike: DislikeRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var connectStarted = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _queue = MutableStateFlow(QueueSnapshot())
    val queue: StateFlow<QueueSnapshot> = _queue

    private val _quality = MutableStateFlow(PlayQuality.HIGH)
    val quality: StateFlow<PlayQuality> = _quality

    /** 向上层 UI 抛出的命令流（打开播放页 / 队列页 / Snackbar 提示） */
    val commands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 8)

    private var lastSkipAt = 0L
    private var lastErrorSongKey: String? = null
    private var errorRetryCount = 0

    /** 队列代次：整体替换队列（playQueue）时自增，用于识别并丢弃过期的异步跳转 / 写入 */
    private var queueEpoch = 0

    /** 点播序号：连续点歌时保证只有最后一次的解析结果可以提交（防止慢解析覆盖新点播） */
    private var playQueueSeq = 0

    /** 实际生效音质（stableKey → 实际命中档位；自动降级链结果） */
    private val actualQuality = mutableMapOf<String, PlayQuality>()

    /** 「不喜欢」连锁跳过标记：跳过期间后续切歌继续检查屏蔽规则 */
    private var dislikeSkipGuard = false

    // ---------------- 连接管理 ----------------

    fun connect() {
        if (connectStarted) return
        connectStarted = true
        scope.launch {
            _quality.value = settings.settings.value.quality
            // 音质以设置为准：设置页改动即时生效（重解析当前曲，含自动降级）
            launch {
                settings.settings.collect { s ->
                    if (s.quality != _quality.value) {
                        _quality.value = s.quality
                        reResolveCurrent(s.quality, announce = false)
                    }
                }
            }
            val token = SessionToken(context, ComponentName(context, MusicService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                runCatching {
                    val c = future.get()
                    controller = c
                    c.addListener(playerListener)
                    // 恢复用户设定的播放速度（0.5x - 2.0x，变速不变调）
                    c.setPlaybackParameters(
                        PlaybackParameters(
                            settings.settings.value.playbackSpeed.coerceIn(
                                MIN_PLAYBACK_SPEED,
                                MAX_PLAYBACK_SPEED,
                            ),
                        ),
                    )
                    _connected.value = true
                    startTicker()
                    updateNowPlaying()
                }.onFailure {
                    _connected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onTransition(reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNowPlaying()
            if (!isPlaying) {
                persistProgress()
                persistSession()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateNowPlaying()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateNowPlaying()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateNowPlaying()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    // ---------------- 对外操作 ----------------

    /** 播放一个歌曲列表（从 startIndex 开始） */
    fun playQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long = 0L) {
        if (songs.isEmpty()) return
        val song = songs.getOrNull(startIndex) ?: return
        val c = controller ?: run {
            commands.tryEmit(PlayerCommand.ShowMessage("播放器连接中，请稍后…"))
            return
        }
        val seq = ++playQueueSeq
        scope.launch {
            val resolved = runCatching { repository.resolveForPlayback(song, _quality.value) }
                .getOrElse { e ->
                    commands.tryEmit(PlayerCommand.ShowMessage("音源解析失败：${e.message}"))
                    return@launch
                }

            // 期间用户又点播了新队列：本次提交已过期，放弃（防止慢解析覆盖新点播）
            if (seq != playQueueSeq) return@launch

            val items = songs.mapIndexed { index, s ->
                if (index == startIndex) s.toMediaItem(resolved.url) else s.toPlaceholderMediaItem()
            }
            // 新队列提交：旧队列在途的异步解析 / 跳转全部作废（配合槽位校验，避免写错槽位）
            queueEpoch++
            dislikeSkipGuard = false
            c.setMediaItems(items, startIndex, startPositionMs)
            c.prepare()
            c.play()

            _queue.value = QueueSnapshot(songs, startIndex)
            actualQuality[resolved.song.stableKey] = resolved.quality
            _nowPlaying.value = NowPlaying(
                song = resolved.song,
                isBuffering = true,
                durationMs = if (song.durationMs > 0) song.durationMs else resolved.song.durationMs,
                quality = resolved.quality,
                desiredQuality = _quality.value,
            )
            history.recordStart(resolved.song)
            preloadNext(startIndex)
            persistSession()
        }
    }

    /**
     * 继续收听：恢复上一次的完整播放队列与进度。
     * - 若当前已有活跃队列（进程存活）：直接继续播放，不重开；
     * - 冷启动：从持久化会话恢复「队列 + 索引 + 进度条位置」。
     * 返回是否成功发起恢复。
     */
    fun resumeSession(): Boolean {
        val c = controller
        if (c != null && c.mediaItemCount > 0 && _queue.value.songs.isNotEmpty()) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
            c.play()
            return true
        }

        val session = sessionStore.session.value ?: return false
        if (session.songs.isEmpty()) return false
        val index = session.currentIndex.coerceIn(0, session.songs.lastIndex)
        val song = session.songs[index]
        val position = if (song.durationMs > 0 && session.positionMs >= song.durationMs - 2000) {
            0L
        } else {
            session.positionMs.coerceAtLeast(0L)
        }
        playQueue(session.songs, index, position)
        return true
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = skip(1)

    fun previous() = skip(-1)

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /**
     * 设置播放速度（0.5x - 2.0x，变速不变调）。
     * persist = true 时同时写入设置，作为下次启动的默认速度。
     */
    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        val clamped = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        controller?.setPlaybackParameters(PlaybackParameters(clamped))
        if (persist) scope.launch { settings.setPlaybackSpeed(clamped) }
    }

    /** 暂停播放（定时退出到点触发等场景使用） */
    fun pause() {
        controller?.pause()
    }

    /** 不喜欢当前歌曲：加入屏蔽列表并自动切歌（后续自动切歌将跳过被屏蔽歌曲） */
    fun dislikeCurrent() {
        val song = _nowPlaying.value?.song ?: return
        scope.launch {
            dislike.add(song.title, song.artist)
            commands.tryEmit(PlayerCommand.ShowMessage("已屏蔽「${song.title}」"))
            dislikeSkipGuard = true
            skipDislikedForward()
        }
    }

    /** 跳过被屏蔽的歌曲：前进到下一首；已到队尾则暂停 */
    private fun skipDislikedForward() {
        val c = controller ?: return
        val nextIndex = c.currentMediaItemIndex + 1
        if (nextIndex >= c.mediaItemCount) {
            c.pause()
            dislikeSkipGuard = false
            return
        }
        scope.launch {
            val epoch = queueEpoch
            ensureResolved(nextIndex)
            if (epoch != queueEpoch) return@launch // 解析期间队列已被整体替换：放弃这次过期跳转
            c.seekTo(nextIndex, 0L)
            c.play()
        }
    }

    /** 当前是否正在播放（定时退出判定用；直读控制器，避免状态流延迟） */
    fun isPlayingNow(): Boolean = controller?.isPlaying == true

    /** 切换音质（播放页手动）：持久化为默认 + 当前曲目按降级链重解析 */
    fun setQuality(quality: PlayQuality) {
        if (_quality.value == quality) return
        _quality.value = quality
        scope.launch { settings.setQuality(quality) }
        scope.launch { reResolveCurrent(quality, announce = true) }
    }

    /** 当前曲目按指定音质重解析（含自动降级链）；announce = 是否提示结果 */
    private suspend fun reResolveCurrent(quality: PlayQuality, announce: Boolean) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0 || index >= c.mediaItemCount) return
        val song = _queue.value.songs.getOrNull(index) ?: return
        val expectedMediaId = runCatching { c.getMediaItemAt(index).mediaId }.getOrNull()
        runCatching {
            val wasPlaying = c.isPlaying
            val position = c.currentPosition
            val resolved = repository.resolveForPlayback(song, quality, forceRefresh = true)
            // 槽位校验：解析期间用户可能已切歌，放弃把结果 / 进度写回旧曲目
            if (!slotStillMatches(index, expectedMediaId)) return@runCatching
            c.replaceMediaItem(index, resolved.song.toMediaItem(resolved.url))
            c.prepare()
            c.seekTo(index, position)
            if (wasPlaying) c.play()
            actualQuality[song.stableKey] = resolved.quality
            updateNowPlaying()
            if (announce) {
                val msg = if (resolved.quality == quality) {
                    "已切换音质：${quality.label}"
                } else {
                    "「${quality.label}」暂不可用，已降级为 ${resolved.quality.label}"
                }
                commands.tryEmit(PlayerCommand.ShowMessage(msg))
            }
        }.onFailure {
            if (announce) commands.tryEmit(PlayerCommand.ShowMessage("音质切换失败：${it.message}"))
        }
    }

    /** 下一首播放（插入到当前曲目之后） */
    fun playSongNext(song: Song) {
        val c = controller ?: return
        scope.launch {
            runCatching {
                val resolved = repository.resolveForPlayback(song, _quality.value)
                actualQuality[resolved.song.stableKey] = resolved.quality
                // 插入点统一以「播放器当前索引」为唯一基准：媒体项与队列列表必须同点插入，
                // 否则两个列表一旦错位，后续切歌会出现「音频与歌曲信息错位」。
                val current = c.currentMediaItemIndex
                val insertAt = if (current < 0) c.mediaItemCount else (current + 1).coerceAtMost(c.mediaItemCount)
                c.addMediaItem(insertAt, resolved.song.toMediaItem(resolved.url))

                val snapshot = _queue.value
                val list = snapshot.songs.toMutableList()
                list.add(insertAt.coerceIn(0, list.size), resolved.song)
                _queue.value = snapshot.copy(songs = list)
                persistSession()
                commands.tryEmit(PlayerCommand.ShowMessage("已加入下一首播放"))
            }.onFailure {
                commands.tryEmit(PlayerCommand.ShowMessage("加入失败：${it.message}"))
            }
        }
    }

    /** 追加歌曲到队列末尾（私人 FM 动态续杯等场景）；URL 惰性解析（占位 → 切到该曲时解析） */
    fun appendToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val c = controller ?: return
        val snapshot = _queue.value
        if (snapshot.songs.isEmpty() || c.mediaItemCount == 0) return
        c.addMediaItems(songs.map { it.toPlaceholderMediaItem() })
        _queue.value = snapshot.copy(songs = snapshot.songs + songs)
        persistSession()
    }

    fun toggleFavorite(song: Song? = _nowPlaying.value?.song) {
        val target = song ?: return
        scope.launch { favorites.toggle(target) }
    }

    fun openPlayerSheet() {
        commands.tryEmit(PlayerCommand.OpenPlayerSheet)
    }

    fun openQueueSheet() {
        commands.tryEmit(PlayerCommand.ShowQueueSheet)
    }

    /** 跳转到队列中某一首（队列页使用） */
    fun skipToQueueIndex(index: Int) {
        val c = controller ?: return
        val snapshot = _queue.value
        if (index !in snapshot.songs.indices) return
        // 用户明确点选歌曲：不做「不喜欢」检查
        dislikeSkipGuard = false
        scope.launch {
            val epoch = queueEpoch
            ensureResolved(index)
            if (epoch != queueEpoch) return@launch // 解析期间队列已被整体替换：放弃这次过期跳转
            c.seekTo(index, 0L)
            c.play()
        }
    }

    // ---------------- 内部机制 ----------------

    private fun skip(direction: Int) {
        val c = controller ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipAt < SKIP_DEBOUNCE_MS) return // 切歌防抖
        lastSkipAt = now
        // 手动切歌同样跳过「不喜欢」（落地后自动继续链式跳过）
        dislikeSkipGuard = true

        scope.launch {
            if (c.mediaItemCount == 0) return@launch
            val current = c.currentMediaItemIndex
            val target = if (current + direction in 0 until c.mediaItemCount) {
                current + direction
            } else {
                if (direction > 0) 0 else c.mediaItemCount - 1
            }
            val epoch = queueEpoch
            ensureResolved(target)
            if (epoch != queueEpoch) return@launch // 解析期间队列已被整体替换：放弃这次过期跳转
            c.seekTo(target, 0L)
            c.play()
        }
    }

    private fun onTransition(reason: Int) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val snapshot = _queue.value
        val song = snapshot.songs.getOrNull(index) ?: return
        _queue.value = snapshot.copy(currentIndex = index)
        persistSession()

        // 「不喜欢」自动跳过：自动切歌（或连锁跳过中）命中屏蔽规则时不播放，直接前进
        val checkDislike = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || dislikeSkipGuard
        if (checkDislike && dislike.matches(song)) {
            dislikeSkipGuard = true
            skipDislikedForward()
            return
        }
        dislikeSkipGuard = false

        scope.launch {
            // 切歌瞬间先刷新一次 UI 状态：歌曲信息 / 歌词立即切到新曲，不等占位解析
            updateNowPlaying()
            val item = c.currentMediaItem
            val uri = item?.localConfiguration?.uri?.toString().orEmpty()
            if (uri.isEmpty()) {
                // 占位项：立即解析、替换并继续播放
                ensureResolved(index, force = true)
                c.prepare()
                c.play()
            } else {
                preloadNext(index)
            }
            history.recordStart(song)
            updateNowPlaying()
        }
    }

    /**
     * 槽位校验：异步解析发起时记录目标槽位的 mediaId，写回前确认槽位未变。
     * 防止「解析期间队列被整体替换 / 插入」导致旧结果写进新队列槽位，
     * 造成音频与歌曲信息 / 歌词错位。
     */
    private fun slotStillMatches(index: Int, expectedMediaId: String?): Boolean {
        val c = controller ?: return false
        if (expectedMediaId.isNullOrEmpty()) return false
        if (index < 0 || index >= c.mediaItemCount) return false
        val item = runCatching { c.getMediaItemAt(index) }.getOrNull() ?: return false
        return item.mediaId == expectedMediaId
    }

    /** 确保队列第 index 项已持有真实 URL；返回是否可用 */
    private suspend fun ensureResolved(index: Int, force: Boolean = false): Boolean {
        val c = controller ?: return false
        if (index < 0 || index >= c.mediaItemCount) return false
        val song = _queue.value.songs.getOrNull(index) ?: return false
        val item = c.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (!force && uri.isNotEmpty()) return true
        val expectedMediaId = item.mediaId
        return try {
            val resolved = repository.resolveForPlayback(song, _quality.value, forceRefresh = force)
            actualQuality[resolved.song.stableKey] = resolved.quality
            // 槽位校验：解析期间队列可能被替换 / 插入过，禁止把结果写进已变化的槽位
            if (!slotStillMatches(index, expectedMediaId)) return false
            c.replaceMediaItem(index, resolved.song.toMediaItem(resolved.url))
            true
        } catch (e: Exception) {
            commands.tryEmit(PlayerCommand.ShowMessage("「${song.title}」解析失败：${e.message}"))
            false
        }
    }

    /** 预解析下一曲，实现无缝衔接 */
    private fun preloadNext(index: Int) {
        val c = controller ?: return
        val nextIndex = index + 1
        val snapshot = _queue.value
        if (nextIndex >= snapshot.songs.size || nextIndex >= c.mediaItemCount) return
        val nextSong = snapshot.songs[nextIndex]
        val item = c.getMediaItemAt(nextIndex)
        if (item.localConfiguration?.uri?.toString().orEmpty().isNotEmpty()) return
        val expectedMediaId = item.mediaId

        scope.launch {
            runCatching {
                val resolved = repository.resolveForPlayback(nextSong, _quality.value)
                actualQuality[resolved.song.stableKey] = resolved.quality
                // 槽位校验：解析期间队列可能被替换 / 插入过，禁止把结果写进已变化的槽位
                if (!slotStillMatches(nextIndex, expectedMediaId)) return@runCatching
                c.replaceMediaItem(nextIndex, resolved.song.toMediaItem(resolved.url))
            }
        }
    }

    /** 错误自愈：重解析（跨平台兜底）→ 重试；同曲两次失败自动跳过 */
    private fun handlePlaybackError(error: PlaybackException) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0 || index >= c.mediaItemCount) return
        val song = _queue.value.songs.getOrNull(index) ?: return
        val key = song.stableKey
        val expectedMediaId = runCatching { c.getMediaItemAt(index).mediaId }.getOrNull()

        if (lastErrorSongKey == key) errorRetryCount++ else {
            lastErrorSongKey = key
            errorRetryCount = 0
        }

        if (errorRetryCount >= 2) {
            errorRetryCount = 0
            lastErrorSongKey = null
            commands.tryEmit(PlayerCommand.ShowMessage("「${song.title}」音源不可用，已跳过"))
            next()
            return
        }

        scope.launch {
            try {
                val resolved = repository.resolveForPlayback(song, _quality.value, forceRefresh = true)
                actualQuality[resolved.song.stableKey] = resolved.quality
                // 槽位校验：解析期间队列可能已变化，避免把重试结果写进错误槽位
                if (!slotStillMatches(index, expectedMediaId)) return@launch
                c.replaceMediaItem(index, resolved.song.toMediaItem(resolved.url))
                c.prepare()
                c.play()
            } catch (e: Exception) {
                if (!slotStillMatches(index, expectedMediaId)) return@launch
                commands.tryEmit(PlayerCommand.ShowMessage("「${song.title}」播放失败：${e.message}"))
                next()
            }
        }
    }

    private fun startTicker() {
        scope.launch {
            var tick = 0L
            while (isActive) {
                updateNowPlaying()
                tick++
                // 每 20 秒落盘一次播放会话（防进程被杀丢失队列 / 进度）
                if (tick % 40 == 0L && controller?.isPlaying == true) persistSession()
                delay(TICK_MS)
            }
        }
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val snapshot = _queue.value
        val song = snapshot.songs.getOrNull(index) ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: song.durationMs
        _nowPlaying.value = NowPlaying(
            song = song,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            repeatMode = c.repeatMode,
            shuffleEnabled = c.shuffleModeEnabled,
            quality = actualQuality[song.stableKey] ?: _quality.value,
            desiredQuality = _quality.value,
        )
    }

    private fun persistProgress() {
        val c = controller ?: return
        val song = _nowPlaying.value?.song ?: return
        scope.launch {
            history.updateProgress(
                songKey = song.stableKey,
                positionMs = c.currentPosition,
                durationMs = c.duration.takeIf { it > 0 } ?: song.durationMs,
            )
        }
    }

    /** 持久化当前播放会话（队列 + 索引 + 进度），供「继续收听」恢复 */
    private fun persistSession() {
        val c = controller ?: return
        val snapshot = _queue.value
        if (snapshot.songs.isEmpty()) return
        val index = c.currentMediaItemIndex.coerceIn(0, snapshot.songs.lastIndex)
        scope.launch {
            sessionStore.save(
                PlaybackSession(
                    songs = snapshot.songs,
                    currentIndex = index,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                ),
            )
        }
    }

    // ---------------- MediaItem 构造 ----------------

    private fun Song.toMediaItem(url: String): MediaItem = MediaItem.Builder()
        .setMediaId(stableKey)
        .setUri(url)
        .setMediaMetadata(metadata())
        .build()

    private fun Song.toPlaceholderMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(stableKey)
        .setUri(Uri.EMPTY)
        .setMediaMetadata(metadata())
        .build()

    private fun Song.metadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .apply { if (coverUrl.isNotBlank()) setArtworkUri(Uri.parse(coverUrl)) }
        .build()

    companion object {
        private const val SKIP_DEBOUNCE_MS = 350L

        /** 播放速度下限 / 上限（0.5x - 2.0x） */
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2f

        /**
         * 播放位置推送周期（ms）：ticker 以该周期刷新 NowPlaying.positionMs。
         * 注意：逐字歌词的「预测式补间」依赖该周期（补间目标 = 当前位置 + 一个周期，
         * 在下一次采样时恰好追平真实进度），调整此值时歌词侧自动适配。
         */
        const val TICK_MS = 500L
    }

}