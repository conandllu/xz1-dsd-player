#!/usr/bin/env bash
# 重编 libusb-1.0.28 静态库到 app/src/main/cpp/libs/<abi>/
#
# 仓库里已经带了编好的静态库，**只有在换 NDK / 升级 libusb / 加新 ABI 时才需要跑**。
# 依赖：Android NDK（r26+）与 libusb 源码（https://github.com/libusb/libusb/releases 下 1.0.28 源码包）
#
# 用法：
#   NDK=/path/to/android-ndk-r27d LIBUSB=/path/to/libusb-1.0.28 scripts/build_libusb.sh
set -euo pipefail
NDK=${NDK:?请设置 NDK=你的 Android NDK 路径}
LIBUSB=${LIBUSB:?请设置 LIBUSB=libusb-1.0.28 源码目录}
OUT=$(dirname "$0")/../app/src/main/cpp/libs
TOOL=$NDK/toolchains/llvm/prebuilt/linux-x86_64
declare -A TRIPLE=( [arm64-v8a]=aarch64-linux-android21 [armeabi-v7a]=armv7a-linux-androideabi21 [x86_64]=x86_64-linux-android21 )
declare -A HOST=( [arm64-v8a]=aarch64-linux-android [armeabi-v7a]=arm-linux-androideabi [x86_64]=x86_64-linux-android )
for abi in "${!TRIPLE[@]}"; do
  t=${TRIPLE[$abi]}; h=${HOST[$abi]}
  b=$LIBUSB/build-$abi
  mkdir -p "$b"
  (cd "$b" && CC=$TOOL/bin/$t-clang ../configure --host=$h \
     --with-sysroot=$TOOL/sysroot --disable-udev --enable-static --disable-shared -q \
   && make -j"$(nproc)" >/dev/null)
  mkdir -p "$OUT/$abi"
  cp "$b/libusb/.libs/libusb-1.0.a" "$OUT/$abi/"
  echo "built $abi"
done
cp "$LIBUSB/libusb/libusb.h" "$(dirname "$0")/../app/src/main/cpp/include/"
echo done
