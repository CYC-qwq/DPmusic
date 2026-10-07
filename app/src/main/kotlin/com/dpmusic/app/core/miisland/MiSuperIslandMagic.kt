package com.dpmusic.app.core.miisland

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 通过 Shizuku 操作系统防火墙链，实现小米超级岛的「断网魔法」。
 *
 * 原理：HyperOS 只对**通过 MIUI 签名校验**的通知渲染超级岛。校验由 XMSF
 * （`com.xiaomi.xmsf`）联网完成；在发通知的瞬间把 XMSF 的 UID 踢进
 * 「OEM DENY」防火墙链，校验请求失败 → 系统降级放行 → 通知按超级岛模板渲染；
 * 渲染完成后再把 UID 放回默认规则，避免影响推送等正常功能。
 * 参照 InstallerX-Revived 的 `SessionNotifierImpl`。
 *
 * 三个技术要点：
 * 1. `IConnectivityManager` 是 @hide，普通 SDK 无法 import，只能反射拿；
 * 2. 反射调用 @hide 方法会被 Hidden API 拦截，必须走 [HiddenApiBypass.invoke]；
 * 3. 调用要落在**系统进程**执行：`ShizukuBinderWrapper` 包装原始 binder 后再 asInterface。
 */
object FirewallController {

    private const val TAG = "MiIslandFirewall"

    /** FIREWALL_CHAIN_OEM_DENY_3 = 9（厂商专用黑名单链），见 AOSP ConnectivityManager 常量。 */
    private const val CHAIN_OEM_DENY_3 = 9

    /** setUidFirewallRule 的规则值：2 = DENY，0 = 默认放行。 */
    private const val RULE_DENY = 2
    private const val RULE_ALLOW = 0

    private const val I_CONNECTIVITY_MANAGER = "android.net.IConnectivityManager"

    /** 取得指向**系统进程**的 IConnectivityManager 代理；失败返回 null。 */
    private fun hookedConnectivityManager(): Any? = try {
        val iCmClass = Class.forName(I_CONNECTIVITY_MANAGER)
        val asInterface = Class.forName("$I_CONNECTIVITY_MANAGER\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)

        val originalBinder = SystemServiceHelper.getSystemService("connectivity")
        val originalCm = asInterface.invoke(null, originalBinder)
        val originalAsBinder = iCmClass.getMethod("asBinder").invoke(originalCm) as android.os.IBinder
        val wrapper = ShizukuBinderWrapper(originalAsBinder)

        asInterface.invoke(null, wrapper)
    } catch (e: Exception) {
        Log.w(TAG, "获取 IConnectivityManager 失败: ${e.message}")
        null
    }

    /** 把指定 UID 加入 OEM DENY 链（阻断其网络）。 */
    suspend fun blockUidNetwork(uid: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val cm = hookedConnectivityManager() ?: return@withContext false
            val cls = Class.forName(I_CONNECTIVITY_MANAGER)
            HiddenApiBypass.invoke(cls, cm, "setFirewallChainEnabled", CHAIN_OEM_DENY_3, true)
            HiddenApiBypass.invoke(cls, cm, "setUidFirewallRule", CHAIN_OEM_DENY_3, uid, RULE_DENY)
            Log.d(TAG, "已阻断 UID=$uid")
            true
        } catch (e: Exception) {
            Log.w(TAG, "阻断 UID=$uid 失败: ${e.message}")
            false
        }
    }

    /** 把指定 UID 放回默认规则（恢复其网络）。失败必须重试，否则会永久断掉该应用网络。 */
    suspend fun restoreUidNetwork(uid: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(RESTORE_ATTEMPTS) { attempt ->
            try {
                val cm = hookedConnectivityManager() ?: return@withContext false
                val cls = Class.forName(I_CONNECTIVITY_MANAGER)
                HiddenApiBypass.invoke(cls, cm, "setUidFirewallRule", CHAIN_OEM_DENY_3, uid, RULE_ALLOW)
                Log.d(TAG, "已恢复 UID=$uid")
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "恢复 UID=$uid 失败（第 ${attempt + 1} 次）: ${e.message}")
            }
        }
        false
    }

    private const val RESTORE_ATTEMPTS = 3
}

/**
 * 小米超级岛「断网魔法」门面。
 *
 * 可用前提（四者缺一不可）：小米/HyperOS 设备、Shizuku 已安装且授权、binder 就绪、XMSF 存在。
 * 任一不满足时 [executeWithMagic] 返回 false，调用方降级为普通发送（超级岛不显示，但通知正常）。
 */
object MiSuperIslandMagic {

    private const val TAG = "MiIslandMagic"

    /** 触发签名校验的上游应用包名 */
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

    /** Shizuku 应用包名 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** 断网保持时长（ms）：太短不足以完成校验失败判定，太长会明显影响 XMSF 推送。 */
    var blockDurationMs: Long = 150

    /**
     * 是否小米系设备。
     *
     * 除 `MANUFACTURER` 外还看 HyperOS 版本属性 —— 部分 Redmi / POCO 机型
     * `ro.product.manufacturer` 并非 "Xiaomi"，但 `ro.mi.os.version.name` 有值。
     */
    fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            systemProp("ro.mi.os.version.name").isNotBlank() ||
            systemProp("ro.miui.ui.version.name").isNotBlank()

    /** 设备是否 HyperOS（`ro.mi.os.version.*` 有值）——超级岛是 HyperOS 能力，MIUI 14 上不存在。 */
    fun isHyperOs(): Boolean = systemProp("ro.mi.os.version.name").isNotBlank()

    /** Shizuku 是否已授权。 */
    fun isShizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** Shizuku 应用是否已安装（未安装时授权按钮无意义，应引导安装）。 */
    fun isShizukuInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Shizuku 服务是否已运行（binder 就绪）。 */
    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    /** 是否具备断网魔法的全部条件。 */
    fun isAvailable(context: Context): Boolean {
        if (!isXiaomiDevice()) return false
        if (!isHyperOs()) return false
        if (!isShizukuGranted() || !isShizukuRunning()) return false
        return xmsfUid(context) > 0
    }

    /** XMSF 的 UID（不存在返回 -1）。 */
    fun xmsfUid(context: Context): Int = try {
        context.packageManager.getPackageInfo(XMSF_PACKAGE, 0).applicationInfo?.uid ?: -1
    } catch (_: Exception) {
        -1
    }

    /**
     * 在「XMSF 断网」窗口内执行 [notifyBlock]，随后恢复网络。
     *
     * @return true = 完成魔法；false = 条件不足或失败，调用方应降级为普通发送。
     *
     * 用 [java.util.concurrent.locks.ReentrantLock] 串行化：断网是**全局副作用**，
     * 两次魔法重叠会导致恢复顺序错乱（A 恢复时 B 仍在断网窗口内）。
     */
    fun executeWithMagic(context: Context, notifyBlock: () -> Unit): Boolean {
        if (!isAvailable(context)) return false
        val uid = xmsfUid(context)
        if (uid <= 0) return false
        if (!lock.tryLock()) return false
        var blocked = false
        return try {
            blocked = runBlockingBlock(uid)
            if (!blocked) {
                false
            } else {
                notifyBlock()
                Thread.sleep(blockDurationMs)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "魔法执行异常: ${e.message}")
            false
        } finally {
            if (blocked) runBlockingRestore(uid)
            lock.unlock()
        }
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** 在**调用线程**同步等待断网完成。断网本身是 binder 调用，耗时在毫秒级。 */
    private fun runBlockingBlock(uid: Int): Boolean =
        kotlinx.coroutines.runBlocking { FirewallController.blockUidNetwork(uid) }

    /** 恢复网络：即使前面抛异常也必须执行，否则 XMSF 将永久断网。 */
    private fun runBlockingRestore(uid: Int) {
        kotlinx.coroutines.runBlocking { FirewallController.restoreUidNetwork(uid) }
    }

    private fun systemProp(key: String): String =
        runCatching { System.getProperty(key) }.getOrNull().orEmpty().ifBlank {
            runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                cls.getMethod("get", String::class.java).invoke(null, key) as? String
            }.getOrNull().orEmpty()
        }
}