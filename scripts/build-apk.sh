#!/usr/bin/env bash
# 不依赖 Gradle 的 APK 构建脚本
#
# 依赖（可用环境变量覆盖）：
#   .toolchain/            默认工具链目录，可用 PET_TOOLCHAIN 指定别的路径
#     android-14/          Android build-tools 34（aapt2 / zipalign / lib/d8.jar / lib/apksigner.jar）
#     android-34/          Android platform 34（android.jar）
#     jdk-17.0.2/          JDK 17
#   keystore/whale.jks     签名密钥，不存在时自动生成；可用 PET_KEYSTORE 指定
#   PET_KS_PASS            密钥口令（默认 whale123456，仅用于本地自签）
#
# 用法：bash scripts/build-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android"
TC="${PET_TOOLCHAIN:-$ROOT/.toolchain}"
BT="$TC/android-14"
ANDROID_JAR="$TC/android-34/android.jar"
JDK="$TC/jdk-17.0.2"
BUILD="$ROOT/build"
OUT="$ROOT/dist"

for need in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$ANDROID_JAR" "$JDK/bin/javac.exe"; do
  if [ ! -e "$need" ]; then
    echo "!! 缺工具链文件：$need"
    echo "   把 JDK 17 + build-tools 34 + platform 34 放到 .toolchain/（或用 PET_TOOLCHAIN 指定），详见 docs/build.md"
    exit 1
  fi
done

PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
if [ -z "$PY" ]; then echo "!! 需要 python3（只为把 classes.dex 塞进 APK）"; exit 1; fi

NODE="${NODE:-node}"
command -v "$NODE" >/dev/null 2>&1 || { echo "!! 需要 node（生成页面）"; exit 1; }

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

echo "==> 1/8 生成 pet.html（内联 95 条动作目录）"
"$NODE" "$ROOT/scripts/gen-assets.mjs"

echo "==> 2/8 清理"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "==> 3/8 编译资源 (aapt2 compile)"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> 4/8 链接资源 + 打包 assets (aapt2 link)"
"$BT/aapt2.exe" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  -A "$APP/assets" \
  -0 .webm \
  --min-sdk-version 26 \
  --target-sdk-version 34

echo "==> 5/8 编译 Java (javac)"
find "$APP/src" "$BUILD/gen" -name "*.java" | sed -E "s|^/([a-zA-Z])/|\U\1:/|" > "$BUILD/sources.txt"
"$JDK/bin/javac.exe" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1 || {
    echo "!! javac 失败:"; iconv -f GBK -t UTF-8 "$BUILD/javac.log" 2>/dev/null | head -30 || head -30 "$BUILD/javac.log"; exit 1; }
echo "    $(find "$BUILD/classes" -name '*.class' | wc -l) 个 class"

echo "==> 6/8 转 dex (d8)"
find "$BUILD/classes" -name "*.class" | sed -E "s|^/([a-zA-Z])/|\U\1:/|" > "$BUILD/classes.txt"
"$JDK/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt" > "$BUILD/d8.log" 2>&1 || {
    echo "!! d8 失败:"; tail -20 "$BUILD/d8.log"; exit 1; }
ls -l "$BUILD/dex"

echo "==> 7/8 组装 APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
BUILD_DIR_WIN="$(cd "$BUILD" && pwd -W 2>/dev/null || echo "$BUILD")" "$PY" - <<'PY'
import os, zipfile
b = os.environ['BUILD_DIR_WIN']
z = zipfile.ZipFile(os.path.join(b, 'unsigned.apk'), 'a', zipfile.ZIP_DEFLATED)
z.write(os.path.join(b, 'dex', 'classes.dex'), 'classes.dex')
z.close()
print('    classes.dex 已写入')
PY
"$BT/zipalign.exe" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> 8/8 签名"
KS="${PET_KEYSTORE:-$ROOT/keystore/whale.jks}"
KS_PASS="${PET_KS_PASS:-whale123456}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  "$JDK/bin/keytool.exe" -genkeypair -v -keystore "$KS" -alias whale \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Whale Pet, OU=DIY, O=Personal, L=NA, ST=NA, C=CN" 2>&1 | tail -2
fi

APK="$OUT/WhalePet-v${VERSION}.apk"
"$JDK/bin/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 26 \
  --out "$APK" "$BUILD/aligned.apk"

echo
echo "==> 完成 versionName=$VERSION versionCode=$VERSION_CODE"
ls -lh "$APK"
"$JDK/bin/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs "$APK" 2>&1 | head -6
