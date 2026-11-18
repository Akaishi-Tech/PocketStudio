package com.hcgstudio.pocketstudio.testing;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.hcgstudio.pocketstudio.build.EmbeddedBuilder;
import com.hcgstudio.pocketstudio.build.SdkPlatforms;
import com.hcgstudio.pocketstudio.upstream.FileResourceNameValidator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public final class EmbeddedBuilderSmoke {
    private EmbeddedBuilderSmoke() {}

    public static void run(Context context) throws Exception {
        Path project = Files.createTempDirectory(context.getCacheDir().toPath(), "build-smoke-");
        try {
            fixture(project);
            SdkPlatforms sdk = new SdkPlatforms(context);
            if (!sdk.installed().contains("34")) {
                File archive = new File(context.getCacheDir(), "platform-34-test.zip");
                try (InputStream input = new FileInputStream(archive)) {
                    require(sdk.importArchive(input).equals("34"), "SDK 导入");
                }
            }
            require(!sdk.installedRevision("34").isEmpty(), "SDK 版本");
            Path assets = Files.createDirectories(project.resolve("app/src/main/assets/nested"));
            Files.write(assets.resolve("example.txt"), "smoke".getBytes(StandardCharsets.UTF_8));
            File first = build(context, project);
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(first.toString(), PackageManager.GET_SIGNATURES | PackageManager.GET_ACTIVITIES);
            require(info != null && info.packageName.equals("example.hello"), "包名");
            require(info.signatures != null && info.signatures.length == 1, "签名");
            require((info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, "调试标志");
            require(info.activities[0].name.equals("example.hello.MainActivity"), "启动 Activity");
            try (ZipFile apk = new ZipFile(first)) {
                require(apk.getEntry("classes.dex") != null, "DEX");
                require(apk.getEntry("resources.arsc") != null, "资源表");
                require(apk.getEntry("assets/nested/example.txt") != null, "嵌套 assets");
            }
            File second = build(context, project);
            PackageInfo rebuilt = pm.getPackageArchiveInfo(second.toString(), PackageManager.GET_SIGNATURES);
            require(Arrays.equals(info.signatures, rebuilt.signatures), "增量安装签名");

            Path config = project.resolve("app/build.gradle");
            byte[] original = Files.readAllBytes(config);
            String script = new String(original, StandardCharsets.UTF_8);
            assertRejectedConfig(context, project, config, script + "\ndependencies { implementation 'test:only:1' }", "Gradle");
            assertRejectedConfig(context, project, config, script.replace("compileSdk 34", "compileSdk 999"), "SDK");
            assertRejectedConfig(context, project, config, script.replace("namespace ", "splits { }\n namespace "), "Gradle");
            assertRejectedConfig(context, project, config, script.replace("'com.android.application'", "'com.android.library'"), "Gradle");
            Files.write(config, original);
            Path kotlin = project.resolve("app/src/main/java/Unexpected.scala");
            Files.createFile(kotlin);
            reject(context, project, "Unexpected.scala");
            Files.delete(kotlin);
            require(FileResourceNameValidator.errorForFile("app/src/main/res/drawable-night", "icon.9.png") == null, "九宫格资源");
            require(FileResourceNameValidator.errorForFile("app/src/main/res/layout", "My_view.xml") != null, "资源字符");
            require(FileResourceNameValidator.errorForFile("app/src/main/res/raw", "class.json") != null, "关键字");
        } finally {
            try (Stream<Path> paths = Files.walk(project)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static File build(Context context, Path root) throws Exception {
        return EmbeddedBuilder.build(context, root.toFile(), message -> Log.i("EmbeddedBuildTest", message));
    }

    private static void assertRejectedConfig(Context c, Path project, Path config, String text, String message) throws Exception {
        Files.write(config, text.getBytes(StandardCharsets.UTF_8));
        reject(c, project, message);
    }

    private static void reject(Context c, Path project, String expected) throws Exception {
        try { build(c, project); }
        catch (IOException e) {
            require(e.getMessage() != null && e.getMessage().contains(expected), expected + " / " + e.getMessage());
            return;
        }
        throw new AssertionError("应拒绝 " + expected);
    }

    private static void require(boolean value, String what) { if (!value) throw new AssertionError(what); }

    private static void fixture(Path project) throws IOException {
        write(project, "settings.gradle", "include ':app'");
        write(project, "app/build.gradle", "plugins { id 'com.android.application' }\n"
            + "android { namespace 'example.hello' compileSdk 34 defaultConfig { applicationId 'example.hello' "
            + "minSdk 26 targetSdk 34 versionCode 1 versionName '1.0' } }");
        write(project, "app/src/main/AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
            + "<application android:label=\"@string/app_name\"><activity android:name=\".MainActivity\"/>"
            + "</application></manifest>");
        write(project, "app/src/main/java/example/hello/MainActivity.java",
            "package example.hello; public class MainActivity extends android.app.Activity {}");
        write(project, "app/src/main/res/values/strings.xml",
            "<resources><string name=\"app_name\">Test</string></resources>");
    }

    private static void write(Path project, String file, String text) throws IOException {
        Path path = project.resolve(file);
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
