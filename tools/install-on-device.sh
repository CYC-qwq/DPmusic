#!/system/bin/sh
# =============================================================================
# DPmusic 一键安装（Android 侧脚本）
#
#   运行身份：shell（Shizuku / adb / root）—— 不能是普通 App 身份
#   运行方式：sh /storage/emulated/0/Download/DPmusic-build/install-on-device.sh
#
# 为什么必须单独一个脚本：
#   system_server 读不了 FUSE（/storage）上的文件，pm install 直接传
#   /storage/... 会报 avc denied + "Can't open file"，
#   所以必须先 cp 到 /data/local/tmp 再安装。
#
# 用法：
#   sh install-on-device.sh                 # 安装共享目录里的 latest.apk 并启动
#   sh install-on-device.sh <某个.apk>      # 安装指定 APK
#   sh install-on-device.sh --no-launch     # 只安装不启动
#   sh install-on-device.sh --uninstall     # 卸载
# =============================================================================

PKG=com.dpmusic.app
SHARED=/storage/emulated/0/Download/DPmusic-build
TMP_APK=/data/local/tmp/dpmusic-install.apk

LAUNCH=1
APK=""

for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH=0 ;;
    --uninstall) UNINSTALL=1 ;;
    --help|-h)
      echo "用法: sh install-on-device.sh [apk路径] [--no-launch] [--uninstall]"
      exit 0 ;;
    *) APK="$arg" ;;
  esac
done

echo "=============================================="
echo " DPmusic 安装脚本"
echo " 身份: $(id 2>/dev/null | cut -c1-60)"
echo "=============================================="

# ---------------------------------------------------------------- 卸载模式 ---
if [ -n "$UNINSTALL" ]; then
  echo ">> 卸载 $PKG ..."
  pm uninstall "$PKG"
  exit $?
fi

# ------------------------------------------------------------ 1. 定位 APK ----
if [ -z "$APK" ]; then
  if [ -f "$SHARED/latest.apk" ]; then
    APK="$SHARED/latest.apk"
  else
    APK="$(ls -t $SHARED/*.apk 2>/dev/null | head -1)"
  fi
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "❌ 找不到 APK"
  echo "   传入路径: ${APK:-<空>}"
  echo "   共享目录: $SHARED"
  echo "   提示: 先在 Ubuntu 侧运行 bash tools/build-and-install.sh"
  exit 1
fi

echo ">> APK : $APK"
echo ">> 大小: $(ls -la "$APK" 2>/dev/null | awk '{print $5}') 字节"

# ------------------------------------------------- 2. 搬运到 /data/local/tmp -
# system_server 无法读 /storage（FUSE），必须先搬进来
echo ">> 复制到 $TMP_APK ..."
cp "$APK" "$TMP_APK" || { echo "❌ 复制失败（没有读 /storage 的权限？）"; exit 1; }

# ------------------------------------------------------------------ 3. 安装 ---
echo ">> pm install -r -t ..."
pm install -r -t "$TMP_APK"
RC=$?
rm -f "$TMP_APK"

if [ $RC -ne 0 ]; then
  echo "❌ 安装失败（exit=$RC）"
  echo "   常见原因："
  echo "     INSTALL_FAILED_UPDATE_INCOMPATIBLE → 与已装版本签名不同，先卸载："
  echo "        pm uninstall $PKG"
  echo "     INSTALL_FAILED_VERSION_DOWNGRADE  → 加 -d 允许降级"
  exit $RC
fi

echo "✅ 安装成功"

# ------------------------------------------------------------------ 4. 校验 ---
pm list packages 2>/dev/null | grep "$PKG" | sed 's/^/   /'
dumpsys package "$PKG" 2>/dev/null \
  | grep -E 'versionName|versionCode|lastUpdateTime' | head -3 | sed 's/^/   /'

# ------------------------------------------------------------------ 5. 启动 ---
if [ "$LAUNCH" = 1 ]; then
  echo ">> 启动 $PKG ..."
  am start -n "$PKG/.MainActivity" 2>&1 | sed 's/^/   /'
  sleep 3
  PID="$(pidof "$PKG" 2>/dev/null)"
  if [ -n "$PID" ]; then
    echo "   ✅ 运行中 PID=$PID"
  else
    echo "   ⚠️  进程未存活，可能是启动即崩溃，可用下面命令看日志："
    echo "      logcat -d -t 200 | grep -E 'AndroidRuntime|$PKG'"
  fi
fi

echo "=============================================="
echo " 完成"
echo "=============================================="
exit 0
