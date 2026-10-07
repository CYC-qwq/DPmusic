package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.PlayQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QQ 音质徽标映射测试。
 *
 * 回归的是「**榜单来源的 QQ 歌曲没有音质徽标**」这个线上问题：
 * 榜单接口不返回嵌套 `file` 对象，而是把体积平铺在歌曲对象上（`sizeflac` 等），
 * 早期映射只认嵌套形态，于是明明有无损也判成「无信息」。
 *
 * 用例里的数字取自 2026-10-01 实测响应（榜单「茶汤」、搜索「晴天」）。
 */
class QqQualityTest {

    private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

    @Test
    fun `搜索接口的嵌套 file 形态`() {
        val el = json("""{"title":"晴天","file":{"size_128":4317292,"size_320":10792943,"size_flac":55397039}}""")
        assertEquals(PlayQuality.LOSSLESS.id, qqMaxQualityOf(el))
    }

    @Test
    fun `嵌套 file 里有 Hi-Res 时取最高档`() {
        val el = json("""{"file":{"size_hires":12345678,"size_flac":55397039,"size_320":10792943}}""")
        assertEquals(PlayQuality.HIRES.id, qqMaxQualityOf(el))
    }

    @Test
    fun `榜单接口的平铺形态（线上问题本体）`() {
        val el = json(
            """{"songname":"茶汤","interval":253,"size128":4909144,"size320":12272540,
               "sizeflac":36316570,"sizeape":0,"size5_1":0,"sizeogg":0}""",
        )
        assertEquals(PlayQuality.LOSSLESS.id, qqMaxQualityOf(el))
    }

    @Test
    fun `平铺形态逐档降级`() {
        assertEquals(
            PlayQuality.HIGH.id,
            qqMaxQualityOf(json("""{"songname":"A","size320":12272540,"size128":4909144}""")),
        )
        assertEquals(
            PlayQuality.STANDARD.id,
            qqMaxQualityOf(json("""{"songname":"B","size128":4909144}""")),
        )
    }

    @Test
    fun `平铺的 ape 也算无损`() {
        assertEquals(
            PlayQuality.LOSSLESS.id,
            qqMaxQualityOf(json("""{"sizeape":36316570,"size320":12272540}""")),
        )
    }

    @Test
    fun `没有任何体积信息时返回空串`() {
        assertEquals("", qqMaxQualityOf(json("""{"songname":"未知","albummid":"001"}""")))
    }

    @Test
    fun `体积字段存在但全为 0 时同样视为无信息`() {
        val el = json("""{"sizeflac":0,"sizeape":0,"size320":0,"size128":0}""")
        assertEquals("", qqMaxQualityOf(el))
    }

    @Test
    fun `嵌套形态优先于平铺形态`() {
        // 两者同时存在时（理论上不会），以嵌套 file 为准
        val el = json("""{"file":{"size_hires":1},"size128":4909144}""")
        assertEquals(PlayQuality.HIRES.id, qqMaxQualityOf(el))
    }

    @Test
    fun `5_1 与 ogg 不参与档位判定`() {
        // 这两个体积没有对应的 PlayQuality 档位，不应被误判成无损/高品
        val el = json("""{"size5_1":99000000,"sizeogg":5000000}""")
        assertEquals("", qqMaxQualityOf(el))
    }
}