package com.dpmusic.app.core.net

import com.dpmusic.app.core.util.AppLogger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 汽水加密音频的取回-解密-落地。
 *
 * ## 为什么是「落盘 → 原地解密 → 写回」
 *
 * relay 给的 `url` 是 **CENC 加密**的（协议 §3.4），播放器直接吃会永久缓冲。
 * 但整曲 10~28MB（lossless），若 `bytes()` + `copyOf()` 会在堆里同时存在两份：
 * 实测 lossless 档会直接把 256MB 堆打爆（`OutOfMemoryError: okio.Segment`）。
 *
 * 因此这里刻意分三步，**任何时刻堆里只有一份整曲**：
 * 1. 流式下载到临时文件（不占堆）；
 * 2. 一次性读回 [ByteArray]，用 [SodaCenc.decryptInPlace] **原地**解密（不再复制）；
 * 3. 写回最终文件、删临时文件。
 *
 * 代价：播放前要等一次整曲下载 + 解密。UI 需有 loading 态。
 *
 * ## 缓存
 *
 * 解密结果按 `md5(url+playAuth)` 缓存到 [cacheDir]，超过 [MAX_CACHE_BYTES] 时按最后修改时间淘汰。
 */
class SodaCencFetcher(
    private val cacheDir: File,
) {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 取回加密音频、解密、落盘。
     *
     * @return 解密后的本地文件；失败（下载失败 / key 推导失败 / 解密失败）返回 null，
     *         调用方应视为播放失败并回退其它通道
     */
    suspend fun fetchDecrypted(encUrl: String, playAuth: String, tag: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, fileNameFor(encUrl, playAuth))
            val tmpEnc = File(cacheDir, target.name + ".enc")
            try {
                cacheDir.mkdirs()
                if (target.exists() && target.length() > 0) return@withContext target

                val key = SodaCencKey.extractKey(playAuth)
                if (key == null) {
                    AppLogger.w(TAG, "play_auth 无法推导密钥：$tag")
                    return@withContext null
                }

                // 1) 流式下载到临时文件（不占堆）
                if (!downloadToFile(encUrl, tmpEnc)) {
                    AppLogger.w(TAG, "下载加密音频失败：$tag")
                    return@withContext null
                }

                // 2) 读回 + 原地解密（堆里只有这一份整曲）
                val data = tmpEnc.readBytes()
                val stats = SodaCenc.decryptInPlace(data, key)
                AppLogger.d(
                    TAG,
                    "汽水解密完成：$tag sample=${stats.decryptedSamples}/${stats.samples} " +
                        "codec=${stats.realCodec} relabel=${stats.relabeled} ${data.size / 1024}KB",
                )

                // 3) 写回最终文件（先写 .tmp 再改名，避免中断留下半个文件）
                val tmpOut = File(cacheDir, target.name + ".tmp")
                tmpOut.writeBytes(data)
                if (!tmpOut.renameTo(target)) {
                    tmpOut.copyTo(target, overwrite = true)
                    tmpOut.delete()
                }
                trimCache()
                target
            } catch (e: OutOfMemoryError) {
                // 兜底：显式报告，避免上层只看到播放器的莫名错误
                AppLogger.w(TAG, "汽水解密内存不足：$tag（${e.message}）")
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "汽水解密流程异常：$tag -> ${e.message}")
                null
            } finally {
                tmpEnc.delete()
            }
        }

    /** 流式下载到文件：不把整个响应读进堆 */
    private fun downloadToFile(url: String, dest: File): Boolean {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            return dest.length() > 0
        }
    }

    /** 超过上限时按最后修改时间淘汰旧文件 */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.extension == "m4a" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileNameFor(url: String, playAuth: String): String {
        val md = MessageDigest.getInstance("MD5")
        val h = md.digest((url + "|" + playAuth).toByteArray())
        return h.joinToString("") { "%02x".format(it) } + ".m4a"
    }

    companion object {
        private const val TAG = "SodaCencFetcher"

        /** 解密结果缓存上限（约 60 首 hi_res，或 14 首 lossless） */
        private const val MAX_CACHE_BYTES = 400L * 1024 * 1024
    }
}