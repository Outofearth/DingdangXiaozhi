#!/usr/bin/env bash
#
# compile_native.sh [arch] - 用 NDK 自带的 LLVM 交叉工具链编译 libopus 1.3.1(浮点) + JNI 封装，
# 产出单个 libopusjni.so。
#   arch=arm   -> prebuilt/armeabi-v7a   (32 位，兼容所有 ARM 设备)
#   arch=arm64 -> prebuilt/arm64-v8a     (64 位，解除新旗舰 ABI 限制)
# 默认 arm。不使用 autotools configure（Windows+GitBash 交叉编译 configure 极慢），
# 改用极简 config.h 直接编译源码；工具链直接用 NDK 预编译的 llvm（无需 make_standalone_toolchain）。
#
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
# 与 build.sh 一致：优先读 ANDROID_SDK，默认取同级 ../build_env/android-sdk
SDK="${ANDROID_SDK:-$ROOT/../build_env/android-sdk}"
# NDK 目录名在各版本间不同（ndk/21.x / ndk-bundle / …），依次试
NDK="${ANDROID_NDK:-}"
if [ -z "$NDK" ]; then
  NDK=$(ls -d "$SDK"/ndk/*/ 2>/dev/null | head -1); NDK=${NDK%/}
fi
[ -n "$NDK" ] || NDK="$SDK/ndk-bundle"
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"

ARCH="${1:-arm}"
if [ "$ARCH" = "arm64" ]; then
  ABI="arm64-v8a"
  CC="$LLVM_BIN/aarch64-linux-android21-clang"
  AR="$LLVM_BIN/aarch64-linux-android-ar"
  STRIP="$LLVM_BIN/aarch64-linux-android-strip"
else
  ABI="armeabi-v7a"
  CC="$LLVM_BIN/armv7a-linux-androideabi21-clang"
  AR="$LLVM_BIN/arm-linux-androideabi-ar"
  STRIP="$LLVM_BIN/arm-linux-androideabi-strip"
fi

if [ ! -x "$CC" ]; then
  echo "找不到交叉编译器：$CC"; echo "请检查 NDK 路径：$LLVM_BIN"; exit 1
fi

OPUS_SRC="$ROOT/opus-1.3.1"
CFGDIR="$ROOT/opus_config"
BUILD="$ROOT/build_native"
JNI="$ROOT/src/jni/opus_jni.c"
OUT="$ROOT/prebuilt/$ABI/libopusjni.so"

# 仓库里只带编译好的 .so，不带 opus 源码（4.9MB，BSD 许可，请自行下载）：
#   curl -LO https://archive.mozilla.org/pub/opus/opus-1.3.1.tar.gz
#   tar xf opus-1.3.1.tar.gz -C "$ROOT"
if [ ! -d "$OPUS_SRC" ]; then
  echo "缺少 opus 源码目录：$OPUS_SRC"
  echo "仓库已提供 prebuilt/*/libopusjni.so，正常使用不必重编；"
  echo "确需重编请下载 opus-1.3.1 解压到该位置后再运行本脚本。"
  exit 1
fi

# 已存在且比 JNI 封装新则跳过（改了 opus 源码想重编时删掉 OUT 即可）
if [ -f "$OUT" ] && [ "$OUT" -nt "$JNI" ]; then
  echo ">> $ABI/libopusjni.so 已是最新，跳过（删除后重编）"
  exit 0
fi

# 极简 config.h（浮点构建，纯 C，无 NEON/RTCD 运行时派发）
mkdir -p "$CFGDIR"
cat > "$CFGDIR/config.h" <<'EOF'
#ifndef CONFIG_H
#define CONFIG_H
#define OPUS_BUILD 1
#define VAR_ARRAYS 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_LRINT 1
#define HAVE_LRINTF 1
#define HAVE_ALLOCA_H 0
#define HAVE_MEMORY_H 0
#define HAVE_DLFCN_H 0
#define ENABLE_ASSERTIONS 0
#define ENABLE_HARDENING 0
#define CUSTOM_MODES 0
#define FLOAT_APPROX 0
#define OPUS_STACK_PROTECTOR 0
#endif
EOF
cat > "$CFGDIR/version.h" <<'EOF'
#ifndef OPUS_VERSION_H
#define OPUS_VERSION_H
#define OPUS_VERSION "1.3.1"
#endif
EOF

# 编译 opus 源文件（排除 arm/ 与 silk/fixed/ 子目录）；直接复用 build_native/obj，覆盖同名 .o
mkdir -p "$BUILD/obj"
INCS="-I$OPUS_SRC -I$OPUS_SRC/include -I$OPUS_SRC/src -I$OPUS_SRC/celt -I$OPUS_SRC/silk -I$OPUS_SRC/silk/float -I$CFGDIR"
OBJS=()
for f in $(find "$OPUS_SRC/src" "$OPUS_SRC/celt" "$OPUS_SRC/silk" "$OPUS_SRC/silk/float" -maxdepth 1 -name '*.c'); do
  rel="${f#$OPUS_SRC/}"
  base="${rel//\//_}"
  o="$BUILD/obj/${base%.c}.o"
  echo "  CC $(basename "$f")"
  "$CC" -O2 -DNDEBUG -DHAVE_CONFIG_H -fPIC $INCS -c "$f" -o "$o"
  OBJS+=("$o")
done

echo ">> 打包 libopus.a ($ABI)"
"$AR" rcs "$BUILD/libopus.a" "${OBJS[@]}"

echo ">> 编译 libopusjni.so ($ABI)"
mkdir -p "$(dirname "$OUT")"
"$CC" -shared -fPIC -O2 -DHAVE_CONFIG_H -I"$OPUS_SRC/include" -I"$CFGDIR" "$JNI" "$BUILD/libopus.a" -lm -o "$OUT"
"$STRIP" --strip-unneeded "$OUT"
echo "完成: $OUT"
