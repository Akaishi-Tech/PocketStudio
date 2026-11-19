package com.hcgstudio.pocketstudio.plugin;

import android.hardware.usb.UsbDevice;
import com.hcgstudio.pocketstudio.adb.Target;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** In-process tools supplied by a verified Android development-environment APK. */
public interface AndroidEnvironment {
    String API = "com.hcgstudio.pocketstudio.ANDROID_ENVIRONMENT_API";
    String ENTRY = "com.hcgstudio.pocketstudio.ANDROID_ENVIRONMENT_ENTRY";
    int VERSION = 1;

    AndroidEnvironmentUi ui();
    Platforms platforms();
    Toolkit buildTools();
    Toolkit kotlinTools();
    File build(File directory, String platform, Consumer<String> log) throws Exception;
    GradleRun newGradleRun();
    ProjectStarter projectTemplate(String starter, String name, String packageName, String language,
                                   int minSdk, String compileSdk) throws IOException;

    String resourceFileNameError(String parentPath, String name);
    List<UsbDevice> usbDevices();
    Target connectUsb(UsbDevice device) throws IOException;
    Target connectRemote(String address, int port, boolean tcp) throws IOException;
    void pairRemote(String address, int port, String code) throws IOException;

    interface LayoutFiles {
        List<String> list(String directory) throws IOException;
        String read(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }

    interface GradleRun {
        List<File> run(Path directory, Path projectCache, String platform, String tasks, int workers,
                       boolean offline, boolean stacktrace, Consumer<String> log) throws Exception;
        void cancel();
    }

    interface Platform {
        String getApi();
        String getName();
        String getLicense();
        String getRevision();
        long getSize();
    }

    interface Platforms {
        List<? extends Platform> catalog() throws Exception;
        List<String> installed() throws IOException;
        String installedRevision(String api) throws IOException;
        void install(Platform platform, Consumer<String> log) throws Exception;
        String importArchive(InputStream archive) throws IOException;
        void remove(String api) throws IOException;
    }

    interface Toolkit {
        String version();
        String archive();
        boolean installed();
        void download(Consumer<String> log) throws Exception;
        void install(InputStream archive, Consumer<String> log) throws Exception;
        void remove() throws IOException;
    }
}
