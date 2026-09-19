package com.dpmusic.app.core.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import com.dpmusic.app.AppContainer
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
 * 音效均衡器管理（进程级单例）：
 * - 由 MusicService 在拿到音频会话 ID 时 attach，把均衡器 / 低音增强 / 环绕声
 *   挂到 ExoPlayer 的音频会话上；会话变更自动重挂；
 * - 设置持久化（DataStore），重启 / 重开会话后自动恢复；
 * - UI（播放页「⋯」→ 音效均衡器）直接读写本单例。
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

    /** 对外状态快照 */
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
    ) {
        val isCustom: Boolean get() = presetId == "custom"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var attachedSessionId = 0
    private var persistenceStarted = false
    private var persistJob: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var persisted = EqualizerRepository.Stored()

    // ---------------- 挂载 / 卸载（由 MusicService 调用） ----------------

    /** App 启动预热：尽早加载持久化设置，避免 UI 首次打开时读到默认值 */
    fun prewarm() {
        ensurePersistence()
    }

    /** 音频会话就绪：创建效果器并应用持久化设置 */
    fun attach(sessionId: Int) {
        if (sessionId <= 0) return
        if (sessionId == attachedSessionId && equalizer != null) return
        releaseAll()
        attachedSessionId = sessionId
        runCatching {
            equalizer = Equalizer(0, sessionId)
            bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
            virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        }
        ensurePersistence()
        persisted = AppContainer.equalizerRepo.settings.value
        applyAll()
    }

    /** 播放器销毁：释放全部效果器 */
    fun detach() {
        attachedSessionId = 0
        releaseAll()
        publishState()
    }

    private fun releaseAll() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
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
        val st = _state.value
        if (index !in 0 until st.bandCount) return
        val levels = st.bandLevelsMb.toMutableList()
        levels[index] = levelMb.coerceIn(st.bandMinMb, st.bandMaxMb)
        persisted = persisted.copy(bandLevelsMb = levels, presetId = "custom")
        runCatching { equalizer?.setBandLevel(index.toShort(), levels[index].toInt().toShort()) }
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
        applyBass()
        schedulePersist()
        publishState()
    }

    fun setSurroundStrength(strength: Int) {
        persisted = persisted.copy(surroundStrength = strength.coerceIn(0, 1000))
        applySurround()
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
                if (equalizer != null) applyAll() else publishState()
            }
        }
    }

    private fun applyAll() {
        val eq = equalizer
        if (eq == null) {
            publishState()
            return
        }
        val bands = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        val levels = when {
            persisted.bandLevelsMb.size == bands && bands > 0 -> persisted.bandLevelsMb
            else -> gainsFor(bands)
        }
        runCatching { eq.enabled = persisted.enabled }
        if (bands > 0) {
            levels.forEachIndexed { i, mb ->
                runCatching { eq.setBandLevel(i.toShort(), mb.toInt().toShort()) }
            }
        }
        applyBass()
        applySurround()
        publishState()
    }

    private fun applyEnabled() {
        runCatching { equalizer?.enabled = persisted.enabled }
        applyBass()
        applySurround()
    }

    private fun applyBass() {
        val bb = bassBoost ?: return
        val supported = runCatching { bb.strengthSupported }.getOrDefault(false)
        runCatching {
            if (supported && persisted.bassStrength > 0) {
                bb.setStrength(persisted.bassStrength.toShort())
                bb.enabled = persisted.enabled
            } else {
                bb.enabled = false
            }
        }
    }

    private fun applySurround() {
        val vz = virtualizer ?: return
        val supported = runCatching { vz.strengthSupported }.getOrDefault(false)
        runCatching {
            if (supported && persisted.surroundStrength > 0) {
                vz.setStrength(persisted.surroundStrength.toShort())
                vz.enabled = persisted.enabled
            } else {
                vz.enabled = false
            }
        }
    }

    /** 预设 → 实际频段电平（对数频率线性插值） */
    private fun gainsFor(bands: Int): List<Float> {
        if (bands <= 0) return emptyList()
        val eq = equalizer ?: return List(bands) { 0f }
        val preset = presets.firstOrNull { it.id == persisted.presetId } ?: presets.first()
        val freqs = (0 until bands).map { i ->
            runCatching { eq.getCenterFreq(i.toShort()) / 1000 }.getOrDefault(1000)
        }
        val minF = ln(freqs.first().coerceAtLeast(20).toDouble())
        val maxF = ln(freqs.last().coerceAtLeast(21).toDouble())
        return (0 until bands).map { i ->
            val f = ln(freqs[i].coerceAtLeast(20).toDouble())
            val t = if (maxF > minF) ((f - minF) / (maxF - minF)).toFloat() else 0.5f
            interpAnchors(preset.anchors, t)
        }
    }

    private fun interpAnchors(anchors: List<Float>, t: Float): Float {
        val pos = t.coerceIn(0f, 1f) * (anchors.size - 1)
        val i = pos.toInt().coerceAtMost(anchors.size - 2)
        val frac = pos - i
        return anchors[i] + (anchors[i + 1] - anchors[i]) * frac
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400L)
            AppContainer.equalizerRepo.save(persisted)
        }
    }

    private fun publishState() {
        val eq = equalizer
        val bands = if (eq != null) runCatching { eq.numberOfBands.toInt() }.getOrDefault(0) else 0
        val freqs = if (bands > 0) {
            (0 until bands).map { i ->
                runCatching { eq!!.getCenterFreq(i.toShort()) / 1000 }.getOrDefault(0)
            }
        } else {
            emptyList()
        }
        val levels = if (bands > 0) {
            (0 until bands).map { i ->
                runCatching { eq!!.getBandLevel(i.toShort()).toFloat() }.getOrDefault(0f)
            }
        } else {
            emptyList()
        }
        val range = if (eq != null) runCatching { eq.bandLevelRange }.getOrNull() else null
        _state.value = State(
            attached = eq != null,
            enabled = persisted.enabled,
            bandCount = bands,
            bandFreqsHz = freqs,
            bandLevelsMb = levels,
            bandMinMb = range?.get(0)?.toFloat() ?: -1500f,
            bandMaxMb = range?.get(1)?.toFloat() ?: 1500f,
            presetId = persisted.presetId,
            bassStrength = persisted.bassStrength,
            bassSupported = bassBoost?.let { runCatching { it.strengthSupported }.getOrDefault(false) } ?: false,
            surroundStrength = persisted.surroundStrength,
            surroundSupported = virtualizer?.let { runCatching { it.strengthSupported }.getOrDefault(false) } ?: false,
        )
    }
}