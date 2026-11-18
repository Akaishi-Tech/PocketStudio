package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.davidehrmann.vcdiff.VCDiffDecoderBuilder;
import com.davidehrmann.vcdiff.VCDiffStreamingDecoder;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The application-owned Android OpenJDK used by the wrapper and its child JVMs. */
final class LocalJava {
    private static final String IMAGE = "mojo-39842631198";
    final File home;
    final File executable;
    private final File userHome;
    private final File tmp;

    private LocalJava(File home, File userHome, File tmp) {
        this.home = home;
        this.executable = new File(home, "bin/java");
        this.userHome = userHome;
        this.tmp = tmp;
    }

    static synchronized LocalJava prepare(Context context, Consumer<String> log) throws IOException {
        String abi = abi(context);
        Path base = context.getNoBackupFilesDir().toPath().resolve("gradle-java");
        Files.createDirectories(base);
        Path home = base.resolve(IMAGE + "-" + abi);
        Path nativeDir = new File(context.getApplicationInfo().nativeLibraryDir).toPath();
        if (!Files.isDirectory(home)) {
            log.accept(uiString(R.string.preparing_on_device_openjdk_17_format, abi));
            Path stage = Files.createTempDirectory(base, "install-");
            try {
                unpack(context, "common", stage);
                unpack(context, "common-jmods", stage);
                unpack(context, abi, stage);
                // PackageManager installs executables on an executable, read-only mount.
                // A single replaceable link also handles APK directory changes after updates.
                Files.createSymbolicLink(stage.resolve("apk-libs"), nativeDir);
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                        context.getAssets().open("local-jdk/" + abi + ".links"), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        String[] fields = line.split("\t");
                        Path destination = stage.resolve(fields[0]);
                        Path target = stage.resolve("apk-libs").resolve(fields[1]);
                        Files.createDirectories(destination.getParent());
                        Files.createSymbolicLink(destination, destination.getParent().relativize(target));
                    }
                }
                Files.move(stage, home, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(stage)) {
                    try (Stream<Path> paths = Files.walk(stage)) {
                        for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        }
        Path installed = home.resolve("apk-libs");
        if (!nativeDir.equals(Files.readSymbolicLink(installed))) {
            Path updated = home.resolve("apk-libs.next");
            Files.deleteIfExists(updated);
            Files.createSymbolicLink(updated, nativeDir);
            Files.move(updated, installed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isExecutable(home.resolve("bin/java"))) throw new IOException(uiString(R.string.the_apk_is_missing_a_java_launcher_for));
        File userHome = Files.createDirectories(context.getNoBackupFilesDir().toPath().resolve("gradle-home")).toFile();
        File tmp = Files.createDirectories(context.getCacheDir().toPath().resolve("gradle-tmp")).toFile();
        return new LocalJava(home.toFile(), userHome, tmp);
    }

    private static String abi(Context context) throws IOException {
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        java.util.List<String> assets = java.util.Arrays.asList(context.getAssets().list("local-jdk"));
        for (String abi : abis) if (assets.contains(abi + ".zip")) return abi;
        throw new IOException(uiString(R.string.the_apk_has_no_android_openjdk_for_this));
    }

    private static void unpack(Context context, String name, Path stage) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                context.getAssets().open("local-jdk/" + name + ".zip"), 128 * 1024))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException(uiString(R.string.jdk_setup_canceled));
                Path file = stage.resolve(entry.getName()).normalize();
                if (!file.startsWith(stage)) throw new IOException(uiString(R.string.invalid_jdk_file_path));
                Files.createDirectories(file.getParent());
                if (entry.getName().endsWith(".vcdiff")) {
                    String nameWithoutSuffix = entry.getName().substring(0, entry.getName().length() - 7);
                    applyDelta(stage.resolve(nameWithoutSuffix), zip);
                } else {
                    Files.copy(zip, file);
                }
            }
        }
    }

    private static void applyDelta(Path source, ZipInputStream zip) throws IOException {
        Path result = source.resolveSibling(source.getFileName() + ".new");
        // Read the baseline through a file mapping. Only one 1 MiB target window is held on the heap.
        VCDiffStreamingDecoder decoder = VCDiffDecoderBuilder.builder().withAllowTargetMatches(false)
                .withMaxTargetFileSize(256L * 1024 * 1024).withMaxTargetWindowSize(1024 * 1024).buildStreaming();
        try (FileChannel file = FileChannel.open(source); OutputStream output = Files.newOutputStream(result)) {
            decoder.startDecoding(file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()));
            byte[] chunk = new byte[64 * 1024];
            int length;
            while ((length = zip.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException(uiString(R.string.jdk_setup_canceled));
                decoder.decodeChunk(ByteBuffer.wrap(chunk, 0, length), output);
            }
            decoder.finishDecoding();
        }
        Files.move(result, source, StandardCopyOption.REPLACE_EXISTING);
    }

    void configure(ProcessBuilder process) {
        Map<String, String> env = process.environment();
        env.put("JAVA_HOME", home.getAbsolutePath());
        env.put("PATH", new File(home, "bin") + ":" + env.getOrDefault("PATH", "/system/bin"));
        // libjli explicitly loads the selected VM by its absolute path. Including the
        // server/client directory here makes it re-exec as plain java when Gradle
        // passes another Android mount alias for JAVA_HOME, losing the tool's main class.
        env.put("LD_LIBRARY_PATH", new File(home, "lib").getAbsolutePath());
        env.put("HOME", userHome.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        // Gradle's desktop Linux JNI integrations link against glibc. The Java fallbacks
        // run under Bionic. JAVA_TOOL_OPTIONS applies these settings to JVM workers too.
        env.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + userHome + " -Djava.io.tmpdir=" + tmp
                + " -Dorg.gradle.native=false -Dorg.gradle.internal.instrumentation.agent=false"
                + " -Djava.awt.headless=true -XX:-UsePerfData");
    }
}
