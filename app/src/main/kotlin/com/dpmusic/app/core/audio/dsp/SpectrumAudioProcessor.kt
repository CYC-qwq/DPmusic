package com.dpmusic.app.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频谱抽取 AudioProcessor —— 播放页「示波器」的数据源。
 *
 * 为什么用 AudioProcessor 而不是 `AudioPlaybackCapture`：
 * - 系统级捕获要 `MediaProjection` 授权 + 前台服务类型声明，用户要额外点同意，不适合常驻；
 * - 音频链内旁听**零权限、零延迟**，且拿到的是「EQ 之后、写入 AudioTrack 之前」的样本，
 *   所见即所听。
 *
 * 产出两份数据（合并在同一个双缓冲数组里发布）：
 * - **频谱柱**：[BAR_COUNT] 根，对数频率分布（20Hz~20kHz），带落峰衰减；
 * - **滚动波形**：[WAVE_COLUMNS] 列 min/max 包络，覆盖最近 [WAVE_SPAN_SEC] 秒 ——
 *   没有触发对齐，横轴就是一段连续时间，新样本从右侧进入、整幅向左流动（水流感）。
 *
 * 线程模型：
 * - [queueInput] 在音频线程；FFT 与发布都在音频线程内完成（1024 点 FFT 约几十微秒，
 *   而一帧 1024 样本的播放时长是 23ms，余量充足）；
 * - UI 线程通过 [copySnapshot] 拷贝当前发布缓冲 —— 双缓冲保证「写的那块 ≠ 读的那块」。
 *
 * ⚠️ 本处理器**只读不改**：输出样本与输入逐字节相同（纯透传），因此它不破坏 bit-perfect。
 */
internal class SpectrumAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = false

    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    private var bytesPerSample: Int = 2

    /** 输入环形缓冲（单声道混合后的最近 FFT_SIZE 个样本） */
    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0

    /** 距上次发布累积的帧数（到 [publishEvery] 就发布一次） */
    private var sincePublish = 0

    /** 每多少帧发布一次（≈ [PUBLISH_HZ] Hz，默认 100Hz > 屏幕 60Hz，避免跳帧） */
    private var publishEvery = 441

    /** 示波环形缓冲（滚动波形的原始样本） */
    private val waveRing = FloatArray(WAVE_RING)
    private var wavePos = 0

    /** 当前可见跨度（样本数，按实际采样率换算；[onConfigure] 里更新） */
    private var waveSpan = (44100 * WAVE_SPAN_SEC).toInt()

    /** 滚动波形包络：每列 `(min, max)` 两个值，长度恒为 [WAVE_COLUMNS] × 2 */
    private val waveEnv = FloatArray(WAVE_COLUMNS * 2)

    // ---- FFT 工作区（全部预分配，音频线程零分配） ----
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE)
    private val twR = FloatArray(FFT_SIZE / 2)
    private val twI = FloatArray(FFT_SIZE / 2)
    private val mag = FloatArray(FFT_SIZE / 2)
    private val barTarget = FloatArray(BAR_COUNT)
    private val barShown = FloatArray(BAR_COUNT)
    private val edges = IntArray(BAR_COUNT + 1)

    /** 发布缓冲：`[0, BAR_COUNT)` 为频谱柱，其后为波形包络（每列 min/max 交替） */
    private val pub = Array(2) { FloatArray(BAR_COUNT + WAVE_COLUMNS * 2) }

    @Volatile
    private var pubIdx = 0

    init {
        // Blackman-Harris 窗：旁瓣 -92dB（汉宁窗只有 -31dB），
        // 实测 1kHz 正弦只点亮 7 根柱（汉宁窗会糊成 16 根），频谱观感干净得多。
        val a0 = 0.35875f
        val a1 = 0.48829f
        val a2 = 0.14128f
        val a3 = 0.01168f
        for (i in 0 until FFT_SIZE) {
            val t = 2.0 * PI * i / FFT_SIZE
            window[i] = (a0 - a1 * cos(t) + a2 * cos(2.0 * t) - a3 * cos(3.0 * t)).toFloat()
        }
        // FFT 旋转因子表（一次性算好，避免每帧三角函数）
        for (k in 0 until FFT_SIZE / 2) {
            val a = -2.0 * PI * k / FFT_SIZE
            twR[k] = cos(a).toFloat()
            twI[k] = sin(a).toFloat()
        }
    }

    /** 开关（关闭时纯透传，不做任何分析） */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 当前是否有真实信号（UI 用来判断显示「无信号」还是波形） */
    val isEnabled: Boolean get() = enabled

    /**
     * 把当前发布缓冲拷进 [dest]。
     *
     * @param dest 长度需 ≥ [BAR_COUNT] + [WAVE_SIZE]
     * @return 本次读取的发布序号（UI 可据此跳过重复帧）
     */
    fun copySnapshot(dest: FloatArray): Int {
        val idx = pubIdx
        val src = pub[idx]
        System.arraycopy(src, 0, dest, 0, minOf(dest.size, src.size))
        return idx
    }

    override fun isActive(): Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2

        // 对数频率分桶（20Hz ~ 20kHz）
        val nyquistBin = FFT_SIZE / 2
        val ratio = MAX_HZ / MIN_HZ
        for (i in 0..BAR_COUNT) {
            val f = MIN_HZ * ratio.pow(i.toFloat() / BAR_COUNT)
            edges[i] = (f * FFT_SIZE / sampleRate).toInt().coerceIn(1, nyquistBin)
        }
        // 保证每桶至少 1 个 bin 且严格递增
        for (i in 1..BAR_COUNT) {
            if (edges[i] <= edges[i - 1]) edges[i] = (edges[i - 1] + 1).coerceAtMost(nyquistBin)
        }

        // 发布周期：240Hz（≥ 屏幕刷新率，避免 UI 侧时间混叠导致「一卡一卡」）
        publishEvery = (sampleRate / PUBLISH_HZ).coerceAtLeast(64)

        // 滚动波形的可见跨度按实际采样率换算（0.5s），并留出列数下限
        waveSpan = (sampleRate * WAVE_SPAN_SEC).toInt().coerceIn(WAVE_COLUMNS, WAVE_RING - 1)

        resetBuffers()
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetBuffers()
    }

    override fun onReset() {
        resetBuffers()
    }

    private fun resetBuffers() {
        ring.fill(0f)
        waveRing.fill(0f)
        waveEnv.fill(0f)
        barShown.fill(0f)
        ringPos = 0
        wavePos = 0
        sincePublish = 0
        pub.forEach { it.fill(0f) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameSize = bytesPerSample * channelCount
        val frameCount = inputBuffer.remaining() / frameSize
        if (frameCount <= 0) return

        val out = replaceOutputBuffer(frameCount * frameSize)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val analyzing = enabled
        val isFloat = bytesPerSample == 4
        for (f in 0 until frameCount) {
            // ---- 逐帧读取：先读满一帧到局部变量（同时算出单声道混合值），再原样写出 ----
            var mono = 0f
            for (ch in 0 until channelCount) {
                if (isFloat) {
                    val v = inputBuffer.float
                    mono += v
                    out.putFloat(v)          // 原样透传（只读不改）
                } else {
                    val s = inputBuffer.short
                    mono += s / 32768f
                    out.putShort(s)          // 原样透传
                }
            }
            mono /= channelCount

            if (analyzing) {
                ring[ringPos] = mono
                ringPos = (ringPos + 1) % FFT_SIZE
                // 波形环形缓冲：**逐帧写入**（不抽点）→ 包络计算时能看到真实峰形
                waveRing[wavePos] = mono
                wavePos = (wavePos + 1) and WAVE_MASK

                sincePublish++
                if (sincePublish >= publishEvery) {
                    sincePublish = 0
                    analyze()
                }
            }
        }
        out.flip()
    }

    private fun analyze() {
        // 有序取出环形缓冲 + 加窗
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft()
        for (i in 0 until FFT_SIZE / 2) {
            mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
        // 分桶取峰值 → dBFS → 归一化
        for (b in 0 until BAR_COUNT) {
            var peak = 0f
            for (k in edges[b] until edges[b + 1]) {
                if (mag[k] > peak) peak = mag[k]
            }
            // 幅度归一化：Blackman-Harris 窗相干增益 0.35875，单边谱正弦峰值 ≈ N*0.35875/2
            val amp = peak / (FFT_SIZE * WINDOW_COHERENT_GAIN / 2f)
            val db = 20f * ln(amp.coerceAtLeast(1e-7f)) / LN10
            barTarget[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        // 落峰衰减（上升瞬时跟随，下降缓慢，视觉更自然）
        for (b in 0 until BAR_COUNT) {
            val t = barTarget[b]
            barShown[b] = if (t >= barShown[b]) t else max(barShown[b] * DECAY, t)
        }
        // 滚动波形：把可见跨度切成 WAVE_COLUMNS 列，每列取 (min, max) 包络。
        // 不做触发对齐 —— 滚动示波器的横轴就是一段连续时间：新样本从右侧进入、整幅向左流动。
        buildWaveEnvelope()
        publish()
    }

    /**
     * 生成滚动波形的包络（每列 min/max）。
     *
     * 为什么发布包络而不是原始样本：
     * - **抗混叠**：0.5s 跨度有两万多个样本，抽点会让 10kHz 伪装成低频；取 min/max
     *   则完整保留每一列的峰形（等价于音频编辑器缩略图的画法）；
     * - **与采样率解耦**：发布长度恒为 [WAVE_COLUMNS] × 2，UI 不必随 44.1k / 48k 改尺寸。
     *
     * 全程整数边界 + 位与取模，音频线程零分配、无浮点除法。
     */
    private fun buildWaveEnvelope() =
        buildWaveEnvelopeInto(waveRing, WAVE_MASK, wavePos, waveSpan, WAVE_COLUMNS, waveEnv)

    /** 发布到「非当前」那块缓冲，然后翻转索引（UI 永远读不到正在写的缓冲） */
    private fun publish() {
        val next = 1 - pubIdx
        val dst = pub[next]
        System.arraycopy(barShown, 0, dst, 0, BAR_COUNT)
        System.arraycopy(waveEnv, 0, dst, BAR_COUNT, WAVE_COLUMNS * 2)
        pubIdx = next
    }

    /** 迭代式 radix-2 Cooley-Tukey FFT（原地，位反转 + 预计算旋转因子） */
    private fun fft() {
        val n = FFT_SIZE
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                while (k < half) {
                    val tw = k * step
                    val wr = twR[tw]
                    val wi = twI[tw]
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    val ur = re[a]
                    val ui = im[a]
                    re[a] = ur + vr
                    im[a] = ui + vi
                    re[b] = ur - vr
                    im[b] = ui - vi
                    k++
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** FFT 长度（2 的幂）。1024 点 @44.1kHz ≈ 23ms 窗，频率分辨率 43Hz */
        const val FFT_SIZE = 1024

        /** 频谱柱数量（对数分布） */
        const val BAR_COUNT = 48

        /** 滚动波形的可见时间跨度（秒）。
         *
         * 滚动示波器没有触发对齐，横轴就是一段**连续时间**：跨度越长，水流越缓、
         * 越像瀑布；越短，波形细节越多但滚动越快 —— 23ms（单窗）跨度下每帧要横移
         * 1/3 屏，只会看到闪烁而不是流动。
         *
         * 0.5s 在 512 列上约 1ms/列（44.1kHz 下 ~43 样本/列）：既看得清包络，
         * 又足够细腻。想更"缓"就调大（画面更密、更像水流），想更"跳"就调小。 */
        const val WAVE_SPAN_SEC = 0.5f

        /** 滚动波形的发布列数（每列发布 min/max 两个值） */
        const val WAVE_COLUMNS = 512

        /**
         * 波形环形缓冲容量（2 的幂，便于用位与代替取模）。
         *
         * 32768 样本 = 0.68s @48kHz，足够容纳 [WAVE_SPAN_SEC] 的跨度且有余量。
         */
        private const val WAVE_RING = 32768

        private const val WAVE_MASK = WAVE_RING - 1

        /**
         * 发布频率（Hz）。
         *
         * 必须**高于屏幕刷新率**，否则 UI 每个 vsync 读到的快照会「有时是新、有时是旧的」，
         * 视觉上就是走走停停的抖动（时间混叠）。
         *
         * 早期取 100Hz（针对 60Hz 屏），但在 120Hz 屏上 100 < 120：每帧是否拿到新数据
         * 取决于两个时钟的相位，表现为「走一帧、停一帧」的节拍错位 —— 这正是
         * 「示波器一卡一卡」的根因。取 240Hz = 120Hz 的 2 倍，任何 ≤240Hz 的屏幕
         * 每一帧都必然拿到新快照。
         *
         * 代价：FFT 从每 10ms 一次变成每 4.2ms 一次（1024 点约几十微秒），
         * 约占音频线程 1~2% 的余量，远不足以影响播放。
         */
        private const val PUBLISH_HZ = 240

        private const val MIN_HZ = 20f
        private const val MAX_HZ = 20000f

        /** 显示下限（dBFS）：低于此值视为静音 */
        private const val FLOOR_DB = -84f

        /**
         * 落峰衰减：从峰值落到 1% 所需时长（秒）—— 视觉上就是柱子的「尾巴」长度。
         *
         * 以前这里是硬编码的 0.912，注释里写着「按 100Hz 每秒 100 次计算」——
         * 发布频率一改，尾巴长度就跟着变（改成 240Hz 后会快 2.4 倍，肉眼可见地「僵」）。
         * 现在按 [PUBLISH_HZ] 反推，改发布率不会再动到观感。
         *
         * ⚠️ 已从 0.5s 收到 0.10s：参考实现里数据侧**完全没有衰减**，柱子的下落
         * 完全由 UI 侧缓动决定 → 观感是「快速跟随、干脆落下」。
         * 0.5s 的尾巴叠在 UI 缓动之上会让柱子拖得很慢（与参考的活泼感相反）；
         * 这里留 0.10s 只作为 FFT 噪声的防抖，不再主导观感。
         */
        private const val DECAY_TO_ONE_PERCENT_SEC = 0.10f

        /** 每次发布衰减到上一值的多少（由 [DECAY_TO_ONE_PERCENT_SEC] 与 [PUBLISH_HZ] 反推） */
        private val DECAY: Float = 0.01f.pow(1f / (PUBLISH_HZ * DECAY_TO_ONE_PERCENT_SEC))

        /** Blackman-Harris 窗的相干增益（用于把谱峰还原成正弦幅度） */
        private const val WINDOW_COHERENT_GAIN = 0.35875f

        private const val LN10 = 2.3025851f
    }
}

/** Float 幂（避免引入 kotlin.math.pow 的歧义） */
private fun Float.pow(exp: Float): Float = kotlin.math.exp(exp * kotlin.math.ln(this))
/**
 * 把环形缓冲里**最近 [span] 个样本**切成 [columns] 列，每列取 (min, max) 依次写入 [out]
 * （min/max 交替，长度需 ≥ `columns × 2`）。
 *
 * 抽成顶层纯函数的原因：环形回绕与列边界是最容易出 off-by-one 的地方，
 * 单测直接喂人造缓冲就能验证（不必起整条音频链）。
 *
 * 约定：`ring` 的写指针是 [writePos]（下一个待写位置），容量是 `mask + 1`（2 的幂）。
 * 窗口起点 `writePos - span` 可能为负，用 `and mask` 自动回绕到缓冲区尾部。
 *
 * 列边界用**整数除法**而不是浮点步进：既避免累积误差，也让音频线程上没有浮点除法。
 * [span] 小于 [columns] 时会出现空列，退化为 (0, 0) —— 不能把 Float.MAX_VALUE 传出去。
 */
internal fun buildWaveEnvelopeInto(
    ring: FloatArray,
    mask: Int,
    writePos: Int,
    span: Int,
    columns: Int,
    out: FloatArray,
) {
    val base = writePos - span
    var idx = base
    var o = 0
    for (c in 0 until columns) {
        val end = base + (c + 1) * span / columns
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        while (idx < end) {
            val v = ring[idx and mask]
            if (v < mn) mn = v
            if (v > mx) mx = v
            idx++
        }
        if (mn > mx) {
            mn = 0f
            mx = 0f
        }
        out[o++] = mn
        out[o++] = mx
    }
}
