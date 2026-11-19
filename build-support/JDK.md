# OpenJDK on Android

English · [简体中文](JDK.zh-CN.md)

The Android development environment plugin bundles OpenJDK 17.0.21.0.2 built for Android's Bionic C library on arm64-v8a, armeabi-v7a, x86 and x86_64. The JDK includes HotSpot, javac, jar, jlink, jmod, the standard modules and upstream licenses. On the first Gradle run, Studio extracts the JDK for the device's ABI from the APK. The Android package installer places the native launchers in the plugin's executable library directory. The project's Gradle distribution and dependencies are cached separately.

The runtime uses OpenJDK's GPLv2 with the Classpath Exception. Third-party components retain their respective licenses. [OpenJDK source](https://github.com/openjdk/jdk17u/tree/jdk-17.0.21.0.2-ga) · [Pinned Android build scripts and patches](https://github.com/MojoLauncher/android-openjdk-build-17-25/tree/1e90171cce3eca93c14c6ec59811bd88a9792f34) · [Original builds for all four ABIs](https://github.com/MojoLauncher/android-openjdk-build-17-25/actions/runs/39842631198)

## Verifying and repackaging

From the repository root, verify the JDK data and native launchers used to build the APK.

```sh
sha256sum -c build-support/JDK-SHA256SUMS
```

Repackaging requires Python 3.12 or later, xdelta3 and the GitHub CLI signed in with access to public Actions artifacts. The script pins the four artifact IDs and SHA-256 digests. Its directory argument is a cache for the original artifacts and extracted JDKs.

```sh
python3 scripts/package-local-jdk.py /path/to/jdk-cache
```

GitHub Actions artifacts expire. To rebuild from the pinned Android build scripts, follow the upstream workflow with `TARGET_VERSION=17` and run `1_ci_build_arch_aarch64.sh`, `1_ci_build_arch_aarch32.sh`, `1_ci_build_arch_x86.sh` and `1_ci_build_arch_x86_64.sh` for their respective architectures. Extract each full JDK under the matching `arm64-v8a`, `armeabi-v7a`, `x86` or `x86_64` cache subdirectory and run the packager again. Rebuilt images can contain different timestamps, so update the checksum manifest to match the new artifacts.

```sh
python3 scripts/package-local-jdk.py /path/to/jdk-cache --from-images
```

The packager puts identical files in shared ZIP archives and stores standard VCDIFF deltas for ABI-specific modules and JMODs. The installer reconstructs the originals in 1 MiB windows. Android installs the native launchers for the device's CPU architecture. Gradle uses its pure Java operating-system integration on Android. [VCDIFF format](https://www.rfc-editor.org/rfc/rfc3284) · [vcdiff-java decoder and Apache 2.0 license](https://github.com/ehrmann/vcdiff-java)

## Verified environments

On an Android 16 x86_64 emulator, javac compiled code, jmod read a JMOD and jlink produced a runtime containing only java.base. On both 64-bit ABIs, the Java launchers, HotSpot and core JDK shared libraries have 16 KiB ELF LOAD alignment. The upstream optional AWT font library `libfreetype.so` uses 4 KiB alignment. Workloads that use AWT fonts on devices with 16 KiB pages still need separate verification.
