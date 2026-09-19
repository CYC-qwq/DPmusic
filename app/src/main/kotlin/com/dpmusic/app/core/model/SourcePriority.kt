package com.dpmusic.app.core.model

/** 音源解析优先级（自定义脚本 vs 远端代理 Key） */
enum class SourcePriority(
    val id: String,
    val label: String,
    val description: String,
) {
    /** 脚本优先：脚本可用时用脚本，失败回退 Key 音源 */
    SCRIPT_FIRST("script_first", "脚本优先", "优先使用自定义音源脚本，失败时回退 Key 音源"),

    /** Key 优先：优先 Key 音源，失败回退脚本 */
    KEY_FIRST("key_first", "Key 优先", "优先使用 Key 音源，失败时回退自定义音源脚本"),

    /** 仅脚本：只用脚本 */
    SCRIPT_ONLY("script_only", "仅脚本", "只使用自定义音源脚本，不回退 Key 音源"),

    /** 仅 Key：忽略脚本 */
    KEY_ONLY("key_only", "仅 Key", "只使用 Key 音源，忽略自定义音源脚本");

    companion object {
        fun fromId(id: String?): SourcePriority = entries.firstOrNull { it.id == id } ?: SCRIPT_FIRST
    }
}
