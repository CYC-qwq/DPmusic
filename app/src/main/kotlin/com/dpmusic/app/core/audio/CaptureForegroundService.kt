package com.dpmusic.app.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.dpmusic.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * 系统播放捕获的 mediaProjection 前台服务。
 *
 * Android 14+ 要求：调用 MediaProjectionManager.getMediaProjection() 前，
 * 必须先运行一个 foregroundServiceType="mediaProjection" 的前台服务。
 */
class CaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        started.complete(Unit)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "系统音频识别", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("正在识别系统播放音频")
            .setContentText("DPmusic 听歌识曲进行中")
            .setSmallIcon(R.drawable.ic_stat_music)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recognize_capture"
        private const val NOTIFICATION_ID = 0x5EA1

        @Volatile
        private var started = CompletableDeferred<Unit>()

        /** 启动前台服务并等待其就绪（调用 getMediaProjection 前必须完成） */
        suspend fun startAndAwait(context: Context, timeoutMs: Long = 4_000): Boolean {
            started = CompletableDeferred()
            ContextCompat.startForegroundService(context, Intent(context, CaptureForegroundService::class.java))
            return try {
                withTimeout(timeoutMs) { started.await() }
                true
            } catch (e: Exception) {
                false
            }
        }

        /** 停止前台服务（捕获结束后调用，可重复调用） */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CaptureForegroundService::class.java)) }
        }
    }
}