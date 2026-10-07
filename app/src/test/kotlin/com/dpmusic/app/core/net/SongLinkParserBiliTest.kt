package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 链接解析：B 站新通道的识别，以及**确认没有破坏既有平台**。
 *
 * 这几条直接影响「粘贴一个分享链接能不能打开」——最容易被新增平台改坏的路径。
 */
class SongLinkParserBiliTest {

    @Test
    fun `识别 B 站标准视频链接`() {
        val parsed = SongLinkParser.parse("https://www.bilibili.com/video/BV1BZbSzZEGT")
        assertEquals(MusicPlatform.BB, parsed?.platform)
        assertEquals("BV1BZbSzZEGT", parsed?.songId)
    }

    @Test
    fun `识别带参数的 B 站链接`() {
        val parsed = SongLinkParser.parse("https://www.bilibili.com/video/BV1BZbSzZEGT/?p=1&t=10")
        assertEquals(MusicPlatform.BB, parsed?.platform)
        assertEquals("BV1BZbSzZEGT", parsed?.songId)
    }

    @Test
    fun `识别分享文案里夹带的 B 站链接`() {
        val text = "【【Hi-Res无损】晴天-周杰伦】 https://www.bilibili.com/video/BV1BZbSzZEGT/ 一起看！"
        val parsed = SongLinkParser.parse(text)
        assertEquals(MusicPlatform.BB, parsed?.platform)
        assertEquals("BV1BZbSzZEGT", parsed?.songId)
    }

    @Test
    fun `bvid 参数形式也可识别`() {
        val parsed = SongLinkParser.parse("https://www.bilibili.com/medialist/play?bvid=BV1BZbSzZEGT")
        assertEquals(MusicPlatform.BB, parsed?.platform)
    }

    /* ---------------- 回归：既有平台不受影响 ---------------- */

    @Test
    fun `既有平台链接仍正确识别`() {
        assertEquals(
            MusicPlatform.WY,
            SongLinkParser.parse("https://music.163.com/#/song?id=186016")?.platform,
        )
        assertEquals(
            MusicPlatform.QQ,
            SongLinkParser.parse("https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV")?.platform,
        )
        assertEquals(
            MusicPlatform.QS,
            SongLinkParser.parse("https://www.douyin.com/qishui/song/6696534426169378817")?.platform,
        )
    }

    @Test
    fun `无法识别的文本返回 null`() {
        assertNull(SongLinkParser.parse("随便一段没有链接的文字"))
        assertNull(SongLinkParser.parse(""))
    }
}