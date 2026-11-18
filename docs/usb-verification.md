# Verifying USB debugging between Android devices

English · [简体中文](usb-verification.zh-CN.md)

The USB debugging test runs Pocket Studio and the target app on two independent API 36 x86_64 emulators. It exercises Android's USB Host API, system permission dialog and USB bulk endpoints. Physical OTG power delivery, cables, role negotiation and vendor USB drivers still require verification on real devices.

## Test setup

```text
Host emulator
  Pocket Studio → UsbManager → Android USB permission dialog
  → UsbDeviceConnection.bulkTransfer → Linux dummy_hcd → FunctionFS ADB peripheral
                                                            │
                                               ADB packets forwarded over TCP
                                                            ↓
Target emulator                                         adbd → ART → test Activity
```

The virtual USB peripheral reports VID `18d1`, PID `4ee7` and the standard ADB interface `ff/42/01`. In the November 18, 2026 test, Android enumerated it as `/dev/bus/usb/001/002`, with manufacturer `OnDeviceTest` and name `Developer phone`. The host displayed “Allow 掌上 Studio to access Developer phone?” After approval, Studio opened the device under its normal application UID and used the USB endpoints. The peripheral-side bridge forwarded ADB packets, and the second emulator's adbd handled installation, shell and debugging requests.

## Observed results

| Operation | Result |
| --- | --- |
| Connection and APK installation | A USB shell returned the target AVD name `studio_usb_target`. PackageManager on the target installed the APK and returned its path for `example.hello`. |
| Logcat and process selection | The host read the log marker generated for this run and found the target process in `track-jdwp`. |
| Breakpoint and variables | Tapping the target button suspended execution at `MainActivity$1.onClick:18`. The method appeared in the stack, and the local parameter `view` was an `android.widget.Button`. |
| Step, resume and detach | The debugger stepped to line 19. After resuming, the button displayed `Hello 1`. The debugger detached successfully. |

This system image disables adbd's RSA authorization prompt by default. A separate protocol test used Android Keystore to verify the standard RSA challenge, signature, ADB public key format and persistence. The emulator test exercised the host's USB device access prompt. The target's “Allow USB debugging” RSA prompt still requires a physical-device test.

## Running the test

Use a Linux x86_64 workstation, a C compiler capable of static linking, Linux FunctionFS development headers, Python 3 and an Android SDK with the `system-images;android-36;google_apis;x86_64` image. Follow the [Pocket Studio build instructions](../README.md#building-pocket-studio) once to cache the Gradle dependencies. The script uses the SDK's AAPT2, D8 and apksigner to build the test activity. It creates two temporary AVDs on ports 5592 and 5594 by default. It uses root inside the Android guest to configure the virtual USB controller and runs as a normal user on the workstation.

```sh
export ANDROID_HOME=/path/to/your/android-sdk
tools/test/usb-gadget/run.sh
```

A successful instrumentation run includes the following result.

```text
PASS cross-device install, logcat, JDWP list, breakpoint, stack, locals, step, resume, detach
```

To check packet framing, zero-length USB packets, disconnects and key signatures separately, run `tools/test/usb/run.sh`. Append a test emulator's serial number to exercise the authorization protocol with Android Keystore.
