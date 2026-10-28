#!/usr/bin/env bash
#
# build.sh - 用 Android 本地工具链（aapt2/d8/apksigner）手动构建并签名 APK，
# 规避 Gradle / maven.google 502 问题。依赖 build_env/android-sdk 已解压的
# platform-27、build-tools 30.0.3、maven-jars(okhttp/okio)。
#
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
TARGET="${1:-speaker}"   # speaker=智能音箱版（保留版，横屏锁定） | mobile=手机/平板版（自适应）

# ---- 可移植性：全部走环境变量 / 自动探测，脚本里不写死任何本机绝对路径 ----
# ANDROID_SDK  指向自备的工具链目录（内含 build-tools / platforms / maven-jars），
#              默认取与本工程同级的 ../build_env/android-sdk。
# PYTHON       用来跑注入脚本的解释器，默认 python3，找不到退回 python。
# JAVAC/JAVA_HOME/KEYTOOL  默认取 PATH 里的，其次取 JAVA_HOME。
SDK="${ANDROID_SDK:-$ROOT/../build_env/android-sdk}"
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi
fi
APP_CONFIG="$ROOT/src/java/com/altc/xiaozhi/AppConfig.java"
BT="$SDK/build-tools/android-11"
PLAT="$SDK/platforms/android-8.1.0"
OKHTTP="$SDK/maven-jars/okhttp-3.12.13.jar"
OKIO="$SDK/maven-jars/okio-1.17.5.jar"
JCFS="$SDK/maven-jars/jcifs-2.1.28.jar"
SLF4J="$SDK/maven-jars/slf4j-api-1.7.36.jar"
SLF4J_NOP="$SDK/maven-jars/slf4j-nop-1.7.36.jar"
BC="$SDK/maven-jars/bcprov-jdk15on-1.70.jar"
NATIVE="$ROOT/prebuilt"
BUILD="$ROOT/build"
GEN="$BUILD/gen"
RESFLAT="$BUILD/res"
CLASSES="$BUILD/classes"
DEXDIR="$BUILD/dex"

AAPT2="$BT/aapt2.exe"
AAPT="$BT/aapt.exe"
ALIGN="$BT/zipalign.exe"
run_apksigner() {
  java -jar "$BT/lib/apksigner.jar" "$@"
}
run_d8() {
  java -Djava.ext.dirs="$BT/lib" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
}
# javac / keytool：优先 JAVA_HOME，其次 PATH。都不在就报错退出，别猜路径。
JAVAC="${JAVAC:-}"
if [ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
fi
[ -n "$JAVAC" ] || JAVAC=javac
KEYTOOL="${KEYTOOL:-}"
if [ -z "$KEYTOOL" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
fi
[ -n "$KEYTOOL" ] || KEYTOOL=keytool

# 两个 ABI 都确保存在：缺哪个就编哪个（armeabi-v7a 兼容所有 ARM；arm64-v8a 解除新旗舰限制）
for ABI in armeabi-v7a arm64-v8a; do
  if [ ! -f "$NATIVE/$ABI/libopusjni.so" ]; then
    echo ">> 编译缺失的 $ABI/libopusjni.so"
    "$ROOT/compile_native.sh" "$([ "$ABI" = "arm64-v8a" ] && echo arm64 || echo arm)"
  fi
done
[ -n "$KEYTOOL" ] || { echo "找不到 keytool（JRE 中）"; exit 1; }

mkdir -p "$BUILD" "$GEN" "$RESFLAT" "$CLASSES" "$DEXDIR"

# ===== 注入构建目标（speaker / mobile）：生成 AppConfig + 临时 Manifest =====
"$PY" - "$TARGET" "$APP_CONFIG" "$ROOT" <<'PYEOF'
import sys
target = sys.argv[1]
speaker = (target == "speaker")
is_sp = "true" if speaker else "false"
ver = "智能音箱版" if speaker else "手机/平板版"
bt = "speaker" if speaker else "mobile"
cfg = ('package com.altc.xiaozhi;\n'
       'public final class AppConfig {\n'
       '    /** true=智能音箱版（横屏锁定、保留现有行为）；false=手机/平板版（自适应方向）。 */\n'
       '    public static final boolean IS_SPEAKER = %s;\n'
       '    public static final String VERSION_TAG = "%s";\n'
       '    public static final String BUILD_TARGET = "%s";\n'
       '}\n') % (is_sp, ver, bt)
open(sys.argv[2], "w", encoding="utf-8").write(cfg)
orient = "landscape" if speaker else "unspecified"
src = open(sys.argv[3] + "/src/AndroidManifest.xml", encoding="utf-8").read()
src = src.replace('android:screenOrientation="landscape"',
                  'android:screenOrientation="%s"' % orient)
open(sys.argv[3] + "/build/AndroidManifest.xml", "w", encoding="utf-8").write(src)
print("[inject] TARGET=%s IS_SPEAKER=%s screenOrientation=%s" % (bt, is_sp, orient))
PYEOF

echo "[1/5] aapt2 compile 资源…"
"$AAPT2" compile -o "$RESFLAT" --dir "$ROOT/src/res"

echo "[2/5] aapt2 link 资源+清单…"
FLATS=$(find "$RESFLAT" -name '*.flat')
"$AAPT2" link -o "$BUILD/app.apk" \
  --manifest "$BUILD/AndroidManifest.xml" \
  -I "$PLAT/android.jar" \
  --java "$GEN" \
  $FLATS

echo "[3/5] javac 编译…"
SRC=$(find "$ROOT/src/java" -name '*.java')
RJava=$(find "$GEN" -name 'R.java')
"$JAVAC" -encoding UTF-8 --release 8 -d "$CLASSES" -classpath "$PLAT/android.jar:$OKHTTP:$OKIO:$JCFS:$SLF4J:$SLF4J_NOP:$BC" $SRC $RJava

echo "[4/5] d8 生成 DEX…"
"$PY" - "$CLASSES" "$BUILD/classes.jar" <<'PYEOF'
import sys, zipfile, os
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
PYEOF
run_d8 --min-api 21 --lib "$PLAT/android.jar" --output "$DEXDIR" "$BUILD/classes.jar" "$OKHTTP" "$OKIO" "$JCFS" "$SLF4J" "$SLF4J_NOP" "$BC"

echo "[5/5] 打包 / 对齐 / 签名…"
cp "$BUILD/app.apk" "$BUILD/unsigned.apk"
cd "$BUILD"
cp "$DEXDIR"/*.dex .
for f in *.dex; do
  "$AAPT" add unsigned.apk "$f"
done
for ABI in armeabi-v7a arm64-v8a; do
  mkdir -p "lib/$ABI"
  cp "$NATIVE/$ABI/libopusjni.so" "lib/$ABI/"
  "$AAPT" add unsigned.apk "lib/$ABI/libopusjni.so"
done
cd "$ROOT"

"$ALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$ROOT/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=Android Debug,O=ALTC"
fi
run_apksigner sign --ks "$ROOT/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android "$BUILD/aligned.apk"

cp "$BUILD/aligned.apk" "$ROOT/xiaozhi-$TARGET.apk"
echo "完成: $ROOT/xiaozhi-$TARGET.apk"
