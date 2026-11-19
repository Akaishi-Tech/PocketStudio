package com.hcgstudio.pocketstudio.androidplugin;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import com.hcgstudio.pocketstudio.adb.AndroidUsb;
import com.hcgstudio.pocketstudio.adb.Target;
import com.hcgstudio.pocketstudio.adb.UsbAdb;
import com.hcgstudio.pocketstudio.wireless.WirelessAdb;
import java.util.List;

import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment;
import com.hcgstudio.pocketstudio.plugin.ProjectStarter;
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironmentUi;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

public final class AndroidDevelopment implements AndroidEnvironment {
    private final Context host, plugin;
    private final AndroidEnvironmentUi ui;
    private final SdkPlatforms platforms;
    private final LocalAndroidSdk buildTools;
    private final KotlinTools kotlinTools;

    public AndroidDevelopment(Context host, Context plugin) {
        this.host = host;
        this.plugin = plugin;
        PluginStrings.initialize(host, plugin);
        ui = new AndroidToolsUi(this, plugin);
        platforms = new SdkPlatforms(host);
        buildTools = new LocalAndroidSdk(host, plugin);
        kotlinTools = new KotlinTools(host);
    }

    @Override public AndroidEnvironmentUi ui() { return ui; }
    @Override public Platforms platforms() { return platforms; }
    @Override public Toolkit buildTools() { return buildTools; }
    @Override public Toolkit kotlinTools() { return kotlinTools; }

    @Override public File build(File directory, String platform, Consumer<String> log) throws Exception {
        return EmbeddedBuilder.build(host, plugin, directory, platform, log);
    }

    @Override public GradleRun newGradleRun() { return new GradleRunner(host, plugin); }

    @Override public ProjectStarter projectTemplate(String starter, String name, String packageName, String language,
                                                   int minSdk, String compileSdk) throws IOException {
        return AndroidTemplateKt.androidProjectTemplate(plugin.getAssets(), starter, name, packageName, language, minSdk, compileSdk);
    }

    @Override public String resourceFileNameError(String parentPath, String name) {
        return FileResourceNameValidator.errorForFile(parentPath, name);
    }

    @Override public List<UsbDevice> usbDevices() {
        return AndroidUsb.devices(host.getSystemService(UsbManager.class));
    }

    @Override public Target connectUsb(UsbDevice device) throws IOException {
        return UsbAdb.start(AndroidUsb.open(host, device));
    }

    @Override public Target connectRemote(String address, int port, boolean tcp) throws IOException {
        return tcp ? UsbAdb.connectTcp(host, address, port) : WirelessAdb.connect(host, address, port);
    }

    @Override public void pairRemote(String address, int port, String code) throws IOException {
        WirelessAdb.pair(host, address, port, code);
    }
}
