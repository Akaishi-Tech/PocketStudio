# Android Studio upstream sources

English · [简体中文](README.zh-CN.md)

Pocket Studio pins the upstream source projects to AOSP's `studio-2026.2.2` release. The release manifest is at commit `1fe8c3e0a622df7fc953a2335c2fb7916e758396`. [Local manifest](default.xml) · [Google's published manifest](https://android.googlesource.com/platform/manifest/+/1fe8c3e0a622df7fc953a2335c2fb7916e758396/default.xml)

| Path under `upstream/release/` | Contents | Pinned commit |
| --- | --- | --- |
| `tools/adt/idea` | Android Studio plugins, Logcat, device management, debuggers and designers | [20c7e933](https://android.googlesource.com/platform/tools/adt/idea/+/20c7e9335f817f368be3e519d29805ffe00e6dcc/) |
| `tools/base` | Android Gradle Plugin, ADB libraries and SDK tools | [602d2ddc](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/) |
| `tools/idea` | IntelliJ platform used by Android Studio | [51601cde](https://android.googlesource.com/platform/tools/idea/+/51601cde8d8b67db9da5098d7ad4f699df5a6205/) |

## Checking out the source

To **build Pocket Studio**, initialize just the `tools/base` submodule from the repository root. Run the same command after switching to a revision that pins a different dependency commit.

```sh
git submodule update --init --depth 1 -- upstream/release/tools/base
```

To inspect the **complete Linux desktop source tree**, run the following script from the root. It initializes the pinned submodules under `upstream/release/`. The `upstream/studio` symlink points to the same tree for compatibility.

```sh
./scripts/fetch-studio-source.sh
```

For a separate Google Repo workspace, pass a destination outside this checkout to the same script. That mode requires Google Repo and Git.

The full tree includes 43 projects in the Linux default group plus the optional `frameworks/layoutlib` renderer. The resolved manifest lists all 44 revisions. The original release manifest also lists four prebuilt repositories used only on other operating systems. [Resolved Linux manifest](resolved-linux.xml) · [Google's checkout instructions](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/source.md)

## Code used by the mobile app

The app gets ddmlib log levels, numeric priorities and character mappings from `Log.LogLevel` in the pinned `tools/base` source. The original `Log.java` has Git blob ID `6c23120ae1df96c261909cc8c95aad1605dcb565`. [AOSP Log.java](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/ddmlib/src/main/java/com/android/ddmlib/Log.java)

The mobile port of `FileResourceNameValidator` retains the upstream rules for resource types, nine-patch images, fonts, XML files, file names and Java keywords. It infers the resource type from the directory path and uses the standard library in place of desktop-only types. [Mobile validator](portable/com/hcgstudio/pocketstudio/upstream/FileResourceNameValidator.java) · [Upstream validator](https://android.googlesource.com/platform/tools/base/+/602d2ddc2b0098f62a657af26b90b0ac1034a6f6/sdk-common/src/main/java/com/android/ide/common/resources/FileResourceNameValidator.java)

The ddmlib code and resource filename validator use the Apache License 2.0. For other upstream modules, consult the file headers and NOTICE files in their respective repositories. [Apache License 2.0](LICENSE)
