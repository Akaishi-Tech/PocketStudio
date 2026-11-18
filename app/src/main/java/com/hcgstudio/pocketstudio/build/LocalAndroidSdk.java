package com.hcgstudio.pocketstudio.build;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The SDK shared by the in-app SDK Manager and the project Gradle Wrapper. */
public final class LocalAndroidSdk {
    public static final String VERSION = "36.0.0";
    public static final String ARCHIVE = "build-tools_r36_linux.zip";
    private static final String URL_BASE = "https://dl.google.com/android/repository/";
    private static final long ZIP_SIZE = 63737259;
    private static final String SHA256 = "5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b";
    private static final Object INSTALL_LOCK = new Object();

    public final File root, aapt2;
    private final File packagedAapt2, adb;
    private final Path tools, cache;

    public LocalAndroidSdk(Context context) {
        root = new File(context.getFilesDir(), "sdk");
        tools = root.toPath().resolve("build-tools").resolve(VERSION);
        cache = context.getCacheDir().toPath();
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        // AGP also validates the executable basename. It rejects a path named libaapt2.so.
        aapt2 = new File(context.getFilesDir(), "gradle-bin/aapt2");
        packagedAapt2 = new File(nativeDir, "libaapt2.so");
        adb = new File(nativeDir, "libstudio_adb.so");
    }

    public boolean installed() {
        return Files.isRegularFile(tools.resolve("source.properties"));
    }

    /** Native library paths change on APK updates. Refresh the links before each Gradle run. */
    public void prepare() throws IOException {
        synchronized (INSTALL_LOCK) {
            Files.createDirectories(aapt2.toPath().getParent());
            link(packagedAapt2, aapt2.toPath());
            if (!installed()) return;
            link(packagedAapt2, tools.resolve("aapt2"));
            Path platformTools = Files.createDirectories(root.toPath().resolve("platform-tools"));
            link(adb, platformTools.resolve("adb"));
        }
    }

    private static void link(File executable, Path destination) throws IOException {
        Path target = executable.toPath();
        if (Files.isSymbolicLink(destination) && Files.readSymbolicLink(destination).equals(target)) return;
        Path temporary = destination.resolveSibling("." + destination.getFileName() + "-new");
        Files.deleteIfExists(temporary);
        Files.createSymbolicLink(temporary, target);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void download(Consumer<String> progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL_BASE + ARCHIVE).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        try (InputStream input = connection.getInputStream()) {
            install(input, progress);
        } finally {
            connection.disconnect();
        }
    }

    public void install(InputStream input, Consumer<String> progress) throws IOException {
        synchronized (INSTALL_LOCK) {
            if (!installed()) {
                Path work = cache.resolve("gradle-sdk-install");
                erase(work);
                Files.createDirectories(work);
                try {
                    Path archive = work.resolve(ARCHIVE);
                    verifyAndSave(input, archive, progress);
                    progress.accept(uiString(R.string.installing_android_sdk_build_tools_format, VERSION));
                    Path stage = Files.createDirectory(work.resolve("package"));
                    extract(archive, stage);
                    link(packagedAapt2, stage.resolve("aapt2"));
                    Files.createDirectories(tools.getParent());
                    Files.move(stage, tools, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    erase(work);
                }
            }
            prepare();
        }
    }

    private static void verifyAndSave(InputStream input, Path archive, Consumer<String> progress) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buffer = new byte[65536];
        long total = 0;
        int last = -1;
        try (OutputStream out = Files.newOutputStream(archive)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                total += count;
                if (total > ZIP_SIZE) throw new IOException(uiString(R.string.select_google_s_official_format, ARCHIVE));
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                int percent = (int) (100 * total / ZIP_SIZE);
                if (percent != last) {
                    progress.accept("Build Tools · " + percent + "%");
                    last = percent;
                }
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (total != ZIP_SIZE || !SHA256.contentEquals(hash))
            throw new IOException(uiString(R.string.sdk_package_verification_failed, ARCHIVE));
    }

    private static void extract(Path archive, Path stage) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("android-16/") || entry.isDirectory()) continue;
                Path target = stage.resolve(name.substring("android-16/".length())).normalize();
                if (!target.startsWith(stage)) throw new IOException(uiString(R.string.invalid_sdk_file_path));
                Files.createDirectories(target.getParent());
                try (InputStream data = zip.getInputStream(entry)) {
                    Files.copy(data, target);
                }
            }
        }
    }

    public void remove() throws IOException {
        synchronized (INSTALL_LOCK) {
            if (!installed()) return;
            Path previous = cache.resolve("gradle-sdk-removed");
            erase(previous);
            Files.move(tools, previous, StandardCopyOption.ATOMIC_MOVE);
            erase(previous);
        }
    }

    private static void erase(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> tree = Files.walk(directory)) {
            Iterator<Path> files = tree.sorted(java.util.Comparator.reverseOrder()).iterator();
            while (files.hasNext()) Files.delete(files.next());
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.canceled));
    }
}
