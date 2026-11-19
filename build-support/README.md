# Building projects on Android

English · [简体中文](README.zh-CN.md)

Before building a project on the device, install the Android SDK Platform matching its `compileSdk` in Pocket Studio's SDK Manager. SDK Manager lists stable platforms from Google's package catalog and verifies download sizes and checksums. You can also import an official platform ZIP from the system file picker. Packages are installed in the app's private directory, where you can remove or reinstall them through SDK Manager. Use **Output → Output options → Build SDK** to choose another installed platform for quick builds and Gradle in the current project. After the initial download, the quick builder works offline.

For a Gradle build of the sample Android project, install the project's SDK Platform and **Android SDK Build Tools 36.0.0** in SDK Manager. Download the Build Tools there or import Google's official ZIP (61 MiB). Studio makes this SDK and versions of AAPT2 and ADB built for the device's CPU available to Gradle. Other native programs in Google's ZIP target desktop Linux. Tasks that invoke AIDL or zipalign directly need versions that run on an Android host. [Download Build Tools 36.0.0](https://dl.google.com/android/repository/build-tools_r36_linux.zip)

The quick builder requires Android 11 or later. It supports a single `app` module with constant Gradle configuration values and sources under `src/main/java`, `kotlin`, `res` and `assets`. For Java-only projects, AAPT2 compiles resources, ECJ compiles Java 7 and DX preserves line numbers and local variables. For Kotlin sources, install the optional Kotlin / D8 tools in SDK Manager. The builder uses apksig and an RSA debug key generated in Android Keystore. APKs signed by the same installation of Studio can update each other. AAPT2 uses 16 KiB ELF LOAD alignment on all four Android ABIs. [Component sources and licenses](assets/licenses/TOOLS.txt)

To run Gradle, choose a task from the Build menu, such as `assembleDebug`, `test`, `lint` or `tasks`. Use the custom task option for module-qualified names such as `:app:assembleDebug` or for multiple tasks separated by spaces. Run the task locally to execute the project's own Gradle Wrapper using the bundled OpenJDK 17. The first run may need internet access to download the Gradle distribution, plugins and dependencies. View logs in the build output or stop the running task there. If the task produces multiple APKs, select which one to export or install.

Quick builds and Gradle both run on the device. Source edits and build products are written to the selected project. Projects imported from ZIP run in the app's private directory. On Android 11 or later, Studio requests all-files access at startup so Gradle can use the selected shared-storage folder as its working directory. If you skip the request, Studio syncs project files through the system document provider. Build scripts inherit the app's storage permissions, so choose projects you trust.

For cloud providers that expose document URIs and shared folders without direct access, Studio uses a private working copy. Studio reads the latest files before each run and syncs new, modified and deleted files back after the tools exit, including APKs, test reports and generated sources. It detects conflicts by comparing file contents. If both sides changed the same path, the selected folder keeps the document provider's version and Studio stores the tool's version under `Studio-Recovered-…` in the project root. An interrupted sync resumes on the next run. Existing `local.properties` files are preserved.

## Quick builder regression test

Use a dedicated emulator and provide the official Android SDK Platform 34 ZIP for the first run. The test creates a temporary Java project and checks resources, DEX, signatures, repeat builds, and diagnostics for missing platforms and unsupported configurations.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell -T 'run-as com.hcgstudio.pocketstudio sh -c "mkdir -p cache && cat >cache/platform-34-test.zip"' < platform-34-ext7_r03.zip
adb -s SERIAL shell am instrument -w -e scenario build com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
```

Run `sha256sum -c build-support/SHA256SUMS` from the repository root to verify AAPT2 for all four ABIs.

## Local Gradle regression tests

After installing the two test APKs above, run the `gradle` scenario to execute a custom task and check its output, failure logs and cancellation. The `gradleAndroid` scenario runs `:app:assembleDebug` on the new-project Android template and checks the APK. It uses the platform and Build Tools installed in SDK Manager, or the official ZIPs supplied through the test cache.

```sh
adb -s SERIAL shell am instrument -w -e scenario gradle com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
adb -s SERIAL shell -T 'run-as com.hcgstudio.pocketstudio sh -c "mkdir -p cache && cat >cache/build-tools-test.zip"' < build-tools_r36_linux.zip
adb -s SERIAL shell am instrument -w -e scenario gradleAndroid com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
```

To exercise the system file picker, first open a test folder in Studio. Pass `-e tree` and the granted directory URI to `gradleAndroid`. The scenario creates and removes its test project in that folder. [Bundled OpenJDK sources, verification and repackaging](JDK.md)

## Publishing the two APKs

In **Release Pocket Studio**, choose **Run workflow**, select `main` and choose the version component to increment (default `patch`). [Manual release workflow](https://github.com/Akaishi-Tech/PocketStudio/actions/workflows/app-release.yml) It assigns the next app version, signs both APKs, and publishes them together under a version tag. Install `PocketStudio.apk` for the IDE and `PocketStudio-Codex-Plugin.apk` for the optional Codex plugin. The app can also download the plugin from the fixed `plugin-store` release in **Settings → Plugins**. Android verifies updates against the installed signing certificate.

The first app or plugin release initializes the catalog. A previously published runtime channel also enables scheduled migration. After that, **Update plugin store** is scheduled daily at 03:23 UTC and checks the signed Termux indexes for ARM64 and x86_64. A change to the Codex packages, required libraries or packaging inputs produces an updated plugin. Its update does not advance the IDE version. The release pipeline packages the native binaries and their notices before distribution.

The workflows read four repository Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The first is the Base64 encoding of the release keystore. Keep a separate offline backup of the keystore and password. The app includes only the [public signing certificate](../app/src/main/res/raw/release_signer.der).

Older development builds may use a different signing certificate. Before replacing a debug-signed IDE with the release build, export any files that exist only in its private storage. The previous Codex runtime has a different Android package ID. Install the new Codex Plugin from Settings to use it with this version of Studio.

The plugin retains the Codex and OpenSSL license notices. The Apache 2.0 text referenced by the OpenSSL package is included in the packaging inputs. [License text](codex-plugin/termux-Apache-2.0.txt)
