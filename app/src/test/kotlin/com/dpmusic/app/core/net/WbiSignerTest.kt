package com.dpmusic.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WBI 签名交叉校验测试。
 *
 * 期望值来自**独立实现（Python）**：先用 Python 算出签名去请求真实 B 站接口、
 * 确认返回 `code: 0`（可用），再把同样的输入与结果固化到这里，用来钉住 Kotlin 实现。
 * 这样即使将来重构签名代码，只要输出变了就会立刻失败 —— 不会等到线上出现 -403 才发现。
 */
class WbiSignerTest {

    /** 取自真实 `nav` 响应的 `wbi_img.img_url` / `sub_url`（实测值） */
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `mixinKey 与参考实现一致`() {
        // Python: ''.join((img+sub)[i] for i in MIXIN)[:32]
        assertEquals("ea1db124af3c7062474693fa704f4ff8", WbiSigner.mixinKeyOf(imgKey, subKey))
    }

    @Test
    fun `签名输出与参考实现一致（固定 wts）`() {
        val params = mapOf(
            "search_type" to "video",
            "keyword" to "晴天",
            "page" to "1",
            "page_size" to "20",
        )
        val signed = WbiSigner.sign(params, WbiSigner.mixinKeyOf(imgKey, subKey), wts = 1700000000L)

        // 参数按键名升序；中文按 UTF-8 百分号编码
        assertEquals(
            "keyword=%E6%99%B4%E5%A4%A9&page=1&page_size=20&search_type=video" +
                "&w_rid=25ba73e1c121dec48043833c1e751a16&wts=1700000000",
            signed,
        )
    }

    @Test
    fun `wts 变化则签名变化（确认时间戳真的参与）`() {
        val params = mapOf("bvid" to "BV1BZbSzZEGT")
        val mk = WbiSigner.mixinKeyOf(imgKey, subKey)
        val a = WbiSigner.sign(params, mk, wts = 1700000000L)
        val b = WbiSigner.sign(params, mk, wts = 1735689600L)
        assertEquals("bvid=BV1BZbSzZEGT&w_rid=81e118d5835219c24ffbab307a920df8&wts=1700000000", a)
        assertEquals("bvid=BV1BZbSzZEGT&w_rid=e7685d887341733cff0f6a52ee0f7f2e&wts=1735689600", b)
    }

    @Test
    fun `参数值里的过滤字符被剔除后再参与签名`() {
        val mk = WbiSigner.mixinKeyOf(imgKey, subKey)
        // 两组的『被签名值』相同（撇号被过滤），因此 w_rid 必须相同
        val withApos = WbiSigner.sign(mapOf("a" to "x'y"), mk, wts = 1700000000L)
        val without = WbiSigner.sign(mapOf("a" to "xy"), mk, wts = 1700000000L)
        assertEquals(
            withApos.substringAfter("w_rid=").substringBefore("&"),
            without.substringAfter("w_rid=").substringBefore("&"),
        )
    }

    @Test
    fun `md5 十六进制小写且定长`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", WbiSigner.md5(""))
        assertEquals(32, WbiSigner.md5("abc").length)
    }
}