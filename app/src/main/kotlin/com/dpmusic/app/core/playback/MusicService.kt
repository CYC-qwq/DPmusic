package com.dpmusic.app.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dpmusic.app.MainActivity
import com.dpmusic.app.R
import com.dpmusic.app.core.audio.AudioEffectsManager

/**
 * 播放前台服务：Jetpack Media3 MediaSessionService。
 *
 * 设计要点：
 * - ExoPlayer 与 UI 完全解耦，生命周期由服务持有；
 * - 音频焦点：handleAudioFocus = true，自动处理来电 / 其他应用抢占；
 * - becomingNoisy：拔耳机自动暂停；
 * - 网络唤醒锁：锁屏弱网环境持续缓冲；
 * - 通知栏：定制小图标 + 中文频道名的 DefaultMediaNotificationProvider。
 */
@UnstableApi
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** 音频会话监听：会话就绪 / 变更时（重）挂载音效均衡器 */
    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                AudioEffectsManager.attach(audioSessionId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
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
        player = exo

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("dpmusic_playback")
            .setChannelName(R.string.notification_channel_playback)
            .setNotificationId(1001)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(notificationProvider)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player ?: return
        // 用户主动划掉任务且处于停止状态时，彻底释放服务
        if (!current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        player?.removeListener(audioSessionListener)
        AudioEffectsManager.detach()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}