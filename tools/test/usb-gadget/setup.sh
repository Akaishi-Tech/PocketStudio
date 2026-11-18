#!/usr/bin/env bash
# Invoked only for the explicitly named disposable AVD pair.
set -euo pipefail
host=$1 target=$2 port=$3 work=$4
here=$(cd "$(dirname "$0")" && pwd)
trap 'echo "USB AVD setup failed at line $LINENO" >&2; if [[ -f $work/bridge.log ]]; then cat "$work/bridge.log" >&2; fi' ERR
for entry in "$host:studio_usb_host" "$target:studio_usb_target"; do
  serial=${entry%%:*}
  [[ $serial == emulator-* ]]
  [[ $(adb -s "$serial" shell getprop ro.boot.qemu.avd_name | tr -d '\r') == "${entry#*:}" ]]
done
[[ $(uname -sm) == 'Linux x86_64' ]]
cc -O2 -static -pthread "$here/bridge.c" -o "$work/bridge"
adb -s "$host" root
for i in {1..30}; do
  [[ $(adb -s "$host" shell id -u 2>/dev/null | tr -d '\r') == 0 ]] && break
  sleep 1
done
[[ $(adb -s "$host" shell id -u | tr -d '\r') == 0 ]]
if ! adb -s "$host" shell pm has-feature android.hardware.usb.host | grep -q true; then
  feature=/vendor/etc/permissions/android.hardware.bluetooth.xml
  adb -s "$host" exec-out cat "$feature" | sed 's@</permissions>@<feature name="android.hardware.usb.host" /></permissions>@' > "$work/usb-host.xml"
  grep -q android.hardware.usb.host "$work/usb-host.xml"
  adb -s "$host" push "$work/usb-host.xml" /data/local/tmp/studio-usb-host.xml
  adb -s "$host" shell "chcon u:object_r:vendor_configs_file:s0 /data/local/tmp/studio-usb-host.xml && nsenter -t 1 -m -- mount --bind /data/local/tmp/studio-usb-host.xml $feature && stop && start"
fi
for i in {1..60}; do
  adb -s "$host" shell dumpsys usb 2>/dev/null | grep 'host_manager=' >/dev/null && break
  sleep 1
done
adb -s "$host" shell dumpsys usb | grep 'host_manager=' >/dev/null
adb -s "$host" push "$work/bridge" /data/local/tmp/studio-usb-bridge
adb -s "$host" push "$here/configure-device.sh" /data/local/tmp/studio-usb-setup.sh
adb -s "$host" shell sh /data/local/tmp/studio-usb-setup.sh
# Tunnel only the emulated peripheral's backend through the two emulator consoles.
# Loopback stays available while a fresh Android guest is bringing up Wi-Fi.
adb -s "$host" reverse "tcp:$port" "tcp:$port"
adb -s "$host" shell -T "cd /data/local/tmp/studio-ffs && /data/local/tmp/studio-usb-bridge $port" > "$work/bridge.log" 2>&1 </dev/null &
for i in {1..60}; do
  adb -s "$host" shell test -e /data/local/tmp/studio-ffs/ep2 && break
  sleep 0.25
done
adb -s "$host" shell test -e /data/local/tmp/studio-ffs/ep2
adb -s "$host" shell 'echo dummy_udc.0 >/config/usb_gadget/g1/UDC'
for i in {1..30}; do
  adb -s "$host" shell dumpsys usb | grep 'manufacturer_name=OnDeviceTest' >/dev/null && break
  sleep 0.25
done
adb -s "$host" shell dumpsys usb | grep -E 'manufacturer_name=OnDeviceTest|product_name=Developer phone|serial_number=TARGET5594'
adb -s "$host" shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard'
python3 "$here/approve.py" "$host" > "$work/usb-permission.log" 2>&1 </dev/null &
