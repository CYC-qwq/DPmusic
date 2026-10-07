package com.dpmusic.app.core.lansync

import com.dpmusic.app.core.util.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 局域网同步的接收端（LocalSend 协议 v2.2 的 HTTP 服务端）。
 *
 * ## 边界（刻意收窄）
 *
 * 本服务**不是**通用 LocalSend 接收端：只接受 DPmusic 自己的两个同步数据包
 * （[LanProtocol.FILE_SETTINGS] / [LanProtocol.FILE_LISTS]），其余一律 403。
 * 理由有两个，都不是偷懒：
 * 1. 写盘路径完全不同 —— 通用接收端要把文件落到用户选的目录（SAF / 媒体库 / 通知进度），
 *    而这里只是解析 JSON 并写 DataStore，套用文件接收模型会引入大量与目标无关的分支；
 * 2. 安全面更小 —— 一个只认两种文件名、且校验 sha256 的端点，比一个能写任意文件的端点好得多。
 *
 * ## 为什么不加密（HTTP 而非 HTTPS）
 *
 * 协议允许 HTTPS（自签证书 + fingerprint 校验），但 Android 上自签 TLS 需要额外的
 * 证书生成与信任链路；本功能传的是**用户自己的**设置与歌单，且仅在局域网内，
 * 因此与协议 §5 的反向下载 API 一样采用明文 HTTP。需要保密的场景用 PIN。
 *
 * ## 会话模型
 *
 * 同一时刻只允许一个会话（协议 §4.1：被占用时返回 409）。一次会话最多两个文件，
 * 全部收齐后才解码并落库 —— 半途失败不会留下「只同步了一半」的中间状态。
 */
class LanSyncServer(
    private val port: Int,
    /** 本机自述信息（`/info` 与 `/register` 应答给对端看） */
    private val selfInfo: () -> LanDeviceInfo,
    /** 当前要求的 PIN；返回 null / 空串表示不校验 */
    private val requiredPin: () -> String?,
    /** 收到并校验通过的完整数据包（在 IO 协程中调用） */
    private val onPayload: suspend (LanSyncPayload) -> Unit,
    /** 传输事件（供界面展示进度与结果） */
    private val onEvent: (LanSyncEvent) -> Unit,
) : NanoHTTPD(port) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前会话；null 表示空闲 */
    @Volatile
    private var session: ActiveSession? = null

    private val sessionLock = Any()

    /* ---------------- 请求分发 ---------------- */

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        return runCatching { route(session, uri) }.getOrElse { error ->
            AppLogger.w(TAG, "处理 $uri 失败: ${error.message}")
            fail(Response.Status.INTERNAL_ERROR, "处理失败")
        }
    }

    private fun route(session: IHTTPSession, uri: String): Response = when {
        uri.endsWith(PATH_INFO) -> respondInfo()
        uri.endsWith(PATH_REGISTER) -> respondRegister(session)
        uri.endsWith(PATH_PREPARE_UPLOAD) -> respondPrepareUpload(session)
        uri.endsWith(PATH_UPLOAD) -> respondUpload(session)
        uri.endsWith(PATH_CANCEL) -> respondCancel(session)
        else -> fail(Response.Status.NOT_FOUND, "未知接口")
    }

    /** `GET /info`：对端用于确认我们在线。 */
    private fun respondInfo(): Response {
        val body = LanJson.encodeToString(LanDeviceInfo.serializer(), selfInfo())
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)
    }

    /**
     * `POST /register`：设备发现（协议 §3.2）。
     *
     * 请求体是对方的自述信息 —— 本端不需要保存它（发现结果由[LanDiscovery] 维护），
     * 只要回自己的自述即可。这也是对端「记住本机」的唯一途径。
     */
    private fun respondRegister(session: IHTTPSession): Response {
        readBody(session, MAX_SMALL_BODY) // 必须读干请求体，否则连接会被对端判定为异常
        val body = LanJson.encodeToString(LanDeviceInfo.serializer(), selfInfo())
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)
    }

    /**
     * `POST /prepare-upload`：协商。
     *
     * 验收规则（见类注释的边界说明）：
     * - PIN 不符 → 401；
     * - 已有会话在跑 → 409；
     * - 请求里含有**任一**非 DPmusic 数据包 → 403（整包拒绝，不部分接受）；
     * - 单包超过 [MAX_PAYLOAD_BYTES] → 403；
     * - 通过 → 200 + 逐文件 token。
     */
    private fun respondPrepareUpload(http: IHTTPSession): Response {
        val raw = readBody(http, MAX_SMALL_BODY)
            ?: return fail(Response.Status.BAD_REQUEST, "请求体缺失或过大")
        val request = runCatching { LanJson.decodeFromString(LanPrepareRequest.serializer(), String(raw, Charsets.UTF_8)) }
            .getOrElse { return fail(Response.Status.BAD_REQUEST, "请求体不是合法的 prepare-upload") }

        if (!pinMatches(http.parameters)) return fail(Response.Status.UNAUTHORIZED, "PIN 无效")
        if (session != null) return fail(Response.Status.CONFLICT, "已有会话进行中")

        val accepting = request.files.filterValues { it.fileName in SYNC_FILE_NAMES }
        if (accepting.size != request.files.size || accepting.isEmpty()) {
            onEvent(LanSyncEvent.Rejected("对端想发送非 DPmusic 数据（${request.files.values.firstOrNull()?.fileName}）"))
            return fail(Response.Status.FORBIDDEN, "仅接受 DPmusic 同步数据包")
        }
        // 播放流转是「一次性指令」，与覆盖式数据同步语义完全不同：混在一起要么被当成同步写库，
        // 要么让同步只做一半。这里整包拒绝，让发送端一次只做一件事。
        val hasPlayback = accepting.values.any { it.fileName == LanProtocol.FILE_PLAYBACK }
        if (hasPlayback && accepting.size > 1) {
            onEvent(LanSyncEvent.Rejected("播放流转不能与数据同步混发"))
            return fail(Response.Status.FORBIDDEN, "播放流转需单独发送")
        }
        val tooLarge = accepting.values.firstOrNull { it.size > MAX_PAYLOAD_BYTES }
        if (tooLarge != null) {
            onEvent(LanSyncEvent.Rejected("数据包过大（${tooLarge.size} 字节）"))
            return fail(Response.Status.FORBIDDEN, "数据包过大")
        }

        val active = ActiveSession(
            id = UUID.randomUUID().toString().replace("-", ""),
            tokens = accepting.mapValues { UUID.randomUUID().toString().replace("-", "") },
            metas = accepting.mapValues { it.value },
        )
        synchronized(sessionLock) { session = active }

        onEvent(LanSyncEvent.Accepted(request.info.alias, accepting.values.map { it.fileName }))
        val body = LanJson.encodeToString(LanPrepareResponse.serializer(), LanPrepareResponse(active.id, active.tokens))
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)
    }

    /**
     * `POST /upload`：接收文件体。
     *
     * 按协议做三件事：校验 token、校验 sha256（不符返回 422）、收齐后交给上层落库。
     */
    private fun respondUpload(http: IHTTPSession): Response {
        val active = session ?: return fail(Response.Status.CONFLICT, "没有进行中的会话")
        val sessionId = http.parameters["sessionId"]?.firstOrNull()
        val fileId = http.parameters["fileId"]?.firstOrNull()
        val token = http.parameters["token"]?.firstOrNull()
        if (sessionId.isNullOrBlank() || fileId.isNullOrBlank() || token.isNullOrBlank()) {
            return fail(Response.Status.BAD_REQUEST, "缺少 sessionId / fileId / token")
        }
        if (sessionId != active.id) return fail(Response.Status.FORBIDDEN, "sessionId 不匹配")
        if (active.tokens[fileId] != token) return fail(Response.Status.FORBIDDEN, "token 不匹配")
        val meta = active.metas[fileId] ?: return fail(Response.Status.BAD_REQUEST, "未知 fileId")

        val bytes = readBody(http, MAX_PAYLOAD_BYTES)
            ?: return fail(Response.Status.BAD_REQUEST, "请求体缺失或过大")

        // 协议 §4.2：提供了 sha256 就必须校验，不匹配回 422
        val expected = meta.sha256?.removePrefix("sha256:")?.lowercase()
        if (!expected.isNullOrBlank() && sha256Hex(bytes) != expected) {
            onEvent(LanSyncEvent.Failed("校验和不匹配（${meta.fileName}）"))
            synchronized(sessionLock) { session = null }
            return fail(ChecksumMismatch, "校验和不匹配")
        }

        val complete: ActiveSession? = synchronized(sessionLock) {
            val current = session ?: return@synchronized null
            current.received[fileId] = bytes
            if (current.received.keys.containsAll(current.metas.keys)) current.also { session = null } else null
        }

        // 收齐才落库：半途失败不产生「只同步了一半」的状态
        if (complete != null) {
            onEvent(LanSyncEvent.Receiving(complete.received.values.sumOf { it.size }))
            scope.launch { deliver(complete) }
        } else {
            onEvent(LanSyncEvent.Progress(bytes.size))
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
    }

    /** `POST /cancel`：对端取消会话（协议 §4.3）。 */
    private fun respondCancel(http: IHTTPSession): Response {
        val sessionId = http.parameters["sessionId"]?.firstOrNull()
        synchronized(sessionLock) {
            if (sessionId == null || session?.id == sessionId) session = null
        }
        onEvent(LanSyncEvent.Cancelled)
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
    }

    /* ---------------- 落库 ---------------- */

    /** 解码数据包并交给上层应用；任何失败都只上报事件，不向对端改判（响应早已发出）。 */
    private suspend fun deliver(complete: ActiveSession) {
        runCatching {
            val payload = complete.toPayload()
            onPayload(payload)
            onEvent(LanSyncEvent.Completed(payload.summary()))
        }.onFailure { error ->
            AppLogger.w(TAG, "应用同步数据失败: ${error.message}")
            onEvent(LanSyncEvent.Failed("应用数据失败：${error.message}"))
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun pinMatches(params: Map<String, List<String>>): Boolean {
        val expected = requiredPin()?.trim().orEmpty()
        if (expected.isEmpty()) return true
        return params["pin"]?.firstOrNull()?.trim() == expected
    }

    /**
     * 读取定长请求体。
     *
     * 刻意不走 `parseBody`：那会把内容落到临时文件，而这里只需要内存里的原始字节。
     * 必须读干请求体 —— 不读完就回响应会让客户端报「连接被重置」。
     *
     * 带 `Transfer-Encoding: chunked`（无 content-length）的请求直接拒绝：
     * 协议的两个客户端（本应用与 LocalSend）都用 OkHttp / Dart HTTP 发定长体，
     * 手工解 chunked 没有收益，只需保证 400 而不是把定长解析套上去读出坏数据。
     */
    private fun readBody(http: IHTTPSession, maxBytes: Long): ByteArray? {
        if (http.headers["content-length"] == null) return null
        val length = http.headers["content-length"]!!.toLongOrNull() ?: return null
        if (length < 0 || length > maxBytes) return null
        if (length == 0L) return ByteArray(0)
        val out = ByteArray(length.toInt())
        val input = http.inputStream
        var offset = 0
        while (offset < out.size) {
            val read = input.read(out, offset, out.size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == out.size) out else null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(status: Response.IStatus, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    /**
     * 422（校验和不匹配）。协议规定该状态码，但 NanoHTTPD 的 `Status` 枚举里没有
     * （见 `Status.lookup` 会返回 null），只能自带一个实现 —— 否则这里会退化成 500，
     * 发送端就无法把失败原因区分开。
     */
    private object ChecksumMismatch : Response.IStatus {
        override fun getRequestStatus(): Int = LanHttp.CHECKSUM_MISMATCH
        override fun getDescription(): String = "422 Checksum Mismatch"
    }

    /** 一次会话的运行时状态 */
    private class ActiveSession(
        val id: String,
        val tokens: Map<String, String>,
        val metas: Map<String, LanFileMeta>,
    ) {
        /** fileId -> 已收到的字节 */
        val received = LinkedHashMap<String, ByteArray>()

        fun toPayload(): LanSyncPayload = LanSyncPayload(
            settings = receivedJson(LanProtocol.FILE_SETTINGS),
            lists = receivedJson(LanProtocol.FILE_LISTS),
            playback = receivedJson(LanProtocol.FILE_PLAYBACK),
        )

        private fun receivedJson(fileName: String): String? {
            val fileId = metas.entries.firstOrNull { it.value.fileName == fileName }?.key ?: return null
            return received[fileId]?.let { String(it, Charsets.UTF_8) }
        }
    }

    companion object {
        private const val TAG = "LanSyncServer"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MIME_PLAINTEXT = "text/plain; charset=utf-8"

        private const val PATH_INFO = "/api/localsend/v2/info"
        private const val PATH_REGISTER = "/api/localsend/v2/register"
        private const val PATH_PREPARE_UPLOAD = "/api/localsend/v2/prepare-upload"
        private const val PATH_UPLOAD = "/api/localsend/v2/upload"
        private const val PATH_CANCEL = "/api/localsend/v2/cancel"

        /** 接受的数据包文件名（两个数据同步包 + 一个播放流转包） */
        private val SYNC_FILE_NAMES = setOf(
            LanProtocol.FILE_SETTINGS,
            LanProtocol.FILE_LISTS,
            LanProtocol.FILE_PLAYBACK,
        )

        /** prepare-upload / register 请求体上限（都是小 JSON） */
        private const val MAX_SMALL_BODY = 512L * 1024

        /**
         * 单个同步包上限。
         *
         * 设置与歌单 JSON 实测在几百 KB 量级；给到 8 MB 既能容纳超大收藏库，
         * 又能挡住「把本服务当文件接收端用」的滥用。
         */
        private const val MAX_PAYLOAD_BYTES = 8L * 1024 * 1024
    }
}

/**
 * 收到的局域网数据包（原始 JSON 文本；解码由上层负责，以便复用 SyncManager 的校验）。
 *
 * 三个字段互斥地对应三种数据包：前两个是覆盖式同步（写库），
 * [playback] 是一次性播放指令（立刻起播）。接收端按字段分派，不做合并。
 */
data class LanSyncPayload(
    val settings: String?,
    val lists: String?,
    val playback: String? = null,
) {
    fun summary(): String = buildList {
        if (settings != null) add("设置与音源")
        if (lists != null) add("歌单与数据")
        if (playback != null) add("播放流转")
    }.joinToString(" + ")
}

/** 接收端事件（界面展示用） */
sealed interface LanSyncEvent {
    /** 对端发起了同步，已接受 */
    data class Accepted(val from: String, val files: List<String>) : LanSyncEvent

    /** 传输中（单包字节数） */
    data class Progress(val bytes: Int) : LanSyncEvent

    /** 已收齐全部数据包，开始落库 */
    data class Receiving(val totalBytes: Int) : LanSyncEvent

    /** 同步完成 */
    data class Completed(val summary: String) : LanSyncEvent

    /** 被拒绝（对端发了非 DPmusic 数据 / 数据过大） */
    data class Rejected(val reason: String) : LanSyncEvent

    /** 失败 */
    data class Failed(val reason: String) : LanSyncEvent

    /** 对端取消了会话 */
    data object Cancelled : LanSyncEvent
}