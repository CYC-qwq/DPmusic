package com.dpmusic.app.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 音频文件识别解码器：
 * SAF 文件 → MediaCodec 流式解码（≤60 秒，默认按 16bit PCM 输出）
 * → 挑选 RMS 最大的 10 秒窗口（避开静音 / 淡入淡出段）
 * → 线性插值重采样 48kHz → 共用预处理管线（滤波 / 降采样 / 归一化）。
 */
object AudioFileDecoder {

    private const val WINDOW_SEC = 10
    private const val MAX_ANALYZE_SEC = 60
    private const val TARGET_RATE = 48_000

    /**
     * 解码 + 预处理音频文件，返回可直接送入双引擎的 8kHz 单声道 Float32。
     * @return null = 文件无法解析（格式不支持 / 无音频轨 / 内容为空）
     */
    suspend fun decodeForRecognize(context: Context, uri: Uri): FloatArray? =
        withContext(Dispatchers.IO) {
            val decoded = decodeMono(context, uri) ?: return@withContext null
            val (mono, rate) = decoded
            if (mono.size < rate / 2) return@withContext null
            val window = pickBestWindow(mono, rate)
            val x48 = resampleLinear(window, rate, TARGET_RATE)
            AudioSampler.process48k(x48)
        }

    /** 流式解码为单声道 Float（最多 [MAX_ANALYZE_SEC] 秒） */
    private suspend fun decodeMono(context: Context, uri: Uri): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val rate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)
                .coerceAtLeast(8_000)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)
                .coerceAtLeast(1)

            val maxFrames = MAX_ANALYZE_SEC * rate
            val out = FloatArray(maxFrames)
            var written = 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && written < maxFrames) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                buf.asShortBuffer().get(shorts)
                                val frames = shorts.size / channels
                                var i = 0
                                while (i < frames && written < maxFrames) {
                                    var acc = 0
                                    for (c in 0 until channels) acc += shorts[i * channels + c]
                                    out[written++] = acc / (32768f * channels)
                                    i++
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
            return out.copyOf(written) to rate
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 挑选 RMS 最大的 [WINDOW_SEC] 秒窗口（避开静音 / 淡入淡出段） */
    private fun pickBestWindow(mono: FloatArray, rate: Int): FloatArray {
        val winLen = WINDOW_SEC * rate
        if (mono.size <= winLen) return mono
        var bestStart = 0
        var bestPower = -1.0
        var start = 0
        val step = rate / 2
        while (start + winLen <= mono.size) {
            var sum = 0.0
            var i = start
            val end = start + winLen
            while (i < end) {
                val v = mono[i]
                sum += v * v
                i += 16
            }
            if (sum > bestPower) {
                bestPower = sum
                bestStart = start
            }
            start += step
        }
        return mono.copyOfRange(bestStart, bestStart + winLen)
    }

    /** 线性插值重采样（目标频段 0~4kHz 内足够保真，前后级滤波保证抗混叠） */
    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || src.isEmpty()) return src
        val outLen = (src.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = src[i0.coerceAtMost(src.size - 1)]
            val s1 = src[(i0 + 1).coerceAtMost(src.size - 1)]
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }
}