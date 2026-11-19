package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.content.Context;

import com.hcgstudio.pocketstudio.build.LocalAndroidSdk;
import com.hcgstudio.pocketstudio.build.SdkPlatforms;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A single run of the project's Gradle Wrapper, owned by this app on the device. */
final class LocalGradle {
    private final GradleProcesses processes = new GradleProcesses();
    private Thread worker;
    private Process child;
    private Thread cleanup;
    private int group;
    private boolean cancelled;

    synchronized void cancel() {
        cancelled = true;
        if (worker != null) worker.interrupt();
        startCleanup();
    }

    private synchronized void startCleanup() {
        if (child == null || cleanup != null) return;
        Process process = child;
        int initialGroup = group;
        cleanup = new Thread(() -> processes.stop(process, initialGroup), "Gradle-cancel");
        cleanup.start();
    }

    private void finishProcess() {
        Thread pending;
        synchronized (this) {
            if (child != null && (cancelled || child.isAlive())) startCleanup();
            child = null;
            pending = cleanup;
        }
        if (pending != null) {
            // Cancellation interrupts the worker. Finish killing descendants before closing its workspace.
            while (pending.isAlive()) {
                try { pending.join(); }
                catch (InterruptedException ignored) { }
            }
        }
    }

    private synchronized void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.gradle_task_stopped));
    }

    List<File> run(Project project, String tasks, Consumer<String> log) throws Exception {
        if (!tasks.matches("[:A-Za-z0-9_][-.:A-Za-z0-9_]*(?: +[:A-Za-z0-9_][-.:A-Za-z0-9_]*)*"))
            throw new IOException(uiString(R.string.enter_gradle_task_names_separated_by_spaces));
        synchronized (this) {
            checkCancelled();
            worker = Thread.currentThread();
        }
        try {
            try { project.resolve("gradle/wrapper/gradle-wrapper.jar"); }
            catch (java.io.FileNotFoundException e) { throw new IOException(uiString(R.string.the_project_is_missing_gradle_wrapper_gradle_wrapper), e); }
            Context context = project.context;
            LocalJava java = LocalJava.prepare(context, log);
            checkCancelled();
            LocalAndroidSdk sdk = new LocalAndroidSdk(context);
            sdk.prepare();
            checkCancelled();
            try (GradleWorkspace workspace = GradleWorkspace.open(project, sdk.root, log)) {
                Path directory = workspace.directory;
                Map<Path,BasicFileAttributes> before = apkFiles(directory);
                String platform = BuildSdk.selectedBuildSdk(project);
                Path sdkInit = platform != null ? prepareSdkOverride(context, platform) : null;
                try {
                    ProcessBuilder builder = wrapper(context, workspace, java, sdk, tasks, platform, sdkInit);
                    java.configure(builder);
                    log.accept(uiString(R.string.local_gradle_format, tasks));
                    try { execute(builder, log); }
                    finally { finishProcess(); }
                    return collectApks(context, directory, before);
                } finally {
                    if (sdkInit != null) Files.deleteIfExists(sdkInit);
                }
            }
        } catch (Exception e) {
            for (Throwable cleanup : e.getSuppressed()) {
                log.accept(uiString(R.string.failed_to_write_back_to_project_format, cleanup.getLocalizedMessage()));
            }
            checkCancelled();
            throw e;
        } finally {
            synchronized (this) {
                child = null;
                group = 0;
                worker = null;
                // The coroutine's shared IO thread must not retain our cancellation interrupt.
                if (cancelled) Thread.interrupted();
            }
        }
    }

    private static Path prepareSdkOverride(Context context, String platform) throws IOException {
        if (!new SdkPlatforms(context).installed().contains(platform))
            throw new IOException(uiString(R.string.use_sdk_manager_to_install_android_sdk_platform_format, platform));
        Path init = Files.createTempFile(context.getCacheDir().toPath(), "build-sdk-", ".gradle");
        try (InputStream in = context.getAssets().open("build-sdk.gradle")) {
            Files.copy(in, init, StandardCopyOption.REPLACE_EXISTING);
            return init;
        } catch (IOException error) {
            Files.deleteIfExists(init);
            throw error;
        }
    }

    private static ProcessBuilder wrapper(Context context, GradleWorkspace workspace, LocalJava java, LocalAndroidSdk sdk, String tasks, String platform, Path sdkInit) throws IOException {
        StudioSettings settings = StudioSettings.load(context);
        List<String> command = new ArrayList<>(Arrays.asList(
                "/system/bin/setsid", "/system/bin/sh", "-c", "echo $$; read -r ready || exit 1; exec \"$@\"", "gradle-session",
                // Android toybox xargs does not implement the quoting used in generated gradlew scripts.
                java.executable.getAbsolutePath(), "-Xmx128m", "-Dorg.gradle.appname=gradlew",
                "-classpath", "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain",
                "--no-daemon", "--console=plain", "--max-workers=" + settings.getGradleWorkers(),
                "-Dorg.gradle.native=false", "-Dorg.gradle.vfs.watch=false",
                "-Dorg.gradle.internal.instrumentation.agent=false",
                "-Pandroid.builder.sdkDownload=false", "-Pandroid.aapt2FromMavenOverride=" + sdk.aapt2));
        if (sdkInit != null)
            command.addAll(Arrays.asList("--init-script", sdkInit.toString(), "-PpocketStudioCompileSdk=" + platform));
        if (settings.getGradleOffline()) command.add("--offline");
        if (settings.getGradleStacktrace()) command.add("--stacktrace");
        if (workspace.projectCache != null) {
            // Gradle file locks require POSIX semantics; these are internal caches, not build outputs.
            command.add("--project-cache-dir");
            command.add(Files.createDirectories(workspace.projectCache).toString());
        }
        command.addAll(Arrays.asList(tasks.split(" +")));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.directory.toFile()).redirectErrorStream(true);
        Map<String,String> env = builder.environment();
        Path home = context.getFilesDir().toPath().resolve("gradle-home");
        Files.createDirectories(home);
        env.put("GRADLE_USER_HOME", home.toString());
        env.put("ANDROID_HOME", sdk.root.getAbsolutePath());
        env.put("ANDROID_SDK_ROOT", sdk.root.getAbsolutePath());
        return builder;
    }

    private void execute(ProcessBuilder builder, Consumer<String> log) throws Exception {
        Process process;
        synchronized (this) {
            checkCancelled();
            processes.mark(builder);
            child = process = builder.start();
        }
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            // The initial group is available even while the Wrapper is still starting up.
            String pid = output.readLine();
            if (pid == null || !pid.matches("[1-9][0-9]*")) throw new IOException(uiString(R.string.unable_to_start_the_local_gradle_process_format, (pid == null ? "" : " · " + pid)));
            synchronized (this) {
                group = Integer.parseInt(pid);
                checkCancelled();
                // Do not let the shell spawn workers until cancellation knows which group to stop.
                try (OutputStream input = process.getOutputStream()) { input.write('\n'); }
            }
            for (String line; (line = output.readLine()) != null; ) {
                checkCancelled();
                log.accept(line);
            }
        }
        int status = process.waitFor();
        checkCancelled();
        if (status != 0) throw new IOException(uiString(R.string.local_gradle_exit_code_format, status));
    }

    private Map<Path,BasicFileAttributes> apkFiles(Path directory) throws IOException {
        Map<Path,BasicFileAttributes> apks = new HashMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            Iterator<Path> files = paths.iterator();
            while (files.hasNext()) {
                checkCancelled();
                Path file = files.next();
                if (!file.getFileName().toString().endsWith(".apk")) continue;
                Path relative = directory.relativize(file);
                if (!("/" + relative).contains("/build/outputs/apk/")) continue;
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) apks.put(relative, attributes);
            }
        }
        return apks;
    }

    private List<File> collectApks(Context context, Path directory, Map<Path,BasicFileAttributes> before) throws IOException {
        List<File> results = new ArrayList<>();
        Path output = null;
        try {
            for (Map.Entry<Path,BasicFileAttributes> entry : apkFiles(directory).entrySet()) {
                checkCancelled();
                Path relative = entry.getKey();
                BasicFileAttributes old = before.get(relative), current = entry.getValue();
                if (old != null && old.size() == current.size() && old.lastModifiedTime().equals(current.lastModifiedTime())) continue;
                Path file = directory.resolve(relative);
                if (output == null) {
                    Path root = context.getFilesDir().toPath().resolve("outputs/gradle");
                    Files.createDirectories(root);
                    output = Files.createTempDirectory(root, "run-");
                }
                Path apk = output.resolve(relative);
                Files.createDirectories(apk.getParent());
                Files.copy(file, apk);
                results.add(apk.toFile());
            }
            checkCancelled();
            results.sort(File::compareTo);
            return results;
        } catch (IOException | RuntimeException e) {
            if (output != null) Project.removeTree(output);
            throw e;
        }
    }
}
