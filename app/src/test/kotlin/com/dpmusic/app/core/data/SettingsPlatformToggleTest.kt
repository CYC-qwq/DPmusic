package com.dpmusic.app.core.data

import com.dpmusic.app.core.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音源开关语义（汽水 / B 站可关）与「有效默认平台」回退。
 *
 * 这些是纯函数，但决定了几处 UI 的可见范围（平台选择 / 榜单 / 解析链路 / 跨平台兜底），
 * 一旦回归会导致「关掉的音源仍被使用」这类静默错误，故单测固化。
 */
class SettingsPlatformToggleTest {

    @Test
    fun `默认：常驻三平台 + 汽水开启、B站关闭`() {
        val settings = AppSettings()
        val enabled = settings.enabledPlatforms()
        assertTrue(MusicPlatform.WY in enabled)
        assertTrue(MusicPlatform.QQ in enabled)
        assertTrue(MusicPlatform.KG in enabled)
        // 汽水默认开启（匿名可取免费歌全曲）
        assertTrue(MusicPlatform.QS in enabled)
        // B 站默认关闭（强偏好型音源）
        assertFalse(MusicPlatform.BB in enabled)
    }

    @Test
    fun `开启 B 站后进入启用列表，顺序仍与枚举一致`() {
        val enabled = AppSettings(biliEnabled = true).enabledPlatforms()
        assertTrue(MusicPlatform.BB in enabled)
        assertEquals(MusicPlatform.entries.filter { it in enabled }, enabled)
    }

    @Test
    fun `关闭汽水后从启用列表移除`() {
        assertFalse(MusicPlatform.QS in AppSettings(qishuiEnabled = false).enabledPlatforms())
    }

    @Test
    fun `isPlatformEnabled 与 enabledPlatforms 一致`() {
        val settings = AppSettings(qishuiEnabled = false, biliEnabled = true)
        MusicPlatform.entries.forEach { platform ->
            assertEquals(platform in settings.enabledPlatforms(), settings.isPlatformEnabled(platform))
        }
    }

    @Test
    fun `有效默认平台：默认平台可用时原样返回`() {
        val settings = AppSettings(defaultPlatform = MusicPlatform.BB, biliEnabled = true)
        assertEquals(MusicPlatform.BB, settings.effectiveDefaultPlatform())
    }

    @Test
    fun `有效默认平台：默认平台被关闭时回退到第一个启用平台`() {
        // 默认平台是 B 站，但 B 站关了 → 回退到第一个启用平台（枚举序首个 = 网易云）
        val settings = AppSettings(defaultPlatform = MusicPlatform.BB, biliEnabled = false)
        assertEquals(MusicPlatform.WY, settings.effectiveDefaultPlatform())
    }

    @Test
    fun `有效默认平台：不回写用户已保存的默认平台`() {
        // 回退只影响消费侧取值，原始字段保持用户选择，重开音源后应恢复
        val settings = AppSettings(defaultPlatform = MusicPlatform.BB, biliEnabled = false)
        assertEquals(MusicPlatform.BB, settings.defaultPlatform)
    }

    /* ---------------- 榜单可用平台（开关 × 内容形态） ---------------- */

    @Test
    fun `五个音源都提供榜单内容（形态各异）`() {
        // 网易云/QQ/酷狗=常规榜单；汽水=场景电台；B站=音乐区排行 —— 都有内容
        MusicPlatform.entries.forEach { p ->
            assertTrue("${p.label} 应提供榜单内容", p.hasToplist)
        }
    }

    @Test
    fun `榜单可选平台随开关过滤，但包含汽水与B站`() {
        // B 站开启后，榜单页应能选到全部 5 个音源（汽水/B站各有其榜单形态）
        val enabled = AppSettings(biliEnabled = true).toplistPlatforms()
        assertEquals(MusicPlatform.entries.toList(), enabled)
    }

    @Test
    fun `榜单可选平台尊重开关：关掉的音源不出现`() {
        // 汽水关 → 榜单页不出现汽水
        val enabled = AppSettings(qishuiEnabled = false, biliEnabled = true).toplistPlatforms()
        assertFalse(MusicPlatform.QS in enabled)
        assertTrue(MusicPlatform.BB in enabled)
    }

    @Test
    fun `榜单默认平台：默认平台无内容时回退`() {
        // 默认平台设为 B 站但 B 站关闭 → 回退到首个可用
        val settings = AppSettings(defaultPlatform = MusicPlatform.BB, biliEnabled = false)
        assertEquals(MusicPlatform.WY, settings.effectiveToplistPlatform())
    }

    @Test
    fun `榜单默认平台：默认平台可用时原样返回（汽水也算可用）`() {
        val settings = AppSettings(defaultPlatform = MusicPlatform.QS, qishuiEnabled = true)
        assertEquals(MusicPlatform.QS, settings.effectiveToplistPlatform())
    }
}
