#!/usr/bin/env bash
# Builds the Cabin app, the Bluetooth overlay and the flashable module zip.
# Needs: JDK 11+, Android build-tools (aapt2, d8, zipalign, apksigner) and an android.jar (API 34).
#   ANDROID_JAR=/path/to/android.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

find_tool() {
  if command -v "$1" >/dev/null 2>&1; then command -v "$1"; return; fi
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
  local t
  t=$(ls -d "$sdk"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -1)
  [ -n "$t" ] || { echo "missing $1 (install Android build-tools)" >&2; exit 1; }
  echo "$t"
}
AAPT2=$(find_tool aapt2)
D8=$(find_tool d8)
ZIPALIGN=$(find_tool zipalign)
APKSIGNER=$(find_tool apksigner)

if [ -z "${ANDROID_JAR:-}" ]; then
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
  ANDROID_JAR=$(ls "$sdk"/platforms/android-3*/android.jar 2>/dev/null | sort -V | tail -1 || true)
fi
[ -f "${ANDROID_JAR:-}" ] || { echo "set ANDROID_JAR to an API 34 android.jar" >&2; exit 1; }

VERSION=$(sed -n 's/^version=//p' module/module.prop)
VCODE=$(sed -n 's/^versionCode=//p' module/module.prop)
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT"/{gen,classes,dex,res}

# Signing key: reuse it across builds so upgrades keep the app's data.
KEYSTORE=${KEYSTORE:-signing/cabin.jks}
KS_PASS=${KS_PASS:-cabin-module}
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias cabin -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Cabin" >/dev/null
fi

sign() {
  "$ZIPALIGN" -f -p 4 "$1" "$1.aligned"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out "$2" "$1.aligned"
}

echo "== app"
"$AAPT2" compile --dir app/res -o "$OUT/res/app.zip"
"$AAPT2" link -o "$OUT/app.unsigned.apk" -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VERSION" \
  "$OUT/res/app.zip"
javac --release 11 -Xlint:-options -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/java "$OUT/gen" -name '*.java')
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../app.unsigned.apk classes.dex)
sign "$OUT/app.unsigned.apk" "$OUT/CabinBridge.apk"

echo "== overlay (Android 12 and older)"
"$AAPT2" compile --dir overlay/res -o "$OUT/res/overlay.zip"
"$AAPT2" link -o "$OUT/overlay.unsigned.apk" -I "$ANDROID_JAR" \
  --manifest overlay/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 32 \
  --version-code "$VCODE" --version-name "$VERSION" "$OUT/res/overlay.zip"
sign "$OUT/overlay.unsigned.apk" "$OUT/CabinBtOverlay.apk"

echo "== module"
STAGE=$OUT/module
cp -r module "$STAGE"
mkdir -p "$STAGE/system/priv-app/CabinBridge" "$STAGE/system/product/overlay"
cp "$OUT/CabinBridge.apk" "$STAGE/system/priv-app/CabinBridge/"
cp "$OUT/CabinBtOverlay.apk" "$STAGE/system/product/overlay/"
ZIP="$PWD/$OUT/Cabin-v$VERSION.zip"
(cd "$STAGE" && zip -q -r -X "$ZIP" .)
echo "built $ZIP"
