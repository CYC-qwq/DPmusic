package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 单个平台的一条**解析链路**（有序引擎 + 逐项启停）。持久化为 `SourceChain` 列表。
 *
 * 不可变，所有编辑都以「返回新实例」的方式表达（便于放进 Compose 的 state 里比较）。
 */
@Serializable
data class SourceChain(
    val platformId: String,
    /** **有序**引擎 id 列表：顺序即尝试顺序 */
    val engines: List<String> = emptyList(),
    /** 被用户**关掉**的引擎 id（仍留在 [engines] 里以保持位置，但解析时跳过） */
    val disabled: Set<String> = emptySet(),
) {
    /** 该平台的完整链路（含被禁用的项，用于 UI 展示） */
    val order: List<SourceEngine>
        get() = engines.mapNotNull { SourceEngine.fromIdOrNull(it) }

    /** 实际参与解析的引擎（按顺序，且跳过被禁用 / 不支持该平台的项） */
    fun activeEngines(): List<SourceEngine> {
        val platform = MusicPlatform.fromId(platformId)
        return order.filter { it.id !in disabled && it.supports(platform) }
    }

    fun isEnabled(engine: SourceEngine): Boolean = engine.id !in disabled

    /** 上移 / 下移（越界不动），返回新实例 */
    fun move(engine: SourceEngine, up: Boolean): SourceChain {
        val i = engines.indexOf(engine.id)
        if (i < 0) return this
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= engines.size) return this
        // ⚠️ 必须用临时变量做交换。写成 `it[i] = it[j]; it[j] = it[i]` 是**错的**：
        // 第一句已经把 it[i] 覆盖掉，第二句等于 `it[j] = it[j]`，
        // 结果是「一个元素被复制两份、另一个丢失」，链路会凭空多出重复项。
        val list = engines.toMutableList()
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        return copy(engines = list)
    }

    /**
     * 切换启用状态。
     *
     * ⚠️ 不允许关掉**最后一项**：全关后该平台就再也解析不出任何地址，
     * 且用户看到「一条链全灰」会以为自己弄坏了什么。返回 `this` 表示「操作被忽略」。
     */
    fun toggle(engine: SourceEngine): SourceChain {
        val enabled = isEnabled(engine)
        if (enabled && activeEngines().size <= 1) return this
        val next = if (enabled) disabled + engine.id else disabled - engine.id
        return copy(disabled = next)
    }

    companion object {
        /** 默认链路：酷狗概念版优先（酷狗是「开箱可播」的免费通道） */
        fun defaultFor(platform: MusicPlatform): SourceChain {
            // 默认顺序有意与 [SourceEngine] 的枚举顺序不同：
            // 枚举顺序只是「声明顺序」，而这里的顺序是**实际尝试顺序**。
            // 酷狗概念版匿名即可取免费歌全曲，是本应用唯一「不配置任何东西就能播」的通道，
            // 所以放在最前，避免先撞上「未配置 Key」的空转与误导报错
            //（与旧版 kgLiteForce 默认开启的行为一致）。
            val preferred = listOf(
                SourceEngine.KGLITE,
                SourceEngine.KEY,
                SourceEngine.SCRIPT,
                SourceEngine.PLUGIN,
            )
            return SourceChain(
                platformId = platform.id,
                engines = preferred.filter { it.supports(platform) }.map { it.id },
            )
        }

        /**
         * 从旧版**全局** [SourcePriority] 迁移出默认链路，尽量保留用户原有偏好。
         *
         * | 旧值 | 新链路 |
         * | --- | --- |
         * | `key_first`（默认） | 沿用各平台默认链 |
         * | `script_first` | 脚本提到最前，其余保持默认相对顺序 |
         * | `key_only` | 仅保留 Key |
         * | `script_only` | 仅保留脚本 |
         */
        fun defaultsFor(platform: MusicPlatform, legacy: SourcePriority?): SourceChain {
            val base = defaultFor(platform)
            return when (legacy) {
                null, SourcePriority.KEY_FIRST -> base
                SourcePriority.SCRIPT_FIRST -> base.copy(
                    engines = listOf(SourceEngine.SCRIPT.id) +
                        base.engines.filter { it != SourceEngine.SCRIPT.id },
                )
                SourcePriority.KEY_ONLY -> base.copy(
                    engines = base.engines.filter { it == SourceEngine.KEY.id },
                )
                SourcePriority.SCRIPT_ONLY -> base.copy(
                    engines = base.engines.filter { it == SourceEngine.SCRIPT.id },
                )
            }
        }

        /** 从持久化 JSON 还原：补齐缺失平台、丢弃非法项、确保至少一项 */
        fun sanitize(
            stored: List<SourceChain>,
            legacy: SourcePriority? = null,
        ): List<SourceChain> = MusicPlatform.entries.map { platform ->
            val kept = stored.firstOrNull { it.platformId == platform.id }
            val fallback = defaultsFor(platform, legacy)
            if (kept == null) return@map fallback

            // 只保留「合法且该平台支持」的引擎 id，并按去重后的顺序
            val valid = kept.engines
                .mapNotNull { SourceEngine.fromIdOrNull(it) }
                .filter { it.supports(platform) }
                .distinct()
                .map { it.id }
            // 新增的引擎（应用升级后多出来的）追加到末尾，保证它有机会参与解析
            val missing = fallback.engines.filter { it !in valid }
            val engines = (valid + missing).ifEmpty { fallback.engines }
            SourceChain(
                platformId = platform.id,
                engines = engines,
                disabled = kept.disabled.intersect(engines.toSet()),
            )
        }
    }
}