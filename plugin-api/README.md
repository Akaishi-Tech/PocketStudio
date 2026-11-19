# Pocket Studio plugin API

A plugin is an Android application package with a discoverable marker service. First-party plugins use application IDs of the form `com.hcgstudio.pocketstudio.<name>`. The existing Codex plugin keeps `com.hcgstudio.pocketstudio.codexplugin` on every supported architecture. An APK update must retain its package ID and signing identity.

## Discovery

Declare one marker service per package. Its intent filter advertises `com.hcgstudio.pocketstudio.PLUGIN`. The service carries an integer discovery API version, a lowercase category ID, and an optional developer name. Supply a localized `android:description` on the service and an `android:icon` on the application for the store. Use string resources for translated text.

```xml
<service android:name=".PluginMarker" android:exported="true"
    android:description="@string/plugin_description">
    <intent-filter>
        <action android:name="com.hcgstudio.pocketstudio.PLUGIN" />
    </intent-filter>
    <meta-data android:name="com.hcgstudio.pocketstudio.PLUGIN_API" android:value="1" />
    <meta-data android:name="com.hcgstudio.pocketstudio.PLUGIN_CATEGORY" android:value="agent" />
    <meta-data android:name="com.hcgstudio.pocketstudio.PLUGIN_DEVELOPER" android:value="Akaishi Tech" />
</service>
```

`PluginDiscovery.query(packageManager)` returns compatible registrations and their category and display metadata through Android's package manager. Discovery reads the installed manifest without starting the marker service. The marker can implement `Service.onBind` by returning `null`. A new category may be discovered and displayed before Studio implements a runtime for it. Studio checks the installed package's signing certificate before loading a provider. [Discovery contract](src/main/java/com/hcgstudio/pocketstudio/plugin/PluginDiscovery.kt)

The `agent` category uses an additional versioned entry point for login and messages. Implement that contract and add the agent API metadata to the same service. Studio also recognizes the earlier agent-only registration in already installed Codex plugins. [Agent API](../agent-api/README.md) · [Codex implementation](../plugins/codex/apk/src/main/AndroidManifest.xml)

## Android development environment

The Android plugin uses the package ID `com.hcgstudio.pocketstudio.androidplugin` and the store category `development-environment`. Its marker service declares the discovery action and version above, with these additional values. [Android plugin manifest](../plugins/android/src/main/AndroidManifest.xml)

```xml
<meta-data android:name="com.hcgstudio.pocketstudio.PLUGIN_CATEGORY"
    android:value="development-environment" />
<meta-data android:name="com.hcgstudio.pocketstudio.ANDROID_ENVIRONMENT_API"
    android:value="1" />
<meta-data android:name="com.hcgstudio.pocketstudio.ANDROID_ENVIRONMENT_ENTRY"
    android:value="com.hcgstudio.pocketstudio.androidplugin.AndroidDevelopment" />
```

Studio loads the installed, signed provider through its public constructor `(Context host, Context plugin)`. Use the host context for project access and persistent storage, and the plugin context for the APK’s resources, templates and native tools. The Android project type becomes available when Studio loads the provider. Its `ui().NewProject` composable owns the wizard and passes a `ProjectStarter` to the host to write files to the selected folder. The same provider supplies the SDK manager, Gradle and quick builds, layout preview and remote device connections. Local device debugging and plugin installation run in the host. [Environment API](src/main/java/com/hcgstudio/pocketstudio/plugin/AndroidEnvironment.java) · [Wizard host API](src/main/java/com/hcgstudio/pocketstudio/plugin/ProjectWizardHost.kt)

## Linux project environments

Declare a signed development-environment marker with integer `com.hcgstudio.pocketstudio.LINUX_API = 1` and an entry class in `com.hcgstudio.pocketstudio.LINUX_ENTRY`. The entry implements `LinuxEnvironment` and accepts host and plugin contexts. Each project belongs to one provider. Its process interface accepts argument vectors, supports cancellation, and preserves binary-clean stdio for file operations and language servers. The plugin packages the language definitions in `assets/linux-languages.json`. [Environment contract](src/main/java/com/hcgstudio/pocketstudio/plugin/LinuxEnvironment.java)
