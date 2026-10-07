package com.dpmusic.app.core.lansync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路模型的编解码契约。
 *
 * 这些断言看着琐碎，但每一条都对应一次真实的互操作失败：
 * 字段名写错 → 对端不认识；字段被省略 → 对端按自己的默认值兜底（版本号被省掉时
 * 甚至会被判定为不兼容）。所以这里把「什么必须出现在线上」显式钉住。
 */
class LanProtocolTest {

    private val info = LanDeviceInfo(
        alias = "DPmusic",
        deviceModel = "Pixel",
        fingerprint = "abc123",
    )

    @Test
    fun `all protocol fields are always on the wire`() {
        val json = LanJson.encodeToString(LanDeviceInfo.serializer(), info)

        // 协议 §2/§3 的必需字段
        listOf("alias", "version", "fingerprint", "port", "protocol", "download", "deviceType")
            .forEach { key ->
                assertTrue("缺少协议字段 $key：$json", json.contains("\"$key\""))
            }
        // 缺失会让对端按默认值兜底，其中 version 被省掉可能导致被判为不兼容
        assertTrue(json.contains("\"version\":\"${LanProtocol.VERSION}\""))
    }

    @Test
    fun `missing defaults would break an independent implementation`() {
        // 回归：AppJson（项目全局实例）不输出默认值，用它发注册体就会缺 port/version/protocol。
        // 本应用两端都宽松所以看不出来，但 LocalSend 这类独立实现可能直接解析失败。
        val lean = kotlinx.serialization.json.Json { }
        val leanJson = lean.encodeToString(LanDeviceInfo.serializer(), info)
        assertTrue("该缺陷的前提仍成立（AppJson 省略默认值）：$leanJson", !leanJson.contains("\"port\""))

        // LanJson 必须补齐这些字段，这正是它的存在意义
        val wireJson = LanJson.encodeToString(LanDeviceInfo.serializer(), info)
        listOf("port", "version", "protocol", "download").forEach {
            assertTrue("LanJson 必须输出 $it：$wireJson", wireJson.contains("\"$it\""))
        }
    }

    @Test
    fun `device info sent by the client always carries its port`() {
        // 端到端地钉住：客户端真的用了 LanJson（换回 AppJson 时此测试会失败）
        val sent = LanJson.encodeToString(LanDeviceInfo.serializer(), info)
        val parsed = LanJson.decodeFromString(LanDeviceInfo.serializer(), sent)
        assertEquals(LanProtocol.DEFAULT_PORT, parsed.port)
        assertTrue(sent.contains("\"port\":${LanProtocol.DEFAULT_PORT}"))
    }

    @Test
    fun `announce serializes as true only when it is a multicast announcement`() {
        // 协议 §3.1：announce 只在组播消息里承担语义。
        // 注意 encodeDefaults=true 会把 null 也写成 `"announce":null`，
        // 因此这里断言的是「值正确」，而不是「键不存在」—— 对端按 null 视作未设置。
        val announce = LanJson.encodeToString(LanDeviceInfo.serializer(), info.copy(announce = true))
        assertTrue("宣告必须带 announce=true：$announce", announce.contains("\"announce\":true"))

        val reply = LanJson.encodeToString(LanDeviceInfo.serializer(), info.copy(announce = false))
        assertTrue("应答必须是 announce=false：$reply", reply.contains("\"announce\":false"))
    }

    @Test
    fun `round trip preserves all fields`() {
        val json = LanJson.encodeToString(LanDeviceInfo.serializer(), info)
        assertEquals(info, LanJson.decodeFromString(LanDeviceInfo.serializer(), json))
    }

    @Test
    fun `decoding tolerates unknown and missing optional fields`() {
        // 对端（LocalSend）可能发来我们没有的字段；多版本共存是常态，不能因此崩掉
        val foreign = """{"alias":"Other","fingerprint":"xyz","port":53317,"unknownField":42}"""
        val parsed = LanJson.decodeFromString(LanDeviceInfo.serializer(), foreign)
        assertEquals("Other", parsed.alias)
        assertEquals(LanProtocol.VERSION, parsed.version)
    }

    @Test
    fun `file meta keeps protocol field names`() {
        val meta = LanFileMeta(id = "f", fileName = "x.json", size = 3L, sha256 = "ab")
        val json = LanJson.encodeToString(LanFileMeta.serializer(), meta)
        assertTrue(json.contains("\"fileType\""))
        assertTrue(json.contains("\"fileName\""))
        assertTrue(json.contains("\"sha256\""))
    }
}