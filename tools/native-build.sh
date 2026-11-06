#!/usr/bin/env bash
# armeabi-v7a 交叉编译（API 28）
#
# 为什么不用标准的 clang.cmd：
#   本机 NDK 的 clang.exe 一启动就段错误（exit 139），必须用 clang-cl.exe。
# 为什么不用 NDK 的 libc.so：
#   sysroot 里那个是损坏/加密的，链接时改从电视上 pull 的真 libc.so 当 stub。
#
# 用法：
#   bash tools/native-build.sh <源文件.c> <输出名>
#
# 准备（只需一次）：
#   adb shell su -c 'cp /system/lib/libc.so /data/local/tmp/libc_dev.so; chmod 644 /data/local/tmp/libc_dev.so'
#   adb pull /data/local/tmp/libc_dev.so build/devlibs/libc.so
set -euo pipefail

SRC="${1:?用法: native-build.sh <源文件.c> <输出名>}"
NAME="${2:?用法: native-build.sh <源文件.c> <输出名>}"

NDK="${NDK:-D:\\Documents\\deepseek-harness\\default-workspace\\LIPTV\\vendor\\android\\sdk\\ndk\\26.1.10909125\\toolchains\\llvm\\prebuilt\\windows-x86_64}"
NDK_U="${NDK_U:-/d/Documents/deepseek-harness/default-workspace/LIPTV/vendor/android/sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64}"
API="${API:-28}"
ABI_LIB="arm-linux-androideabi"
LIBDIR="$NDK\\sysroot\\usr\\lib\\$ABI_LIB\\$API"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build"
mkdir -p "$WORK/devlibs"

if [ ! -f "$WORK/devlibs/libc.so" ]; then
    cat <<'EOF'
缺少 build/devlibs/libc.so（设备 libc stub）。先执行：

  adb shell su -c 'cp /system/lib/libc.so /data/local/tmp/libc_dev.so; chmod 644 /data/local/tmp/libc_dev.so'
  adb pull /data/local/tmp/libc_dev.so build/devlibs/libc.so

原因：本机 NDK 副本的 sysroot/usr/lib/.../libc.so 是损坏的（链接报 unknown directive）。
EOF
    exit 1
fi

echo "== 编译 =="
# /Zl 去掉 MSVC 默认库指令（否则 ld.lld 会去找 libcmt / oldnames）
MSYS_NO_PATHCONV=1 "$NDK\\bin\\clang-cl.exe" \
    --target=armv7a-linux-androideabi$API /O2 /Zl \
    -I"$NDK_U/sysroot/usr/include" \
    -I"$NDK_U/sysroot/usr/include/$ABI_LIB" \
    -c "$SRC" -o "$WORK/$NAME.o"

echo "== 链接 =="
# 路径必须是 Windows 风格，ld.lld 不认 /d/...
MSYS_NO_PATHCONV=1 "$NDK\\bin\\ld.lld.exe" \
    -m armelf_linux_eabi -pie --dynamic-linker /system/bin/linker \
    -o "$WORK/$NAME" \
    "$WORK/$NAME.o" \
    "$LIBDIR\\crtbegin_dynamic.o" "$LIBDIR\\crtend_android.o" \
    -L"$WORK/devlibs" -L"$LIBDIR" -ldl -lc

ls -l "$WORK/$NAME"
echo
echo "运行（LD_LIBRARY_PATH 必须给，否则 dlopen 厂商库失败）："
echo "  adb push $WORK/$NAME /data/local/tmp/"
echo "  adb shell su -c 'chmod 755 /data/local/tmp/$NAME; cd /data/local/tmp; \\"
echo "    LD_LIBRARY_PATH=/vendor/lib:/system/lib ./$NAME'"
