#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"

if [[ ! -d "$SDK_ROOT/build-tools" ]]; then
  echo "Error: Android SDK not found at $SDK_ROOT (set ANDROID_HOME)." >&2
  exit 1
fi

BUILD_TOOLS="$(ls -d "$SDK_ROOT"/build-tools/* | sort -V | tail -n 1)"
PLATFORM_JAR="$(ls "$SDK_ROOT"/platforms/android-*/android.jar | sort -V | tail -n 1)"

if [[ -z "${JAVA_HOME:-}" && -d "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

WORK="$REPO_ROOT/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/overlay" "$REPO_ROOT/dist"

KEYSTORE="$WORK/release.keystore"
"$KEYTOOL" -genkeypair -noprompt \
  -keystore "$KEYSTORE" \
  -storepass android -keypass android \
  -alias glass_ee2_headphones \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=GlassEE2Headphones,O=Community,C=BE" >/dev/null 2>&1

echo "[1/2] Building standalone GlassHeadphoneBridge.apk..."
"$JAVAC" --release 8 \
  -classpath "$PLATFORM_JAR" \
  -d "$WORK/classes" \
  "$REPO_ROOT"/bridge-app/src/com/bw/glass/headphonebridge/*.java

"$BUILD_TOOLS/d8" \
  --lib "$PLATFORM_JAR" \
  --min-api 26 \
  --output "$WORK/dex" \
  $(find "$WORK/classes" -name "*.class")

"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$REPO_ROOT/bridge-app/AndroidManifest.xml" \
  -o "$WORK/bridge-unsigned.apk"

(cd "$WORK/dex" && zip -q "$WORK/bridge-unsigned.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f 4 "$WORK/bridge-unsigned.apk" "$WORK/bridge-aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$REPO_ROOT/dist/GlassHeadphoneBridge.apk" \
  "$WORK/bridge-aligned.apk"
rm -f "$REPO_ROOT/dist/GlassHeadphoneBridge.apk.idsig"

echo "[2/2] Rebuilding GlassA2dpSinkOverlay.apk..."
"$BUILD_TOOLS/aapt2" compile \
  "$REPO_ROOT/overlay-src/res/values/bools.xml" \
  -o "$WORK/overlay/compiled.zip"

"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$REPO_ROOT/overlay-src/AndroidManifest.xml" \
  -o "$WORK/overlay/unsigned.apk" \
  "$WORK/overlay/compiled.zip"

"$BUILD_TOOLS/zipalign" -f 4 "$WORK/overlay/unsigned.apk" "$WORK/overlay/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$REPO_ROOT/magisk-template/system/vendor/overlay/GlassA2dpSinkOverlay/GlassA2dpSinkOverlay.apk" \
  "$WORK/overlay/aligned.apk"
rm -f "$REPO_ROOT/magisk-template/system/vendor/overlay/GlassA2dpSinkOverlay/GlassA2dpSinkOverlay.apk.idsig"

echo "Done! Built $REPO_ROOT/dist/GlassHeadphoneBridge.apk"
