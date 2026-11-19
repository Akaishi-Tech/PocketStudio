# 设备内构建

[English](README.md) · 简体中文

首次构建 Android 工程前，请在应用的 SDK Manager 安装与项目 `compileSdk` 匹配的 Android SDK Platform。它从 Google 的包目录获取稳定版本，下载时验证大小和校验和，也可从系统文件选择器导入官方平台 ZIP。卸载和重新安装在应用私有目录内完成。在「输出 → 输出选项 → 构建 SDK」中可为当前项目的快速构建和 Gradle 任务选择其他已安装的平台。下载一次后，快速构建可以离线运行。

用 Gradle 构建示例 Android 工程时，在 SDK Manager 中安装与项目匹配的 SDK Platform 和「Android SDK Build Tools 36.0.0」。Build Tools 可直接下载，或导入 Google 官方 ZIP（61 MiB）。Studio 会把这套 SDK 和适合当前手机处理器的 AAPT2、ADB 提供给 Gradle。官方 ZIP 中其余原生程序面向桌面 Linux，单独调用 AIDL、zipalign 等程序的任务需要相应的 Android 主机版工具。[下载 Build Tools 36.0.0](https://dl.google.com/android/repository/build-tools_r36_linux.zip)

内置构建器支持 Android 11 及以上宿主、单模块 `app`、常量 Gradle 配置，以及 `src/main/java`、`kotlin`、`res` 和 `assets`。纯 Java 工程使用 AAPT2 编译资源、ECJ 编译 Java 7，并用 DX 保留行号和局部变量。构建含 Kotlin 源码的项目，需先在 SDK Manager 安装可选的 Kotlin / D8 编译工具。apksig 使用 Android Keystore 生成的 RSA 调试密钥签名，支持覆盖安装同一 IDE 安装实例签出的调试包。四种 Android ABI 的 AAPT2 均使用 16 KiB ELF LOAD 对齐。[组件来源和许可证](assets/licenses/TOOLS.txt)

在「构建」菜单选择 Gradle 任务，例如 `assembleDebug`、`test`、`lint` 或 `tasks`。选择「自定义 Gradle 任务」可以输入模块限定名（如 `:app:assembleDebug`），多个任务用空格分隔。点击「在本机运行」后，Studio 使用随应用提供的 OpenJDK 17，在当前手机上执行工程的 Gradle Wrapper。首次运行可能需要联网下载 Wrapper 指定的 Gradle 发行版以及项目插件和依赖。任务日志会显示在「构建输出」，运行中可点击「停止」。任务生成多个 APK 时，可以选择要导出或安装的产物。

快速构建和 Gradle 都在手机上运行，源码改动和生成物会留在所选项目中。ZIP 导入的项目直接使用应用私有目录。Android 11 及以上设备启动掌上 Studio 时会请求所有文件访问权限。授权后，Gradle 可以直接在所选共享存储目录中构建。跳过授权时，Studio 会通过系统文档服务同步项目文件。构建脚本拥有应用的存储权限，应选择自己信任的项目。

云盘等仅提供文档 URI 的位置和未授予直接访问权限的共享目录使用应用私有工作副本。每次运行前接收目录的最新更改，工具退出后将新增、修改和删除的文件同步回所选目录，包括 APK、测试报告和生成的源码。同步按文件内容检测冲突。两边修改同一路径时，所选目录保留文档服务中的版本，工具的版本保存在项目根目录的 `Studio-Recovered-…` 文件夹。中途关闭应用后，下一次运行会继续同步。原有的 `local.properties` 会保留。

## 快速构建回归

使用一台专用模拟器，将官方 Android SDK Platform 34 ZIP 提供给首次运行的测试。测试会构造临时 Java 工程并验证资源、DEX、签名、重复构建、SDK 缺失和不兼容配置的诊断。

~~~sh
./gradlew :app:assembleX86_64Debug :app:assembleX86_64DebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk
adb -s SERIAL shell -T 'run-as com.hcgstudio.pocketstudio sh -c "mkdir -p cache && cat >cache/platform-34-test.zip"' < platform-34-ext7_r03.zip
adb -s SERIAL shell am instrument -w -e scenario build com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
~~~

从仓库根目录运行 `sha256sum -c build-support/SHA256SUMS` 核对四种 ABI 的 AAPT2。

## 本机 Gradle 回归

安装上述两个测试 APK 后，`gradle` 场景会运行自定义任务，检查产物、失败日志和任务取消。另一个场景 `gradleAndroid` 使用新建 Android 工程的模板执行 `:app:assembleDebug`，并检查生成的 APK。它使用 SDK Manager 已安装的平台和 Build Tools，也可按上方示例将官方 ZIP 存入测试缓存。

~~~sh
adb -s SERIAL shell am instrument -w -e scenario gradle com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
adb -s SERIAL shell -T 'run-as com.hcgstudio.pocketstudio sh -c "mkdir -p cache && cat >cache/build-tools-test.zip"' < build-tools_r36_linux.zip
adb -s SERIAL shell am instrument -w -e scenario gradleAndroid com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest
~~~

要覆盖系统文件选择器，可先在 Studio 中打开一个测试文件夹，再给 `gradleAndroid` 传入 `-e tree` 和已授权的目录 URI。该场景会在选定位置创建并清理本次测试工程。

[APK 内置 OpenJDK 的来源、校验与重新打包](JDK.zh-CN.md)

## 发布

在 **Release Pocket Studio** 选择 `main` 分支和版本增量（默认 `patch`）。工作流分配下一版本，在对应的版本标签下分别上传 ARM64、ARMv7、x86_64 和 x86 的签名 `PocketStudio-<abi>.apk`。[主应用发布工作流](https://github.com/Akaishi-Tech/PocketStudio/actions/workflows/app-release.yml)

主应用工作流使用 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD` 四项 Actions Secrets。首项为发布密钥库的 Base64 编码。工作流根据内置公开证书验证每份 APK 的签名、包名、ABI 和版本。[公开签名证书](../app/src/main/res/raw/release_signer.der)

插件目录、图标和各 ABI 的 Codex APK 由独立仓库发布。应用的插件商店会选择与设备匹配的安装包。[插件发布仓库](https://github.com/Akaishi-Tech/PocketStudio-Plugins)

旧调试版 IDE 如果使用不同签名，换装正式版前请先导出应用私有目录中的重要文件。Codex 插件的现有包名和签名延续使用，更新可保留已有配置。
