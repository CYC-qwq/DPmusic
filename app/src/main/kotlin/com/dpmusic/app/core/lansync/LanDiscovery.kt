package com.dpmusic.app.core.lansync

import android.content.Context
import android.net.wifi.WifiManager
import com.dpmusic.app.core.util.AppLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 局域网设备发现（LocalSend 协议 §3）。
 *
 * 两条通道，互为兜底 —— 这也是协议本身的意图：
 * 1. **组播（默认）**：`224.0.0.167:53317` 上的 UDP 宣告与应答；
 * 2. **HTTP 扫描（legacy）**：组播不可用时逐个 IP 发注册请求。
 *
 * 第 2 条不是可有可无的：协议「故障排查」把「设备不可见」列为首要问题，
 * 其成因（AP 隔离 / VPN / Windows 公用网络）几乎都会同时干掉组播，只剩单播能穿透。
 *
 * 身份一律用 `fingerprint` —— 协议规定它同时承担「避免发现自己」与「记忆设备」两个职责。
 */
class LanDiscovery(private val context: Context) {

    private val _devices = MutableStateFlow<List<LanDevice>>(emptyList())

    /** 当前已发现的设备（已排除本机）。 */
    val devices: StateFlow<List<LanDevice>> = _devices.asStateFlow()

    /** 串行化扫描：并发扫描无收益，只会成倍发包并互相覆盖结果。 */
    private val scanMutex = Mutex()

    /** 组播锁：Android 在屏幕关闭后会停止接收未持锁进程的组播包。 */
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 执行一轮发现并返回结果（同时更新 [devices]）。
     *
     * @param self 本机自述信息：用于排除自己，也作为注册请求体
     * @param timeoutMs 总预算。组播先跑满窗口；组播一无所获时才把预算用于网段扫描
     */
    suspend fun scan(self: LanDeviceInfo, timeoutMs: Long = DEFAULT_SCAN_MS): List<LanDevice> =
        scanMutex.withLock {
            val found = LinkedHashMap<String, LanDevice>()
            val deadline = System.currentTimeMillis() + timeoutMs

            val multicast = runCatching { collectMulticast(self) }
                .onFailure { AppLogger.w(TAG, "组播发现失败（将回退网段扫描）: ${it.message}") }
                .getOrDefault(emptyList())
            multicast.filterNot { it.info.fingerprint == self.fingerprint }
                .forEach { found[it.info.fingerprint] = it }
            AppLogger.i(TAG, "组播发现 ${multicast.size} 台（有效 ${found.size} 台）")

            // 只有组播彻底没结果时才扫网段：这是「兜底」而非「默认」，不该每次都发包 254 次。
            // 用剩余预算而非完整 timeoutMs，否则实际耗时会是「组播窗口 + 完整预算」。
            if (found.isEmpty()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > MIN_SUBNET_SCAN_MS) {
                    // 本机网段：组播不通时，用户最需要知道的就是「到底扫了哪个网段」
                    val subnets = LanHttpClient.localNetworks()
                        .joinToString { "${it.host}/${it.prefixLength}" }
                    runCatching { scanSubnet(self, remaining) }
                        .onFailure { AppLogger.w(TAG, "网段扫描失败: ${it.message}") }
                        .getOrDefault(emptyList())
                        .filterNot { it.info.fingerprint == self.fingerprint }
                        .forEach { found[it.info.fingerprint] = it }
                    AppLogger.i(TAG, "网段扫描 [$subnets] 完成，发现 ${found.size} 台")
                } else {
                    AppLogger.i(TAG, "组播无结果且剩余预算不足（${remaining}ms），跳过网段扫描")
                }
            }

            found.values.sortedBy { it.info.alias }.also { _devices.value = it }
        }

    /** 清空发现结果（离开同步页时调用，避免过期设备留在界面上）。 */
    fun clear() {
        _devices.value = emptyList()
    }

    /* ---------------- 组播 ---------------- */

    /**
     * 发送一次宣告并收集窗口期内的所有组播消息。
     *
     * 收到 `announce = true` 的宣告要**应答**（协议如此规定），否则对端看不到我们，
     * 会出现「A 能看到 B、B 看不到 A」的单向发现，用户会以为设备没连上。
     */
    private suspend fun collectMulticast(self: LanDeviceInfo): List<LanDevice> {
        val group = InetAddress.getByName(LanProtocol.MULTICAST_ADDRESS)
        acquireMulticastLock()
        try {
            // 必须显式绑定 Wi-Fi 网卡：设备上可能同时存在蜂窝（rmnet_*）与 VPN（tun0），
            // 而不带网卡的 joinGroup 由内核按路由表挑接口 —— 实测在带 VPN 的机型上
            // 会选错，造成「组播发得出去、收不回来」，即对端完全不可见。
            val wifi = LanHttpClient.localNetworks().firstOrNull()
            MulticastSocket(LanProtocol.MULTICAST_PORT).use { socket ->
                // 用 soTimeout 而非已弃用的 MulticastSocket.timeout（Java 14+ 移除）
                socket.soTimeout = SOCKET_TIMEOUT_MS
                if (wifi != null) {
                    val nic = NetworkInterface.getByInetAddress(InetAddress.getByName(wifi.host))
                    if (nic != null) socket.networkInterface = nic
                }
                socket.joinGroup(group)
                try {
                    sendPacket(socket, group, self.copy(announce = true))

                    val collected = LinkedHashMap<String, LanDevice>()
                    val deadline = System.currentTimeMillis() + MULTICAST_WINDOW_MS
                    val buffer = ByteArray(MAX_PACKET_BYTES)

                    while (System.currentTimeMillis() < deadline) {
                        currentCoroutineContext().ensureActive()
                        val packet = DatagramPacket(buffer, buffer.size)
                        // 超时属于正常情况（窗口内本就没消息），用于周期性检查整体截止时间
                        if (!runCatching { socket.receive(packet); true }.getOrDefault(false)) continue

                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val info = decodeInfo(text) ?: continue
                        if (info.fingerprint == self.fingerprint) continue

                        if (info.announce == true) {
                            // 单播应答回来源地址（比再发组播更可靠，也与 LocalSend 行为一致）
                            runCatching { sendUnicast(socket, packet.address, self.copy(announce = false)) }
                        }
                        collected[info.fingerprint] = LanDevice(packet.address?.hostAddress.orEmpty(), info)
                    }
                    return collected.values.filter { it.host.isNotBlank() }
                } finally {
                    runCatching { socket.leaveGroup(group) }
                }
            }
        } finally {
            releaseMulticastLock()
        }
    }

    private fun sendPacket(socket: MulticastSocket, group: InetAddress, info: LanDeviceInfo) {
        val payload = LanJson.encodeToString(LanDeviceInfo.serializer(), info).toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(payload, payload.size, group, LanProtocol.MULTICAST_PORT))
    }

    private fun sendUnicast(socket: MulticastSocket, target: InetAddress?, info: LanDeviceInfo) {
        if (target == null) return
        val payload = LanJson.encodeToString(LanDeviceInfo.serializer(), info).toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(payload, payload.size, target, LanProtocol.MULTICAST_PORT))
    }

    private fun decodeInfo(text: String): LanDeviceInfo? =
        runCatching { LanJson.decodeFromString(LanDeviceInfo.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.fingerprint.isNotBlank() }

    /* ---------------- HTTP 扫描（legacy 兜底） ---------------- */

    /**
     * 逐个 IP 发注册请求（协议 §3.2）。
     *
     * 并发度刻意压低（[SCAN_CONCURRENCY]）：/24 有 254 个地址，全并发会在部分路由器上
     * 触发连接数限制而漏掉真实设备；且同时受 [timeoutMs] 约束，用户不该为发现等太久。
     */
    private suspend fun scanSubnet(self: LanDeviceInfo, timeoutMs: Long): List<LanDevice> {
        // 每个本机网络各展开一份候选地址（不再假设只有一个 IP）
        val candidates = LanHttpClient.localNetworks()
            .flatMap { net -> LanHttpClient.subnetCandidates(net.host, net.prefixLength) }
            .distinct()
        if (candidates.isEmpty()) return emptyList()

        // 探两个端口：协议默认 53317（对端多为 LocalSend / 未改过端口），
        // 以及本机配置的端口（对端很可能与本机设成一样，这是自建的两个实例之间最常见的配合方式）。
        val ports = LanHttpClient.scanPorts(self.port)
        val deadline = System.currentTimeMillis() + timeoutMs
        val found = LinkedHashMap<String, LanDevice>()

        for (batch in candidates.chunked(SCAN_CONCURRENCY)) {
            if (System.currentTimeMillis() >= deadline) break
            currentCoroutineContext().ensureActive()
            coroutineScope {
                batch.flatMap { host -> ports.map { port -> host to port } }
                    .map { (host, port) ->
                        async(Dispatchers.IO) {
                            if (System.currentTimeMillis() >= deadline) return@async null
                            LanHttpClient.register(host, port, self)?.let { info ->
                                // 对端自述里的 port 才是它的真实端口；若缺失则用本次探测端口
                                LanDevice(host, info.copy(port = info.port.takeIf { it > 0 } ?: port))
                            }
                        }
                    }.awaitAll().filterNotNull().forEach { found[it.info.fingerprint] = it }
            }
        }
        return found.values.toList()
    }

    /* ---------------- 组播锁 ---------------- */

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLogger.w(TAG, "获取组播锁失败: ${it.message}") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "LanDiscovery"
        const val MULTICAST_LOCK_TAG = "dpmusic:lansync"

        /** 一轮发现的总预算 */
        const val DEFAULT_SCAN_MS = 12_000L

        /** 组播收集窗口 */
        const val MULTICAST_WINDOW_MS = 2_500L

        /**
         * 网段扫描的最低剩余预算。
         *
         * 低于这个值还不如不扫：/24 共 254 个地址，按 [SCAN_CONCURRENCY] 分批、
         * 每批最坏 [LanHttpClient] 的 600ms 连接超时，走完一轮需要数秒。
         * 预算不足就开扫，只会烧完时间却一台都发现不了。
         */
        const val MIN_SUBNET_SCAN_MS = 1_500L

        /** 单次 receive 等待上限：替代忙等，同时保证能周期性检查整体截止时间 */
        const val SOCKET_TIMEOUT_MS = 250

        const val MAX_PACKET_BYTES = 8 * 1024
        const val SCAN_CONCURRENCY = 24
    }
}

/**
 * 发现到的一台设备。
 *
 * @param host 对端 IPv4 地址（后续 prepare-upload / upload 都用它）
 * @param info 对端自述信息（含协议端口与 fingerprint）
 */
data class LanDevice(
    val host: String,
    val info: LanDeviceInfo,
) {
    /** 界面展示名：对端别名优先，缺失时退回地址 */
    val displayName: String get() = info.alias.ifBlank { host }
}