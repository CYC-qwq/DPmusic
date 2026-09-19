package com.dpmusic.app.core.download

import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import kotlinx.serialization.Serializable

/** 下载任务状态（字符串常量：序列化兼容，避免枚举改名破坏持久化数据） */
object DownloadTaskStatus {
    const val WAITING = "waiting"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val ERROR = "error"
    const val PAUSED = "paused"
}

/** 元数据写入状态（标签 / 封面 / 歌词 三个维度） */
object MetaStatus {
    const val PENDING = "pending"
    const val SUCCESS = "success"
    const val FAIL = "fail"

    /** 未开启对应开关 / 格式不支持 */
    const val SKIPPED = "skipped"
}

/**
 * 下载任务：
 * - 由 [DownloadTaskStore] 串行执行并持久化到磁盘（JSON）；
 * - [song] 在跨平台换源后更新为实际下载的歌曲；
 * - [requestedSongKey] 用于判断是否发生了换源。
 */
@Serializable
data class DownloadTask(
    val id: String,
    /** 下载歌曲（跨平台换源后为实际下载的歌曲） */
    val song: Song,
    /** 请求音质 id */
    val requestedQualityId: String,
    /** 实际下载音质 id（降级后更新） */
    val qualityId: String = requestedQualityId,
    /** 请求时的歌曲唯一键（判断是否换源） */
    val requestedSongKey: String = song.stableKey,
    /** 状态：waiting / downloading / completed / error / paused */
    val status: String = DownloadTaskStatus.WAITING,
    /** 是否同时保存 .lrc 歌词文件 */
    val withLyrics: Boolean = false,
    val filePath: String? = null,
    val fileName: String = "",
    /** 0..1（未知总长时保持 0） */
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    /** 过程提示（降级 / 换源等） */
    val notes: List<String> = emptyList(),
    val errorMsg: String? = null,
    val createdAt: Long = 0L,
    val finishedAt: Long? = null,
    /** .lrc 歌词文件是否已保存 */
    val lyricsSaved: Boolean = false,
    /** 元数据写入状态：标签 / 封面 / 歌词 */
    val metaTags: String = MetaStatus.PENDING,
    val metaCover: String = MetaStatus.PENDING,
    val metaLyric: String = MetaStatus.PENDING,
) {
    val isActive: Boolean
        get() = status == DownloadTaskStatus.WAITING || status == DownloadTaskStatus.DOWNLOADING

    val requestedQuality: PlayQuality get() = PlayQuality.fromId(requestedQualityId)

    val actualQuality: PlayQuality get() = PlayQuality.fromId(qualityId)
}
