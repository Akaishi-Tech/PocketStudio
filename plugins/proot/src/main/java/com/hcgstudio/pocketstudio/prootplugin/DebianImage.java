package com.hcgstudio.pocketstudio.prootplugin;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.zip.GZIPInputStream;

/** Unpacks one signed image in a staging directory and switches versions only after successful extraction. */
final class DebianImage {
    private static final Object lock = new Object();
    private static boolean installing;
    private static int commands;
    private final Context app;
    final Path base;
    DebianImage(Context context) {
        app = context.getApplicationContext();
        base = app.getNoBackupFilesDir().toPath();
    }

    String bundled() throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                app.getAssets().open("debian-rootfs.sha256"), StandardCharsets.US_ASCII))) {
            String value = in.readLine();
            if (value == null || !value.matches("[a-f0-9]{64}")) throw new IOException("Invalid Debian image digest");
            return value;
        }
    }

    String active() {
        return app.getSharedPreferences("debian", 0).getString("active", "");
    }

    private Path directory(String hash) { return base.resolve("debian-" + hash); }
    boolean installing() { synchronized (lock) { return installing; } }

    Path acquire() throws IOException {
        synchronized (lock) {
            if (installing) throw new IOException("Debian installation is in progress");
            String hash = active();
            Path directory = directory(hash);
            if (!hash.matches("[a-f0-9]{64}") || !Files.isDirectory(directory))
                throw new IOException(app.getString(R.string.setup_install));
            commands++;
            return directory;
        }
    }

    void release() {
        synchronized (lock) { commands--; }
    }

    void install() throws IOException {
        String target = bundled();
        synchronized (lock) {
            if (installing) throw new IOException("Another installation is in progress");
            if (target.equals(active()) && Files.isDirectory(directory(target))) return;
            if (commands != 0) throw new IOException("Close running Linux commands before updating the image");
            installing = true;
        }
        Path stage = base.resolve("debian.staging");
        try {
            String previous = active();
            remove(stage);
            Path dest = directory(target);
            if (!Files.isDirectory(dest)) {
                Files.createDirectory(stage);
                extract(stage, target);
                if (!Files.isRegularFile(stage.resolve("usr/bin/bash")) ||
                        !Files.isDirectory(stage.resolve("home/studio"), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Debian image is missing Bash or the studio home directory");
                Path home = base.resolve("home");
                if (!Files.exists(home)) {
                    Files.move(stage.resolve("home/studio"), home, StandardCopyOption.ATOMIC_MOVE);
                    Files.createDirectory(stage.resolve("home/studio"));
                }
                Files.move(stage, dest, StandardCopyOption.ATOMIC_MOVE);
            }
            if (!app.getSharedPreferences("debian", 0).edit().putString("active", target).commit())
                throw new IOException("Could not record installed Debian version");
            if (!previous.equals(target) && previous.matches("[a-f0-9]{64}")) remove(directory(previous));
        } finally {
            try { remove(stage); } finally { synchronized (lock) { installing = false; } }
        }
    }

    private void extract(Path stage, String expected) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        File log = File.createTempFile("debian-install-", ".log", app.getCacheDir());
        try {
            Process tar = new ProcessBuilder("/system/bin/toybox", "tar", "-xo", "-f", "-")
                    .directory(stage.toFile()).redirectErrorStream(true).redirectOutput(log).start();
            try {
                try (InputStream asset = app.getAssets().open("debian-rootfs.tar.gz");
                     DigestInputStream compressed = new DigestInputStream(asset, digest);
                     InputStream data = new GZIPInputStream(compressed, 128 * 1024);
                     OutputStream out = tar.getOutputStream()) {
                    byte[] block = new byte[128 * 1024];
                    for (int n; (n = data.read(block)) != -1;) out.write(block, 0, n);
                    while (compressed.read(block) != -1) { /* Include trailing bytes in the digest. */ }
                }
                if (tar.waitFor() != 0) throw new IOException("Could not unpack Debian: " + logTail(log));
                StringBuilder result = new StringBuilder(64);
                for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
                if (!result.toString().equals(expected)) throw new IOException("Debian image checksum mismatch");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Debian installation interrupted", e);
            } finally { tar.destroy(); }
        } finally { log.delete(); }
    }

    private static String logTail(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            int size = (int)Math.min(2048, in.length());
            in.seek(in.length() - size);
            byte[] data = new byte[size];
            in.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void remove(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(tree, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) {
                dir.toFile().setWritable(true, true);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
