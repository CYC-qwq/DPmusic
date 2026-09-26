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
import androidx.media3.common.Timeline
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
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI 与 MediaSessionService 之间的唯一桥接层。
 *
 * 关键机制：
 * 1. 懒解析队列：仅当前曲与下一曲持有真实 URL，其余为占位媒体项；
 *    播放推进到占位项时实时解析并 replaceMediaItem（避免批量预解析拖慢起播）。
 * 2. 错误自愈：onPlayerError -> 强制重解析（含跨平台兜底）-> 重试；
 *    同一首连续失败 2 次则提示并自动跳过。
 * 3. 切歌：按下即预览（纯 UI，不解析不加载）→ 目标槽位已备好则立即落地（零等待零请求）；
 *    需要解析时进入合并窗口，连按只提交最后一次（省音源配额、不加载路过的歌）。
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

    /**
     * 队列歌曲索引：`stableKey -> Song`。
     *
     * 用于「以控制器播放列表为唯一真相」重建 UI 队列——
     * 控制器的 mediaItems 只携带 `mediaId`（= stableKey）与展示元数据，
     * 而 UI 队列需要完整的 [Song]（平台 / id / 时长 / 来源），故在此留一份索引。
     * 所有进入队列的歌曲都会先注册，容量有上限（FIFO 淘汰），不会无限增长。
     */
    private val songIndex = LinkedHashMap<String, Song>()

    /**
     * 点歌提交进行中标记。
     *
     * 点歌时先做「乐观 UI」（队列 / 正在播放立即切到目标曲），控制器要等解析出真实 URL 才提交。
     * 这段窗口内控制器仍持有**旧队列**，若放任其事件（onTransition / onTimelineChanged /
     * onIsPlayingChanged）回写状态，就会用旧队列的索引覆盖新状态——
     * 正是「歌曲信息与音频错位」的成因。故提交期间统一抑制控制器事件回写。
     */
    private var pendingPlayCommit = false

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

    /**
     * 切歌序号：高频连切时作废中间几次的解析结果。
     *
     * 场景：用户快速点按 5 次「下一首」——
     * 只有第 5 次会真正发起解析请求（前 4 次在合并窗口内被取代），
     * 既省音源配额、避免触发风控，又保证最终落点是用户真正想去的那首。
     */
    private var skipSeq = 0

    /**
     * 待落地的切歌目标索引。
     *
     * 高频连切时播放器索引尚未推进到上一次目标，若以「播放器当前索引」为基准累加，
     * 连按 N 次只会前进 1 步。以本字段为基准即可保证最终精确前进 N 步。
     * 快速路径落地 / 解析完成 / 解析失败时都会清空。
     */
    private var pendingSkipTarget: Int? = null

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
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // 播放列表（真实队列）发生变化 → 反向校正 UI 队列，保证两者永不分叉
            reconcileQueueWithController()
        }

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

    /**
     * 播放一个歌曲列表（从 startIndex 开始）。
     *
     * 体验设计：**零延迟响应 + 单次请求**。
     * ① 立即（同步）把队列与「正在播放」切到目标曲——播放页 / 迷你条 / 列表高亮瞬间到位；
     * ② 只有「解析出真实播放地址」这一步必须走网络（没有 URL 就无从起播），
     *    故解析完成后才提交控制器播放列表（提交前用 [pendingPlayCommit] 抑制旧队列事件回写）；
     * ③ 期间用户若又点了别的歌，本次解析结果直接作废（[playQueueSeq]），
     *    既不会出现「慢解析覆盖新点播」，也不会浪费一次音源请求之外的东西。
     */
    fun playQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long = 0L) {
        if (songs.isEmpty()) return
        val song = songs.getOrNull(startIndex) ?: return
        val c = controller ?: run {
            commands.tryEmit(PlayerCommand.ShowMessage("播放器连接中，请稍后…"))
            return
        }
        val seq = ++playQueueSeq
        registerSongs(songs)

        // ① 即时反馈：UI 先切到目标曲（此时控制器尚未提交，靠 pendingPlayCommit 屏蔽其回写）
        //    记录旧状态，解析失败时原样回滚——避免「没播起来却显示正在播放」
        val previousQueue = _queue.value
        val previousNowPlaying = _nowPlaying.value
        pendingPlayCommit = true
        _queue.value = QueueSnapshot(songs, startIndex)
        _nowPlaying.value = NowPlaying(
            song = song,
            isPlaying = false,
            isBuffering = true,
            durationMs = song.durationMs,
            quality = _quality.value,
            desiredQuality = _quality.value,
            repeatMode = c.repeatMode,
            shuffleEnabled = c.shuffleModeEnabled,
        )

        scope.launch {
            val resolved = runCatching { repository.resolveForPlayback(song, _quality.value) }
                .getOrElse { e ->
                    if (seq == playQueueSeq) {
                        // 解析失败：回滚乐观状态，UI 不得停在「假装正在播放」的假象上
                        pendingPlayCommit = false
                        _queue.value = previousQueue
                        _nowPlaying.value = previousNowPlaying
                    }
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
            pendingSkipTarget = null
            skipSeq++ // 作废在途的切歌解析，避免其 seekTo 落在新队列上
            pendingPlayCommit = false
            c.setMediaItems(items, startIndex, startPositionMs)
            c.prepare()
            c.play()

            // 提交后与控制器对账一次：保证 UI 队列 = 真实播放队列
            _queue.value = QueueSnapshot(songs, startIndex)
            actualQuality[resolved.song.stableKey] = resolved.quality
            updateNowPlaying()
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

    /**
     * 外部切歌入口：状态栏通知 / 耳机线控 / 蓝牙 / 锁屏 / Android Auto。
     *
     * 这些来源直接作用于服务端的 Player，本来会绕过 UI 侧的合并窗口，
     * 于是「高频连按状态栏」= 高频真实 seek + 高频现解析，既费配额又抽搐。
     * 统一路由到这里后，它们与 App 内按钮共享同一套
     * 「即时预览 → 合并窗口 → 序号取代 → 预解析」逻辑。
     *
     * @return true = 已接管；false = 控制器尚未就绪，调用方应回退默认行为
     */
    fun requestExternalSkip(direction: Int): Boolean {
        if (direction == 0) return false
        connect() // 幂等：仅在首次真正发起连接
        val c = controller ?: return false
        if (c.mediaItemCount == 0) return false
        skip(direction)
        return true
    }

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
                registerSongs(listOf(resolved.song))
                // 插入点统一以「播放器当前索引」为唯一基准：媒体项与队列列表必须同点插入，
                // 否则两个列表一旦错位，后续切歌会出现「音频与歌曲信息错位」。
                val current = c.currentMediaItemIndex
                val insertAt = if (current < 0) c.mediaItemCount else (current + 1).coerceAtMost(c.mediaItemCount)

                val snapshot = _queue.value
                val list = snapshot.songs.toMutableList()
                list.add(insertAt.coerceIn(0, list.size), resolved.song)
                // UI 队列先落地，控制器紧随其后（onTimelineChanged 会对账，二者不会分叉）
                _queue.value = snapshot.copy(songs = list)
                c.addMediaItem(insertAt, resolved.song.toMediaItem(resolved.url))
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
        registerSongs(songs)
        // UI 队列先落地，控制器紧随其后（onTimelineChanged 会对账，二者不会分叉）
        _queue.value = snapshot.copy(songs = snapshot.songs + songs)
        c.addMediaItems(songs.map { it.toPlaceholderMediaItem() })
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

    // ---------------- 队列一致性（唯一真相源 = 真实播放队列） ----------------

    /** 注册队列歌曲（进入队列前调用），供 [reconcileQueueWithController] 还原完整 [Song] */
    private fun registerSongs(songs: List<Song>) {
        songs.forEach { song ->
            songIndex.remove(song.stableKey)
            songIndex[song.stableKey] = song
        }
        while (songIndex.size > MAX_SONG_INDEX) {
            val oldest = songIndex.keys.firstOrNull() ?: break
            songIndex.remove(oldest)
        }
    }

    /**
     * 队列一致性对账：**以控制器（真实播放队列）为唯一真相**，反向校正 UI 队列。
     *
     * 背景：`_queue`（UI 可见队列）与 ExoPlayer 的 mediaItems 是两套独立存储。
     * 只要存在任何一处「单边写入」（只改 UI、或只改控制器），就会出现
     * 「列表显示的队列 ≠ 实际播放的队列」——表现为切歌跳到列表里没有的歌、
     * 歌曲信息与音频 / 歌词错位、队尾判定失准。
     *
     * 本函数在**每次播放列表变化时**（onTimelineChanged）以控制器内容为准重建 `_queue`，
     * 因此两者在结构上不可能长期分叉；索引严格按位对齐，保证 currentIndex 语义正确。
     */
    private fun reconcileQueueWithController() {
        if (pendingPlayCommit) return // 点歌提交中：控制器仍是旧队列，此刻对账会误伤
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count == 0) {
            if (_queue.value.songs.isNotEmpty()) _queue.value = QueueSnapshot()
            return
        }

        val ids = ArrayList<String>(count)
        for (i in 0 until count) {
            ids += runCatching { c.getMediaItemAt(i).mediaId }.getOrNull().orEmpty()
        }

        val snapshot = _queue.value
        val currentIndex = c.currentMediaItemIndex
        val aligned = snapshot.songs.size == count &&
            ids.indices.all { ids[it] == snapshot.songs[it].stableKey }
        if (aligned) {
            if (currentIndex != snapshot.currentIndex) {
                _queue.value = snapshot.copy(currentIndex = currentIndex)
            }
            return
        }

        // 分叉：以控制器为准重建（长度 / 顺序 / 索引严格对齐）
        val rebuilt = ids.map { key ->
            songIndex[key] ?: snapshot.songs.firstOrNull { it.stableKey == key }
        }
        if (rebuilt.any { it == null }) {
            AppLogger.w(TAG, "队列对账跳过：真实队列含未知曲目（UI=${snapshot.songs.size} / 实际=$count）")
            return
        }
        @Suppress("UNCHECKED_CAST")
        _queue.value = QueueSnapshot(rebuilt as List<Song>, currentIndex)
        AppLogger.w(TAG, "队列已按真实播放列表校正：UI ${snapshot.songs.size} → $count 首")
    }

    // ---------------- 内部机制 ----------------

    /**
     * 切歌（上一首 / 下一首）。
     *
     * 设计目标：**UI 零延迟 + 音源只请求一次 + 中间曲目不加载**。
     *
     * 1. **即时预览**：每次按下立刻把「正在播放」切到目标曲（标题 / 封面 / 歌词 / 列表高亮）。
     *    这一步纯 UI、完全不碰播放器 —— 所以连按 N 次不会让 N 首歌依次进入播放器开始缓冲，
     *    这正是「高频切歌时感觉每一首都加载了」的根治点。
     * 2. **意图累计**：以 [pendingSkipTarget] 为基准累加，连按 N 次最终精确前进 N 步。
     * 3. **合并提交**：只有用户停止连按 [SKIP_COALESCE_MS] 之后，才真正向播放器提交**一次**
     *    （必要时解析**一次**播放地址）；中间 N-1 次既不解析、也不加载、也不产生错误重试。
     * 4. **序号取代**：提交前若序号已过期（用户又切了）或队列已被替换，直接放弃本次提交。
     */
    private fun skip(direction: Int) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        // 点歌提交中：控制器仍是旧队列，此刻切歌会落在错误队列上；新队列即将接管
        if (pendingPlayCommit) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipAt < SKIP_DEBOUNCE_MS) return // 防抖：仅滤除同帧抖动，不阻断连按
        lastSkipAt = now
        // 手动切歌同样跳过「不喜欢」（落地后自动继续链式跳过）
        dislikeSkipGuard = true

        // 以「待落地目标」为基准累计用户意图。
        // 必须按**当前真实播放顺序**推进，不能做 index ± 1：
        // 随机播放下 ±1 走的是「播放列表顺序」，而状态栏 / 耳机 / 锁屏的下一首
        // 走的是「随机顺序」，两者不一致 → 表现为切歌抽搐、连跳几首。
        val base = pendingSkipTarget?.coerceIn(0, c.mediaItemCount - 1) ?: c.currentMediaItemIndex
        val target = stepIndex(c, base, direction)
        pendingSkipTarget = target

        // ① 即时预览：UI 立刻响应，播放器原地不动（不解析、不加载、零请求）
        updateNowPlaying()

        val mySeq = ++skipSeq

        // ② 目标槽位已就绪（下一首通常已被预解析备好）→ 零成本，立即落地：
        //    单次切歌没有任何等待，也不产生任何网络请求。
        if (slotHasUrl(c, target)) {
            commitSkip(c, target, mySeq)
            return
        }

        // ③ 需要付出一次解析成本 → 进入合并窗口：
        //    连按期间一律不提交（只预览），只有停止连按后的最后一次才真正解析 + 落地。
        scope.launch {
            if (SKIP_COALESCE_MS > 0) delay(SKIP_COALESCE_MS)
            // 用户还在连按：本次不提交，交给最新的一次（省音源配额 + 不加载路过的歌）
            if (mySeq != skipSeq) return@launch
            if (pendingPlayCommit) return@launch

            val epoch = queueEpoch
            val ok = ensureResolved(target) // 已有真实地址时零网络请求
            if (mySeq != skipSeq) return@launch
            if (epoch != queueEpoch || pendingPlayCommit) return@launch
            if (!ok) {
                pendingSkipTarget = null
                dislikeSkipGuard = false
                updateNowPlaying()
                return@launch
            }
            commitSkip(c, target, mySeq)
        }
    }

    /** 该槽位是否已持有真实播放地址（用于判断这次切歌是否需要付出一次解析成本） */
    private fun slotHasUrl(c: MediaController, index: Int): Boolean = runCatching {
        c.getMediaItemAt(index).localConfiguration?.uri?.toString().orEmpty().isNotEmpty()
    }.getOrDefault(false)

    /**
     * 按「当前真实播放顺序」从 [from] 推进一步，返回目标媒体项索引。
     *
     * - **随机播放**：跟随 ExoPlayer 的 shuffle 顺序（与状态栏 / 耳机 / 锁屏触发的
     *   `seekToNextMediaItem()` 完全一致），因此 UI 预览的那首就是真正会播的那首；
     * - **单曲循环**：`nextMediaItemIndex` 会返回当前项（原地重复），此时按「换一首」处理，
     *   与用户直觉一致；
     * - **边界**：回绕到另一端（保持原有交互），不会出现 `INDEX_UNSET` 落空。
     */
    private fun stepIndex(c: MediaController, from: Int, direction: Int): Int {
        val count = c.mediaItemCount
        if (count <= 0) return 0
        val safeFrom = from.coerceIn(0, count - 1)

        // 单步：优先采用 Player 自己给出的答案，保证与 seekToNextMediaItem 一致
        if (safeFrom == c.currentMediaItemIndex) {
            val direct = if (direction > 0) c.nextMediaItemIndex else c.previousMediaItemIndex
            if (direct in 0 until count && direct != safeFrom) return direct
        }

        // 多步（连按累计）：用 timeline 的窗口顺序推进，同样遵守 shuffle 顺序
        val timeline = c.currentTimeline
        val walked = if (direction > 0) {
            timeline.getNextWindowIndex(safeFrom, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        } else {
            timeline.getPreviousWindowIndex(safeFrom, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return if (walked in 0 until count && walked != safeFrom) {
            walked
        } else {
            if (direction > 0) 0 else count - 1
        }
    }

    /**
     * 落地一次切歌：向控制器提交跳转。
     *
     * 注意这里**不清除** [pendingSkipTarget]：它同时承担「UI 预览」职责，
     * 若此刻清除而控制器尚未完成过渡，UI 会闪回旧曲。
     * 清除时机见 [updateNowPlaying]：控制器索引真正追上预览目标时自动收尾。
     */
    private fun commitSkip(c: MediaController, target: Int, seq: Int) {
        if (seq != skipSeq || pendingPlayCommit) return
        c.seekTo(target, 0L)
        c.play()
        updateNowPlaying()
    }

    private fun onTransition(reason: Int) {
        val c = controller ?: return
        // 点歌提交中：控制器仍是旧队列，其过渡事件不得回写新状态（否则歌曲信息与音频错位）
        if (pendingPlayCommit) return
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
                // 占位项：立即解析、替换并继续播放（force = false：已解析过的槽位不再重复请求）
                ensureResolved(index, force = false)
                c.prepare()
                c.play()
            }
            // 无论当前项是「占位刚解析」还是「已就绪」，都预解析下一曲：
            // 这样下一次切歌的目标始终是「已备好」状态 → 零等待、零请求。
            // （此前只在已就绪分支预解析，导致落地占位曲后链路一直是冷的，每次切歌都要现解析）
            preloadNext(index)
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
        // 诊断：走到这里说明该槽位确实需要解析（槽位已就绪时会提前 return，不产生任何请求）
        AppLogger.d(TAG, "解析槽位 #$index「${song.title}」force=$force")
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
        // 连按切歌期间不预解析：否则「每落地一次就发一次请求」。
        // 等连按结束、预览目标落地后，由 onTransition 统一预解析一次即可。
        if (pendingSkipTarget != null) return
        // 关键：必须预解析「按当前播放顺序的下一首」。
        // 随机播放下 index + 1 是播放列表顺序的下一首，与真正会播的那首无关，
        // 等于没预热 → 每次随机切歌都落在「占位项」上，触发一次现解析 + prepare，
        // 表现为切歌抽搐、卡顿（正是「随机模式下状态栏切歌抽搐」的根因之一）。
        val nextIndex = stepIndex(c, index, 1)
        if (nextIndex == index) return
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
        // 点歌提交中：旧队列的报错不应触发重解析 / 自动跳过（会污染新队列状态）
        if (pendingPlayCommit) return
        val index = c.currentMediaItemIndex
        if (index < 0 || index >= c.mediaItemCount) return
        val song = _queue.value.songs.getOrNull(index) ?: return

        // 占位项（空 URI）必然让 ExoPlayer 报错，但这属于「还没解析」而非「音源不可用」。
        // 必须单独走自愈路径：否则会被计入错误重试，两次之后自动 next() ——
        // 表现就是「随机模式下切歌连跳几首、抽搐」。
        val landedUri = runCatching {
            c.getMediaItemAt(index).localConfiguration?.uri?.toString().orEmpty()
        }.getOrNull().orEmpty()
        if (landedUri.isEmpty()) {
            healPlaceholderLanding(index, song)
            return
        }

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

    /**
     * 落地到「占位项」（尚未解析出真实地址）时的自愈。
     *
     * 与 [handlePlaybackError] 的区别：这里**不计入错误重试、绝不自动跳过**。
     * 占位项报错只是因为「还没解析」，属于正常流程（例如状态栏切歌比预解析更快），
     * 解析完成后原地替换并继续播放即可。
     */
    private fun healPlaceholderLanding(index: Int, song: Song) {
        val c = controller ?: return
        val expectedMediaId = runCatching { c.getMediaItemAt(index).mediaId }.getOrNull()
        val epoch = queueEpoch
        scope.launch {
            try {
                val resolved = repository.resolveForPlayback(song, _quality.value)
                actualQuality[resolved.song.stableKey] = resolved.quality
                if (epoch != queueEpoch) return@launch
                if (!slotStillMatches(index, expectedMediaId)) return@launch
                c.replaceMediaItem(index, resolved.song.toMediaItem(resolved.url))
                c.prepare()
                c.play()
                updateNowPlaying()
                // 顺手把「下一首」预热好，避免下一次切歌又落到冷槽位
                preloadNext(index)
            } catch (e: Exception) {
                if (epoch != queueEpoch) return@launch
                commands.tryEmit(PlayerCommand.ShowMessage("「${song.title}」解析失败：${e.message}"))
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
        // 点歌提交中：控制器仍持有旧队列，此刻回写会用旧索引覆盖乐观状态
        if (pendingPlayCommit) return
        // 切歌预览：UI 先切到目标曲（控制器尚未提交），不得用控制器的旧索引覆盖
        // 控制器索引已追上预览目标 → 预览结束（此后以控制器为准），避免预览状态残留
        val preview = pendingSkipTarget?.takeIf { it != c.currentMediaItemIndex }
        if (preview == null) pendingSkipTarget = null
        val index = preview?.coerceIn(0, (_queue.value.songs.size - 1).coerceAtLeast(0))
            ?: c.currentMediaItemIndex
        val snapshot = _queue.value
        val song = snapshot.songs.getOrNull(index) ?: return
        val duration = if (preview != null) {
            song.durationMs
        } else {
            c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: song.durationMs
        }
        _nowPlaying.value = NowPlaying(
            song = song,
            isPlaying = c.isPlaying,
            isBuffering = if (preview != null) true else c.playbackState == Player.STATE_BUFFERING,
            positionMs = if (preview != null) 0L else c.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            repeatMode = c.repeatMode,
            shuffleEnabled = c.shuffleModeEnabled,
            quality = actualQuality[song.stableKey] ?: _quality.value,
            desiredQuality = _quality.value,
        )
        // 队列高亮同步到预览目标：迷你条 / 播放页 / 队列页三处展示保持一致
        if (preview != null && snapshot.currentIndex != index) {
            _queue.value = snapshot.copy(currentIndex = index)
        }
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
        // 点歌提交中：控制器与 UI 队列尚未对齐，此刻落盘会存下错位索引
        if (pendingPlayCommit) return
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
        private const val TAG = "PlayerConnection"

        /** 队列歌曲索引容量上限（FIFO 淘汰；远超任何实际队列长度） */
        private const val MAX_SONG_INDEX = 500

        /**
         * 切歌防抖窗口（ms）：**仅用于滤除同帧重复触发**（例如按键与手势同时上报），
         * 刻意设得很短——绝不能阻断用户高频连按的意图，否则「连按 N 次只前进 1 步」。
         * 省配额与防封控由 [SKIP_COALESCE_MS] 的合并窗口负责。
         */
        private const val SKIP_DEBOUNCE_MS = 50L

        /**
         * 切歌请求合并窗口（ms）。
         *
         * 仅作用于「目标曲尚未解析、需要付出一次请求成本」的切歌：
         * 此时不立即发请求，而是等该窗口；窗口内若用户又切了歌，
         * 本次请求直接作废（连按 N 次只发 1 次请求 → 省音源配额、避免风控），
         * 最终落点始终是用户最后想听的那首。
         *
         * 注意：若目标槽位**已持有真实地址**（下一首通常已被预解析备好），
         * 则完全不走这个窗口，而是立即落地——单次切歌零等待、零请求。
         *
         * - 调大：更省配额，但「需要解析的跳转」落地稍慢；
         * - 设为 0：退化为立即请求（不合并）。
         */
        private const val SKIP_COALESCE_MS = 250L

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