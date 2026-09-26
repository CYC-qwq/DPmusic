#!/usr/bin/env bash
# =============================================================================
# DPmusic 一键构建 & 部署脚本
#
#   运行位置：设备本地 Ubuntu（proot）终端  —— 构建必须在这里做
#   依赖：JDK 17+ / Android SDK / 工程自带 gradle wrapper / aarch64 可用的 aapt2
#
# 用法（/storage 上没有可执行位，必须用 bash 调用）：
#   bash tools/build-and-install.sh                 # 增量构建 debug + 验证 + 投递
#   bash tools/build-and-install.sh --clean         # 先 clean 再全量构建
#   bash tools/build-and-install.sh --release       # 构建 release（默认未签名）
#   bash tools/build-and-install.sh --no-deploy     # 只构建，不投递
#
# 构建完成后会自动：
#   1) 校验 APK（apksigner verify + badging + sha256）
#   2) 把 APK 复制到共享目录（Android 侧可读）
#   3) 生成 Android 侧一键安装脚本
#
# 最后一步「安装」必须在 Android 侧执行（proot 里没有系统权限）：
#   super_admin:shell →  sh <共享目录>/install-on-device.sh
# =============================================================================
set -u

# ---------------------------------------------------------------- 默认配置 ----
PROJ="${PROJ:-/storage/emulated/0/AndroidIDEProjects/DPmusic}"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
SHARED_DIR="${SHARED_DIR:-/storage/emulated/0/Download/DPmusic-build}"
LOG="${LOG:-/tmp/dpmusic-build.log}"
BUILD_TIMEOUT="${BUILD_TIMEOUT:-2400}"      # 构建最长等待（秒）

VARIANT="Debug"
DO_CLEAN=0
DO_VERIFY=1
DO_DEPLOY=1
AAPT2_OVERRIDE="${AAPT2_OVERRIDE:-}"

# ------------------------------------------------------------------ 参数解析 --
usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --clean)      DO_CLEAN=1 ;;
    --release)    VARIANT="Release" ;;
    --debug)      VARIANT="Debug" ;;
    --no-verify)  DO_VERIFY=0 ;;
    --no-deploy)  DO_DEPLOY=0 ;;
    --proj)       PROJ="$2"; shift ;;
    --shared)     SHARED_DIR="$2"; shift ;;
    --aapt2)      AAPT2_OVERRIDE="$2"; shift ;;
    --log)        LOG="$2"; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "未知参数: $1"; echo; usage; exit 2 ;;
  esac
  shift
done

TASK=":app:assemble${VARIANT}"
VARIANT_LC="$(echo "$VARIANT" | tr 'A-Z' 'a-z')"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"

step() { echo; echo "===== $* ====="; }

# ------------------------------------------------------------ 1. 环境自检 ----
step "[1/5] 环境自检"
[ -d "$PROJ" ]     || { echo "❌ 项目目录不存在: $PROJ"; exit 1; }
[ -f "$PROJ/gradlew" ] || { echo "❌ 未找到 gradlew: $PROJ/gradlew"; exit 1; }
command -v java >/dev/null 2>&1 || { echo "❌ 未找到 java（需要 JDK 17+）"; exit 1; }
echo "  项目       : $PROJ"
echo "  构建任务   : $TASK$([ "$DO_CLEAN" = 1 ] && echo '（含 clean）')"
echo "  java       : $(java -version 2>&1 | head -1)"
echo "  SDK        : $SDK"
echo "  build-tools: ${BT:-<未找到>}"
echo "  共享目录   : $SHARED_DIR"
echo "  日志       : $LOG"
[ -d "$SDK/platforms" ] || echo "  ⚠️  SDK platforms 目录缺失，构建可能失败"

# -------------------------------------------------------------- 2. 选 aapt2 ---
step "[2/5] 准备 aarch64 aapt2"
# 候选顺序：显式指定 > Android Code Studio 静态版 > linker 包装版 > AndroidIDE 内置
CANDIDATES=(
  "$AAPT2_OVERRIDE"
  /opt/android-aapt2/acs/bin/aapt2
  /opt/android-aapt2/bin/aapt2
  "$HOME/.androidide/aapt2"
)
AAPT2=""
for c in "${CANDIDATES[@]}"; do
  [ -n "$c" ] && [ -x "$c" ] || continue
  out="$("$c" version 2>&1 | tail -1)"
  case "$out" in
    *aapt*|*AAPT*) AAPT2="$c"; echo "  ✅ $c"; echo "     $out"; break ;;
    *) echo "  ✗ $c（不是可用的 aapt2: ${out:-无输出}）" ;;
  esac
done

if [ -z "$AAPT2" ]; then
  cat <<'EOM'
  ❌ 找不到可用的 aarch64 aapt2。准备方式（推荐静态版）：

     mkdir -p /opt/android-aapt2/acs/bin
     cp <AndroidCodeStudio源码>/termux/application/src/main/jniLibs/arm64-v8a/libaapt2.so \
        /opt/android-aapt2/acs/bin/aapt2
     chmod 755 /opt/android-aapt2/acs/bin/aapt2
     /opt/android-aapt2/acs/bin/aapt2 version     # 应输出 Android Asset Packaging Tool

EOM
  exit 1
fi

if (sleep 1; echo quit) | timeout 8 "$AAPT2" daemon 2>&1 | grep -q Ready; then
  echo "  ✅ daemon 模式可用"
else
  echo "  ⚠️  daemon 自检未通过，AGP 可能在 mergeResources 阶段失败"
fi

# ------------------------------------------------------------------ 3. 构建 ---
step "[3/5] 构建 $TASK"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
# locale：proot 里通常只装了 C.UTF-8，硬设 en_US.UTF-8 会刷一堆 setlocale 警告
if locale -a 2>/dev/null | grep -qi '^en_US'; then
  export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
else
  export LANG=C.UTF-8 LC_ALL=C.UTF-8
fi

TASKS="$TASK"
[ "$DO_CLEAN" = 1 ] && TASKS="clean $TASK"

cd "$PROJ" || exit 1
: > "$LOG"

# setsid：让 gradle 脱离当前进程组。
# 这样即使外层（AI/终端）超时发送 SIGINT，构建也不会被中断。
setsid nohup bash ./gradlew $TASKS \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  --stacktrace >> "$LOG" 2>&1 < /dev/null &

BUILD_PID=$!
echo "  构建进程 PID=$BUILD_PID（已脱离会话，不受外层中断影响）"

START_TS=$(date +%s)
LAST_LINES=0
while true; do
  if grep -qE '^BUILD (SUCCESSFUL|FAILED)' "$LOG" 2>/dev/null; then break; fi
  ELAPSED=$(( $(date +%s) - START_TS ))
  if [ "$ELAPSED" -ge "$BUILD_TIMEOUT" ]; then
    echo "  ⏱  超过 ${BUILD_TIMEOUT}s 仍未结束，放弃等待（构建进程可能仍在跑）"
    break
  fi
  LINES=$(wc -l < "$LOG" 2>/dev/null || echo 0)
  if [ "$LINES" -gt "$LAST_LINES" ]; then
    tail -1 "$LOG" | cut -c1-100 | sed 's/^/  │ /'
    LAST_LINES=$LINES
  fi
  sleep 5
done

ELAPSED=$(( $(date +%s) - START_TS ))
if grep -q '^BUILD SUCCESSFUL' "$LOG"; then
  echo "  ✅ BUILD SUCCESSFUL（用时 ${ELAPSED}s）"
  RC=0
else
  echo "  ❌ 构建失败（用时 ${ELAPSED}s）。日志尾部："
  echo "  ------------------------------------------------------------"
  tail -40 "$LOG" | sed 's/^/  /'
  echo "  ------------------------------------------------------------"
  echo "  完整日志：$LOG"
  exit 1
fi

# ---------------------------------------------------------------- 4. 验证 -----
APK_DIR="$PROJ/app/build/outputs/apk/$VARIANT_LC"
APK="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "❌ 未找到 APK 产物（$APK_DIR）"; exit 1; }

step "[4/5] 校验产物"
echo "  路径 : $APK"
echo "  大小 : $(du -h "$APK" | cut -f1)（$(stat -c %s "$APK") 字节）"

if [ "$DO_VERIFY" = 1 ]; then
  echo "  sha256: $(sha256sum "$APK" | cut -d' ' -f1)"

  echo "  --- badging ---"
  "$AAPT2" dump badging "$APK" 2>/dev/null \
    | grep -E "^package:|^sdkVersion|^targetSdkVersion" | sed 's/^/  /'

  echo "  --- 签名 ---"
  if [ -n "${BT:-}" ] && [ -x "$BT/apksigner" ]; then
    "$BT/apksigner" verify --print-certs "$APK" 2>/dev/null \
      | grep -E 'certificate DN|SHA-256 digest' | sed 's/^/  /'
  else
    echo "  ⚠️  未找到 apksigner，跳过签名校验"
  fi
fi

# -------------------------------------------------------- 5. 投递 & 安装提示 --
step "[5/5] 投递到共享目录"
if [ "$DO_DEPLOY" = 0 ]; then
  echo "  （--no-deploy，跳过）"
else
  mkdir -p "$SHARED_DIR"
  STAMP="$(date +%Y%m%d-%H%M%S)"
  DEST="$SHARED_DIR/DPmusic-${VARIANT_LC}-${STAMP}.apk"
  cp -f "$APK" "$DEST"
  cp -f "$APK" "$SHARED_DIR/latest.apk"
  echo "  ✅ $DEST"
  echo "  ✅ $SHARED_DIR/latest.apk（始终指向最新）"

  # 只保留最近 KEEP_APK 个历史包（latest.apk 不计入）
  KEEP_APK="${KEEP_APK:-5}"
  ls -t "$SHARED_DIR"/DPmusic-*.apk 2>/dev/null \
    | tail -n +$((KEEP_APK + 1)) \
    | while read -r old; do
        rm -f "$old" && echo "  🧹 清理旧包: $(basename "$old")"
      done

  # 安装端脚本（在 Android 侧用 shell 身份执行）
  SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
  if [ -f "$SELF_DIR/install-on-device.sh" ]; then
    cp -f "$SELF_DIR/install-on-device.sh" "$SHARED_DIR/install-on-device.sh"
    echo "  ✅ $SHARED_DIR/install-on-device.sh"
  fi
fi

cat <<EOM

================================================================
 构建完成 ✅
 APK : $APK
================================================================
 最后一步「安装」需在 Android 侧执行（proot 无系统权限）：

   sh $SHARED_DIR/install-on-device.sh

 （在 Operit 中即调用 super_admin:shell 执行上面这一行；
   脚本会自动 cp 到 /data/local/tmp 后 pm install，并拉起 App）
================================================================
EOM

exit 0
