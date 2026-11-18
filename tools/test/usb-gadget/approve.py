"""Accept only the fixture's system USB permission dialog on a disposable test emulator."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", "-s", sys.argv[1], *args], timeout=8)


if not sys.argv[1].startswith("emulator-") or adb("shell", "getprop", "ro.boot.qemu.avd_name").strip() != b"studio_usb_host":
    sys.exit("Unexpected host AVD")

deadline = time.monotonic() + 65
while time.monotonic() < deadline:
    try:
        adb("shell", "uiautomator", "dump", "/data/local/tmp/studio-usb-prompt.xml")
        xml = ET.fromstring(adb("exec-out", "cat", "/data/local/tmp/studio-usb-prompt.xml"))
        nodes = list(xml.iter("node"))
        prompts = [node.get("text", "") for node in nodes if node.get("package") == "com.android.systemui"]
        app_names = ("Pocket Studio", "掌上 Studio")
        if not any("Developer phone" in prompt and any(name in prompt for name in app_names) for prompt in prompts):
            time.sleep(0.4)
            continue
        button = next(n for n in nodes if n.get("resource-id") == "android:id/button1"
                      and n.get("package") == "com.android.systemui")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", button.get("bounds", "")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        sys.exit(0)
    except (subprocess.SubprocessError, ET.ParseError, StopIteration):
        time.sleep(0.4)
sys.exit("Expected system USB permission dialog did not appear")
