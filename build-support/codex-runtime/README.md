# Standalone Codex plugin

`scripts/build-codex-plugin.py` builds `PocketStudio-Codex.apk` for ARM64 and x86-64. The package name is `com.hcgstudio.pocketstudio.codexruntime`. The upstream Codex version is the Android `versionName`. The release workflow allocates a monotonic `versionCode` for each new build, including dependency and packaging updates.

## Build

Use Linux with Python 3.9 or newer, `gpgv`, `dpkg-deb`, GNU `readelf`, a JDK, and the Android SDK. CI installs Android platform 37.0 and Build Tools 37.0.0. Point `ANDROID_HOME` to the SDK. To reproduce a prior build on a machine with several SDK versions, set `ANDROID_PLATFORM=android-37.0` and `ANDROID_BUILD_TOOLS_VERSION=37.0.0`.

```sh
python3 scripts/build-codex-plugin.py resolve --output build/codex-plugin-inputs/lock.json
python3 scripts/build-codex-plugin.py build --lock build/codex-plugin-inputs/lock.json \
  --version-code "$PLUGIN_VERSION_CODE" --out dist/release
```

The signing step reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. The APK must verify with the release certificate pinned in the main app. `codex-plugin.json` records the APK byte length, SHA-256, signer fingerprint, build ID, and Android versions. [Pinned public certificate](../../app/src/main/res/raw/codex_plugin_signer.der)

The resolver verifies the Termux OpenPGP signature on `Release`, then checks both ABI package indexes against its SHA-256 list. The builder checks the size and digest of every Debian package. The build ID covers the selected package records, packager, Android manifest, verification keys and added notices. An unrelated repository-index update leaves that identity unchanged. [Termux signing key source](https://github.com/termux/termux-packages/blob/b56f93df91fbf9ce6a0aa98f06fe9b504e21f594/packages/termux-keyring/termux-autobuilds.gpg)

For offline builds, retain the lock and the populated `~/.cache/pocketstudio-codex-plugin` directory from an online build, and add `--offline` to the build command. Both subcommands accept `--cache PATH`. Retain historical caches when reproducibility matters because the upstream repository rotates old versions. The packager fixes ZIP entry times, order and permissions.

## Android payload

Each ABI includes the Codex executable, `codex-code-mode-host`, `libssl.so.3` and `libcrypto.so.3`. The builder checks ELF architecture, Android linker, 16 KiB load alignment and dynamic dependencies. Android extracts these files into the package's read-only native-library directory. The resource-only manifest sets `android:debuggable` so the package manager extracts the upstream helper and versioned OpenSSL filenames. The plugin declares no components or permissions. The main IDE launches the helper as its own child process.

Codex supplies its `LICENSE`, `NOTICE` and `license.md`. The OpenSSL Debian copyright entry references the shared Apache 2.0 text from `termux-licenses`. The packager includes that text and an OpenSSL notice under `assets/licenses/openssl`. [Termux license package](https://packages.termux.dev/apt/termux-main/pool/main/t/termux-licenses/termux-licenses_2.2_all.deb)
