package com.dpmusic.app.core.lansync

import com.dpmusic.app.core.net.AppJson
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LanSyncServer] 的端到端测试：起真实 NanoHTTPD，用真实 HTTP 客户端打过去。
 *
 * 为什么不做纯函数测试：这个类最容易错的不是算法，而是**与 NanoHTTPD 的集成**
 * —— 请求体怎么读、非标准状态码怎么回、路由怎么匹配。这些只有真起服务才能验证。
 */
class LanSyncServerTest {

    private lateinit var server: LanSyncServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** 收到的载荷（服务端在 IO 协程里回调，故用并发安全容器） */
    private val received = CopyOnWriteArrayList<LanSyncPayload>()

    /** 收到的对端自述（校验服务端有没有把我们的注册体读干净） */
    private val events = CopyOnWriteArrayList<LanSyncEvent>()

    private val selfInfo = LanDeviceInfo(
        alias = "Test Device",
        fingerprint = "self-fingerprint",
        port = PORT,
    )

    private val requiredPin: String? = "1234"

    @Before
    fun setUp() {
        server = LanSyncServer(
            port = PORT,
            selfInfo = { selfInfo },
            requiredPin = { requiredPin },
            onPayload = { received += it },
            onEvent = { events += it },
        )
        server.start(5_000, false)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /* ---------------- 路由与发现 ---------------- */

    @Test
    fun `info returns self description`() {
        val body = client.newCall(get("/api/localsend/v2/info")).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body?.string().orEmpty()
        }
        val info = AppJson.decodeFromString<LanDeviceInfo>(body)
        assertEquals("Test Device", info.alias)
        assertEquals("self-fingerprint", info.fingerprint)
    }

    @Test
    fun `register replies with self description`() {
        val payload = AppJson.encodeToString(LanDeviceInfo(alias = "Peer", fingerprint = "peer-fp"))
        val resp = client.newCall(post("/api/localsend/v2/register", payload)).execute()
        resp.use {
            assertEquals(200, it.code)
            val info = AppJson.decodeFromString<LanDeviceInfo>(it.body!!.string())
            assertEquals("self-fingerprint", info.fingerprint)
        }
    }

    @Test
    fun `unknown route returns 404`() {
        client.newCall(get("/api/localsend/v2/nope")).execute().use {
            assertEquals(404, it.code)
        }
    }

    /* ---------------- prepare-upload 的校验 ---------------- */

    @Test
    fun `prepare rejects wrong pin with 401`() {
        prepare(mapOf(LanProtocol.FILE_SETTINGS to settingsFile("{}".toByteArray())), pin = "9999")
            .use { assertEquals(401, it.code) }
    }

    @Test
    fun `prepare rejects non dpmusic file with 403`() {
        val alien = LanFileMeta(id = "x", fileName = "photo.jpg", size = 10L, sha256 = null)
        // 与合法包混在一起：整包拒绝，不做部分接受
        val files = mapOf(
            "x" to alien,
            LanProtocol.FILE_SETTINGS to settingsFile("{}".toByteArray()),
        )
        prepare(files).use { assertEquals(403, it.code) }
        assertTrue(events.any { it is LanSyncEvent.Rejected })
    }

    @Test
    fun `prepare rejects oversized payload with 403`() {
        // 声明一个超过 8MB 上限的包（不必真传那么大的体）
        val huge = LanFileMeta(
            id = LanProtocol.FILE_LISTS,
            fileName = LanProtocol.FILE_LISTS,
            size = 9L * 1024 * 1024,
            sha256 = null,
        )
        prepare(mapOf(LanProtocol.FILE_LISTS to huge)).use { assertEquals(403, it.code) }
    }

    @Test
    fun `prepare returns tokens for accepted files`() {
        val settings = settingsFile("""{"version":1}""".toByteArray())
        prepare(mapOf(LanProtocol.FILE_SETTINGS to settings)).use { resp ->
            assertEquals(200, resp.code)
            val parsed = AppJson.decodeFromString<LanPrepareResponse>(resp.body!!.string())
            assertTrue(parsed.sessionId.isNotBlank())
            assertEquals(setOf(LanProtocol.FILE_SETTINGS), parsed.files.keys)
        }
    }

    @Test
    fun `second prepare is blocked by 409 while a session is active`() {
        val settings = settingsFile("{}".toByteArray())
        prepare(mapOf(LanProtocol.FILE_SETTINGS to settings)).use { assertEquals(200, it.code) }
        // 上一个会话未取消、未传完，仍占位
        prepare(mapOf(LanProtocol.FILE_SETTINGS to settings)).use { assertEquals(409, it.code) }
    }

    @Test
    fun `prepare on empty file map is rejected`() {
        // 协议 §4.1：无可传文件应回 204；但空 files 连「要传什么」都没说清，
        // 本实现按 403 拒绝（不做部分接受），这里把该行为钉住
        prepare(emptyMap()).use { assertEquals(403, it.code) }
    }

    /* ---------------- upload ---------------- */

    @Test
    fun `prepare rejects mixing playback handoff with data sync`() {
        // 播放流转是一次性指令，数据同步是覆盖写库：混在一个会话里，
        // 接收端要么把播放指令当同步写入，要么让同步只做一半。
        val playback = LanFileMeta(
            id = LanProtocol.FILE_PLAYBACK,
            fileName = LanProtocol.FILE_PLAYBACK,
            size = 2L,
            sha256 = sha256Hex("{}".toByteArray()),
        )
        val files = mapOf(
            LanProtocol.FILE_PLAYBACK to playback,
            LanProtocol.FILE_SETTINGS to settingsFile("{}".toByteArray()),
        )
        prepare(files).use { assertEquals(403, it.code) }
        assertTrue(events.any { it is LanSyncEvent.Rejected })
    }

    @Test
    fun `prepare accepts a lone playback payload`() {
        val meta = LanFileMeta(
            id = LanProtocol.FILE_PLAYBACK,
            fileName = LanProtocol.FILE_PLAYBACK,
            size = 2L,
            sha256 = sha256Hex("{}".toByteArray()),
        )
        prepare(mapOf(LanProtocol.FILE_PLAYBACK to meta)).use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `upload applies both payloads after both files arrive`() = runBlocking {
        val settingsJson = """{"version":1,"lastModified":111,"data":{}}"""
        val listsJson = """{"version":1,"lastModified":222,"data":{},"downloadTasks":[]}"""
        val settingsBytes = settingsJson.toByteArray()
        val listsBytes = listsJson.toByteArray()

        val accepted = startSession(
            mapOf(
                LanProtocol.FILE_SETTINGS to settingsFile(settingsBytes),
                LanProtocol.FILE_LISTS to listsFile(listsBytes),
            ),
        )

        // 只传第一个文件时不应落库
        assertNull(upload(accepted, LanProtocol.FILE_SETTINGS, settingsBytes))
        assertTrue("只收齐一个文件时不该应用", received.isEmpty())

        assertNull(upload(accepted, LanProtocol.FILE_LISTS, listsBytes))

        withTimeout(3_000) {
            while (received.isEmpty()) delay(20)
        }
        val payload = received.first()
        assertEquals(settingsJson, payload.settings)
        assertEquals(listsJson, payload.lists)
    }

    @Test
    fun `upload with wrong token is rejected`() {
        val bytes = "{}".toByteArray()
        val accepted = startSession(mapOf(LanProtocol.FILE_SETTINGS to settingsFile(bytes)))
        val bad = LanPrepareResponse(
            sessionId = accepted.sessionId,
            files = mapOf(LanProtocol.FILE_SETTINGS to "wrong"),
        )
        val error = upload(bad, LanProtocol.FILE_SETTINGS, bytes)
        assertTrue(error.orEmpty().contains("403"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `upload with mismatched checksum returns 422`() {
        val bytes = "actual".toByteArray()
        val accepted = startSession(mapOf(LanProtocol.FILE_SETTINGS to settingsFile(bytes)))
        // 故意发不同内容：服务端按协议应回 422，而不是 500
        val error = upload(accepted, LanProtocol.FILE_SETTINGS, "tampered".toByteArray())
        assertTrue("期望 422，实际：$error", error.orEmpty().contains("422"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `cancel frees the session`() {
        val bytes = "{}".toByteArray()
        val accepted = startSession(mapOf(LanProtocol.FILE_SETTINGS to settingsFile(bytes)))
        client.newCall(
            post("/api/localsend/v2/cancel?sessionId=${accepted.sessionId}", ""),
        ).execute().use { assertEquals(200, it.code) }

        // 取消后应能重新协商
        prepare(mapOf(LanProtocol.FILE_SETTINGS to settingsFile(bytes))).use {
            assertEquals(200, it.code)
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun settingsFile(bytes: ByteArray) = LanFileMeta(
        id = LanProtocol.FILE_SETTINGS,
        fileName = LanProtocol.FILE_SETTINGS,
        size = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
    )

    private fun settingsFile(json: String) = settingsFile(json.toByteArray())

    private fun listsFile(bytes: ByteArray) = LanFileMeta(
        id = LanProtocol.FILE_LISTS,
        fileName = LanProtocol.FILE_LISTS,
        size = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
    )

    private fun prepare(files: Map<String, LanFileMeta>, pin: String? = requiredPin): Response {
        val body = AppJson.encodeToString(LanPrepareRequest(info = selfInfo, files = files))
        val url = buildString {
            append(base()).append("/api/localsend/v2/prepare-upload")
            if (pin != null) append("?pin=").append(pin)
        }
        return client.newCall(
            Request.Builder().url(url).post(body.toRequestBody(JSON)).build(),
        ).execute()
    }

    /** 协商成功并返回 sessionId + tokens；失败直接让测试报错。 */
    private fun startSession(files: Map<String, LanFileMeta>): LanPrepareResponse {
        prepare(files).use { resp ->
            assertEquals("协商应成功，实际 ${resp.code}", 200, resp.code)
            return AppJson.decodeFromString<LanPrepareResponse>(resp.body!!.string())
        }
    }

    /** @return null 表示 200，否则为状态码字符串 */
    private fun upload(accepted: LanPrepareResponse, fileId: String, bytes: ByteArray): String? {
        val token = accepted.files.getValue(fileId)
        val url = "${base()}/api/localsend/v2/upload" +
            "?sessionId=${accepted.sessionId}&fileId=$fileId&token=$token"
        return client.newCall(
            Request.Builder().url(url).post(bytes.toRequestBody(OCTET)).build(),
        ).execute().use { if (it.isSuccessful) null else "HTTP ${it.code}" }
    }

    private fun get(path: String) = Request.Builder().url(base() + path).get().build()

    private fun post(path: String, body: String) = Request.Builder()
        .url(base() + path)
        .post(body.toRequestBody(JSON))
        .build()

    private fun base() = "http://127.0.0.1:$PORT"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PORT = 45317
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}
