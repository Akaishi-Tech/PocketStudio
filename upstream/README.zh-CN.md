# Android Studio 上游源码

[English](README.md) · 简体中文

本项目选用 AOSP 发布标签 `studio-2026.2.2`。完整发布清单的提交为 `1fe8c3e0a622df7fc953a2335c2fb7916e758396`。[本地清单](default.xml) · [Google 发布清单](https://android.googlesource.com/platform/manifest/+/1fe8c3e0a622df7fc953a2335c2fb7916e758396/default.xml)

| `upstream/release/` 下的目录 | 内容 | 发布提交 |
| --- | --- | --- |
| `tools/adt/idea` | Android Studio 插件、Logcat、设备管理、调试器及设计器 | [20c7e933](https://android.googlesource.com/platform/tools/adt/idea/+/20c7e9335f817f368be3e519d29805ffe00e6dcc/) |
| `tools/base` | Android Gradle Plugin、ADB 库及 SDK 工具 | [602d2ddc](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/) |
| `tools/idea` | Android Studio 所用的 IntelliJ 平台 | [51601cde](https://android.googlesource.com/platform/tools/idea/+/51601cde8d8b67db9da5098d7ad4f699df5a6205/) |

## 获取源码

**构建掌上 Studio** 时，只需在项目根目录初始化 `tools/base` 子模块。切换到依赖版本不同的提交后，再运行一次相同命令即可。

```sh
git submodule update --init --depth 1 -- upstream/release/tools/base
```

如需查看**完整 Linux 桌面源码树**，在项目根目录运行下列脚本。它在 `upstream/release/` 下检出固定版本的子模块。`upstream/studio` 通过相对符号链接指向同一目录。

```sh
./scripts/fetch-studio-source.sh
```

如需另行创建独立的 Google Repo 工作区，可将仓库之外的目标目录作为参数传给同一脚本。此模式需要安装 Google Repo 和 Git。

完整源码树包括 Linux 默认组的 43 个项目和可选的 `frameworks/layoutlib` 渲染器，共 44 项。原始发布清单中另有四个仅供其他操作系统使用的预编译工具仓。[Linux 项目及发布提交](resolved-linux.xml) · [Google 的源码检出说明](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/source.md)

## 移动应用使用的代码

移动 APK 使用固定版本 `tools/base` 中 ddmlib 的 `Log.LogLevel`，包括日志级别、优先级及字符映射。`Log.java` 的 Git blob 为 `6c23120ae1df96c261909cc8c95aad1605dcb565`。[AOSP Log.java](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/ddmlib/src/main/java/com/android/ddmlib/Log.java)

`FileResourceNameValidator` 移植自 sdk-common 的同名类，保留资源类型、九宫格图片、字体、XML、文件名和 Java 关键字规则。移动实现通过目录路径识别资源类型，使用标准库代替桌面端类型。[移动端实现](../build-support/android-plugin/src/main/java/com/hcgstudio/pocketstudio/androidplugin/FileResourceNameValidator.java) · [AOSP 文件及资源名校验源码](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/sdk-common/src/main/java/com/android/ide/common/resources/FileResourceNameValidator.java)

ddmlib 日志代码及资源文件名校验器采用 Apache License 2.0。其他上游模块的许可证见对应源码的文件头和 NOTICE。[Apache License 2.0](LICENSE)
