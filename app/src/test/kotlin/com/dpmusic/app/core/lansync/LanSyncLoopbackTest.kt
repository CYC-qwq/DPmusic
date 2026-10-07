package com.dpmusic.app.core.lansync

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.AppJson
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 发送端与接收端的**回路测试**：用 [LanHttpClient] 打真实启动的 [LanSyncServer]。
 *
 * 单独放在一个类里，是因为它验证的是「两端对协议的实现是否一致」——
 * 字段名、sha256 大小写、状态码映射、token 传递。单看任何一端都发现不了这类偏差，
 * 而真机上发现它们的代价是「同步失败但不知道哪一步错了」。
 */
class LanSyncLoopbackTest {

    private lateinit var server: LanSyncServer
    private val applied = CopyOnWriteArrayList<LanSyncPayload>()
    private val self = LanDeviceInfo(alias = "Sender", fingerprint = "sender-fp", port = PORT)

    @Before
    fun setUp() {
        server = LanSyncServer(
            port = PORT,
            selfInfo = { LanDeviceInfo(alias = "Receiver", fingerprint = "receiver-fp", port = PORT) },
            requiredPin = { null },
            onPayload = { applied += it },
            onEvent = { },
        )
        server.start(5_000, false)
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun `prepare reports pin required when receiver demands one`() = runBlocking {
        val pinServer = LanSyncServer(
            port = PORT + 1,
            selfInfo = { self },
            requiredPin = { "secret" },
            onPayload = { },
            onEvent = { },
        )
        pinServer.start(5_000, false)
        try {
            val outcome = LanHttpClient.prepareUpload(
                host = "127.0.0.1",
                port = PORT + 1,
                request = LanPrepareRequest(self, mapOf("f" to meta(LanProtocol.FILE_SETTINGS, "{}".toByteArray()))),
                pin = null,
            )
            assertEquals(LanPrepareOutcome.PinRequired, outcome)
        } finally {
            pinServer.stop()
        }
    }

    @Test
    fun `full send flow delivers both payloads`() = runBlocking {
        val settingsJson = """{"version":1,"lastModified":7,"data":{"a":"1"}}"""
        val listsJson = """{"version":1,"lastModified":8,"data":{},"downloadTasks":[]}"""
        val files = mapOf(
            LanProtocol.FILE_SETTINGS to settingsJson.toByteArray(),
            LanProtocol.FILE_LISTS to listsJson.toByteArray(),
        )

        val outcome = LanHttpClient.prepareUpload(
            host = "127.0.0.1",
            port = PORT,
            request = LanPrepareRequest(self, files.mapValues { (name, bytes) -> meta(name, bytes) }),
        )
        val accepted = outcome as? LanPrepareOutcome.Accepted
        assertNotNull("协商应被接受，实际：$outcome", accepted)
        assertEquals(files.keys, accepted!!.tokens.keys)

        for ((name, bytes) in files) {
            val error = LanHttpClient.upload(
                host = "127.0.0.1",
                port = PORT,
                sessionId = accepted.sessionId,
                fileId = name,
                token = accepted.tokens.getValue(name),
                bytes = bytes,
            )
            assertEquals("上传 $name 不应失败", null, error)
        }

        withTimeout(3_000) { while (applied.isEmpty()) delay(20) }
        val payload = applied.first()
        // 逐字节一致：任何 JSON 转义 / 字符集上的偏差都会在这里暴露
        assertEquals(settingsJson, payload.settings)
        assertEquals(listsJson, payload.lists)
    }

    @Test
    fun `prepare reports 409 when receiver is busy`() = runBlocking {
        val first = LanHttpClient.prepareUpload(
            host = "127.0.0.1",
            port = PORT,
            request = LanPrepareRequest(self, mapOf("f" to meta(LanProtocol.FILE_SETTINGS, "{}".toByteArray()))),
        )
        assertTrue(first is LanPrepareOutcome.Accepted)

        val second = LanHttpClient.prepareUpload(
            host = "127.0.0.1",
            port = PORT,
            request = LanPrepareRequest(self, mapOf("f" to meta(LanProtocol.FILE_SETTINGS, "{}".toByteArray()))),
        )
        assertEquals(LanPrepareOutcome.Busy, second)
    }

    @Test
    fun `playback handoff survives the full protocol round trip`() = runBlocking {
        // 流转包走的是与数据同步完全相同的三段协议（prepare-upload → upload）。
        // 这条测试把「编码 → 协商 → 上传 → 服务端解码」整条链路串起来，
        // 验证队列与毫秒级进度在线上没有丢失或错位。
        val payload = LanPlaybackPayload(
            songs = listOf(
                Song(
                    id = "12345",
                    platform = MusicPlatform.WY,
                    title = "测试曲目",
                    artist = "测试歌手",
                    album = "测试专辑",
                    durationMs = 261_000L,
                    coverUrl = "https://example.com/cover.jpg",
                ),
            ),
            currentIndex = 0,
            positionMs = 133_500L,
            sentAtMs = 1_700_000_000_000L,
            isPlaying = true,
            fromAlias = "手机 A",
        )
        val bytes = LanJson.encodeToString(LanPlaybackPayload.serializer(), payload).toByteArray()
        val name = LanProtocol.FILE_PLAYBACK

        val outcome = LanHttpClient.prepareUpload(
            host = "127.0.0.1",
            port = PORT,
            request = LanPrepareRequest(self, mapOf(name to meta(name, bytes))),
        )
        val accepted = outcome as? LanPrepareOutcome.Accepted
        assertNotNull("流转包协商应被接受，实际：$outcome", accepted)

        val error = LanHttpClient.upload(
            host = "127.0.0.1",
            port = PORT,
            sessionId = accepted!!.sessionId,
            fileId = name,
            token = accepted.tokens.getValue(name),
            bytes = bytes,
        )
        assertEquals("上传流转包不应失败", null, error)

        withTimeout(3_000) { while (applied.isEmpty()) delay(20) }
        val received = applied.first()
        // 关键：playback 字段必须独立于两个同步字段到达，且能逐字段还原
        assertEquals(null, received.settings)
        assertEquals(null, received.lists)
        val decoded = LanJson.decodeFromString(LanPlaybackPayload.serializer(), received.playback!!)
        assertEquals(payload, decoded)
        assertEquals(133_500L, decoded.positionMs)
    }

    @Test
    fun `prepare reports failure when nothing is listening`() = runBlocking {
        // 未监听的端口：必须是可展示的失败，而不是抛异常
        val outcome = LanHttpClient.prepareUpload(
            host = "127.0.0.1",
            port = 45999,
            request = LanPrepareRequest(self, mapOf("f" to meta(LanProtocol.FILE_SETTINGS, "{}".toByteArray()))),
        )
        assertTrue("期望 Failed，实际：$outcome", outcome is LanPrepareOutcome.Failed)
        assertTrue(outcome is LanPrepareOutcome.Failed && outcome.message.isNotBlank())
    }

    @Test
    fun `register against our own server returns its description`() = runBlocking {
        val info = LanHttpClient.register("127.0.0.1", PORT, self)
        assertNotNull(info)
        assertEquals("receiver-fp", info!!.fingerprint)
    }

    @Test
    fun `subnet candidates cover a slash 24 without network and broadcast`() {
        val candidates = LanHttpClient.subnetCandidates("192.168.1.37")
        assertEquals(254, candidates.size)
        assertEquals("192.168.1.1", candidates.first())
        assertEquals("192.168.1.254", candidates.last())
        assertTrue("192.168.1.0" !in candidates)
        assertTrue("192.168.1.255" !in candidates)
    }

    @Test
    fun `subnet candidates handle other prefixes correctly`() {
        // /26 = 64 个地址，主机位 6 位；192.168.1.70 属于 192.168.1.64/26
        val slash26 = LanHttpClient.subnetCandidates("192.168.1.70", 26)
        assertEquals(62, slash26.size)
        assertEquals("192.168.1.65", slash26.first())
        assertEquals("192.168.1.126", slash26.last())

        // /30 只有 4 个地址、2 个可用主机
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), LanHttpClient.subnetCandidates("10.0.0.1", 30))

        // 前缀过短（/16）时按 /24 处理，绝不展开六万多个地址
        assertEquals(254, LanHttpClient.subnetCandidates("10.1.2.3", 16).size)

        // 非法输入返回空表而不是抛异常
        assertTrue(LanHttpClient.subnetCandidates("not-an-ip").isEmpty())
        assertTrue(LanHttpClient.subnetCandidates("192.168.1.999").isEmpty())
    }

    @Test
    fun `cellular interface is excluded from scanning`() {
        // 实测：手机的蜂窝接口会拿到 10.x（RFC1918），isSiteLocalAddress 为真、
        // 但前缀是 /32。若不额外过滤，扫描会朝运营商网段发几百个请求。
        val addresses = listOf(
            nic("wlan0", "192.168.0.100", 24, siteLocal = true),
            nic("rmnet_data4", "10.26.110.125", 32, siteLocal = true),
            nic("tun0", "172.19.0.1", 28, siteLocal = true, pointToPoint = true),
            nic("lo", "127.0.0.1", 8, siteLocal = false, loopback = true),
            nic("rmnet_data0", "100.64.1.5", 32, siteLocal = false),
        )
        val scannable = LanHttpClient.scannableLanAddresses(addresses)
        assertEquals(listOf("192.168.0.100"), scannable.map { it.host })
        assertEquals(24, scannable.single().prefixLength)
    }

    @Test
    fun `scan ports include default and configured`() {
        assertEquals(listOf(53317, 12345), LanHttpClient.scanPorts(12345))
        // 配置成默认端口时不重复
        assertEquals(listOf(53317), LanHttpClient.scanPorts(53317))
    }

    private fun nic(
        name: String,
        host: String,
        prefix: Int,
        siteLocal: Boolean = true,
        loopback: Boolean = false,
        pointToPoint: Boolean = false,
        up: Boolean = true,
    ) = NicAddress(
        nicName = name,
        host = host,
        prefixLength = prefix,
        isUp = up,
        isLoopback = loopback,
        isPointToPoint = pointToPoint,
        isSiteLocal = siteLocal,
    )

    @Test
    fun `announce is only present in multicast messages`() {
        // 协议里 announce 只存在于组播消息；HTTP 注册体带上它是多余噪声
        assertTrue(!AppJson.encodeToString(self).contains("announce"))
    }

    private fun meta(name: String, bytes: ByteArray) = LanFileMeta(
        id = name,
        fileName = name,
        size = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) },
    )

    private companion object {
        const val PORT = 45318
    }
}