package com.dpmusic.app.core.audio

import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.audio.dsp.ParametricEqProcessor
import com.dpmusic.app.core.data.EqualizerRepository
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 音效均衡器控制层（进程级单例）—— UI 与 DSP 之间的唯一桥梁。
 *
 * ## v1.2.0 后端迁移（重要）
 * 旧实现用平台 `android.media.audiofx.Equalizer` / `BassBoost` / `Virtualizer`，
 * 挂在 ExoPlayer 的 audio session 上。三个致命问题：
 * 1. `Virtualizer` 在 **Android 15+ 已废弃失效**（环绕声滑杆实际上早就没效果）；
 * 2. 平台音效是**框架级 DSP**，挂在 session 上会**破坏 bit-perfect 直通**；
 * 3. 频段数由厂商决定（常见 5 段），无法做参数均衡（没有 Q 值 / 架形滤波）。
 *
 * 现在后端换成 [DspEngine] 里的 [ParametricEqProcessor]（Media3 AudioProcessor）：
 * - **10 段峰形 EQ** + 低架（120Hz，替代 BassBoost）+ 高架（8kHz），全部可调 Q；
 * - **立体声宽度**（M/S 扩展，替代已死的 Virtualizer）；
 * - 与音频链同源，**可以在 bit-perfect 开启时瞬间整体旁路**。
 *
 * ## 兼容策略
 * 对外 API 与 [State] 字段**完全保持不变**（`bandLevelsMb` / `bassStrength` /
 * `surroundStrength` / `presets` / `attach` / `detach` …），所以 `EqualizerSheet.kt`
 * 一行都不用改；单位仍是「毫贝（mb）」，1 dB = 100 mb。
 *
 * ## 频段映射
 * - 频段 0 → 低架 120Hz（低频整体抬升，等价旧 BassBoost 的角色）
 * - 频段 1..8 → 8 段峰形（60/170/310/600/1k/3k/6k/12k，对数分布）
 * - 频段 9 → 高架 8kHz
 * 预设的 5 个锚点按对数频率插值到这 10 个频点（沿用旧逻辑）。
 */
object AudioEffectsManager {

    /** 预设（5 锚点，按对数频率插值到实际频段数） */
    data class Preset(val id: String, val label: String, val anchors: List<Float>)

    val presets: List<Preset> = listOf(
        Preset("flat", "正常", listOf(0f, 0f, 0f, 0f, 0f)),
        Preset("pop", "流行", listOf(-100f, 200f, 400f, 200f, -100f)),
        Preset("rock", "摇滚", listOf(400f, 300f, -100f, 300f, 400f)),
        Preset("jazz", "爵士", listOf(300f, 200f, 0f, 200f, 300f)),
        Preset("classical", "古典", listOf(400f, 300f, 0f, 200f, 400f)),
        Preset("vocal", "人声", listOf(-200f, 100f, 400f, 300f, 0f)),
        Preset("bass", "重低音", listOf(600f, 400f, 100f, 0f, 100f)),
    )

    /** 对外状态快照（字段与旧版完全一致，UI 无感） */
    data class State(
        val attached: Boolean = false,
        val enabled: Boolean = false,
        val bandCount: Int = 0,
        val bandFreqsHz: List<Int> = emptyList(),
        val bandLevelsMb: List<Float> = emptyList(),
        val bandMinMb: Float = -1500f,
        val bandMaxMb: Float = 1500f,
        val presetId: String = "flat",
        val bassStrength: Int = 0,
        val bassSupported: Boolean = false,
        val surroundStrength: Int = 0,
        val surroundSupported: Boolean = false,
        /** 是否被 bit-perfect 强制旁路（UI 用来提示「此时均衡器不可用」） */
        val bypassedByBitPerfect: Boolean = false,
    ) {
        val isCustom: Boolean get() = presetId == "custom"
    }

    /**
     * 10 个频点的中心频率，**按频率单调递增**（UI 曲线左→右）。
     * - 索引 0 → 低架（低频整体抬升，承担旧 BassBoost 的角色）
     * - 索引 1..8 → 8 段峰形（可调 Q）
     * - 索引 9 → 高架（高频整体抬升）
     */
    private val BAND_FREQS = intArrayOf(
        80, 100, 250, 500, 1_000, 2_000, 4_000, 8_000, 12_000, 16_000,
    )

    /** 峰形段索引 → Q 值（低频段宽一点、高频段窄一点，听感更自然） */
    private val BAND_Q = floatArrayOf(
        0.707f, 0.707f, 0.80f, 0.90f, 1.00f, 1.10f, 1.20f, 1.30f, 1.40f, 0.707f,
    )

    private const val LOW_SHELF_HZ = 80f
    private const val HIGH_SHELF_HZ = 16_000f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var persistenceStarted = false
    private var persistJob: Job? = null
    private var attached = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var persisted = EqualizerRepository.Stored()

    /** bit-perfect 是否正在强制旁路（由 AppContainer 同步） */
    @Volatile
    private var bitPerfectBypass = false

    // ---------------- 挂载 / 卸载 ----------------

    /** App 启动预热：尽早加载持久化设置，避免 UI 首次打开时读到默认值 */
    fun prewarm() {
        ensurePersistence()
    }

    /**
     * 播放会话就绪。
     *
     * 与旧版不同：这里**不再需要 audioSessionId** —— DSP 跑在 Media3 音频链内，
     * 播放器一构造就存在。参数保留是为了不动 `MusicService` 的调用点。
     */
    fun attach(@Suppress("UNUSED_PARAMETER") sessionId: Int) {
        attached = true
        ensurePersistence()
        persisted = AppContainer.equalizerRepo.settings.value
        applyAll()
    }

    /** 播放器销毁 */
    fun detach() {
        attached = false
        publishState()
    }

    /** bit-perfect 开关联动（开 = 强制旁路全部 DSP） */
    fun setBitPerfectBypass(bypass: Boolean) {
        bitPerfectBypass = bypass
        DspEngine.setBitPerfectBypass(bypass)
        applyEnabled()
        publishState()
    }

    // ---------------- UI 操作 ----------------

    fun setEnabled(enabled: Boolean) {
        persisted = persisted.copy(enabled = enabled)
        applyEnabled()
        schedulePersist()
        publishState()
    }

    /** 手动拖动频段：进入「自定义」预设 */
    fun setBandLevel(index: Int, levelMb: Float) {
        if (index !in BAND_FREQS.indices) return
        val levels = currentLevels().toMutableList()
        levels[index] = levelMb.coerceIn(-1500f, 1500f)
        persisted = persisted.copy(bandLevelsMb = levels, presetId = "custom")
        pushToDsp()
        schedulePersist()
        publishState()
    }

    fun setPreset(id: String) {
        persisted = persisted.copy(presetId = id, bandLevelsMb = emptyList())
        applyAll()
        schedulePersist()
    }

    fun setBassStrength(strength: Int) {
        persisted = persisted.copy(bassStrength = strength.coerceIn(0, 1000))
        pushToDsp()
        schedulePersist()
        publishState()
    }

    fun setSurroundStrength(strength: Int) {
        persisted = persisted.copy(surroundStrength = strength.coerceIn(0, 1000))
        pushToDsp()
        schedulePersist()
        publishState()
    }

    fun reset() {
        persisted = persisted.copy(
            presetId = "flat",
            bandLevelsMb = emptyList(),
            bassStrength = 0,
            surroundStrength = 0,
        )
        applyAll()
        schedulePersist()
    }

    // ---------------- 内部：应用 / 状态 ----------------

    private fun ensurePersistence() {
        if (persistenceStarted) return
        persistenceStarted = true
        scope.launch {
            AppContainer.equalizerRepo.settings.collect { s ->
                persisted = s
                applyAll()
            }
        }
    }

    /** 当前应显示的 10 段电平（mb）：自定义 → 用户值；预设 → 插值；否则全 0 */
    private fun currentLevels(): List<Float> {
        if (persisted.bandLevelsMb.size == BAND_FREQS.size) return persisted.bandLevelsMb
        val preset = presets.firstOrNull { it.id == persisted.presetId }
        if (preset == null || preset.id == "flat") return List(BAND_FREQS.size) { 0f }
        val minF = ln(BAND_FREQS.min().toDouble())
        val maxF = ln(BAND_FREQS.max().toDouble())
        return BAND_FREQS.map { hz ->
            val t = ((ln(hz.toDouble()) - minF) / (maxF - minF)).toFloat()
            interpAnchors(preset.anchors, t)
        }
    }

    private fun interpAnchors(anchors: List<Float>, t: Float): Float {
        val pos = t.coerceIn(0f, 1f) * (anchors.size - 1)
        val i = pos.toInt().coerceAtMost(anchors.size - 2)
        val frac = pos - i
        return anchors[i] + (anchors[i + 1] - anchors[i]) * frac
    }

    /** 把当前持久化状态翻译成 DSP 参数并发布 */
    private fun pushToDsp() {
        val levels = currentLevels()
        // 低架增益 = 频段 0 + 低音增强滑杆（0..1000 → 0..12 dB）
        val bassDb = (levels.getOrNull(0) ?: 0f) / 100f + persisted.bassStrength / 1000f * 12f
        val highShelfDb = (levels.getOrNull(9) ?: 0f) / 100f
        val bands = (1..8).mapNotNull { i ->
            val mb = levels.getOrNull(i) ?: 0f
            ParametricEqProcessor.Band(
                freqHz = BAND_FREQS[i].toFloat(),
                q = BAND_Q[i],
                gainDb = mb / 100f,
            )
        }
        // 立体声宽度：0..1000 → 1.0 .. 2.0（M/S 扩展，替代已废弃的 Virtualizer）
        val width = 1f + persisted.surroundStrength / 1000f
        DspEngine.publishEq(
            ParametricEqProcessor.Params(
                preampDb = preampFor(levels, bassDb, highShelfDb),
                lowShelfHz = LOW_SHELF_HZ,
                lowShelfDb = bassDb,
                highShelfHz = HIGH_SHELF_HZ,
                highShelfDb = highShelfDb,
                stereoWidth = width,
                bands = bands,
            ),
        )
    }

    /**
     * 自动预增益：整体提升时按峰值衰减，避免削顶。
     *
     * 只对正向增益做补偿（衰减时不动），上限 -12dB。
     */
    private fun preampFor(levels: List<Float>, bassDb: Float, highShelfDb: Float): Float {
        val peak = maxOf(
            bassDb,
            highShelfDb,
            levels.maxOrNull()?.div(100f) ?: 0f,
        )
        return if (peak <= 0f) 0f else -peak.coerceAtMost(12f)
    }

    private fun applyAll() {
        applyEnabled()
        publishState()
    }

    private fun applyEnabled() {
        // bit-perfect 优先：开启时强制旁路（真直通），其余情况按用户开关
        DspEngine.publishEqEnabled(persisted.enabled && !bitPerfectBypass)
        pushToDsp()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400L)
            AppContainer.equalizerRepo.save(persisted)
        }
    }

    private fun publishState() {
        val levels = currentLevels()
        _state.value = State(
            attached = attached,
            enabled = persisted.enabled,
            bandCount = BAND_FREQS.size,
            bandFreqsHz = BAND_FREQS.toList(),
            bandLevelsMb = levels,
            bandMinMb = -1500f,
            bandMaxMb = 1500f,
            presetId = persisted.presetId,
            bassStrength = persisted.bassStrength,
            bassSupported = true,
            surroundStrength = persisted.surroundStrength,
            surroundSupported = true,
            bypassedByBitPerfect = bitPerfectBypass,
        )
    }
}