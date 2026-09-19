package com.dpmusic.app.core.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 下载路径工具：
 * - 默认目录：公共音乐目录下的 DPmusic（/storage/emulated/0/Music/DPmusic）；
 * - 可写性检测（mkdirs + 试写探针文件）；
 * - 失败诊断：区分「需要所有文件访问权限」（Android 11+）、
 *   「需要存储权限」（Android 10-）与「路径不可写」（需重新填写）。
 */
object DownloadPaths {

    private const val APP_DIR_NAME = "DPmusic"

    /** 默认下载目录（公共 Music/DPmusic；外部存储不可用时退回应用专属目录） */
    fun defaultPath(context: Context): String {
        val shared = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            APP_DIR_NAME,
        )
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            shared.absolutePath
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, APP_DIR_NAME).absolutePath
        }
    }

    /** 将设置中的原始路径解析为实际目录（空 = 默认目录） */
    fun resolve(context: Context, raw: String): File {
        val path = raw.trim()
        return if (path.isEmpty()) File(defaultPath(context)) else File(path)
    }

    /** 路径可写性检测结果 */
    sealed interface Check {
        /** 可写：可保存 / 可下载 */
        data object Ok : Check

        /** Android 11+：需要「所有文件访问权限」（去系统设置授予） */
        data object NeedAllFilesAccess : Check

        /** Android 10-：需要存储权限（运行时弹窗授予） */
        data object NeedStoragePermission : Check

        /** 权限已具备（或路径无需权限）但仍不可写：需重新填写 */
        data class NotWritable(val reason: String) : Check
    }

    /** 检查目录是否可写（含原因诊断） */
    fun check(context: Context, dir: File): Check {
        if (tryWrite(dir)) return Check.Ok

        val path = dir.absolutePath
        if (!path.startsWith("/")) return Check.NotWritable("请输入绝对路径（以 / 开头）")
        if (isAppScope(context, dir)) return Check.NotWritable("应用专属目录不可写，请更换路径")
        if (!isSharedStorage(dir)) return Check.NotWritable("路径不存在或不可写，请重新填写")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Check.NotWritable("该目录受系统保护（如 Android/data），请更换路径")
            } else {
                Check.NeedAllFilesAccess
            }
        } else {
            if (hasLegacyStoragePermission(context)) {
                Check.NotWritable("权限已授予但目录不可写，请重新填写路径")
            } else {
                Check.NeedStoragePermission
            }
        }
    }

    /** 低版本存储权限是否已授予（Android 11+ 恒视为 true，统一走「所有文件访问权限」） */
    fun hasLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /* ---------------- 内部 ---------------- */

    private fun tryWrite(dir: File): Boolean = runCatching {
        if (dir.exists() && !dir.isDirectory) return@runCatching false
        if (!dir.exists() && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".dpmusic_write_test")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    private fun isAppScope(context: Context, dir: File): Boolean {
        val path = dir.absolutePath
        val scopes = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.noBackupFilesDir,
            context.getExternalFilesDir(null),
            context.externalCacheDir,
        ).map { it.absolutePath }
        return scopes.any { path.startsWith(it) }
    }

    private fun isSharedStorage(dir: File): Boolean {
        val path = dir.absolutePath
        return path.startsWith(Environment.getExternalStorageDirectory().absolutePath) ||
            path.startsWith("/sdcard") ||
            path.startsWith("/storage/")
    }
}
