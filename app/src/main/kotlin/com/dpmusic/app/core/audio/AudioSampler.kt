package com.dpmusic.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 环境音频采样器：录制 N 秒 48kHz 单声道 PCM，
 * 经「抗混叠低通 → 降采样 8kHz → 峰值归一化」预处理，供双引擎直接消费
 * （酷狗 PCM 直传 / 网易云指纹）。
 *
 * 音频仅在内存中处理，不落盘、不上传原始录音（仅上传指纹/8kHz PCM）。
 */
object AudioSampler {

    const val SAMPLE_RATE = 48_000
    const val TARGET_RATE = 8_000

    /** 降采样倍率：48kHz → 8kHz */
    private const val DECIMATION = SAMPLE_RATE / TARGET_RATE

    /** 归一化目标峰值（留 5% 余量，充分利用 16bit 量化精度） */
    private const val NORMALIZE_PEAK = 0.95f

    /** 静音保护：峰值低于该值不做归一化（避免放大纯噪声） */
    private const val SILENCE_PEAK = 1e-4f

    /**
     * 抗混叠低通（8 阶 Butterworth，fc=3.6kHz，4 级 biquad 级联，Direct Form II Transposed）。
     *
     * 直接「每 6 点取 1」会把 4kHz 以上噪声折叠进识别频段（0~4kHz），嘈杂环境下显著拉低信噪比；
     * 该滤波器在降采样前滤除 3.6kHz 以上内容（4kHz 约 -8dB、6kHz 约 -38dB、8kHz 约 -61dB）。
     * 系数由双线性变换 + 预畸变（fs=48kHz）设计，极点均在单位圆内。
     */
    private val ANTI_ALIAS_SOS: Array<FloatArray> = arrayOf(
        floatArrayOf(3.25020596e-06f, 6.50041193e-06f, 3.25020596e-06f, -1.63702333f, 0.837274194f),
        floatArrayOf(1f, 2f, 1f, -1.29367685f, 0.451927453f),
        floatArrayOf(1f, 2f, 1f, -1.23299897f, 0.38382715f),
        floatArrayOf(1f, 2f, 1f, -1.42307889f, 0.597158849f),
    )

    /**
     * 录制 [durationMs] 毫秒环境音频，经「抗混叠低通 → 降采样 8kHz → 峰值归一化」预处理。
     *
     * @param onProgress 回调（progress 0..1, level 0..1 音量），用于 UI 进度/波纹。
     * @return 8kHz 单声道 Float32 采样（长度 = 秒数 × 8000，已滤波 + 归一化）。
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        durationMs: Long = 10_000,
        onProgress: (Float, Float) -> Unit = { _, _ -> },
    ): FloatArray = withContext(Dispatchers.IO) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "当前设备不支持 48kHz 录音" }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, SAMPLE_RATE / 2) * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "录音设备初始化失败" }

        val pcm = ShortArray(totalSamples)
        try {
            recorder.startRecording()
            var offset = 0
            val chunk = ShortArray(4096)
            while (offset < totalSamples) {
                coroutineContext.ensureActive()
                val n = recorder.read(chunk, 0, minOf(chunk.size, totalSamples - offset))
                if (n <= 0) break
                System.arraycopy(chunk, 0, pcm, offset, n)
                offset += n
                // 块级 RMS 音量（0..1），供 UI 波纹
                var sum = 0.0
                for (i in 0 until n) {
                    val v = chunk[i] / 32768.0
                    sum += v * v
                }
                val level = if (n > 0) sqrt(sum / n).toFloat() else 0f
                onProgress((offset.toFloat() / totalSamples).coerceIn(0f, 1f), level.coerceIn(0f, 1f))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        // 预处理：抗混叠低通 → 降采样 8kHz → 峰值归一化（三条拾音链路共用）
        process48k(FloatArray(totalSamples) { pcm[it] / 32768f })
    }

    /**
     * 预处理管线：48kHz 单声道 Float32 → 8kHz。
     * 抗混叠低通 → 降采样（每 6 点取 1）→ 峰值归一化；麦克风 / 系统播放 / 音频文件共用。
     */
    fun process48k(input: FloatArray): FloatArray {
        // 1) 抗混叠低通（滤除 4kHz 以上折叠噪声）
        val filtered = input.copyOf()
        antiAliasFilter(filtered)

        // 2) 降采样：每 6 点取 1（48kHz → 8kHz）
        val out = FloatArray(filtered.size / DECIMATION)
        for (i in out.indices) out[i] = filtered[i * DECIMATION]

        // 3) 峰值归一化：弱信号也能用满 16bit 动态范围
        var peak = 0f
        for (v in out) {
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        if (peak > SILENCE_PEAK) {
            val gain = NORMALIZE_PEAK / peak
            for (i in out.indices) out[i] *= gain
        }
        return out
    }

    /** 级联 biquad 滤波（Direct Form II Transposed），就地处理 */
    private fun antiAliasFilter(x: FloatArray) {
        for (section in ANTI_ALIAS_SOS) {
            val b0 = section[0]
            val b1 = section[1]
            val b2 = section[2]
            val a1 = section[3]
            val a2 = section[4]
            var z1 = 0f
            var z2 = 0f
            for (i in x.indices) {
                val v = x[i] - a1 * z1 - a2 * z2
                x[i] = b0 * v + b1 * z1 + b2 * z2
                z2 = z1
                z1 = v
            }
        }
    }

    /**
     * Float32 [-1, 1] → 16bit 小端 PCM 字节（酷狗识曲上传用）。
     * 负值 ×32768 / 正值 ×32767，与官方前端转换一致。
     */
    fun floatToInt16Le(data: FloatArray): ByteArray {
        val out = ByteArray(data.size * 2)
        var bi = 0
        for (f in data) {
            val s = f.coerceIn(-1f, 1f)
            val v = if (s < 0) (s * 32768f).toInt() else (s * 32767f).toInt()
            out[bi++] = (v and 0xFF).toByte()
            out[bi++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
