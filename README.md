# Pocket Studio

English · [简体中文](README.zh-CN.md)

Pocket Studio is an Android development environment that runs on a phone or tablet. You can edit a project, build an APK and debug it on the same device or on a second Android device. On phones, the bottom bar switches between the project, editor, agent, debugger and build output. At widths of 600 dp or more, the project tree and editor sit side by side.

Pocket Studio runs on Android 8.0 or later. The interface follows the device language and falls back to English. A Simplified Chinese translation is included.

![Project tree and code editor on a tablet](docs/screenshots/tablet.png)

## Getting started

Install `PocketStudio.apk` from [GitHub Releases](https://github.com/Akaishi-Tech/PocketStudio/releases) or [build it from source](#building-pocket-studio). On the welcome screen, create a project, enter its name, confirm the package name and choose a parent folder in the system file picker. Studio creates a project folder and opens `MainActivity.java`. You can also create a project from the File menu in an existing workspace.

The template uses Java and XML. On Android 11 or later, install SDK Platform 34 in SDK Manager, then use the Build menu to build your first debug APK.

| Workspace | What you can do |
| --- | --- |
| Project | Open a folder with the system picker, expand the project tree, create files and folders, find a file by path or search text across files. |
| Editor | Edit Java, Kotlin, XML and Gradle files with line numbers, syntax highlighting and identifier completion. Undo, redo, find and replace text, jump to a line and recover unsaved drafts. |
| Agents | Use Codex in the current project and review chat history, Markdown replies, file operations and build details. |
| Layout preview | Open a layout XML file and preview it from the View menu. The preview supports common Android views and project string, color and dimension resources. |
| Build | Build a single-module Java app offline, package resources, locate compiler errors, sign, export and install the APK. Kotlin projects can use the optional Kotlin / D8 tools. |
| Gradle and Git | Run the project's Gradle Wrapper on the device for builds, tests, lint or custom tasks. Inspect local Git status, diffs and history. |
| Devices | Select a target, install an APK, launch an app, run shell commands, filter live Logcat output by text or level, and inspect the target's screen and view hierarchy. |
| Debugger | List debuggable processes, attach to ART, add and remove source breakpoints, inspect the stack and current frame variables, step, resume and detach. |

The debug APK action runs the built-in quick builder. To run a task defined by the project, select a Gradle task in the Build menu and run it locally. Its log appears in the build output. [On-device build guide](build-support/README.md)

[Phone workspace](docs/screenshots/phone.png) · [Debugger](docs/screenshots/debugger.png)

## Using Codex in a project

Open **Agents** in a project and choose **Download Codex**. Studio downloads the signed `PocketStudio-Codex.apk` from this repository’s `codex-runtime` GitHub Release, verifies its size, SHA-256 digest, package name and signing certificate, and opens the Android installer. The plugin is named **Pocket Studio - Codex Plugin** in English. It includes the native Codex runtime for ARM64 and x86_64. You can also install it alongside the main APK from the same app release. Studio checks for plugin updates when you open Agents and lets you decide when to install them.

Open the account and usage panel in the top-right corner. Choose **Continue with ChatGPT** to authorize your plan in the system browser, or **API Key** to use your own OpenAI API account. In plan mode, the panel shows the remaining percentage when the service reports a usage window. You can also open ChatGPT from the panel to check usage. API key usage is billed to the associated API account. [ChatGPT plan usage](https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites)

Start a new chat to work with the current project. Codex can list, read and edit files. It asks for confirmation before running Gradle tasks. Expand a tool summary to inspect its actions and results. The editor keeps unsaved drafts and reports conflicts when a file changes on disk.

[Chat and tool details on a phone](docs/screenshots/agents.png)

## Installing and debugging on the same device

**Install an APK.** After a successful build, install the APK from the build output and confirm in the system installer. On first use, Android may ask you to allow Pocket Studio to install unknown apps. You can also tap an APK in the project tree. Local installation works on Android 8.0 or later with Developer options turned off.

**Debug an app on the same device.** On Android 11 or later, connect to Wi-Fi and follow these steps.

1. Connect to Wi-Fi, open a project and choose **Debug → Debug this device**. Open pairing settings and allow notifications when prompted.
2. In Android's **Developer options → Wireless debugging**, enable wireless debugging and tap **Pair device with pairing code**. Leave the system dialog open, pull down the notification shade and reply to the Studio notification with the six-digit code.
3. Studio discovers the local connection port after pairing. Once connected, build and run the project on the device from the Build menu, then attach to its process. You can also attach to an existing debuggable app. Trigger a breakpoint and return to Studio to inspect the stack and local variables or resume execution.
4. On subsequent sessions, enable wireless debugging and choose **Debug this device** to reconnect using the saved key. Remove the device from the paired devices list in Settings to revoke access.

## Debugging a second Android device

1. Enable USB debugging in Developer options on the target. Use a data-capable USB cable and connect the devices with the phone running Pocket Studio acting as the USB host.
2. Connect over USB from the Debug menu. Select the target, grant USB access on the host and accept the debugging authorization on the target.
3. Install and launch a debug APK with debugging information. With the target connected, the build-and-run action in the Build menu builds, installs and launches the project.
4. Attach to the target application's process. In source code matching the installed APK, tap a line number or enter a loaded class's fully qualified name and an executable line number in the debugger.
5. Trigger that line on the target. On the host, inspect the suspended thread's stack and variables, step or resume. In the sample app, set a breakpoint on `count++;` and tap the target app's button.

For a wireless connection, put both devices on the same LAN. On the target, open **Developer options → Wireless debugging → Pair device with pairing code**. Use wireless pairing on the host with the target's pairing port and six-digit code. After pairing, connect through the port on the target's main Wireless debugging screen. Already paired devices can reconnect directly through this port. [Wireless debugging and verification](docs/wireless-debugging.md)

Production Android devices expose processes whose apps allow debugging. Source breakpoints and variable inspection require the corresponding debug information in the APK. The ADB TCP option supports development emulators and devices already listening for classic TCP connections. [Android debugging guide](https://developer.android.com/studio/debug)

## AOSP sources

Pocket Studio uses the AOSP release tag `studio-2026.2.2`. Android Studio's plugin lives in `tools/adt/idea`, the IntelliJ platform in `tools/idea`, and the Android tools libraries in `tools/base`. The mobile app uses AOSP's ddmlib log levels, resource filename validation rules and Android build tools. Its workspace uses Jetpack Compose and the Android USB Host API. Desktop features such as full IntelliJ semantic indexing and refactoring, Compose preview, Profiler, LLDB/C++ debugging, AVD management and the desktop plugin system remain outside the mobile port. [Pinned revisions, licenses and checkout instructions](upstream/README.md)

## Building Pocket Studio

Install JDK 17 or 21, Android SDK Platform 37 and matching Build Tools on your development machine. Set `ANDROID_HOME` or put `sdk.dir` in `local.properties`. From the checkout root, initialize the AOSP tools dependency, then run the build.

```sh
git submodule update --init --depth 1 -- upstream/release/tools/base
./gradlew :app:assembleDebug :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With the Android SDK's platform tools on your path, install it on a connected device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The two-device tests also use ADB and two running test emulators. They install a sample on the target and exercise Logcat, process discovery, breakpoints, stack inspection, variables, stepping, resuming and detaching. The USB tests cover packet framing, disconnects, concurrency and Android Keystore signatures. A separate end-to-end test uses Android's USB Host permission dialog and virtual bulk endpoints on two independent emulators. Physical OTG cables, role negotiation and vendor drivers still need testing on real devices. [USB test setup and results](docs/usb-verification.md)

```sh
tools/test/jdwp/run.sh
tools/test/usb/run.sh emulator-HOST
tools/test/two-emulators.sh emulator-HOST emulator-TARGET
```

## Licenses

Pocket Studio's original code is available under the [MIT license](LICENSE). AOSP code and other bundled components retain their respective licenses. Open **Open-source licenses** from the welcome screen or the workspace's Tools menu to read them on the device. [AOSP source notes](upstream/README.md) · [Third-party notices](build-support/assets/licenses/)
