# Wireless debugging between Android devices

English · [简体中文](wireless-debugging.zh-CN.md)

Install Pocket Studio on the host and use a target running Android 11 or later. Connect both devices to the same Wi-Fi network with peer-to-peer traffic allowed.

1. On the target, enable **Developer options → Wireless debugging** and approve the network when prompted. Write down the IP address and the **connection port** on this screen.
2. Tap **Pair device with pairing code**. Leave the dialog open and note the six-digit code and the **pairing port** shown with it.
3. On the host, choose wireless pairing from the Debug menu. Enter the target IP, pairing port and code. When pairing succeeds, the host appears in the target's list of paired devices.
4. The host opens a connection dialog. Enter the **connection port** from the main Wireless debugging screen. The host can reconnect directly to this port in later sessions. Check the port again after changing networks or restarting wireless debugging.
5. Install and launch the target app's debug APK, then attach to its process by package name. Select an executable source line and press Ctrl+F8, or enter the fully qualified class name and line number in the debugger. Trigger the line on the target. Use the host to inspect the stack and variables, press F8 to step over or F9 to resume.

The host stores its pairing key in the app's private directory. Clearing Studio's app data requires pairing again. To revoke access, remove the host from the paired devices list on the target.

## Verifying with two emulators

Start two disposable Android emulators that allow test APK installation. Open the six-digit pairing code dialog on the target, then run the following command from the project root. Replace the serial numbers with your own. The last two arguments are the target's pairing port and connection port, in that order. Enter the six-digit code when prompted.

```sh
tools/test/two-emulators.sh emulator-5580 emulator-5582 --wireless PAIR_PORT CONNECT_PORT
```

Use the project's JDK, Gradle cache and Android SDK. Set `ANDROID_HOME` to the installed SDK. The script forwards the two ports through the workstation's loopback interface to connect the emulators' separate virtual networks. The ADB component in Pocket Studio on the host emulator performs pairing and establishes the TLS connection. On physical devices sharing a LAN, use the address and ports shown in the target's settings.

The two-emulator test passed on Android 16 (API 36), x86_64, with 4 KiB pages. It covered pairing keys, APK installation, Logcat, attaching to ART, source breakpoints, local variables, stepping and the target app's state after resuming. Each run compiles a dedicated test activity. Physical Wi-Fi networks, other CPU architectures and 16 KiB page sizes remain unverified by this test.

## Verifying same-device debugging

Start one test emulator connected to Wi-Fi. Enable wireless debugging and leave **Pair device with pairing code** open. Replace the serial number below with the emulator's serial and enter the code shown in Settings when prompted.

```sh
tools/test/two-emulators.sh emulator-5584 --local
```

The script installs Studio, the instrumentation APK and a debuggable sample on the same emulator. It tests port discovery, local pairing and reconnection with the saved key. The test harness also accepts explicit ports after `--local` for transport diagnostics. To test the app interface, open a project, choose **Debug → Debug this device**, open pairing settings and reply to the notification with the six-digit code from Settings.

On an Android 16 (API 36) x86_64 emulator, the notification flow was verified with a six-digit code, a second reply after entering too few digits, and reconnection after reopening Studio. The transport test on a 4 KiB page emulator also covered installing and launching the sample, reading Logcat, hitting a source breakpoint, inspecting local variables, stepping, resuming and detaching.
