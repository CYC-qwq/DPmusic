package com.dpmusic.app.core.audio

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.dpmusic.app.core.audio.dsp.ParametricEqProcessor
import com.dpmusic.app.core.audio.dsp.SpectrumAudioProcessor

/**
 * 播放器 DSP 引擎（进程级单例）—— 全项目唯一的「音频处理器链」宿主。
 *
 * 它做两件事：
 * 1. 提供 [DspRenderersFactory]，把参数均衡器 / 频谱抽取器注入 ExoPlayer 的 AudioSink；
 * 2. 对外暴露 [publishEq] / [publishSpectrumEnabled] / [setBitPerfectBypass] 控制入口。
 *
 * ## 为什么整条链都要自己接管
 * Media3 的 `DefaultRenderersFactory.buildAudioSink` 默认会创建
 * `DefaultAudioProcessorChain(silenceSkipping, sonic)`。想在**保留变速（Sonic）与
 * 静音跳过**能力的前提下插入自己的处理器，必须自己 new 一个
 * `DefaultAudioProcessorChain(processors)` —— 其内部构造器会把 Sonic / SilenceSkipping
 * 追加到我们给的数组后面（已用字节码核实）。
 *
 * ## 线程安全
 * - `buildAudioSink` 在 ExoPlayer 构造期调用（主线程）；
 * - `publishXxx` 可从任意线程调用 —— 处理器内部用 `@Volatile` 字段一次性发布，音频线程读取；
 * - **绝不跨线程直接改滤波器系数**。
 *
 * ## Bit-Perfect 联动
 * [setBitPerfectBypass] 打开时均衡器被强制旁路（真直通）。
 * 频谱抽取器**不需要**旁路：它是纯旁听、逐样本原样透传，不改变任何一个比特。
 */
@UnstableApi
object DspEngine {

    /** 参数均衡器处理器（常驻音频链） */
    internal val eq: ParametricEqProcessor = ParametricEqProcessor()

    /** 频谱抽取处理器（常驻音频链，只读不改） */
    internal val spectrum: SpectrumAudioProcessor = SpectrumAudioProcessor()

    /** 频谱柱数量（UI 画图用） */
    val barCount: Int get() = SpectrumAudioProcessor.BAR_COUNT

    /** 滚动波形的列数（UI 画图用；每列发布 min/max 两个值） */
    val waveColumns: Int get() = SpectrumAudioProcessor.WAVE_COLUMNS

    /** 总开关：均衡器（关闭后音频线程只透传） */
    internal fun publishEqEnabled(enabled: Boolean) {
        eq.setEnabled(enabled)
    }

    /** 参数发布：均衡器（低架 / 频段 / 高架 / 预增益 / 立体声宽度） */
    internal fun publishEq(params: ParametricEqProcessor.Params) {
        eq.setParams(params)
    }

    /** 总开关：频谱抽取（关闭时零开销，UI 不再更新） */
    internal fun publishSpectrumEnabled(enabled: Boolean) {
        spectrum.setEnabled(enabled)
    }

    /**
     * Bit-Perfect 强制旁路：把均衡器整条关掉（真直通）。
     *
     * 只关 EQ，不动频谱 —— 频谱是逐样本原样透传的旁听者，不影响比特完整性。
     */
    internal fun setBitPerfectBypass(bypass: Boolean) {
        if (bypass) eq.setEnabled(false)
    }

    /** 读取当前频谱快照（UI 线程调用；返回发布序号，可用于跳过重复帧） */
    internal fun copySpectrum(dest: FloatArray): Int = spectrum.copySnapshot(dest)

    /**
     * 渲染器工厂：注入自定义 AudioSink（含 DSP 链）。
     *
     * 其余行为与 Media3 默认实现完全一致（float 输出开关 / AudioTrack 播放参数开关照传）。
     */
    class DspRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(dspChain)
            .build()
    }

    /**
     * 自定义处理器链：两个处理器插到最前，Sonic / SilenceSkipping 由
     * `DefaultAudioProcessorChain` 内部自动追加在后面。
     */
    private val dspChain: AudioProcessorChain = object : AudioProcessorChain {
        private val delegate =
            DefaultAudioSink.DefaultAudioProcessorChain(eq, spectrum)

        override fun getAudioProcessors(): Array<AudioProcessor> = delegate.audioProcessors

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
            delegate.applyPlaybackParameters(playbackParameters)

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
            delegate.applySkipSilenceEnabled(skipSilenceEnabled)

        override fun getMediaDuration(mediaDurationUs: Long): Long =
            delegate.getMediaDuration(mediaDurationUs)

        override fun getSkippedOutputFrameCount(): Long = delegate.skippedOutputFrameCount
    }
}