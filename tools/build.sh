#!/usr/bin/env bash
# 构建 classes.dex —— 不需要 Gradle，不需要 aapt
#
# 可用环境变量覆盖：
#   JDK          默认 /d/Android/jdk17
#   ANDROID_SDK  默认 /d/Android/sdk
#
# 注意：d8 在 Windows 下无法处理含中文的路径（会报 Invalid output），
#       所以编译在系统临时目录里进行，产物再拷回来。
set -euo pipefail

JDK="${JDK:-/d/Android/jdk17}"
ANDROID_SDK="${ANDROID_SDK:-/d/Android/sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK/build-tools/34.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_SDK/platforms/android-34}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/src/com/droidlogic/app"
OUT="$ROOT/build"

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

# 在纯 ASCII 路径下编译
STAGE="${TMPDIR:-/tmp}/tvss-build-$$"
mkdir -p "$STAGE/classes" "$STAGE/java/com/droidlogic/app"
trap 'rm -rf "$STAGE"' EXIT
cp "$SRC"/*.java "$STAGE/java/com/droidlogic/app/"

echo "== javac =="
"$JDK/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$(win "$PLATFORM/android.jar")" \
    -d "$(win "$STAGE/classes")" \
    "$(win "$STAGE/java/com/droidlogic/app")"/*.java

echo "== d8 =="
JAVA_HOME="$(win "$JDK")" MSYS_NO_PATHCONV=1 \
    "$BUILD_TOOLS/d8.bat" --min-api 28 \
    --lib "$(win "$PLATFORM/android.jar")" \
    --output "$(win "$STAGE")/" \
    "$(win "$STAGE/classes/com/droidlogic/app")"/*.class

mkdir -p "$OUT"
cp "$STAGE/classes.dex" "$OUT/classes.dex"
ls -l "$OUT/classes.dex"
echo
echo "部署："
echo "  adb push $OUT/classes.dex /data/local/tmp/sc.dex"
echo "  adb shell su -c 'sh /data/local/tmp/run_srv.sh 960 540 8899'"
