package com.dpmusic.app

import android.app.Application
import coil3.SingletonImageLoader
import com.dpmusic.app.core.util.AppLogger
import com.dpmusic.app.core.util.MemoryPolicy
import com.dpmusic.app.core.util.MemoryPressureCenter
import com.dpmusic.app.core.util.StorageManager

/**
 * 应用入口：
 * - 初始化依赖容器；
 * - 安装 Coil 全局图片加载器（复用 OkHttp 连接池与统一 UA；
 *   磁盘缓存上限由设置决定，超限自动按最久未用逐出）；
 * - 接入系统内存压力响应（见 [MemoryPressureCenter]）。
 */
class DPmusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.installCrashHandler()
        AppLogger.i("App", "DPmusic 启动")
        AppContainer.init(this)
        // Coil 全局图片加载器：磁盘缓存按设置上限构建
        SingletonImageLoader.setSafe { context ->
            StorageManager.buildImageLoader(context)
        }
        // 存储守护：启动即检查一次，之后每 10 分钟检查一次；
        // 缓存总占用超过「最大可占用」时自动清理（临时文件 + 图片缓存 LRU 收缩）
        StorageManager.startAutoClean(this)
        // 内存压力响应：先建系统回调通道，再装配各处可降载资源
        // （原先对 onTrimMemory 完全无响应，低内存设备上会一直占着缓存直到被系统杀掉）
        MemoryPressureCenter.install(this)
        MemoryPolicy.assemble(this)
    }
}