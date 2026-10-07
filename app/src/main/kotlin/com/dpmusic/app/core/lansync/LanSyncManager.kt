package com.dpmusic.app.core.lansync

import android.content.Context
import android.os.Build
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.sync.ListsSyncPayload
import com.dpmusic.app.core.sync.SettingsSyncPayload
import com.dpmusic.app.core.sync.SyncManager
import com.dpmusic.app.core.util.AppLogger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 把接收端事件渲染成一行日志（后台常驻时没有界面可展示，只能进日志）。 */
private fun LanSyncEvent.describe(): String = when (this) {
    is LanSyncEvent.Accepted -> "$from 正在发送：${files.joinToString(" + ")}"
    is LanSyncEvent.Progress -> "接收中…（${bytes / 1024} KB）"
    is LanSyncEvent.Receiving -> "已收齐 ${totalBytes / 1024} KB，正在应用…"
    is LanSyncEvent.Completed -> "已完成：$summary"
    is LanSyncEvent.Rejected -> "已拒绝：$reason"
    is LanSyncEvent.Failed -> "失败：$reason"
    LanSyncEvent.Cancelled -> "对端已取消"
}

/**
 * 局域网设备同步的编排层（进程级单例）。
 *
 * 把三块拼起来：
 * - [LanDiscovery] 发现设备；[LanSyncServer] 接收对端推送；本类负责**发送**与生命周期。
 *
 * ## 同步语义：覆盖（与 WebDAV 一致）
 *
 * 复用 [SyncManager] 的载荷构建 / 应用方法，因此：
 * - 白名单只有一份（[com.dpmusic.app.core.sync.SyncScopes]），不存在「WebDAV 能同步的键局域网不能同步」；
 * - 敏感键（账号 Cookie / API Key / WebDAV 密码）同样永不参与；
 * - 恢复是覆盖语义：远端没有的本地键会被删除。
 *
 * ## 为什么不做自动合并
 *
 * 歌单与收藏没有稳定的「最后修改时间」可比较（收藏是纯列表，歌单是整体 JSON），
 * 做双向合并只能靠猜，一旦猜错会静默丢数据。因此这里只提供**方向明确的单向同步**，
 * 由用户决定谁覆盖谁 —— 与既有 WebDAV 的「上传 / 恢复」心智保持一致。
 */
class LanSyncManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val sync: SyncManager,
    /**
     * 播放器：仅在**播放流转**时需要（导出实时队列与进度、接收后接着播）。
     *
     * 用 `() ->` 而非直接引用，是为了避免 `AppContainer` 里
     * `lanSyncManager` 与 `player` 两条懒加载链互相触发（两者都出现在对方的构造参数里）。
     */
    private val playerProvider: () -> PlayerConnection,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = LanDiscovery(context)

    @Volatile
    private var server: LanSyncServer? = null

    /**
     * 传输事件接收方。默认只写日志，界面进入同步页时替换为 UI 回调。
     *
     * 这样接收端就**不依赖界面存活**：后台常驻时事件进日志，界面出现时接到 UI。
     * 早期版本把「事件回调」当成 startServer 的参数，等价于把接收端绑死在页面上 ——
     * 页面一关就停监听，对端便再也发现不了本机（「找不到设备」的直接成因）。
     */
    @Volatile
    private var eventSink: (LanSyncEvent) -> Unit = { AppLogger.i(TAG, "局域网事件：${it.describe()}") }

    /** 已发现的设备（转发自 [LanDiscovery]，供界面直接订阅） */
    val devices = discovery.devices

    /** 设置 / 清除界面事件回调；传 null 恢复为只记日志。 */
    fun setEventSink(sink: ((LanSyncEvent) -> Unit)?) {
        eventSink = sink ?: { AppLogger.i(TAG, "局域网事件：${it.describe()}") }
    }

    /**
     * 按设置启停接收端（进程内只启动一次收集协程）。
     *
     * 关键点：监听的开关来自**设置**而不是界面。用户打开「接收」后，
     * 无论在哪个页面、甚至切到后台，端口都保持监听 —— 这正是对端能发现本机的前提。
     */
    private var settingsWatchStarted = false

    /**
     * 观察到的「开关 + 端口」快照。
     *
     * [settings.settings] 是整份设置快照，**任何**设置变更（改歌词字号、填 relay 地址…）
     * 都会重新发射。早期版本对每次发射都调 [startServer]，于是每次改设置都尝试重新 bind：
     * 端口被占用（如与 LocalSend 的 53317 撞车）时失败、失败后 `isAlive` 又永远为 false，
     * 导致日志被 `EADDRINUSE` 反复刷屏。这里只在**开关或端口真正变化**时才动作。
     */
    private var watchedEnabled: Boolean? = null
    private var watchedPort: Int? = null

    fun applyReceiveSetting() {
        if (settingsWatchStarted) return
        settingsWatchStarted = true
        scope.launch {
            settings.settings.collect { s ->
                val enabledChanged = s.lanSyncReceiveEnabled != watchedEnabled
                // 首次（watchedPort == null）不算「端口变更」，否则会误判成需要重启
                val portChanged = watchedPort != null && s.lanSyncPort != watchedPort
                if (!enabledChanged && !portChanged) return@collect
                watchedEnabled = s.lanSyncReceiveEnabled
                watchedPort = s.lanSyncPort
                if (!s.lanSyncReceiveEnabled) {
                    stopServer()
                    return@collect
                }
                // NanoHTTPD 的端口在构造时就绑定了：端口变更必须「停旧起新」，
                // 否则 startServer 的 isAlive 守卫会直接返回，用户会看到「改了端口却仍监听旧端口」。
                val error = if (portChanged && serverRunning) restartServer() else startServer()
                if (error != null) AppLogger.w(TAG, "常驻接收端启动失败：$error")
            }
        }
    }

    /* ---------------- 身份 ---------------- */

    /**
     * 本机自述信息。
     *
     * `fingerprint` 首次调用时生成并持久化：协议要求它在一台设备上稳定，
     * 否则「避免发现自己」会失效（本机会把自己当成一台新设备反复列出）。
     */
    suspend fun selfInfo(): LanDeviceInfo {
        val s = settings.settings.value
        val fingerprint = s.lanSyncFingerprint.ifBlank {
            randomFingerprint().also { generated ->
                settings.setLanSyncFingerprint(generated)
            }
        }
        return LanDeviceInfo(
            alias = s.lanSyncAlias.ifBlank { defaultAlias() },
            deviceModel = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() },
            fingerprint = fingerprint,
            port = s.lanSyncPort,
        )
    }

    /** 默认别名：设备型号 + 应用名，避免局域网里出现两台都叫「DPmusic」的设备。 */
    private fun defaultAlias(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) DEFAULT_ALIAS else "$DEFAULT_ALIAS ($model)"
    }

    /** 随机指纹：16 字节随机数的十六进制（明文模式下协议未规定格式，只需稳定且唯一）。 */
    private fun randomFingerprint(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /* ---------------- 接收端 ---------------- */

    /**
     * 启动接收端（幂等）。
     *
     * @return null 表示启动成功，否则为失败原因（端口被占用等，需展示给用户）
     */
    fun startServer(): String? {
        if (server?.isAlive == true) return null
        stopServer()
        val port = settings.settings.value.lanSyncPort
        // 确保指纹已生成：HTTP 处理线程拿不到 suspend，只能读缓存，
        // 若此时仍是占位值，对端会把我们记成一个「每次都在改名」的设备。
        scope.launch {
            selfInfo()
            refreshSelfInfo()
        }
        val instance = LanSyncServer(
            port = port,
            selfInfo = { selfInfoCached() },
            requiredPin = { settings.settings.value.lanSyncPin.takeIf { it.isNotBlank() } },
            onPayload = { payload -> applyPayload(payload) },
            onEvent = { event ->
                if (event is LanSyncEvent.Completed) {
                    scope.launch { settings.setLanSyncLastSyncTime(System.currentTimeMillis()) }
                }
                eventSink(event)
            },
        )
        return runCatching { instance.start(SOCKET_READ_TIMEOUT_MS, false) }
            .onFailure { error ->
                // 不再逐次刷屏：绑定失败通常是端口被占用（例如与 LocalSend 的默认 53317 撞车），
                // 会在每次改设置时重试，逐次打日志会把运行日志刷满。这里改为「失败状态变化才记」。
                val reason = error.message.orEmpty()
                if (reason != lastStartError) {
                    lastStartError = reason
                    AppLogger.w(TAG, "接收端启动失败: $reason")
                }
            }
            .map { instance.also { server = it }; null.also { lastStartError = null; lastFailure = null } }
            .getOrElse {
                val detail = it.message.orEmpty()
                val message = if (detail.contains("Address already in use") || detail.contains("EADDRINUSE")) {
                    "端口 $port 已被占用（可能与其他局域网传输应用冲突，例如 LocalSend 默认也用 53317）" +
                        "。请把端口改为其它值后重试。"
                } else {
                    "接收端启动失败：${detail.ifBlank { "端口 $port 可能已被占用" }}"
                }
                lastFailure = message
                message
            }
    }

    /** 上一次绑定失败原因：用于抑制「同一原因反复刷屏」 */
    @Volatile
    private var lastStartError: String? = null

    /** 上一次启动失败的**可读原因**（供界面展示；成功时为 null）。 */
    @Volatile
    private var lastFailure: String? = null

    /** 最近一次启动失败原因；成功启动过则为 null。 */
    fun lastStartFailure(): String? = lastFailure

    /** 停止接收端（用户关闭开关时调用）。 */
    fun stopServer() {
        runCatching { server?.stop() }
        server = null
    }

    /**
     * 重启接收端（端口变更后调用）。
     *
     * 必须是「停旧 + 起新」而不是只 stop：NanoHTTPD 的端口是构造时绑定的，
     * 不重启就仍监听着旧端口，用户会看到「改了端口但对端连不上」。
     */
    fun restartServer(): String? {
        stopServer()
        return startServer()
    }

    /** 接收端是否在运行。 */
    val serverRunning: Boolean get() = server?.isAlive == true

    /**
     * 缓存最近一次的自述信息：`/info` 与 `/register` 会被对端高频调用，
     * 每次都读 DataStore 是没必要的开销。指纹一旦生成就不会再变，别名改动由 [refreshSelfInfo] 刷新。
     */
    @Volatile
    private var cachedSelfInfo: LanDeviceInfo? = null

    private fun selfInfoCached(): LanDeviceInfo =
        cachedSelfInfo ?: runBlockingSelfInfo().also { cachedSelfInfo = it }

    /** 单次阻塞读取（仅用于 HTTP 处理线程无法挂起时的兜底）。 */
    private fun runBlockingSelfInfo(): LanDeviceInfo {
        val s = settings.settings.value
        return LanDeviceInfo(
            alias = s.lanSyncAlias.ifBlank { defaultAlias() },
            deviceModel = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() },
            // 指纹尚未生成时先用一个临时值：真正的生成走 suspend 的 selfInfo()
            fingerprint = s.lanSyncFingerprint.ifBlank { PENDING_FINGERPRINT },
            port = s.lanSyncPort,
        )
    }

    /** 别名 / 端口变更后刷新缓存的自述信息。 */
    fun refreshSelfInfo() {
        cachedSelfInfo = null
    }

    /** 应用收到的数据包：解码后交给 [SyncManager] 复用既有覆盖语义。 */
    private suspend fun applyPayload(payload: LanSyncPayload) {
        payload.settings?.let { raw ->
            val decoded = AppJson.decodeFromString<SettingsSyncPayload>(raw)
            sync.applySettingsPayload(decoded)
        }
        payload.lists?.let { raw ->
            val decoded = AppJson.decodeFromString<ListsSyncPayload>(raw)
            sync.applyListsPayload(decoded)
        }
        payload.playback?.let { raw ->
            // 播放流转是「立刻起播」而非覆盖数据：解码后直接交给播放器。
            // 用 LanJson（encodeDefaults=true）而非 AppJson：载荷由本实现与
            // 可能的未来对端共同产生，字段应当完整出现在线上。
            val decoded = LanJson.decodeFromString<LanPlaybackPayload>(raw)
            // 这里运行在 IO 线程（NanoHTTPD 的落库协程），故交给播放器自行切主线程
            playerProvider().receiveHandoff(decoded)
        }
    }

    /* ---------------- 发送端 ---------------- */

    /**
     * 把本机数据推送到 [device]。
     *
     * 流程严格按协议三段：`prepare-upload`（协商）→ `upload`（逐文件传体）。
     *
     * @param includeSettings 是否包含「设置与音源」
     * @param includeLists 是否包含「歌单与数据」
     * @return 结果描述（成功 / 失败原因），可直接展示
     */
    suspend fun sendTo(
        device: LanDevice,
        includeSettings: Boolean,
        includeLists: Boolean,
    ): LanSendResult {
        if (!includeSettings && !includeLists) return LanSendResult.Failed("请至少选择一项要同步的内容")

        // 载荷沿用项目全局 AppJson（与 WebDAV 上传完全同源，且只由本应用解析）
        val self = selfInfo()
        val port = device.info.port.takeIf { it > 0 } ?: LanProtocol.DEFAULT_PORT

        // 组装待传文件：内容与「WebDAV 上传」完全同源，不另造一套载荷
        val files = LinkedHashMap<String, ByteArray>()
        if (includeSettings) {
            files[LanProtocol.FILE_SETTINGS] = AppJson.encodeToString(sync.buildSettingsPayload())
                .toByteArray(Charsets.UTF_8)
        }
        if (includeLists) {
            files[LanProtocol.FILE_LISTS] = AppJson.encodeToString(sync.buildListsPayload())
                .toByteArray(Charsets.UTF_8)
        }

        val metas = files.mapValues { (name, bytes) ->
            LanFileMeta(
                id = name,
                fileName = name,
                size = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
            )
        }

        // ① 协商
        val pin = settings.settings.value.lanSyncPin.takeIf { it.isNotBlank() }
        val outcome = LanHttpClient.prepareUpload(
            host = device.host,
            port = port,
            request = LanPrepareRequest(info = self, files = metas),
            pin = pin,
        )
        val accepted = when (outcome) {
            is LanPrepareOutcome.Accepted -> outcome
            LanPrepareOutcome.NotNeeded -> return LanSendResult.Success("对端无需同步（数据一致）")
            LanPrepareOutcome.PinRequired -> return LanSendResult.Failed("PIN 不正确，或对端要求 PIN")
            LanPrepareOutcome.Rejected -> return LanSendResult.Failed("${device.displayName} 拒绝了本次同步")
            LanPrepareOutcome.Busy -> return LanSendResult.Failed("${device.displayName} 正在与其它设备同步")
            is LanPrepareOutcome.Failed -> return LanSendResult.Failed(outcome.message)
        }

        // ② 逐文件传体。只传对端批准的文件（协议允许只批准一部分）
        var sentBytes = 0
        for ((fileId, token) in accepted.tokens) {
            val bytes = files[fileId] ?: continue
            val error = LanHttpClient.upload(
                host = device.host, port = port,
                sessionId = accepted.sessionId, fileId = fileId, token = token,
                bytes = bytes,
            )
            if (error != null) {
                LanHttpClient.cancel(device.host, port, accepted.sessionId)
                return LanSendResult.Failed(error)
            }
            sentBytes += bytes.size
        }

        val summary = buildList {
            if (includeSettings) add("设置与音源")
            if (includeLists) add("歌单与数据")
        }.joinToString(" + ")
        scope.launch { settings.setLanSyncLastSyncTime(System.currentTimeMillis()) }
        return LanSendResult.Success("已向 ${device.displayName} 发送：$summary（${sentBytes / 1024} KB）")
    }

    /* ---------------- 播放流转 ---------------- */

    /**
     * 把本机当前播放队列与**精确进度**推送到 [device]，让对端接着播。
     *
     * 与 [sendTo] 共用同一条协议通道（prepare-upload → upload），但语义不同：
     * 这是**一次性播放指令**，对端收到即起播，不落任何持久化数据、不写白名单里的任何键。
     *
     * 因此这里不走 [SyncManager]，只需一次协商 + 一次上传（单文件，无部分接受问题）。
     *
     * @return 结果描述（成功 / 失败原因），可直接展示
     */
    suspend fun sendPlayback(device: LanDevice): LanSendResult {
        val self = selfInfo()
        // 导出必须在主线程发生（读的是播放器内存状态，与控制器回调同线程）
        val payload = withContext(Dispatchers.Main) {
            playerProvider().exportHandoffSession(self.alias)
        } ?: return LanSendResult.Failed("本机还没有播放内容，先播放一首歌再流转")

        if (payload.songs.isEmpty()) return LanSendResult.Failed("播放队列为空，无处可流转")

        val bytes = LanJson.encodeToString(LanPlaybackPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val name = LanProtocol.FILE_PLAYBACK
        val meta = LanFileMeta(
            id = name,
            fileName = name,
            size = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
        )

        val port = device.info.port.takeIf { it > 0 } ?: LanProtocol.DEFAULT_PORT
        val pin = settings.settings.value.lanSyncPin.takeIf { it.isNotBlank() }
        val outcome = LanHttpClient.prepareUpload(
            host = device.host,
            port = port,
            request = LanPrepareRequest(info = self, files = mapOf(name to meta)),
            pin = pin,
        )
        val accepted = when (outcome) {
            is LanPrepareOutcome.Accepted -> outcome
            // 对端回 204 表示「无需传输」：对数据同步是「数据一致」，
            // 对播放流转只可能是对端版本不认识这个包，必须如实告知而不是报成功
            LanPrepareOutcome.NotNeeded -> return LanSendResult.Failed("${device.displayName} 未接受流转（对端版本可能不支持）")
            LanPrepareOutcome.PinRequired -> return LanSendResult.Failed("PIN 不正确，或对端要求 PIN")
            LanPrepareOutcome.Rejected -> return LanSendResult.Failed("${device.displayName} 拒绝了本次流转")
            LanPrepareOutcome.Busy -> return LanSendResult.Failed("${device.displayName} 正在与其它设备传输")
            is LanPrepareOutcome.Failed -> return LanSendResult.Failed(outcome.message)
        }

        val token = accepted.tokens[name]
            ?: return LanSendResult.Failed("${device.displayName} 未批准播放流转数据包")
        val error = LanHttpClient.upload(
            host = device.host, port = port,
            sessionId = accepted.sessionId, fileId = name, token = token,
            bytes = bytes,
        )
        if (error != null) {
            LanHttpClient.cancel(device.host, port, accepted.sessionId)
            return LanSendResult.Failed(error)
        }

        val song = payload.songs[payload.currentIndex.coerceIn(0, payload.songs.lastIndex)]
        val playState = if (payload.isPlaying) "正在播放" else "已暂停"
        return LanSendResult.Success(
            "已流转到 ${device.displayName}：${song.title}（${formatPosition(payload.positionMs)}，$playState，共 ${payload.songs.size} 首）",
        )
    }

    /* ---------------- 发现 ---------------- */

    /** 扫描局域网设备（同时把本机宣告出去，让对端也能看到我们）。 */
    suspend fun scan(): List<LanDevice> = discovery.scan(selfInfo())

    /** 清空发现结果。 */
    fun clearDevices() = discovery.clear()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 进度展示为 `分:秒`（与播放页的时间格式一致，避免用户换算） */
    private fun formatPosition(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val TAG = "LanSyncManager"

        /** NanoHTTPD 的 socket 读超时：够一次局域网往返，又不至于让线程长期占用 */
        const val SOCKET_READ_TIMEOUT_MS = 10_000

        const val DEFAULT_ALIAS = "DPmusic"

        /** 指纹尚未生成时的占位（首次扫描会立刻写入真实值） */
        const val PENDING_FINGERPRINT = "pending"
    }
}

/** 发送结果 */
sealed interface LanSendResult {
    /** @param detail 成功描述，可直接展示 */
    data class Success(val detail: String) : LanSendResult

    /** @param reason 失败原因，可直接展示 */
    data class Failed(val reason: String) : LanSendResult
}