package com.dpmusic.app

import android.app.Application
import coil3.SingletonImageLoader
import com.dpmusic.app.core.util.AppLogger
import com.dpmusic.app.core.util.StorageManager

/**
 * 应用入口：
 * - 初始化依赖容器；
 * - 安装 Coil 全局图片加载器（复用 OkHttp 连接池与统一 UA；
 *   磁盘缓存上限由设置决定，超限自动按最久未用逐出）。
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
    }
}