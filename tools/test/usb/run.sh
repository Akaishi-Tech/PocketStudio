#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/.android-sdk"}}
if [[ ! -d $sdk/platforms && -f $root/local.properties ]]; then
  sdk=$(sed -n 's/^sdk.dir=//p' "$root/local.properties")
fi
android_jar=$(find "$sdk/platforms" -name android.jar | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/classes"
javac --release 11 -cp "$android_jar" -d "$work/classes" \
  "$root"/plugin-api/src/main/java/com/hcgstudio/pocketstudio/protocol/*.java \
  "$root"/plugin-api/src/main/java/com/hcgstudio/pocketstudio/adb/*.java \
  "$root"/plugins/android/src/main/java/com/hcgstudio/pocketstudio/adb/*.java \
  "$root"/tools/test/usb/*.java
java -cp "$work/classes:$android_jar" com.hcgstudio.pocketstudio.adb.TransportCheck
# Optional: exercise the real AndroidKeyStore signing provider and both AUTH responses.
if [[ $# == 1 ]]; then
  serial=$1
  [[ $serial == emulator-* ]] || { echo "Pass a test emulator serial" >&2; exit 2; }
  build_tools=$(dirname "$(find "$sdk/build-tools" -name d8 | sort -V | tail -1)")
  jar cf "$work/test.jar" -C "$work/classes" .
  "$build_tools/d8" --min-api 26 --lib "$android_jar" --output "$work" "$work/test.jar"
  "$build_tools/aapt2" link --manifest "$root/tools/test/usb/AndroidManifest.xml" \
    -I "$android_jar" --min-sdk-version 26 --target-sdk-version 35 -o "$work/unsigned.apk"
  (cd "$work" && zip -q unsigned.apk classes.dex)
  keytool -genkeypair -keystore "$work/test.p12" -storepass android -alias test \
    -keyalg RSA -keysize 2048 -dname CN=TransportCheck -validity 1 >/dev/null 2>&1
  "$build_tools/apksigner" sign --ks "$work/test.p12" --ks-pass pass:android --out "$work/check.apk" "$work/unsigned.apk"
  adb -s "$serial" install "$work/check.apk"
  trap 'adb -s "$serial" uninstall dev.ondevice.studio.adbcheck >/dev/null; rm -rf "$work"' EXIT
  adb -s "$serial" shell am instrument -w -r \
    dev.ondevice.studio.adbcheck/com.hcgstudio.pocketstudio.adb.UsbCheckRunner | tee "$work/results"
  grep -q '^INSTRUMENTATION_RESULT: result=PASS' "$work/results"
fi
