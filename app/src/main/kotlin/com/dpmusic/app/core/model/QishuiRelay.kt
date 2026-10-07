package com.dpmusic.app.core.model

/**
 * 汽水 relay 取链的结果。
 *
 * 依据《汽水音乐接入协议 v1》§3.2。relay 由「家里电脑上的汽水客户端」解析，
 * 返回**完整歌曲**（`isFullLength=true`）且音质到 `hi_res`（AAC ~634kbps，SVIP 档）。
 *
 * 与 [QishuiPlayUrl]（原生 `h5/seo_track`，匿名，热门曲常只给试听片段）的关键区别：
 * - 本通道**必须** `isFullLength == true` 才允许播放；
 * - 档位以服务端返回的 [quality] 为准（可能低于请求档位，协议 §5「档位可能回退」）。
 */
data class QishuiRelayResult(
    /** 播放地址（直连 CDN，支持 Range；有效期 ~12h） */
    val url: String,
    /** 服务端实际给出的档位（`hi_res` / `highest` / `higher` / `medium` ...） */
    val quality: String,
    /** 编码，通常 `aac` */
    val codec: String,
    /** 解码得到时长（秒） */
    val durationS: Double,
    /** 官方标称时长（秒，用于对齐进度条） */
    val catalogueS: Double,
    /**
     * **必须为 true 才播放**。
     * true 表示「解码时长 == 官方时长」，即完整歌曲；false 表示拿到的是试听片段或别的曲目。
     */
    val isFullLength: Boolean,
    /** `hit` = 服务端缓存命中（快）；其它值 = 现场解析 */
    val cache: String,
    /** 服务端建议缓存秒数（43200 = 12 小时） */
    val expiresHintS: Long,
    /**
     * CENC 解密密钥 blob（base64）。**与本次 [url] 一一对应**，不同档位密钥不同（协议 §3.4）。
     * 明文流为空串。
     */
    val playAuth: String = "",
    /** 该 [url] 是否为加密流；true 时必须先解密再交播放器，否则永久缓冲 */
    val encrypted: Boolean = false,
) {
    /** 档位展示名（UI 用；协议 §0「默认 hi_res」，lossless 为 v2 新增真无损档） */
    val qualityLabel: String
        get() = when (quality.lowercase()) {
            "hi_res" -> "高音质"
            "spatial" -> "空间音频"
            "highest" -> "极高"
            "higher" -> "较高"
            "medium" -> "标准"
            "lossless" -> "无损"
            else -> quality.ifBlank { "未知" }
        }
}

/** relay 链路错误（带协议 §3.2 的错误码，便于上层按建议动作处理） */
class QishuiRelayException(
    val code: String,
    val httpStatus: Int,
    message: String,
) : Exception(message) {
    /** 家里电脑离线（HTTP 503 / `agent_unavailable`） */
    val agentOffline: Boolean get() = httpStatus == 503 || code == "agent_unavailable"

    /** 签名或时间戳不对（HTTP 401） */
    val unauthorized: Boolean get() = httpStatus == 401 || code == "unauthorized"

    companion object {
        /** 协议 §3.2：`no_tier` 建议「重试一次」 */
        const val CODE_NO_TIER = "no_tier"
        const val CODE_AGENT_UNAVAILABLE = "agent_unavailable"
        const val CODE_UNAUTHORIZED = "unauthorized"
        const val CODE_BAD_REQUEST = "bad_request"
    }
}