# DPmusic R8 规则
#
# 开启 R8 后，凡是「运行时靠名字查找」的东西都必须显式保留，否则会在真机上炸
# （典型症状：ClassNotFoundException / NoSuchMethodError / 序列化字段丢失 / 桥方法不回调）。
# 本文件按「反射来源」分类列出。

# ---------------------------------------------------------------------------
# 1. WebView JS 桥（@JavascriptInterface）
#
# JS 侧用字符串方法名调用（如 AndroidBridge.onResult），R8 看不到调用点。
# 用通配规则覆盖全部桥，新增 Bridge 时不必再改这里。
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# 已知的两个宿主页（听歌识曲 / 汽水扫码登录）——保留内部类名，便于日志排查
-keep class com.dpmusic.app.core.audio.AudioFingerprintEngine$Bridge { *; }
-keep class com.dpmusic.app.core.net.QishuiLoginHost$Bridge { *; }

# ---------------------------------------------------------------------------
# 2. kotlinx.serialization
#
# @Serializable 生成的 $$serializer 与字段名解析依赖反射 / 生成的 Companion。
# 插件版本已随 Kotlin 2.1，仍保守保留，避免「运行时找不到 serializer」。
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# enum 序列化：R8 会移除未引用的枚举常量，导致反序列化失败
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# 3. Media3 / ExoPlayer
#
# 播放器通过反射实例化 Renderer / AudioProcessor / Extractor。
# androidx.media3 自带 consumerProguardFiles，这里再兜一层常见项。
# ---------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------------------------------------------------------------------------
# 4. 反射 / 系统接口
#
# MiSuperIslandMagic 通过 Class.forName 拿的是 **系统类**
# （android.net.IConnectivityManager、android.os.SystemProperties），
# 不受 R8 影响（不在本 APK 内）。这里只保留 Shizuku / hiddenapibypass 的入口。
# ---------------------------------------------------------------------------
-keep class rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn org.lsposed.hiddenapibypass.**

# ---------------------------------------------------------------------------
# 5. QuickJS（LX 音源脚本引擎）
#
# 通过 JNI 回调 Java 方法，同样靠名字查找。
# ---------------------------------------------------------------------------
-keep class wang.harlon.quickjs.** { *; }
-dontwarn wang.harlon.quickjs.**

# ---------------------------------------------------------------------------
# 6. 第三方 SDK / 通用兜底
# ---------------------------------------------------------------------------
-keep class org.json.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 保留行号，便于真机崩溃定位（体积代价很小）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile