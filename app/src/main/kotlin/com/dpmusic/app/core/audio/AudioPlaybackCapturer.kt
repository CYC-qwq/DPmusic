package com.dpmusic.app.core.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.dpmusic.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 系统播放音频捕获（Android 10+，AudioPlaybackCapture）：
 * - 捕获手机内部正在播放的媒体音频（需用户授予 MediaProjection 授权）；
 * - 立体声 48kHz → 下混单声道 → 共用预处理管线（滤波 / 降采样 / 归一化）。
 *
 * 注意：Android 14+ 要求调用 [obtainProjection] 前先运行 mediaProjection 前台服务
 * （见 [CaptureForegroundService]）。
 */
object AudioPlaybackCapturer {

    /** 系统播放捕获是否可用（Android 10+） */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 从授权结果获取 MediaProjection（调用前须先启动 mediaProjection 前台服务） */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun obtainProjection(resultCode: Int, data: Intent): MediaProjection? {
        if (resultCode != Activity.RESULT_OK) return null
        val manager = AppContainer.appContext
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return null
        // 注册回调（Android 14+ 前置要求；用户随时可在系统 UI 中停止捕获）
        runCatching { projection.registerCallback(object : MediaProjection.Callback() {}, null) }
        return projection
    }

    /** 捕获 [durationMs] 毫秒系统播放音频 → 8kHz 单声道 Float32（与麦克风链路同管线） */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun capture(
        projection: MediaProjection,
        durationMs: Long,
        onProgress: (Float, Float) -> Unit = { _, _ -> },
    ): FloatArray = withContext(Dispatchers.IO) {
        val rate = AudioSampler.SAMPLE_RATE
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "当前设备不支持 48kHz 系统音频捕获" }

        val recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, rate / 2) * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "系统音频捕获初始化失败" }

        val totalFrames = (rate * durationMs / 1000).toInt()
        val mono = FloatArray(totalFrames)
        try {
            recorder.startRecording()
            var offset = 0
            val chunk = ShortArray(8192)
            while (offset < totalFrames) {
                coroutineContext.ensureActive()
                val n = recorder.read(chunk, 0, minOf(chunk.size, (totalFrames - offset) * 2))
                if (n <= 0) break
                val frames = n / 2
                var sum = 0.0
                for (i in 0 until frames) {
                    val v = (chunk[i * 2] + chunk[i * 2 + 1]) / 65536.0
                    mono[offset + i] = v.toFloat()
                    sum += v * v
                }
                offset += frames
                val level = if (frames > 0) sqrt(sum / frames).toFloat() else 0f
                onProgress((offset.toFloat() / totalFrames).coerceIn(0f, 1f), level.coerceIn(0f, 1f))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        AudioSampler.process48k(mono)
    }
}