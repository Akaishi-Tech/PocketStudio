#!/usr/bin/env sh
# Pass two disposable emulators, or one emulator followed by --local for a same-device test.
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
host=${1:?usage: two-emulators.sh emulator-HOST emulator-TARGET}
target=${2:?usage: two-emulators.sh emulator-HOST emulator-TARGET or --local [PAIR_PORT CONNECT_PORT]}
local_device=false
if [ "$target" = --local ]; then
    local_device=true
    target="$host"
else
    test "$host" != "$target"
fi
read_pairing_code() {
    printf 'Open the emulator pairing dialog and enter its six-digit pairing code: ' >&2
    IFS= read -r pairing_code
    case "$pairing_code" in
        [0-9][0-9][0-9][0-9][0-9][0-9]) ;;
        *) echo 'Expected six digits' >&2; exit 1 ;;
    esac
}
if [ "$local_device" = true ] || [ "${3-}" = "--wireless" ]; then read_pairing_code; fi
for serial in "$host" "$target"; do
    case "$serial" in emulator-*) ;; *) echo "Expected a disposable emulator: $serial" >&2; exit 1 ;; esac
    test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
done
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the SDK containing the app compile platform}
work=$(mktemp -d)
port=
pair_port=
tcp_changed=false
fixture_copied=false
cleanup() {
    if [ -n "$pair_port" ]; then adb -s "$target" forward --remove "tcp:$pair_port" >/dev/null || :; fi
    if [ -n "$port" ]; then adb -s "$target" forward --remove "tcp:$port" >/dev/null || :; fi
    if [ "$tcp_changed" = true ]; then adb -s "$target" usb >/dev/null || :; fi
    if [ "$fixture_copied" = true ]; then
        adb -s "$host" shell run-as com.hcgstudio.pocketstudio rm -f files/studio-test-target.apk >/dev/null || :
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT
fixture="$root/tools/test/debuggee"
android_jar=$(find "$sdk/platforms" -name android.jar | sort -V | tail -1)
build_tools=$(dirname "$(find "$sdk/build-tools" -name d8 | sort -V | tail -1)")
mkdir -p "$work/classes" "$work/dex"
javac --release 8 -encoding UTF-8 -g -cp "$android_jar" -d "$work/classes" "$fixture/BreakpointActivity.java"
jar cf "$work/target.jar" -C "$work/classes" .
"$build_tools/d8" --debug --min-api 26 --lib "$android_jar" --output "$work/dex" "$work/target.jar"
"$build_tools/aapt2" link --manifest "$fixture/AndroidManifest.xml" -I "$android_jar" \
    --min-sdk-version 26 --target-sdk-version 35 -o "$work/target.apk"
(cd "$work/dex" && zip -q "$work/target.apk" classes.dex)
# AGP creates its debug key when assembling the host. Reuse it so rerunning on the same
# disposable target can update the fixture with adb install -r.
"$root/gradlew" --offline -p "$root" :app:assembleDebug :app:assembleDebugAndroidTest
debug_key=${ANDROID_USER_HOME:-"$HOME/.android"}/debug.keystore
"$build_tools/apksigner" sign --ks "$debug_key" --ks-pass pass:android "$work/target.apk"
adb -s "$host" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$host" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s "$host" shell run-as com.hcgstudio.pocketstudio mkdir -p files
fixture_copied=true
adb -s "$host" shell -T 'run-as com.hcgstudio.pocketstudio sh -c "cat >files/studio-test-target.apk"' < "$work/target.apk"

if [ "$local_device" = true ]; then
    # No forwarding. With no port overrides, both endpoints must be discovered on-device.
    set -- -e scenario local -e pairPort "${3:-0}" -e port "${4:-0}" -e code "$pairing_code"
elif [ "${3-}" = "--wireless" ]; then
    # Open Wireless debugging > Pair device with pairing code on the TARGET emulator.
    # Pass its pairing and connection ports as arguments, then enter its six-digit code on stdin.
    pairing_endpoint=${4:?supply target pairing port}
    connect_endpoint=${5:?supply target connection port}
    # 10.0.2.2 in the host emulator reaches these development-machine loopback ports.
    pair_port=$(adb -s "$target" forward tcp:0 "tcp:$pairing_endpoint" | tr -d '\r')
    port=$(adb -s "$target" forward tcp:0 "tcp:$connect_endpoint" | tr -d '\r')
    set -- -e scenario wireless -e pairPort "$pair_port" -e port "$port" -e code "$pairing_code"
else
    if [ "$(adb -s "$target" shell getprop service.adb.tcp.port | tr -d '\r')" != 5555 ]; then
        tcp_changed=true
        adb -s "$target" tcpip 5555
        adb -s "$target" wait-for-device
    fi
    # tcpip restarts adbd. An old transport can briefly satisfy wait-for-device.
    for attempt in $(seq 1 30); do
        if [ "$(adb -s "$target" shell getprop service.adb.tcp.port 2>/dev/null | tr -d '\r')" = 5555 ]; then
            port=$(adb -s "$target" forward tcp:0 tcp:5555 2>/dev/null | tr -d '\r')
            [ -n "$port" ] && break
        fi
        sleep 1
    done
    [ -n "$port" ] || { echo "Target adbd did not open port 5555" >&2; exit 1; }
    if [ "${3-}" = "--usb-gadget" ]; then
        "$root/tools/test/usb-gadget/setup.sh" "$host" "$target" "$port" "$work"
        set -- -e scenario usb -e port "$port"
    else
        set -- -e port "$port"
    fi
fi
source_file="$fixture/BreakpointActivity.java"
break_line=$(awk '/count\+\+;/{ print NR; exit }' "$source_file")
step_line=$(awk '/button.setText\("Hits " \+ count\)/{ print NR; exit }' "$source_file")
test -n "$break_line" && test -n "$step_line"
adb -s "$host" shell am instrument -w -r "$@" -e breakLine "$break_line" -e stepLine "$step_line" \
    com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest > "$work/result"
cat "$work/result"
grep -q '^INSTRUMENTATION_RESULT: result=PASS ' "$work/result"
