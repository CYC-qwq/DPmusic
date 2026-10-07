package com.dpmusic.app.core.lansync

import com.dpmusic.app.core.model.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LocalSend 协议 v2.2 的线路模型（wire format）。
 *
 * 对齐 https://github.com/localsend/protocol —— 字段名严格按协议原文，
 * 因为对端（LocalSend 客户端）是**独立实现**，任何「顺手的重命名」都会直接导致互不可见。
 *
 * 刻意保留协议默认值（而非一律 `???`）：注册请求允许省略设备型号，缺省比报错更稳。
 */
object LanProtocol {

    /** 协议版本号（`major.minor`）；对端按此判定是否兼容。 */
    const val VERSION = "2.2"

    /** 发现 / 传输统一端口（UDP 组播与 HTTP 服务共用）。 */
    const val DEFAULT_PORT = 53317

    /**
     * 组播地址。
     *
     * 协议明确规定默认组播**网段**为 `224.0.0.0/24`，因为部分 Android 设备会拒绝
     * 该网段之外的组播组；协议给的可用地址是 `224.0.0.167`。
     */
    const val MULTICAST_ADDRESS = "224.0.0.167"

    /** 设备类型（协议枚举：mobile | desktop | web | headless | server） */
    const val DEVICE_TYPE_MOBILE = "mobile"

    /**
     * 组播发现固定端口（协议 §1 的默认值）。
     *
     * 刻意**不跟** [DEFAULT_PORT] 一起做成可配置：组播是「无连接的约定」，
     * 两端必须监听同一个固定端口才能互相看见。若允许用户改组播端口，
     * 改完就与所有对端失联，且现象是「没有任何设备」——极难排查。
     * 因此可配置端口只影响 HTTP 监听，组播始终用协议默认值。
     */
    const val MULTICAST_PORT = 53317

    /** 传输协议（协议枚举：http | https）。局域网内明文 HTTP，见 [LanSyncServer] 的说明。 */
    const val PROTOCOL_HTTP = "http"

    /**
     * 同步数据包的「文件名」，用于 `/api/localsend/v2/upload`。
     *
     * 接收端按文件名分派，因此这两个常量是两端的分派契约，不可随意改。
     */
    const val FILE_SETTINGS = "dpmusic-settings.json"
    const val FILE_LISTS = "dpmusic-lists.json"

    /**
     * 播放流转数据包（主页「流转」按钮推送的播放队列 + 精确进度）。
     *
     * 与前两个包一样走标准 prepare-upload / upload 流程，只是内容语义不同：
     * 前缀两个是**覆盖式数据同步**（写 DataStore），这个是一次性的**播放指令**
     * （立刻起播，不落任何持久化数据）。因此接收端不会把它并入 [LanSyncPayload]
     * 的同步语义，而是单独分派。
     */
    const val FILE_PLAYBACK = "dpmusic-playback.json"

    /** 同步包固定 MIME（接收端据此判定是否是需要的数据包）。 */
    const val MIME_JSON = "application/json"
}

/**
 * 播放流转载荷：把发送端的播放队列与「精确到毫秒」的进度交给对端接着播。
 *
 * ## 为什么进度是字段而不是「让对端自己从头放」
 *
 * 需求是「无缝接着听」：用户在 A 手机听到 2:13，到 B 手机应当从 2:13 继续，
 * 而不是第一首从头开始。因此 [positionMs] 必须随包传，且取的是**控制器实时值**
 * （见 `PlayerConnection.exportHandoffSession`），而不是最多滞后 20 秒的持久化快照。
 *
 * ## 为什么带 [sentAtMs]
 *
 * 网络与解析有耗时（几十到几百毫秒）。接收端按「已经过去的时间」补偿进度，
 * 才能让两端的进度条对齐；不补则每次都差一个传输耗时，长曲子上会越来越明显。
 * 该值只用于补偿，时钟不一致时以「非负且不超过曲长」兜底，不做跨设备时钟同步。
 *
 * ## 越界数据的处理契约
 *
 * [currentIndex] 与 [positionMs] 都可能来自不同版本的对端，接收端必须
 * **夹取（coerce）而不是信任**：索引越界会让 `playQueue` 静默失败，
 * 进度超过曲长会让播放器落到末尾直接跳下一首。夹取规则写在接收端。
 *
 * @param songs 完整播放队列（含平台 / 时长 / 封面等，接收端不重新检索）
 * @param currentIndex 发送端正在播放的队列下标
 * @param positionMs 发送端的实时播放进度（毫秒）
 * @param sentAtMs 打包时刻（`System.currentTimeMillis()`，用于进度补偿）
 * @param isPlaying 发送端是否处于播放态；若为暂停，接收端应同样只载入不自动播
 * @param fromAlias 发送端显示名，用于接收端提示「来自 xxx 的流转」
 */
@Serializable
data class LanPlaybackPayload(
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val sentAtMs: Long = 0L,
    val isPlaying: Boolean = true,
    val fromAlias: String = "",
) {
    /**
     * 按「已过去的传输耗时」补偿后的进度。
     *
     * 暂停时**不补偿**：暂停态的进度是静止的，加了补偿反而会跳到一个未来位置。
     */
    fun compensatedPositionMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        // 时钟异常（对端时间在未来）时 elapsed 为负，coerceAtLeast 兜底为不补偿
        val elapsed = (nowMs - sentAtMs).coerceAtLeast(0L)
        return (positionMs + elapsed).coerceAtLeast(0L)
    }
}

/**
 * 局域网同步的线路 JSON（与 [com.dpmusic.app.core.net.AppJson] 刻意分开）。
 *
 * 关键差异是 `encodeDefaults = true`：协议字段（`version` / `port` / `protocol` /
 * `download` / `deviceType`）都带默认值，而项目全局的 AppJson **不输出默认值** ——
 * 用它序列化出来的注册体就会缺这些字段。本应用两端都宽松，自测不会暴露问题，
 * 但独立实现的对端（LocalSend）可能把 `port` 当必需字段，缺了就直接解析失败。
 *
 * 同时保留 `ignoreUnknownKeys` / `isLenient`：对端版本高于本实现时，
 * 多出来的字段必须被忽略而不是抛异常。
 *
 * @see LanProtocol 字段名的定义来源
 */
val LanJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 设备自述信息。既用作组播宣告 / 注册请求，也用作注册响应 —— 协议里三者是同一个结构。
 *
 * @param announce 仅组播消息使用：`true` = 宣告（希望对方回复），`false` = 对宣告的应答。
 *   发往 `/register` 的 HTTP 请求不带该字段（协议如此）。
 */
@Serializable
data class LanDeviceInfo(
    val alias: String,
    val version: String = LanProtocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = LanProtocol.DEVICE_TYPE_MOBILE,
    val fingerprint: String,
    val port: Int = LanProtocol.DEFAULT_PORT,
    val protocol: String = LanProtocol.PROTOCOL_HTTP,
    /** 是否开放「反向下载 API」（本实现不需要，恒为 false）。 */
    val download: Boolean = false,
    val announce: Boolean? = null,
)

/** `/api/localsend/v2/prepare-upload` 请求体 */
@Serializable
data class LanPrepareRequest(
    val info: LanDeviceInfo,
    val files: Map<String, LanFileMeta>,
)

/** 待传输文件的元数据（协议 §4.1；本实现只用于传 JSON 数据包） */
@Serializable
data class LanFileMeta(
    val id: String,
    val fileName: String,
    val size: Long,
    @SerialName("fileType") val fileType: String = LanProtocol.MIME_JSON,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * `/prepare-upload` 成功响应。
 *
 * `files` 是 `fileId -> token` 的映射；接收端会**只批准部分文件**，
 * 因此发送端必须以该映射为准，而不是假定自己提交的全部被接受。
 */
@Serializable
data class LanPrepareResponse(
    val sessionId: String,
    val files: Map<String, String> = emptyMap(),
)

/** 协议 §4.1 的 HTTP 状态码；非 200 时无 JSON 体，只能靠状态码区分原因。 */
object LanHttp {

    /** 无文件需要传输（双方数据一致） */
    const val NO_FILE_TRANSFER = 204

    /** 请求体非法 */
    const val BAD_REQUEST = 400

    /** 需要 PIN / PIN 错误 */
    const val PIN_REQUIRED = 401

    /** 被接收端拒绝（用户点了拒绝，或发送端不在允许列表） */
    const val REJECTED = 403

    /** 已有会话占用 */
    const val SESSION_BLOCKED = 409

    /** 请求过于频繁 */
    const val TOO_MANY_REQUESTS = 429

    /** 校验和不匹配（sha256） */
    const val CHECKSUM_MISMATCH = 422
}