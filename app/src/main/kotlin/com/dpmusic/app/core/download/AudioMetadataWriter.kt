package com.dpmusic.app.core.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** 元数据写入内容（null = 不写该维度） */
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: ByteArray? = null,
    val lyric: String? = null,
)

/** 各维度写入结果（true = 已成功写入） */
data class WriteResult(
    val tags: Boolean,
    val cover: Boolean,
    val lyric: Boolean,
)

/**
 * 音频元数据写入器（无第三方依赖的最小实现）：
 * - MP3：ID3v2.3 标签（TIT2 / TPE1 / TALB / APIC / USLT）；
 * - FLAC：Vorbis Comment（TITLE / ARTIST / ALBUM）+ PICTURE 块；
 * - 其他格式：返回 false（跳过）。
 *
 * 写入策略：先构建完整临时文件，成功后原子替换原文件——
 * 任何一步失败都不会破坏原文件。
 */
object AudioMetadataWriter {

    private val SUPPORTED = setOf("mp3", "flac")

    private fun ext(file: File): String = file.extension.lowercase()

    /** 该文件格式是否支持写入 */
    fun supports(file: File): Boolean = ext(file) in SUPPORTED

    /** 该文件格式是否支持嵌入歌词（当前仅 MP3；FLAC 无标准歌词块） */
    fun supportsLyric(file: File): Boolean = ext(file) == "mp3"

    /** 写入元数据（一次重写文件） */
    fun write(file: File, metadata: AudioMetadata): WriteResult = when (ext(file)) {
        "mp3" -> writeMp3(file, metadata)
        "flac" -> writeFlac(file, metadata)
        else -> WriteResult(false, false, false)
    }

    // ================= MP3（ID3v2.3） =================

    private fun writeMp3(file: File, m: AudioMetadata): WriteResult {
        val audioStart = mp3AudioStart(file)
        val frames = mutableListOf<ByteArray>()
        if (!m.title.isNullOrBlank()) frames += textFrame("TIT2", m.title)
        if (!m.artist.isNullOrBlank()) frames += textFrame("TPE1", m.artist)
        if (!m.album.isNullOrBlank()) frames += textFrame("TALB", m.album)
        val cover = m.cover
        val hasCover = cover != null && cover.isNotEmpty()
        if (cover != null && cover.isNotEmpty()) frames += apicFrame(coverMime(cover), cover)
        val lyric = m.lyric
        val hasLyric = lyric != null && lyric.isNotBlank()
        if (lyric != null && lyric.isNotBlank()) frames += usltFrame(lyric)

        // 没有任何可写内容：不动原文件
        if (frames.isEmpty()) return WriteResult(false, false, false)

        val framesSize = frames.sumOf { it.size }
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3
        header[4] = 0
        header[5] = 0
        syncsafe(framesSize).copyInto(header, 6)

        val tmp = File(file.parentFile, file.name + ".meta_tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(header)
                frames.forEach { out.write(it) }
                copyRange(file, audioStart, file.length(), out)
            }
        } catch (e: Exception) {
            tmp.delete()
            return WriteResult(false, false, false)
        }
        if (!replaceFile(file, tmp)) {
            tmp.delete()
            return WriteResult(false, false, false)
        }
        return WriteResult(
            tags = !m.title.isNullOrBlank() || !m.artist.isNullOrBlank() || !m.album.isNullOrBlank(),
            cover = hasCover,
            lyric = hasLyric,
        )
    }

    /** 已有 ID3v2 头的长度（无标签时返回 0） */
    private fun mp3AudioStart(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) {
                0L
            } else {
                val head = ByteArray(10)
                raf.readFully(head)
                if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                    val size = ((head[6].toInt() and 0x7F) shl 21) or
                        ((head[7].toInt() and 0x7F) shl 14) or
                        ((head[8].toInt() and 0x7F) shl 7) or
                        (head[9].toInt() and 0x7F)
                    (10 + size).toLong()
                } else {
                    0L
                }
            }
        }
    } catch (e: Exception) {
        0L
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0, 0, 4)
        out[4] = ((payload.size ushr 24) and 0xFF).toByte()
        out[5] = ((payload.size ushr 16) and 0xFF).toByte()
        out[6] = ((payload.size ushr 8) and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        // flags（8、9）= 0
        payload.copyInto(out, 10)
        return out
    }

    private fun textFrame(id: String, text: String): ByteArray {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val body = byteArrayOf(1) + bom + text.toByteArray(Charsets.UTF_16LE)
        return frame(id, body)
    }

    private fun apicFrame(mime: String, image: ByteArray): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        val body = ByteArray(1 + mimeBytes.size + 1 + 1 + 1 + image.size)
        var i = 0
        body[i++] = 0 // ISO-8859-1
        mimeBytes.copyInto(body, i)
        i += mimeBytes.size
        body[i++] = 0 // mime 结束
        body[i++] = 3 // 封面（front）
        body[i++] = 0 // 描述结束
        image.copyInto(body, i)
        return frame("APIC", body)
    }

    private fun usltFrame(lyric: String): ByteArray {
        val lang = "chi".toByteArray(Charsets.ISO_8859_1)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val desc = bom + byteArrayOf(0, 0) // 空描述（UTF-16 + 结束符）
        val content = bom + lyric.toByteArray(Charsets.UTF_16LE)
        val body = byteArrayOf(1) + lang + desc + content
        return frame("USLT", body)
    }

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    // ================= FLAC =================

    private fun writeFlac(file: File, m: AudioMetadata): WriteResult {
        data class Block(val type: Int, val data: ByteArray)

        val raf = RandomAccessFile(file, "r")
        val magic = ByteArray(4)
        try {
            raf.readFully(magic)
        } catch (e: Exception) {
            raf.close()
            return WriteResult(false, false, false)
        }
        if (!(magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte())
        ) {
            raf.close()
            return WriteResult(false, false, false)
        }

        // 有新封面才替换旧 PICTURE 块，否则保留原封面
        val replacePicture = m.cover != null && m.cover.isNotEmpty()
        val kept = mutableListOf<Block>()
        var last = false
        var ok = true
        while (!last && ok) {
            val h = ByteArray(4)
            if (raf.read(h) != 4) {
                ok = false
                break
            }
            last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or
                ((h[2].toInt() and 0xFF) shl 8) or
                (h[3].toInt() and 0xFF)
            if (type != 4 && (type != 6 || !replacePicture)) {
                val data = ByteArray(len)
                if (raf.read(data) != len) {
                    ok = false
                    break
                }
                kept += Block(type, data)
            } else {
                raf.seek(raf.filePointer + len)
            }
        }
        val audioStart = raf.filePointer
        raf.close()
        if (!ok) return WriteResult(false, false, false)

        val vorbis = buildVorbisComment(m)
        val cover = m.cover
        val picture = if (cover != null && cover.isNotEmpty()) buildPictureBlock(cover) else null
        val hasTags = !m.title.isNullOrBlank() || !m.artist.isNullOrBlank() || !m.album.isNullOrBlank()
        if (!hasTags && picture == null) {
            // 没有任何可写内容：不动原文件
            return WriteResult(false, false, false)
        }
        val newBlocks = kept.toMutableList()
        newBlocks += Block(4, vorbis)
        if (picture != null) newBlocks += Block(6, picture)

        val tmp = File(file.parentFile, file.name + ".meta_tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(magic)
                newBlocks.forEachIndexed { i, b ->
                    val isLast = i == newBlocks.size - 1
                    val head = ByteArray(4)
                    head[0] = ((if (isLast) 0x80 else 0) or b.type).toByte()
                    head[1] = ((b.data.size ushr 16) and 0xFF).toByte()
                    head[2] = ((b.data.size ushr 8) and 0xFF).toByte()
                    head[3] = (b.data.size and 0xFF).toByte()
                    out.write(head)
                    out.write(b.data)
                }
                copyRange(file, audioStart, file.length(), out)
            }
        } catch (e: Exception) {
            tmp.delete()
            return WriteResult(false, false, false)
        }
        if (!replaceFile(file, tmp)) {
            tmp.delete()
            return WriteResult(false, false, false)
        }
        return WriteResult(
            tags = !m.title.isNullOrBlank() || !m.artist.isNullOrBlank() || !m.album.isNullOrBlank(),
            cover = picture != null,
            lyric = false, // FLAC 无标准歌词块（.lrc 文件由下载链路另行保存）
        )
    }

    private fun buildVorbisComment(m: AudioMetadata): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "DPmusic".toByteArray(Charsets.UTF_8)
        out.write(le32(vendor.size))
        out.write(vendor)
        val comments = mutableListOf<String>()
        if (!m.title.isNullOrBlank()) comments += "TITLE=${m.title}"
        if (!m.artist.isNullOrBlank()) comments += "ARTIST=${m.artist}"
        if (!m.album.isNullOrBlank()) comments += "ALBUM=${m.album}"
        out.write(le32(comments.size))
        comments.forEach { c ->
            val bytes = c.toByteArray(Charsets.UTF_8)
            out.write(le32(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun buildPictureBlock(image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val mime = coverMime(image).toByteArray(Charsets.US_ASCII)
        out.write(be32(3)) // 封面（front）
        out.write(be32(mime.size))
        out.write(mime)
        out.write(be32(0)) // 描述
        val size = imageSize(image)
        out.write(be32(size.first))
        out.write(be32(size.second))
        out.write(be32(24)) // 色深
        out.write(be32(0)) // 索引色数（非索引图）
        out.write(be32(image.size))
        out.write(image)
        return out.toByteArray()
    }

    // ================= 图片信息 =================

    private fun coverMime(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        else -> "image/jpeg"
    }

    private fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        // PNG：IHDR（固定位置）
        if (bytes.size > 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            return be32At(bytes, 16) to be32At(bytes, 20)
        }
        // JPEG：扫描 SOF 标记
        if (bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) {
                    i++
                    continue
                }
                val marker = bytes[i + 1].toInt() and 0xFF
                if (marker == 0xDA) break // 进入压缩数据，停止扫描
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    return w to h
                }
                if (marker == 0xD8 || marker in 0xD0..0xD9) {
                    i += 2
                    continue
                }
                val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (segLen < 2) break
                i += 2 + segLen
            }
        }
        return 0 to 0
    }

    private fun be32At(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    // ================= 字节工具 =================

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    // ================= 文件工具 =================

    private fun copyRange(src: File, start: Long, end: Long, out: OutputStream) {
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var remaining = end - start
            while (remaining > 0) {
                val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
    }

    /** 临时文件就绪后替换原文件（失败时回滚） */
    private fun replaceFile(original: File, temp: File): Boolean = try {
        val backup = File(original.parentFile, original.name + ".meta_bak")
        if (backup.exists()) backup.delete()
        if (!original.renameTo(backup)) {
            false
        } else if (temp.renameTo(original)) {
            backup.delete()
            true
        } else {
            backup.renameTo(original)
            false
        }
    } catch (e: Exception) {
        false
    }
}
