package com.dpmusic.app.core.model

/**
 * 音源**引擎**（解析链路上的一个环节）。
 *
 * 与 [SourcePriority] 的区别：那个是「全局二选一 + 回退」的粗粒度开关，
 * 这个是可以**逐平台排序 / 逐项启停**的细粒度环节。链路见 [SourceChain]。
 *
 * 顺序即尝试顺序：从上到下依次尝试，某项失败（或不可用）自动落到下一项。
 */
enum class SourceEngine(
    val id: String,
    val label: String,
    val description: String,
) {
    /** 远端代理 Key 音源（LX 协议，需自备 Key） */
    KEY("key", "Key 代理", "远端代理音源，需自备 Key；全平台可用"),

    /** 自定义 LX 音源脚本（用户导入 JS） */
    SCRIPT("script", "LX 脚本", "自定义音源脚本；脚本不支持该平台时自动跳过"),

    /** MusicFree 插件（用户导入 JS 插件） */
    PLUGIN("plugin", "MusicFree 插件", "第三方插件音源；未导入或未启用时自动跳过"),

    /** 酷狗概念版（**仅酷狗**；匿名即可取免费歌全曲） */
    KGLITE("kglite", "酷狗概念版", "匿名即可取免费歌全曲（仅酷狗曲库可用）"),
    ;

    /** 该引擎是否对该平台**有意义**（不满足时 UI 灰掉、解析时跳过） */
    fun supports(platform: MusicPlatform): Boolean = when (this) {
        KGLITE -> platform == MusicPlatform.KG
        else -> true
    }

    companion object {
        /** 解析持久化 id；**缺失 / 未知一律忽略**（由调用方兜底成默认链） */
        fun fromIdOrNull(id: String): SourceEngine? = entries.firstOrNull { it.id == id }
    }
}
