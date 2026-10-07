package com.dpmusic.app.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dpmusic.app.AppContainer
import com.dpmusic.app.MainActivity
import com.dpmusic.app.R
import com.dpmusic.app.core.audio.AudioEffectsManager
import com.dpmusic.app.core.audio.BitPerfectController
import com.dpmusic.app.core.audio.DspEngine
import com.dpmusic.app.core.miisland.MiIslandController
import com.dpmusic.app.core.miisland.MiIslandNotificationProvider
import com.dpmusic.app.core.miisland.MiIslandPublisher
import com.dpmusic.app.core.playback.PlayMode
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放前台服务：Jetpack Media3 MediaSessionService。
 *
 * 设计要点：
 * - ExoPlayer 与 UI 完全解耦，生命周期由服务持有；
 * - 音频焦点：handleAudioFocus = true，自动处理来电 / 其他应用抢占；
 * - becomingNoisy：拔耳机自动暂停；
 * - 网络唤醒锁：锁屏弱网环境持续缓冲；
 * - 通知栏：定制小图标 + 中文频道名的 DefaultMediaNotificationProvider；
 * - 小米超级岛：在原生通知外再包一层 [MiIslandNotificationProvider]（见 core/miisland）。
 */
@UnstableApi
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** 超级岛通知提供者（持有「重建通知」能力，供刷新循环复用） */
    private var islandProviderRef: MiIslandNotificationProvider? = null
    /** 超级岛发布器：歌词行变化时重建 + 魔法重发通知 */
    @Volatile
    private var islandPublisher: MiIslandPublisher? = null

    /**
     * 原生媒体岛（系统从 MediaSession 媒体通知渲染的岛）的两个自定义按钮命令。
     *
     * 为什么用自定义 SessionCommand 而非 MediaButton：
     * 媒体按钮只覆盖「上一首 / 播放 / 下一首」这类预定义语义，「喜欢」「播放模式」没有对应按键，
     * 只能作为自定义动作承载（参考实现 otter-music 同样如此）。
     *
     * 这两个命令必须同时出现在两处，缺一不可：
     * 1. [sessionCallback] 的 `availableSessionCommands` —— 否则按钮被判为不可用而被剔除；
     * 2. 按钮的 `sessionCommand` —— 否则按钮不会出现在通知 / 媒体岛上。
     */
    private val likeCommand = SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY)
    private val playModeCommand = SessionCommand(CUSTOM_ACTION_PLAY_MODE, Bundle.EMPTY)

    /** 收藏态来自 DataStore 流（非 Player 事件），用它跟随刷新按钮图标 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 会话回调：放开自定义命令，并处理媒体岛按钮点击。
     *
     * 只覆盖这两个回调，其余（onSetMediaItems 等）沿用 Media3 默认实现 ——
     * 队列与解析逻辑在 [com.dpmusic.app.core.playback.PlayerConnection]，不在此重复。
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            // 以该 controller 的默认可命令集为基线，只追加两个自定义命令，
            // 不扩大授信范围（untrusted controller 仍拿不到写命令）。
            val base = if (controller.isTrusted()) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            }
            val available = base.buildUpon()
                .add(likeCommand)
                .add(playModeCommand)
                .build()
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(available)
                    .build(),
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_ACTION_LIKE -> AppContainer.player.toggleFavorite()
                CUSTOM_ACTION_PLAY_MODE -> AppContainer.player.cyclePlayMode()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            // 两个动作都改变按钮自身图标（实心↔空心、随机↔循环），立即回写偏好
            updateMediaButtons()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }


    /** 音频会话监听：会话就绪 / 变更时（重）挂载音效均衡器 */
    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                AudioEffectsManager.attach(audioSessionId)
            }
        }
    }

    /**
     * 音频格式监听：把**解码器真实输出格式**上报给 Bit-Perfect 控制器。
     *
     * 为什么必须用它：网络流的采样率/位深由上游决定，只有解码后才知道；
     * 提前按「设置里的音质」猜会导致 USB DAC 拿到不匹配的 mixer 格式。
     */
    private val audioFormatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            BitPerfectController.onStreamFormat(format.sampleRate, format.channelCount)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(buildMediaSourceFactory())
            .setRenderersFactory(DspEngine.DspRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(buildLoadControl())
            .build()
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.addListener(audioSessionListener)
        exo.addAnalyticsListener(audioFormatListener)
        player = exo

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, SkipInterceptingPlayer(exo))
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            // ★ 原生媒体岛按钮：收藏 + 播放模式。必须在 build() 时给定，
            //   否则首个媒体通知控制器连接前不会有这两个按钮（见 currentMediaButtons 注释）。
            .setMediaButtonPreferences(currentMediaButtons())
            .build()
        // 收藏态来自 DataStore（不是 Player 事件），切换收藏后需主动回写按钮图标
        serviceScope.launch {
            AppContainer.favorites.favorites.collect { updateMediaButtons() }
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("dpmusic_playback")
            .setChannelName(R.string.notification_channel_playback)
            .setNotificationId(MiIslandController.NOTIFICATION_ID)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_music)
        // ★ 在外层包一层：原生通知行为完全保留，仅额外挂上超级岛载荷
        // onBuilt 通过形参桥接 publisher：provider 必须先于 publisher 创建
        // （publisher 需要 provider.rebuild()），而 provider 的构建回调又需要 publisher。
        val islandProvider = MiIslandNotificationProvider(this, notificationProvider) { notification ->
            islandPublisher?.onNotificationBuilt(notification)
        }
        islandProviderRef = islandProvider
        setMediaNotificationProvider(islandProvider)

        // 超级岛：常驻「重建通知 + 断网魔法重发」流水线（关闭模式时自身空转，零副作用）
        islandPublisher = MiIslandPublisher(this) { islandProvider.rebuild() }
        // 设置页改样式 / 配色后立即重发（内容键不含这些偏好，不主动作废就看不到变化）
        MiIslandController.refreshHook = { islandPublisher?.refreshNow() }
        islandPublisher?.start()

        // 切歌 → 重新拉取歌词/封面；播放态变化 → 立即回写（不等 1s 周期）
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                MiIslandController.onSongChanged(AppContainer.player.nowPlaying.value?.song)
                islandPublisher?.invalidate()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                islandPublisher?.invalidate()
            }

            // 模式变化 → 回写媒体岛「播放模式」按钮图标（随机/列表/单曲/顺序）
            override fun onRepeatModeChanged(repeatMode: Int) {
                updateMediaButtons()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateMediaButtons()
            }
        })
    }

    /**
     * 媒体源工厂：在真正发起请求前，按 URL 注入音源要求的 HTTP 头。
     *
     * 部分音源（尤其 MusicFree 插件解析出的地址）必须带 Referer / User-Agent 才能播放，
     * 而 Media3 的 MediaItem 无法携带请求头 —— 这里用 ResolvingDataSource
     * 在 DataSpec 层面把 [PlaybackHeaderStore] 里登记的请求头补上。
     *
     * ⚠️ **必须用 [DefaultDataSource.Factory] 而不是 `DefaultHttpDataSource.Factory`**：
     * 后者只认 http(s)，遇到 `file://`（本地文件）会直接报错。汽水音源解密后的音频
     * 就落在 App 缓存目录里、以 `file://` 交给播放器，用 HTTP-only 工厂会报 `Source error`。
     * `DefaultDataSource` 会按 scheme 自动分发：file → FileDataSource，http → HttpDataSource。
     */
    private fun buildMediaSourceFactory(): MediaSource.Factory {
        val baseFactory = DefaultDataSource.Factory(this)
        val resolvingFactory = ResolvingDataSource.Factory(baseFactory) { dataSpec ->
            val headers = PlaybackHeaderStore.headersFor(dataSpec.uri.toString())
            if (headers.isEmpty()) {
                dataSpec
            } else {
                dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            }
        }
        return DefaultMediaSourceFactory(resolvingFactory)
    }

    /**
     * 缓冲策略：
     * - 流媒体（弱网调优）：最小缓冲 30s / 最大 60s；起播 1.5s / 断流恢复 3s；
     * - 本地文件（快速起播 + 低内存）：最小 1s / 最大 15s；起播 1s / 断流恢复 1s；
     * - 通用：回退缓冲 30s（保留关键帧）。
     */
    private fun buildLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            /* minBufferMs = */ 30_000,
            /* maxBufferMs = */ 60_000,
            /* bufferForPlaybackMs = */ 1_500,
            /* bufferForPlaybackAfterRebufferMs = */ 3_000,
        )
        .setBufferDurationsMsForLocalPlayback(
            /* minBufferMs = */ 1_000,
            /* maxBufferMs = */ 15_000,
            /* bufferForPlaybackMs = */ 1_000,
            /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .setBackBuffer(30_000, true)
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 当前媒体岛自定义按钮（收藏 + 播放模式）。
     *
     * 两者都不指定 slot，因此默认落在 `SLOT_OVERFLOW` —— 系统把它们排在
     * 「上一首 / 播放暂停 / 下一首」**之后**，不占用那三个主位，也就不会与
     * 原生媒体按钮抢位。这是「加按钮但不破坏原有布局」的关键。
     *
     * 图标用 Media3 内置常量而不是自绘 drawable：
     * `ICON_HEART_FILLED / ICON_HEART_UNFILLED`、`ICON_REPEAT_ALL / ONE / OFF`、
     * `ICON_SHUFFLE_ON` 是 1.11 起自带的标准媒体图标，**系统与车机都认**，
     * 换自绘图标反而会丢失这套语义映射。
     */
    private fun currentMediaButtons(): ImmutableList<CommandButton> {
        val favorited = AppContainer.player.isCurrentFavorite()
        val likeButton = CommandButton.Builder(
            if (favorited) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setSessionCommand(likeCommand)
            .setDisplayName(if (favorited) "取消喜欢" else "喜欢")
            .build()

        val modeIcon = when (AppContainer.player.playMode()) {
            PlayMode.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON
            PlayMode.REPEAT_ONE -> CommandButton.ICON_REPEAT_ONE
            PlayMode.REPEAT_ALL -> CommandButton.ICON_REPEAT_ALL
            PlayMode.SEQUENCE -> CommandButton.ICON_REPEAT_OFF
        }
        val modeButton = CommandButton.Builder(modeIcon)
            .setSessionCommand(playModeCommand)
            // 文案即「点一次会切成什么」，与 App 内循环按钮的轮换语义一致
            .setDisplayName(nextPlayModeLabel())
            .build()

        return ImmutableList.of(likeButton, modeButton)
    }

    /** 「点一次切到的模式」描述，供按钮无障碍文案使用 */
    private fun nextPlayModeLabel(): String = when (AppContainer.player.playMode()) {
        PlayMode.SHUFFLE -> "列表循环"
        PlayMode.REPEAT_ALL -> "单曲循环"
        PlayMode.SEQUENCE, PlayMode.REPEAT_ONE -> "随机播放"
    }

    /**
     * 回写媒体岛按钮。
     *
     * 收藏 / 播放模式变化不改变「歌曲 + 播放态」，若不主动回写，按钮图标会停在旧状态。
     * [MediaSession.setMediaButtonPreferences] 内部会切到 session 线程并通知媒体通知控制器
     * 重建通知，故可安全地从任意线程调用。
     */
    private fun updateMediaButtons() {
        session?.setMediaButtonPreferences(currentMediaButtons())
    }

    /**
     * 超级岛按钮的落点：`textButton` 里每个按钮挂一条指向本服务的 PendingIntent，
     * 由这里分发到 [com.dpmusic.app.core.playback.PlayerConnection]。
     *
     * 为什么不复用 Media3 的媒体按钮通道：超级岛按钮是**载荷里的自定义 action**，
     * 与通知的 MediaStyle 动作不是同一条链路，Media3 不会替我们分发。
     * 未匹配的 intent 必须交给 `super` —— 它负责会话恢复等框架级 intent。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> AppContainer.player.togglePlayPause()
            ACTION_NEXT -> AppContainer.player.next()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 切歌拦截播放器：把「下一首 / 上一首」统一转交给 App 侧的切歌协调器。
     *
     * 覆盖来源：状态栏通知、耳机线控、蓝牙、锁屏、Android Auto —— 这些命令直接作用于
     * 服务端 Player，原本会绕过 UI 的合并窗口（高频连按 = 高频真实 seek + 高频现解析，
     * 既费音源配额又抽搐）。路由到 [PlayerConnection.requestExternalSkip] 后，
     * 与 App 内按钮共享同一套「即时预览 → 合并窗口 → 序号取代 → 预解析」逻辑。
     *
     * 若协调器尚未就绪（控制器未连接），回退为 Player 默认行为，保证功能不失效。
     */
    private class SkipInterceptingPlayer(delegate: Player) : ForwardingPlayer(delegate) {
        override fun seekToNextMediaItem() {
            if (!AppContainer.player.requestExternalSkip(1)) super.seekToNextMediaItem()
        }

        override fun seekToNext() {
            if (!AppContainer.player.requestExternalSkip(1)) super.seekToNext()
        }

        override fun seekToPreviousMediaItem() {
            if (!AppContainer.player.requestExternalSkip(-1)) super.seekToPreviousMediaItem()
        }

        override fun seekToPrevious() {
            if (!AppContainer.player.requestExternalSkip(-1)) super.seekToPrevious()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player ?: return
        // 用户主动划掉任务且处于停止状态时，彻底释放服务
        if (!current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        MiIslandController.refreshHook = null
        serviceScope.cancel()
        islandPublisher?.release()
        islandPublisher = null
        islandProviderRef = null
        player?.removeListener(audioSessionListener)
        player?.removeAnalyticsListener(audioFormatListener)
        AudioEffectsManager.detach()
        // 归还 USB 独占控制权（否则其他应用会被锁在 bit-perfect 模式下）
        BitPerfectController.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    companion object {
        /** 超级岛「播放/暂停」按钮 action */
        const val ACTION_TOGGLE_PLAY = "com.dpmusic.app.action.ISLAND_TOGGLE_PLAY"

        /** 超级岛「下一首」按钮 action */
        const val ACTION_NEXT = "com.dpmusic.app.action.ISLAND_NEXT"

        /**
         * 原生媒体岛两个自定义按钮的命令名。
         *
         * 与 `ISLAND_*` 那三条 action 的区别：那三条是 **focus 载荷**（Shizuku 魔法链路）
         * 里按钮的 PendingIntent 落点，走 `onStartCommand`；这里的两个是 **MediaSession
         * 自定义命令**，走 `MediaSession.Callback.onCustomCommand`，由 Media3 自动从媒体
         * 通知 / 媒体岛的按钮点击派发过来 —— 两条链路互不相干，不能混用。
         */
        const val CUSTOM_ACTION_LIKE = "com.dpmusic.app.action.LIKE"
        const val CUSTOM_ACTION_PLAY_MODE = "com.dpmusic.app.action.PLAY_MODE"

        // 媒体通知 ID 统一由 MiIslandController 定义（超级岛刷新复用同一条通知，必须一致）
    }
}