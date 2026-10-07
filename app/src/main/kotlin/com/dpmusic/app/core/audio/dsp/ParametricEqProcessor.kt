package com.dpmusic.app.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * 参数均衡器 AudioProcessor：跑在 Media3 音频处理链内，直接处理 PCM。
 *
 * 结构：[preamp]（总增益）→ [lowShelf]（低架，替代 BassBoost）→ [bands]（N 段峰形 EQ）
 * → [highShelf]（高架）→ [limiter]（软削波，防提升后爆表）。
 *
 * 支持两种 PCM 编码（由解码器决定）：
 * - [C.ENCODING_PCM_16BIT]：16-bit 小端整数（最常见，MediaCodec 硬解输出）
 * - [C.ENCODING_PCM_FLOAT]：32-bit float（`setEnableAudioFloatOutput(true)` 时）
 * 其它编码返回 [AudioProcessor.AudioFormat.NOT_SET] → 本处理器被自动跳过（安全降级）。
 *
 * 线程约束：所有参数写入都走 [pendingParams]（`@Volatile` 一次性发布），
 * 音频线程在 [queueInput] 开头读取并就地重算系数 —— **避免跨线程直接改系数**。
 */
internal class ParametricEqProcessor : BaseAudioProcessor() {

    /** 单段峰形 EQ 参数（频率 / Q / 增益 dB） */
    data class Band(val freqHz: Float, val q: Float, val gainDb: Float)

    /** 完整 EQ 参数（一次发布，避免音频线程读到半更新状态） */
    data class Params(
        val preampDb: Float = 0f,
        val lowShelfHz: Float = 120f,
        val lowShelfDb: Float = 0f,
        val highShelfHz: Float = 8000f,
        val highShelfDb: Float = 0f,
        /** 立体声宽度（1 = 原样；>1 拓宽；仅对 2 声道生效）—— 替代已废弃的平台 Virtualizer */
        val stereoWidth: Float = 1f,
        val bands: List<Band> = emptyList(),
    ) {
        /** 是否等于「完全不处理」——用于让处理器整条旁路 */
        val isFlat: Boolean
            get() = preampDb == 0f && lowShelfDb == 0f && highShelfDb == 0f &&
                stereoWidth == 1f && bands.all { it.gainDb == 0f }
    }

    @Volatile
    private var pendingParams: Params = Params()

    /**
     * UI 侧的「参数均衡器」总开关。
     *
     * ⚠️ 为什么必须存在：Media3 只在 `configure()` 阶段按 `isActive()` 决定是否把本处理器
     * 编入音频链；一旦编入，后续 `isActive()` 返回 false 也不会被移出。所以「播放中打开 EQ」
     * 要真正生效，必须在链上时**预先注册**（见 AudioProcessorChain 侧），而本字段负责
     * 用户显式关闭时立刻旁路（不处理、只透传）。
     */
    @Volatile
    private var eqEnabled: Boolean = false

    /** 已应用的参数（音频线程持有，用于判断是否需要重算系数） */
    private var appliedParams: Params = Params()
    private var appliedSampleRate = 0
    private var appliedChannelCount = 0

    // 预分配的滤波器实例（最多 10 段峰形 + 低架 + 高架）
    private val bandFilters = Array(MAX_BANDS) { Biquad() }
    private val lowShelf = Biquad()
    private val highShelf = Biquad()
    private var preampLinear = 1f

    /** 限制器状态（简单峰值检测，避免提升后削顶爆音） */
    private var limiterGain = 1f

    /**
     * ⚠️ **恒为 true（常驻音频链）**，这是刻意的。
     *
     * Media3 只在 `configure()` 阶段按 `isActive()` 决定是否把处理器编入链，
     * 编入后**再改返回值也不会被移出**。若这里返回 `eqEnabled`，那么「播放中打开 EQ」
     * 会因链上没编入而完全无声无息地不生效。所以恒为 true，真正的开/关由
     * [eqEnabled] 在 [queueInput] 内部决定（关闭时纯透传，开销仅一次缓冲拷贝）。
     */
    override fun isActive(): Boolean = true

    /** 参数是否已生效（UI 显示用） */
    val appliedBandCount: Int get() = appliedParams.bands.size

    /** 由控制层调用（任意线程）：更新参数，音频线程下一帧生效 */
    fun setParams(params: Params) {
        pendingParams = params
    }

    /** 由控制层调用（任意线程）：总开关。关闭后音频线程只透传，不做任何运算 */
    fun setEnabled(enabled: Boolean) {
        eqEnabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        // 多声道（5.1 等）不做处理：滤波器状态按 2 声道预分配，硬塞会串音
        if (inputAudioFormat.channelCount > Biquad.MAX_CHANNELS) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        appliedSampleRate = inputAudioFormat.sampleRate
        appliedChannelCount = inputAudioFormat.channelCount
        appliedParams = Params()          // 强制下一帧重算系数
        return inputAudioFormat          // 采样率 / 声道 / 编码均不变
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetFilters()
    }

    override fun onReset() {
        resetFilters()
        appliedParams = Params()
        appliedSampleRate = 0
        appliedChannelCount = 0
        preampLinear = 1f
    }

    /** 清空滤波器延迟线 + 限制器状态（切歌 / 换流时避免旧状态产生瞬态爆音） */
    private fun resetFilters() {
        lowShelf.reset()
        highShelf.reset()
        bandFilters.forEach { it.reset() }
        limiterGain = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // 1) 参数变更 → 重算系数（只在参数真的变了才做，避免每帧重复三角运算）
        val params = pendingParams
        if (params != appliedParams || appliedSampleRate != inputAudioFormat.sampleRate) {
            applyCoefficients(params)
            appliedParams = params
            appliedSampleRate = inputAudioFormat.sampleRate
        }

        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameCount = inputBuffer.remaining() / (bytesPerSample * appliedChannelCount)
        if (frameCount <= 0) return

        val out = replaceOutputBuffer(frameCount * bytesPerSample * appliedChannelCount)
        out.order(ByteOrder.LITTLE_ENDIAN)

        // 处理条件：开关打开 + 参数非平 + （有频段 或 有立体声拓宽）
        val wide = appliedChannelCount == 2 && params.stereoWidth != 1f
        val process = eqEnabled && !params.isFlat && (appliedBandCount > 0 || wide)
        val preamp = preampLinear
        val width = params.stereoWidth
        for (f in 0 until frameCount) {
            if (process) {
                if (appliedChannelCount == 2) {
                    // ---- 立体声：先做单声道共同处理，再做 M/S 宽度 ----
                    var l = if (bytesPerSample == 4) inputBuffer.float else inputBuffer.short / 32768f
                    var r = if (bytesPerSample == 4) inputBuffer.float else inputBuffer.short / 32768f
                    if (eqEnabled && appliedBandCount > 0) {
                        l = filterChain(l, 0)
                        r = filterChain(r, 1)
                    }
                    l *= preamp
                    r *= preamp
                    if (wide) {
                        // M/S：拓宽只放大「差信号」，人声/贝斯等居中成分不受影响
                        val mid = (l + r) * 0.5f
                        val side = (l - r) * 0.5f * width
                        l = mid + side
                        r = mid - side
                    }
                    l = softLimit(l)
                    r = softLimit(r)
                    writeSample(out, l, bytesPerSample)
                    writeSample(out, r, bytesPerSample)
                } else {
                    // ---- 单声道：只做 EQ ----
                    var v = if (bytesPerSample == 4) inputBuffer.float else inputBuffer.short / 32768f
                    if (eqEnabled && appliedBandCount > 0) {
                        v = filterChain(v, 0)
                        v *= preamp
                        v = softLimit(v)
                    }
                    writeSample(out, v, bytesPerSample)
                }
            } else {
                // 纯透传（未启用 / 全平）—— 逐样本原样写出，避免任何数值改动
                for (ch in 0 until appliedChannelCount) {
                    if (bytesPerSample == 4) out.putFloat(inputBuffer.float)
                    else out.putShort(inputBuffer.short)
                }
            }
        }
        out.flip()
    }

    /** 低架 → N 段峰形 → 高架（单声道链路，Direct Form I） */
    private fun filterChain(x: Float, ch: Int): Float {
        var v = lowShelf.process(x, ch)
        for (i in 0 until appliedParams.bands.size) {
            v = bandFilters[i].process(v, ch)
        }
        return highShelf.process(v, ch)
    }

    /** 写出单个归一化样本（16-bit 时先夹紧再转整数，避免溢出绕回） */
    private fun writeSample(out: ByteBuffer, v: Float, bytesPerSample: Int) {
        if (bytesPerSample == 4) {
            out.putFloat(v)
        } else {
            out.putShort((v.coerceIn(-1f, 0.9999695f) * 32767f).toInt().toShort())
        }
    }

    /** 软限制：仅在接近满刻度时介入，正常电平零染色 */
    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= LIMIT_THRESHOLD) {
            limiterGain = 1f
            return x
        }
        // 超过阈值 → 平滑压缩（一阶平滑，避免抽吸感过强）
        val target = LIMIT_THRESHOLD / ax
        limiterGain += (target - limiterGain) * 0.35f
        return x * limiterGain
    }

    private fun applyCoefficients(params: Params) {
        val sr = appliedSampleRate
        if (sr <= 0) return
        preampLinear = dbToLinear(params.preampDb)
        lowShelf.setLowShelf(sr, params.lowShelfHz, LOW_SHELF_Q, params.lowShelfDb)
        highShelf.setHighShelf(sr, params.highShelfHz, HIGH_SHELF_Q, params.highShelfDb)
        params.bands.forEachIndexed { i, band ->
            if (i < MAX_BANDS) {
                bandFilters[i].setPeaking(sr, band.freqHz, band.q, band.gainDb)
            }
        }
        // 多余的段置旁路（参数段数变少时）
        for (i in params.bands.size until MAX_BANDS) {
            bandFilters[i].setBypass()
        }
    }

    private fun dbToLinear(db: Float): Float =
        if (db == 0f) 1f else 10f.pow(db / 20f)

    companion object {
        /** 最多 10 段峰形（AutoEQ 预设常见 5~10 段） */
        const val MAX_BANDS = 10
        private const val LOW_SHELF_Q = 0.707f
        private const val HIGH_SHELF_Q = 0.707f

        /** 软限制介入阈值（约 -1.5 dBFS） */
        private const val LIMIT_THRESHOLD = 0.84f
    }
}