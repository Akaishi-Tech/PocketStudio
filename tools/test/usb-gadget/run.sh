#!/usr/bin/env bash
# Creates and removes two disposable AVDs. No host USB device is forwarded.
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
sdk=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}
image=system-images/android-36/google_apis/x86_64
[[ -f $sdk/$image/system.img ]] || { echo "Install $image" >&2; exit 1; }
export PATH="$sdk/platform-tools:$PATH"
work=$(mktemp -d)
export ANDROID_AVD_HOME="$work/avd"
mkdir "$ANDROID_AVD_HOME"
host_port=${STUDIO_USB_HOST_PORT:-5592}
target_port=${STUDIO_USB_TARGET_PORT:-5594}
[[ $host_port != "$target_port" ]]
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || :; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || :; done
  rm -r -- "$work"
}
trap cleanup EXIT
boot() {
  local name=$1 port=$2
  if [[ $(adb -s "emulator-$port" get-state 2>/dev/null || :) == device ]]; then
    echo "Emulator port $port is already in use" >&2; return 1
  fi
  local dir="$ANDROID_AVD_HOME/$name.avd"
  mkdir "$dir"
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-36\n' "$dir" > "$ANDROID_AVD_HOME/$name.ini"
  cat > "$dir/config.ini" <<CONF
AvdId=$name
avd.ini.displayname=$name
abi.type=x86_64
hw.cpu.arch=x86_64
hw.lcd.width=1080
hw.lcd.height=2040
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.ramSize=2048
image.sysdir.1=$image/
disk.dataPartition.size=6442450944
tag.id=google_apis
tag.display=Google APIs
CONF
  "$sdk/emulator/emulator" -avd "$name" -port "$port" -no-window -no-snapshot -no-audio \
      -no-boot-anim -gpu swiftshader_indirect -cores 2 > "$work/$name.log" 2>&1 &
  pids+=("$!")
}
boot studio_usb_host "$host_port"
boot studio_usb_target "$target_port"
for port in "$host_port" "$target_port"; do
  timeout 120 adb -s "emulator-$port" wait-for-device
  for _ in {1..120}; do
    [[ $(adb -s "emulator-$port" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && break
    sleep 1
  done
  [[ $(adb -s "emulator-$port" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
done
"$root/tools/test/two-emulators.sh" "emulator-$host_port" "emulator-$target_port" --usb-gadget
