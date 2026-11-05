#!/usr/bin/env bash
# Build PhonePilot.apk without Android Studio or Gradle.
# Needs: JDK (javac, keytool), aapt, dx (dalvik-exchange), zipalign, apksigner, and an android.jar (API 30+).
#   Ubuntu/Debian: sudo apt install default-jdk aapt dalvik-exchange zipalign apksigner
#   ANDROID_JAR=/path/to/android-34/android.jar ./build_apk.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?set ANDROID_JAR to an android.jar for API 30 or newer}"
DX=$(command -v dx || command -v dalvik-exchange)
OUT=build/manual; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen"

# aapt needs the package name and SDK levels in the manifest (Gradle injects them normally).
sed -e 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.phonepilot" android:versionCode="8" android:versionName="0.7.1">\n    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="34" />#' \
    app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"

aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S app/src/main/res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find "$OUT/gen" app/src/main/java -name '*.java') 2>&1 | grep -v "^warning\|^Note\|^1 warning" || true
[ -f "$OUT/classes/dev/phonepilot/MainActivity.class" ] || { echo "javac failed"; exit 1; }
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && cp unsigned.apk withdex.apk && zip -q withdex.apk classes.dex)
zipalign -f 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-$(pwd)/phonepilot-debug.keystore}  # kept with the project so rebuilds install as updates
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias pilot \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Phone Pilot Debug" >/dev/null
fi
apksigner sign --v4-signing-enabled false --ks "$KS" --ks-pass pass:android --key-pass pass:android --out PhonePilot.apk "$OUT/aligned.apk"
apksigner verify PhonePilot.apk && echo "Built $(pwd)/PhonePilot.apk"
