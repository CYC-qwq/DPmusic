package com.dpmusic.app.core.lansync

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** `/prepare-upload` 的结果：协议用 HTTP 状态码区分原因，这里还原成可分支的类型。 */
sealed interface LanPrepareOutcome {
    /** 接收端已受理，附带 sessionId 与逐文件 token */
    data class Accepted(val sessionId: String, val tokens: Map<String, String>) : LanPrepareOutcome

    /** 204：接收端认为无需传输（数据一致） */
    data object NotNeeded : LanPrepareOutcome

    /** 401：需要 PIN 或 PIN 错误 */
    data object PinRequired : LanPrepareOutcome

    /** 403：对端拒绝 */
    data object Rejected : LanPrepareOutcome

    /** 409：对端已有会话在传输 */
    data object Busy : LanPrepareOutcome

    /** 其它失败（网络异常 / 未预期状态码） */
    data class Failed(val message: String) : LanPrepareOutcome
}

/** 一块可用于扫描的本地网络 */
data class LocalAddress(
    /** 本机在该网络上的地址 */
    val host: String,
    /** 前缀长度（/24 → 24） */
    val prefixLength: Int,
)

/**
 * 一张网卡上的一个地址（[LanHttpClient.scannableLanAddresses] 的输入）。
 *
 * 刻意不直接传 [NetworkInterface]：那样这个判定逻辑就没法做单元测试，
 * 而它恰恰是容易出错的地方（见 [LanHttpClient.scannableLanAddresses] 的说明）。
 */
data class NicAddress(
    val nicName: String,
    val host: String,
    val prefixLength: Int,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isPointToPoint: Boolean,
    val isSiteLocal: Boolean,
)

/**
 * LocalSend 协议 v2.2 的 HTTP 客户端（发送端）。
 *
 * 只实现本功能需要的四条路径：
 * - `POST /api/localsend/v2/register` —— 设备注册（发现，协议 §3.2）；
 * - `GET  /api/localsend/v2/info`     —— 确认在线（协议 §6.1）；
 * - `POST /api/localsend/v2/prepare-upload` —— 传输协商（协议 §4.1）；
 * - `POST /api/localsend/v2/upload`   —— 传输数据（协议 §4.2）。
 *
 * 刻意**不复用** `core/net/Http`：那个客户端按公网 API 调优（15s 连接超时 + 重试），
 * 而局域网扫描要求「失败得快」——扫一个 /24 网段时 15 秒超时会让整轮发现卡死。
 */
object LanHttpClient {

    /** 扫描 / 注册用的短超时客户端：局域网不可达必须立刻失败。 */
    private val scanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SCAN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SCAN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SCAN_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 扫描时对端未开服务是常态，重试只会拖慢整轮
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 传输用的客户端：允许较慢的局域网写入，但仍远短于公网客户端。 */
    private val transferClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BINARY_MEDIA = "application/octet-stream".toMediaType()

    /* ---------------- 注册（发现） ---------------- */

    /**
     * 向指定设备发起注册（协议 §3.2 的 HTTP 模式）。
     *
     * @param host 对端 IP
     * @param port **对端**的 HTTP 端口（不是 [info] 里的本机端口 —— 后者是告诉对端往哪回连）
     * @return 对方自述信息；对方未运行服务 / 不是 LocalSend 设备时返回 null
     *   （扫描的绝大多数 IP 都属于这种情况，必须静默，不能当成错误）
     */
    suspend fun register(host: String, port: Int, info: LanDeviceInfo): LanDeviceInfo? = withContext(Dispatchers.IO) {
        val body = LanJson.encodeToString(LanDeviceInfo.serializer(), info).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("${baseUrl(host, port)}$PATH_REGISTER")
            .post(body)
            .build()
        runCatching {
            scanClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { LanJson.decodeFromString(LanDeviceInfo.serializer(), it) }
            }
        }.getOrNull()
    }

    /** `GET /api/localsend/v2/info`：确认服务在线并取回对端自述（协议 §6.1）。 */
    suspend fun info(host: String, port: Int): LanDeviceInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl(host, port)}$PATH_INFO").get().build()
        runCatching {
            scanClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { LanJson.decodeFromString(LanDeviceInfo.serializer(), it) }
            }
        }.getOrNull()
    }

    /* ---------------- 传输（上传） ---------------- */

    /** `POST /prepare-upload`：协商。状态码映射见协议 §4.1 的错误表。 */
    suspend fun prepareUpload(
        host: String,
        port: Int,
        request: LanPrepareRequest,
        pin: String? = null,
    ): LanPrepareOutcome = withContext(Dispatchers.IO) {
        val url = buildString {
            append(baseUrl(host, port)).append(PATH_PREPARE_UPLOAD)
            if (!pin.isNullOrBlank()) append("?pin=").append(pin.trim())
        }
        val body = LanJson.encodeToString(LanPrepareRequest.serializer(), request).toRequestBody(JSON_MEDIA)
        runCatching {
            transferClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val json = resp.body?.string().orEmpty()
                        val parsed = LanJson.decodeFromString(LanPrepareResponse.serializer(), json)
                        if (parsed.files.isEmpty()) LanPrepareOutcome.NotNeeded
                        else LanPrepareOutcome.Accepted(parsed.sessionId, parsed.files)
                    }
                    resp.code == LanHttp.NO_FILE_TRANSFER -> LanPrepareOutcome.NotNeeded
                    resp.code == LanHttp.PIN_REQUIRED -> LanPrepareOutcome.PinRequired
                    resp.code == LanHttp.REJECTED -> LanPrepareOutcome.Rejected
                    resp.code == LanHttp.SESSION_BLOCKED -> LanPrepareOutcome.Busy
                    else -> LanPrepareOutcome.Failed("接收端返回 HTTP ${resp.code}")
                }
            }
        }.getOrElse { LanPrepareOutcome.Failed(it.message ?: "无法连接接收端") }
    }

    /**
     * `POST /upload`：传输单个文件体。
     *
     * 协议允许并行调用（每个 fileId 一次），本实现串行发送 —— 总共只有两个小包。
     *
     * @return null 表示成功，否则为失败原因（直接可展示给用户）
     */
    suspend fun upload(
        host: String,
        port: Int,
        sessionId: String,
        fileId: String,
        token: String,
        bytes: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        val url = "${baseUrl(host, port)}$PATH_UPLOAD" +
            "?sessionId=$sessionId&fileId=$fileId&token=$token"
        runCatching {
            transferClient.newCall(
                Request.Builder().url(url).post(bytes.toRequestBody(BINARY_MEDIA)).build(),
            ).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == LanHttp.CHECKSUM_MISMATCH -> "校验和不匹配（HTTP 422）"
                    resp.code == LanHttp.SESSION_BLOCKED -> "接收端已被其它会话占用（HTTP 409）"
                    resp.code == LanHttp.REJECTED -> "接收端拒绝了该文件（HTTP 403）"
                    else -> "上传失败：HTTP ${resp.code}"
                }
            }
        }.getOrElse { it.message ?: "上传失败" }
    }

    /** `POST /cancel`：取消会话（尽力而为，失败不影响结果）。 */
    suspend fun cancel(host: String, port: Int, sessionId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl(host, port)}$PATH_CANCEL?sessionId=$sessionId"
            transferClient.newCall(
                Request.Builder().url(url).post(ByteArray(0).toRequestBody(BINARY_MEDIA)).build(),
            ).execute().close()
        }
        Unit
    }

    /* ---------------- 本机网络 ---------------- */

    /** 本机可扫描的 IPv4 点分地址。 */
    fun localIpv4Addresses(): List<String> = localNetworks().map { it.host }

    /**
     * 本机可用于扫描的网络（地址 + 前缀长度）。
     *
     * 判定规则集中在 [scannableLanAddresses]（纯函数，可测试），这里只负责枚举网卡。
     */
    fun localNetworks(): List<LocalAddress> = runCatching {
        scannableLanAddresses(
            NetworkInterface.getNetworkInterfaces().toList().flatMap { nic ->
                val isLoopback = nic.isLoopback
                val isPointToPoint = nic.isPointToPoint
                val isUp = nic.isUp
                nic.interfaceAddresses.mapNotNull { ia ->
                    val addr = ia.address as? Inet4Address ?: return@mapNotNull null
                    NicAddress(
                        nicName = nic.name,
                        host = addr.hostAddress ?: return@mapNotNull null,
                        prefixLength = ia.networkPrefixLength.toInt(),
                        isLoopback = isLoopback,
                        isPointToPoint = isPointToPoint,
                        isUp = isUp,
                        isSiteLocal = addr.isSiteLocalAddress,
                    )
                }
            },
        )
    }.getOrDefault(emptyList())

    /**
     * 从网卡地址里挑出「值得扫描」的（纯函数，便于测试）。
     *
     * 实测一个真实陷阱：手机上除了 Wi-Fi，还有**蜂窝接口**（如 `rmnet_data4` 拿到
     * `10.26.110.125`）。RFC1918 的 10/8 让 `isSiteLocalAddress` 为真，于是纯地址过滤
     * 会把它当成局域网 —— 结果是往运营商网段里发几百个请求，既找不到设备又浪费流量。
     * 因此额外要求前缀是局域网常见长度（[MIN_SCAN_PREFIX_LENGTH] 及以上）。
     *
     * 点对点接口（VPN 的 tun）同样排除。
     */
    fun scannableLanAddresses(addresses: List<NicAddress>): List<LocalAddress> = addresses
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.isSiteLocal }
        .filter { it.prefixLength in MIN_SCAN_PREFIX_LENGTH..MAX_SCAN_PREFIX_LENGTH }
        .distinctBy { it.host }
        .map { LocalAddress(it.host, it.prefixLength) }

    /**
     * 由本机地址 + 前缀长度推出同网段待扫描的候选主机地址。
     *
     * 为什么需要它：组播在部分路由器（AP 隔离 / IGMP snooping 异常）下不可靠，
     * 协议为此规定了 HTTP legacy 兜底模式（§3.2）——逐个 IP 发注册请求。
     *
     * 只展开**主机位不超过 8 位**（即 /24 及更大前缀）的网段：/16 会展开出 6 万多个地址，
     * 那不是「发现」而是「压测」。前缀更短时按 /24 处理，覆盖最常见的家用网段。
     */
    fun subnetCandidates(localIp: String, prefixLength: Int = 24): List<String> {
        val octets = localIp.split('.').mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 0..255 } }
        if (octets.size != 4) return emptyList()

        val hostBits = (32 - prefixLength).coerceIn(1, 8)
        val network = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        // 把主机位清零得到网络号；再跳过网络号与广播号
        val networkBase = network and (0xFFFFFFFF.toInt() shl hostBits)
        val hostCount = (1 shl hostBits) - 1

        return (1 until hostCount).map { offset ->
            val v = networkBase + offset
            "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
        }
    }

    /**
     * 探哪些端口。
     *
     * 两个都探：协议默认端口（对端多为 LocalSend 或未改过端口），
     * 以及本机配置的端口（两个 DPmusic 实例通常设成一样）。
     */
    fun scanPorts(configuredPort: Int): List<Int> =
        listOf(LanProtocol.DEFAULT_PORT, configuredPort).distinct()

    private fun baseUrl(host: String, port: Int): String = "http://$host:$port"

    private const val PATH_REGISTER = "/api/localsend/v2/register"
    private const val PATH_INFO = "/api/localsend/v2/info"
    private const val PATH_PREPARE_UPLOAD = "/api/localsend/v2/prepare-upload"
    private const val PATH_UPLOAD = "/api/localsend/v2/upload"
    private const val PATH_CANCEL = "/api/localsend/v2/cancel"

    private const val SCAN_CONNECT_TIMEOUT_MS = 600L
    private const val SCAN_READ_TIMEOUT_MS = 1_500L
    private const val SCAN_WRITE_TIMEOUT_MS = 1_500L
    private const val TRANSFER_TIMEOUT_MS = 15_000L

    /**
     * 允许扫描的前缀长度区间。
     *
     * 上界 [MAX_SCAN_PREFIX_LENGTH] 是实测踩出来的：蜂窝接口的地址落在 10/8（RFC1918），
     * `isSiteLocalAddress` 为真，但前缀是 /32 —— 单主机链路。若不设上界，
     * 扫描会朝运营商网段发几百个请求。/32、/31 这类本质是点对点链路，没有可扫的邻居。
     *
     * 下界 /24 是因为更短的网段（/16）会有 6 万多个地址，逐探测等于压测；
     * 家用局域网几乎都是 /24。
     */
    private const val MIN_SCAN_PREFIX_LENGTH = 24
    private const val MAX_SCAN_PREFIX_LENGTH = 30
}