# Android 本机 OpenJDK

[English](JDK.md) · 简体中文

Android 开发环境插件随附 OpenJDK 17.0.21.0.2 的 Android/Bionic 构建，支持 arm64-v8a、armeabi-v7a、x86 和 x86_64。JDK 包含 HotSpot、javac、jar、jlink、jmod、标准模块和上游许可证。首次运行 Gradle 时，应用从插件 APK 展开对应 ABI 的 JDK。Android 的安装程序负责将原生启动器放到插件 APK 的可执行目录。项目 Wrapper 下载的 Gradle 发行版和项目声明的依赖另行缓存。

运行时采用 OpenJDK 的 GPLv2 和 Classpath Exception，第三方组件适用各自许可证。版本来源和 Android 补丁固定到以下构建。[OpenJDK 源码](https://github.com/openjdk/jdk17u/tree/jdk-17.0.21.0.2-ga) · [Android 构建脚本和补丁](https://github.com/MojoLauncher/android-openjdk-build-17-25/tree/1e90171cce3eca93c14c6ec59811bd88a9792f34) · [四种 ABI 的原始构建产物](https://github.com/MojoLauncher/android-openjdk-build-17-25/actions/runs/39842631198)

## 完整性与重新打包

在仓库根目录核对 APK 构建所用的 JDK 数据和启动器。

~~~sh
sha256sum -c build-support/JDK-SHA256SUMS
~~~

重新打包需要 Python 3.12 或更高版本、xdelta3、GitHub CLI 以及读取公开 Actions 产物所需的 GitHub 登录。脚本固定了四个原始产物的 ID 和 SHA-256。传入的目录用于缓存原始产物及展开后的 JDK。

~~~sh
python3 scripts/package-local-jdk.py /path/to/jdk-cache
~~~

GitHub 的 Actions 产物有保留期限。也可从上述固定提交按上游工作流设置 `TARGET_VERSION=17`，分别运行 `1_ci_build_arch_aarch64.sh`、`1_ci_build_arch_aarch32.sh`、`1_ci_build_arch_x86.sh` 和 `1_ci_build_arch_x86_64.sh`。将得到的完整 JDK 分别展开到缓存目录的 `arm64-v8a`、`armeabi-v7a`、`x86` 和 `x86_64` 子目录，再运行打包脚本。重新编译的产物可能包含不同构建时间，需按新产物更新校验清单。

~~~sh
python3 scripts/package-local-jdk.py /path/to/jdk-cache --from-images
~~~

打包器把相同的数据放入共享的 ZIP 文件，并为其他 ABI 的模块和 JMOD 保存标准 VCDIFF 差异。安装器按 1 MiB 窗口还原原始文件。每个原生启动器由 APK 安装器按 CPU 架构部署。Gradle 在 Android 上使用纯 Java 的操作系统集成功能。[VCDIFF 格式](https://www.rfc-editor.org/rfc/rfc3284) · [vcdiff-java 解码器和 Apache 2.0 许可](https://github.com/ehrmann/vcdiff-java)

## 已核对的运行条件

已在 Android 16 x86_64 模拟器上运行 javac、jmod 和 jlink，分别完成编译、读取 JMOD 和生成只含 java.base 的运行时。两种 64 位 ABI 的 Java 启动器、HotSpot 和 JDK 核心动态库具有 16 KiB ELF LOAD 对齐。上游的可选 AWT 字体库 `libfreetype.so` 为 4 KiB 对齐，在使用 16 KiB 页面的系统上依赖 AWT 字体的任务需要单独验证。
