package com.dpmusic.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB / 外接 DAC 的 **Bit-Perfect 独占输出**控制器（Android 14+）。
 *
 * ## 它到底做了什么
 * 调用 `AudioManager.setPreferredMixerAttributes(usageAttrs, device, mixerAttrs)`，
 * 让系统的 audio mixer 对该输出设备切换到 `MIXER_BEHAVIOR_BIT_PERFECT` —— 即
 * **采样数据不做任何重采样 / 混音 / 音量缩放**，原样送到 DAC。
 *
 * ## 为什么必须「独占」而不是「音质更好」
 * 一旦 bit-perfect，框架层任何 DSP（包括我们的均衡器、系统的音效、软件音量）都会破坏它。
 * 所以本控制器与 [DspEngine.setBitPerfectBypass] 是**强绑定**关系：
 * 开启即强制旁路均衡器（见 [DspEngine]）。
 *
 * ## 真实 API 签名（已用 android-34/36 的 android.jar 逐个核实）
 * ```
 * setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes): Boolean  // 3 参
 * clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo): Boolean                       // 2 参
 * getSupportedMixerAttributes(AudioDeviceInfo): List<AudioMixerAttributes>
 * AudioMixerAttributes.Builder(AudioFormat).setMixerBehavior(int).build()   // 构造器就要传格式，无 setFormat
 * ```
 * ⚠️ **不存在** `isBitPerfectPlaybackSupported(device)` —— 判断支持与否必须遍历
 * `getSupportedMixerAttributes` 找 `mixerBehavior == MIXER_BEHAVIOR_BIT_PERFECT`。
 *
 * ## 三个防坑设计
 * 1. **防抖**：相同 (设备, 采样率, 声道) 不重复调用 `setPreferredMixerAttributes` ——
 *    重复调用会让部分 USB DAC 反复断流（继电器咔哒声）；
 * 2. **设备拔插监听**：`AudioDeviceCallback` 在设备变化时重新评估，拔掉即释放；
 * 3. **生命周期归还**：关闭 / 换设备 / 播放器销毁时 `clearPreferredMixerAttributes` 归还控制权，
 *    否则其他 App 会被锁在 bit-perfect 模式下。
 */
object BitPerfectController {

    /** 对外状态（UI 展示） */
    data class State(
        /** 当前系统 + 设备是否支持 bit-perfect */
        val supported: Boolean = false,
        /** 用户是否开启（持久化开关） */
        val userEnabled: Boolean = false,
        /** 是否已真正生效（系统已接受） */
        val active: Boolean = false,
        /** 当前生效/候选的输出设备名 */
        val deviceName: String? = null,
        /** 实际生效的采样率（0 = 未知） */
        val sampleRate: Int = 0,
        /** 实际生效的声道数（0 = 未知） */
        val channelCount: Int = 0,
        /** 人话说明（UI 副标题） */
        val reason: String = "",
    )

    private const val TAG = "BitPerfect"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var callbackRegistered = false

    /** 用户开关（由设置层同步过来） */
    @Volatile
    private var userEnabled = false

    /** 播放器上报的真实解码格式（用于挑选匹配的 mixer 格式） */
    @Volatile
    private var streamSampleRate = 0

    @Volatile
    private var streamChannelCount = 0

    /** 已应用的组合（防抖键） */
    private var appliedKey: String? = null

    /** 已成功应用时使用的设备，用于释放 */
    private var appliedDevice: AudioDeviceInfo? = null

    private val usageAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            reevaluate("设备接入")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            reevaluate("设备移除")
        }
    }

    /** 进程启动预热（AppContainer.init） */
    fun prewarm(context: Context) {
        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerCallback()
        reevaluate("启动")
    }

    /** 设置层同步用户开关 */
    fun setUserEnabled(enabled: Boolean) {
        userEnabled = enabled
        reevaluate(if (enabled) "用户开启" else "用户关闭")
    }

    /** 播放器上报真实解码格式（AnalyticsListener.onAudioInputFormatChanged 调用） */
    fun onStreamFormat(sampleRate: Int, channelCount: Int) {
        if (sampleRate == streamSampleRate && channelCount == streamChannelCount) return
        streamSampleRate = sampleRate
        streamChannelCount = channelCount
        if (userEnabled) reevaluate("格式变化")
    }

    /** 播放停止 / 播放器销毁：归还控制权 */
    fun release() {
        appliedKey = null
        appliedDevice?.let { device ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    audioManager?.clearPreferredMixerAttributes(usageAttributes, device)
                }
            }
        }
        appliedDevice = null
        _state.value = _state.value.copy(active = false)
    }

    // ---------------------------------------------------------------- 内部 ----

    private fun registerCallback() {
        if (callbackRegistered) return
        val am = audioManager ?: return
        runCatching {
            am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        }
    }

    /** 重新评估并（必要时）应用 */
    private fun reevaluate(reason: String) {
        if (Build.VERSION.SDK_INT < 34) {
            _state.value = State(
                supported = false,
                userEnabled = userEnabled,
                reason = "需要 Android 14 及以上",
            )
            return
        }
        val am = audioManager
        if (am == null) {
            _state.value = State(userEnabled = userEnabled, reason = "音频服务不可用")
            return
        }

        val device = pickOutputDevice(am)
        if (device == null) {
            appliedKey = null
            appliedDevice = null
            _state.value = State(
                supported = false,
                userEnabled = userEnabled,
                reason = "未检测到外接音频设备（插上 USB DAC 后自动启用）",
            )
            return
        }

        val supportedList = runCatching { am.getSupportedMixerAttributes(device) }.getOrNull().orEmpty()
        val bitPerfect = supportedList.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        if (bitPerfect.isEmpty()) {
            appliedKey = null
            appliedDevice = null
            _state.value = State(
                supported = false,
                userEnabled = userEnabled,
                deviceName = deviceLabel(device),
                reason = "该设备不支持 bit-perfect 直通",
            )
            return
        }

        if (!userEnabled) {
            // 用户关闭 → 归还控制权
            appliedDevice?.let {
                runCatching { am.clearPreferredMixerAttributes(usageAttributes, it) }
            }
            appliedKey = null
            appliedDevice = null
            _state.value = State(
                supported = true,
                userEnabled = false,
                deviceName = deviceLabel(device),
                reason = "可用（当前关闭）",
            )
            return
        }

        // 挑选与当前流格式最匹配的 bit-perfect 格式，找不到就用第一个
        val chosen = bitPerfect.firstOrNull { attrs ->
            val f = attrs.format
            (streamSampleRate <= 0 || f.sampleRate == streamSampleRate) &&
                (streamChannelCount <= 0 || f.channelCount == streamChannelCount)
        } ?: bitPerfect.first()

        val key = "${device.id}|${chosen.format.sampleRate}|${chosen.format.channelCount}"
        if (key == appliedKey) {
            // 防抖命中：不重复下发（避免 DAC 反复断流）
            _state.value = _state.value.copy(
                supported = true,
                userEnabled = true,
                active = true,
                deviceName = deviceLabel(device),
                sampleRate = chosen.format.sampleRate,
                channelCount = chosen.format.channelCount,
                reason = "已独占直通（$reason，未重复下发）",
            )
            return
        }

        val ok = runCatching {
            am.setPreferredMixerAttributes(usageAttributes, device, chosen)
        }.getOrDefault(false)

        if (ok) {
            appliedKey = key
            appliedDevice = device
            _state.value = State(
                supported = true,
                userEnabled = true,
                active = true,
                deviceName = deviceLabel(device),
                sampleRate = chosen.format.sampleRate,
                channelCount = chosen.format.channelCount,
                reason = "已独占直通（$reason）",
            )
        } else {
            appliedKey = null
            appliedDevice = null
            _state.value = State(
                supported = true,
                userEnabled = true,
                active = false,
                deviceName = deviceLabel(device),
                reason = "系统拒绝了本次独占请求（可能被其他应用占用）",
            )
        }
    }

    /**
     * 挑选用于 bit-perfect 的输出设备。
     *
     * 优先 USB / 外接；没有外接时退回「当前有线耳机」等 —— 但注意手机自带扬声器
     * 通常不支持 bit-perfect（`getSupportedMixerAttributes` 会返回空或只有 default）。
     */
    private fun pickOutputDevice(am: AudioManager): AudioDeviceInfo? {
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return null
        val preferred = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        for (type in preferred) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val name = runCatching { device.productName?.toString() }.getOrNull().orEmpty()
        if (name.isNotBlank()) return name
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频设备"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
            else -> "外接音频设备"
        }
    }

    /** 构造 bit-perfect 的 AudioMixerAttributes（供测试 / 手动指定格式用） */
    internal fun buildBitPerfectAttributes(sampleRate: Int, channelCount: Int): AudioMixerAttributes? {
        if (Build.VERSION.SDK_INT < 34) return null
        val channelMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return runCatching {
            AudioMixerAttributes.Builder(format)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
        }.getOrNull()
    }

    /** 常量：Media3 的编码常量（避免调用方多一次 import） */
    internal val pcm16: Int = C.ENCODING_PCM_16BIT
}