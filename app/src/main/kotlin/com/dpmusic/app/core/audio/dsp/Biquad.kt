package com.dpmusic.app.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 双二阶（Biquad）IIR 滤波器 —— RBJ Audio EQ Cookbook 标准公式。
 *
 * 为什么自研而不用平台 `android.media.audiofx.Equalizer`：
 * - 平台音效挂在 audio session 上，**会破坏 bit-perfect 直通**（框架级 DSP 无法旁路）；
 * - 平台 `Virtualizer` 在 Android 15+ 已废弃失效；
 * - 平台 EQ 频段数由厂商决定（常见 5 段），且无低架/高架能力。
 *
 * 本实现跑在 Media3 的 AudioProcessor 链内，**可在运行时完全旁路**（[setBypass]）。
 *
 * 线程约束（重要）：系数在 `configure` 阶段（音频线程外）算好；
 * [process] 内只做乘加运算，**不分配任何对象**，可安全用于实时音频线程。
 * 状态变量按声道预分配，[reset] 原地清零。
 */
internal class Biquad {

    // 归一化系数（已除以 a0）
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    /** 直通（无处理）—— 系数为单位冲激 */
    var bypassed: Boolean = true
        private set

    // Direct Form I 状态：每声道独立（预分配，避免音频线程分配）
    private val x1 = FloatArray(MAX_CHANNELS)
    private val x2 = FloatArray(MAX_CHANNELS)
    private val y1 = FloatArray(MAX_CHANNELS)
    private val y2 = FloatArray(MAX_CHANNELS)

    /** 峰形（Peaking）EQ：某频点提升/衰减，Q 控制带宽 */
    fun setPeaking(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (gainDb == 0f) {
            setBypass()
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * PI * (freqHz.coerceIn(20f, sampleRate / 2f - 1f) / sampleRate)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.05f))

        val b0r = 1.0 + alpha * a
        val b1r = -2.0 * cosW0
        val b2r = 1.0 - alpha * a
        val a0r = 1.0 + alpha / a
        val a1r = -2.0 * cosW0
        val a2r = 1.0 - alpha / a
        apply(b0r, b1r, b2r, a0r, a1r, a2r)
    }

    /** 低架（Low Shelf）：整体抬升/压低低频（替代平台 BassBoost） */
    fun setLowShelf(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (gainDb == 0f) {
            setBypass()
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * PI * (freqHz.coerceIn(20f, sampleRate / 2f - 1f) / sampleRate)
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q.coerceAtLeast(0.05f) - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0r = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1r = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2r = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0r = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1r = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2r = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
        apply(b0r, b1r, b2r, a0r, a1r, a2r)
    }

    /** 高架（High Shelf）：整体抬升/压低高频 */
    fun setHighShelf(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (gainDb == 0f) {
            setBypass()
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * PI * (freqHz.coerceIn(20f, sampleRate / 2f - 1f) / sampleRate)
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q.coerceAtLeast(0.05f) - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0r = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1r = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val b2r = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0r = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1r = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val a2r = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
        apply(b0r, b1r, b2r, a0r, a1r, a2r)
    }

    /** 旁路：系数置为单位冲激（输出 == 输入），并清状态避免残留爆音 */
    fun setBypass() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
        bypassed = true
        reset()
    }

    private fun apply(b0r: Double, b1r: Double, b2r: Double, a0r: Double, a1r: Double, a2r: Double) {
        val inv = 1.0 / a0r
        b0 = (b0r * inv).toFloat()
        b1 = (b1r * inv).toFloat()
        b2 = (b2r * inv).toFloat()
        a1 = (a1r * inv).toFloat()
        a2 = (a2r * inv).toFloat()
        bypassed = false
    }

    /**
     * 处理单个采样（Direct Form I）。
     * 无对象分配、无边界检查开销，可安全用于音频线程。
     *
     * @param x 输入采样（归一化到 [-1, 1]）
     * @param ch 声道索引（0 或 1）
     */
    fun process(x: Float, ch: Int): Float {
        if (bypassed) return x
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]
        x1[ch] = x
        y2[ch] = y1[ch]
        y1[ch] = y
        return y
    }

    /** 清空延迟线（切歌 / 参数突变时调用，避免旧状态引起的瞬态） */
    fun reset() {
        for (i in 0 until MAX_CHANNELS) {
            x1[i] = 0f; x2[i] = 0f; y1[i] = 0f; y2[i] = 0f
        }
    }

    companion object {
        /** 立体声足够（音频链路最多 2 声道） */
        const val MAX_CHANNELS = 2
    }
}