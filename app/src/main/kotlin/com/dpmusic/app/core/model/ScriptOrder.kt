package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** 有序列表里的一项 JS：某个脚本/插件在某平台链上的位置与启停 */
@Serializable
data class ScriptRef(
    val id: String,
    val enabled: Boolean = true,
)

/**
 * **JS 顺序与启停**（逐平台一套，见 [platformId]）。
 *
 * JS 与 MusicFree 插件共用这一套结构（`kind` 区分），因为它们的行为完全同构：
 * 都是「一个可以按序尝试、可单独启停的 JS 运行时」。共用后，两份 UI、两份持久化、
 * 两份纠错逻辑都只剩一份。
 *
 * 为什么是**逐平台**一套而不是全局一套：
 * 同一个脚本在不同平台的表现差异极大（比如某个脚本只对网易云有效），
 * 用户需要「这个脚本排网易云的第一个、但别用在 QQ 上」这种表达。
 * 这也与既有的「解析链路」（[SourceChain]）保持同一交互模型。
 */
@Serializable
data class ScriptOrder(
    /** 平台 id（见 [MusicPlatform.id]） */
    val platformId: String,
    /** JS 类型：`script`（LX 脚本）或 `plugin`（MusicFree 插件），见 [ScriptKind.id] */
    val kind: String,
    /** **有序**列表：顺序即尝试顺序 */
    val refs: List<ScriptRef> = emptyList(),
) {
    val scriptKind: ScriptKind get() = ScriptKind.fromId(kind)

    /** 该 id 在本链上的位置（-1 = 不在链上） */
    fun indexOf(id: String): Int = refs.indexOfFirst { it.id == id }

    fun isEnabled(id: String): Boolean = refs.firstOrNull { it.id == id }?.enabled ?: false

    /** 上移 / 下移（越界不动） */
    fun move(id: String, up: Boolean): ScriptOrder {
        val i = indexOf(id)
        if (i < 0) return this
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= refs.size) return this
        val list = refs.toMutableList()
        // ⚠️ 必须用临时变量交换：`list[i] = list[j]; list[j] = list[i]` 会先覆盖 i，
        // 第二句变成自赋值 —— 结果是「一项被复制两份、另一项丢失」。
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        return copy(refs = list)
    }

    /**
     * 切换启用。
     *
     * ⚠️ 不允许关掉**最后一项启用的**：全关后该平台上「LX 脚本」整个环节就没人可用，
     * 用户看到一列全灰会以为弄坏了什么。返回 `this` 表示操作被忽略（调用方负责提示）。
     * 空链（还没导入任何脚本）时也允许「没有任何项」，那是正常的初始状态。
     */
    fun toggle(id: String): ScriptOrder {
        val i = indexOf(id)
        if (i < 0) return this
        val item = refs[i]
        if (item.enabled && refs.count { it.enabled } <= 1) return this
        val list = refs.toMutableList()
        list[i] = item.copy(enabled = !item.enabled)
        return copy(refs = list)
    }

    /**
     * 把「新导入 / 已删除」与链上的条目对齐：
     * - 列表里新增的项追加到末尾（默认启用，行为与「刚导入就该能用」一致）；
     * - 已被删除的项从链上移除；
     * - 保持用户既有顺序。
     */
    fun reconcile(availableIds: List<String>): ScriptOrder {
        val existing = refs.map { it.id }.toSet()
        val kept = refs.filter { it.id in availableIds }
        val added = availableIds.filter { it !in existing }.map { ScriptRef(it) }
        val next = kept + added
        return if (next == refs) this else copy(refs = next)
    }

    companion object {
        /** 生成某平台的全套默认顺序：按「导入顺序」全员启用（与旧版「隐式全可用」最接近） */
        fun defaultFor(
            platform: MusicPlatform,
            kind: ScriptKind,
            availableIds: List<String>,
        ): ScriptOrder = ScriptOrder(
            platformId = platform.id,
            kind = kind.id,
            refs = availableIds.map { ScriptRef(it) },
        )

        /**
         * 归一化持久化数据：补齐缺失的（平台 × 类型）组合、清掉非法项、对齐可用项。
         *
         * @param stored 已存的所有 [ScriptOrder]
         * @param available 每种类型当前「可用的 id 列表」（脚本 / 插件）
         */
        fun sanitize(
            stored: List<ScriptOrder>,
            available: Map<ScriptKind, List<String>>,
        ): List<ScriptOrder> = buildList {
            ScriptKind.entries.forEach { kind ->
                val ids = available[kind].orEmpty()
                MusicPlatform.entries.forEach { platform ->
                    val kept = stored.firstOrNull {
                        it.platformId == platform.id && it.kind == kind.id
                    }
                    add(
                        kept?.reconcile(ids)
                            ?: defaultFor(platform, kind, ids),
                    )
                }
            }
        }
    }
}

/** JS 类型：链路上的两类可排序项 */
enum class ScriptKind(val id: String, val label: String) {
    /** LX Music 自定义音源脚本 */
    SCRIPT("script", "LX 脚本"),

    /** MusicFree 插件 */
    PLUGIN("plugin", "MusicFree 插件"),
    ;

    companion object {
        fun fromId(id: String?): ScriptKind = entries.firstOrNull { it.id == id } ?: SCRIPT
    }
}

/* ---------------- 顺序表的查询辅助（放在类型之后，避免前向引用） ---------------- */

/**
 * 从「逐平台的启用顺序」推导出**需要驻留**的 JS 集合。
 *
 * 一个 JS 只要被**任一平台**启用就要驻留；这样用户在某平台临时关掉它、
 * 别的平台仍启用时无需重新加载（重新加载要重新跑一遍 JS，明显更慢）。
 */
fun List<ScriptOrder>.residentIds(kind: ScriptKind): Set<String> =
    MusicPlatform.entries.flatMap { enabledIds(it, kind) }.toSet()

/** 某平台上「启用且有序」的 JS id 列表（顺序即尝试顺序） */
fun List<ScriptOrder>.enabledIds(platform: MusicPlatform, kind: ScriptKind): List<String> =
    refsFor(platform, kind).filter { it.enabled }.map { it.id }

/** 某平台上「启用且有序」的 JS 条目（含 id 与启用标记） */
fun List<ScriptOrder>.refsFor(platform: MusicPlatform, kind: ScriptKind): List<ScriptRef> =
    firstOrNull { it.platformId == platform.id && it.kind == kind.id }?.refs.orEmpty()

/** 取「某平台 × 某类型」这一格的顺序配置 */
fun List<ScriptOrder>.orderFor(platform: MusicPlatform, kind: ScriptKind): ScriptOrder? =
    firstOrNull { it.platformId == platform.id && it.kind == kind.id }