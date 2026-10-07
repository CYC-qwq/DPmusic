package com.dpmusic.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 站标题清洗 / 时长解析测试。
 *
 * 用**真实抓取的标题**做样例 —— 这些字符串是实测拿到的，
 * 不是构造出来的理想输入，所以能真正覆盖「视频站当音乐源」的脏数据。
 */
class BiliTextTest {

    /* ---------------- 标题清洗 ---------------- */

    @Test
    fun `剥离搜索高亮标签 em`() {
        // 实测搜索返回：标题里搜索词被 <em class="keyword"> 包起来
        val raw = """【𝐇𝐢-𝐑𝐞𝐬无损音质】｜《<em class="keyword">晴天</em>》- <em class="keyword">周杰伦</em> -‘故事的小黄花’"""
        val cleaned = BiliText.cleanTitle(raw)
        assertTrue("不该残留 HTML 标签：$cleaned", !cleaned.contains("<"))
        assertTrue("关键词应保留：$cleaned", cleaned.contains("晴天"))
        assertTrue("歌手应保留：$cleaned", cleaned.contains("周杰伦"))
    }

    @Test
    fun `去掉方括号前缀噪声`() {
        assertEquals("青花瓷", BiliText.cleanTitle("【无损音质】青花瓷"))
        assertEquals("青花瓷", BiliText.cleanTitle("【4K】青花瓷"))
    }

    @Test
    fun `去掉圆括号后缀噪声`() {
        assertEquals("稻香", BiliText.cleanTitle("稻香 (Official MV)"))
        assertEquals("稻香", BiliText.cleanTitle("稻香（官方MV）"))
    }

    @Test
    fun `解码 HTML 实体`() {
        assertEquals("A&B", BiliText.cleanTitle("A&amp;B"))
        // 引号实体：源码里以拼接形式书写，测试同样拼接，避免转义歧义
        assertEquals("他说" + 0x22.toChar() + "你好" + 0x22.toChar(), BiliText.cleanTitle("他说&" + "quot;你好&" + "quot;"))
    }

    @Test
    fun `无噪声的标题原样保留`() {
        assertEquals("晴天", BiliText.cleanTitle("晴天"))
        assertEquals("周杰伦 - 晴天", BiliText.cleanTitle("周杰伦 - 晴天"))
    }

    @Test
    fun `空标题不崩`() {
        assertEquals("", BiliText.cleanTitle(""))
    }

    /* ---------------- 时长解析 ---------------- */

    @Test
    fun `分秒时长解析为毫秒`() {
        assertEquals(4 * 60_000L + 30_000L, BiliText.parseDuration("4:30"))
    }

    @Test
    fun `时分秒时长解析为毫秒`() {
        assertEquals(3600_000L + 2 * 60_000L + 3_000L, BiliText.parseDuration("1:02:03"))
    }

    @Test
    fun `非法时长返回 0`() {
        assertEquals(0L, BiliText.parseDuration(null))
        assertEquals(0L, BiliText.parseDuration(""))
        assertEquals(0L, BiliText.parseDuration("abc"))
    }

    /* ---------------- 封面 URL ---------------- */

    @Test
    fun `协议相对封面补全为 https`() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/x.jpg",
            BiliText.normalizePic("//i0.hdslb.com/bfs/archive/x.jpg"),
        )
    }

    @Test
    fun `已是完整 URL 的封面原样返回`() {
        assertEquals("https://i0.hdslb.com/x.jpg", BiliText.normalizePic("https://i0.hdslb.com/x.jpg"))
    }

    @Test
    fun `空封面返回空串`() {
        assertEquals("", BiliText.normalizePic(null))
        assertEquals("", BiliText.normalizePic(""))
    }
}