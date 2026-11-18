package com.hcgstudio.pocketstudio.build;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.*;

/** Optional ART-ported Kotlin compiler and D8. Only compiler resources are extracted. */
public final class KotlinTools {
    public static final String VERSION = "CodeAssist 3.46.0";
    public static final String URL = "https://github.com/tyron12233/CodeAssist/releases/download/v3.46.0/CodeAssist-3.46.0.apk";
    private static final long SIZE = 242621131;
    private static final String SHA256 = "df30a02f64030d0feb883b0fddf8a909c5f72cbe832b70dcfda24f5f1d7786ac";
    private final Path tools, cache;

    public KotlinTools(Context c) {
        tools = c.getFilesDir().toPath().resolve("build-tools/kotlin-3.46.0");
        cache = c.getCacheDir().toPath();
    }

    public boolean installed() { return Files.isRegularFile(tools.resolve("compiler.jar")); }

    public void download(Consumer<String> progress) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        try (InputStream in = connection.getInputStream()) { install(in, progress); }
        finally { connection.disconnect(); }
    }

    public void install(InputStream input, Consumer<String> progress) throws Exception {
        Path artifact = Files.createTempFile(cache, "kotlin-tools-", ".zip");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(artifact)) {
                byte[] buf = new byte[65536];
                long total = 0; int last = -1, n;
                while ((n = input.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.canceled));
                    total += n;
                    if (total > SIZE) throw new IOException(uiString(R.string.kotlin_tools_package_size_mismatch));
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    int percentage = (int) (total * 100 / SIZE);
                    if (percentage != last) { progress.accept("Kotlin / D8 · " + percentage + "%"); last = percentage; }
                }
                if (total != SIZE || !hex(digest.digest()).equals(SHA256)) throw new IOException(uiString(R.string.kotlin_tools_package_sha_256_mismatch));
            }
            progress.accept(uiString(R.string.extracting_the_kotlin_compiler));
            Files.createDirectories(tools.getParent());
            Path stage = Files.createTempDirectory(tools.getParent(), ".kotlin-install-");
            try (ZipFile release = new ZipFile(artifact.toFile())) {
                extract(release, stage);
                synchronized (KotlinTools.class) {
                    if (!installed()) Files.move(stage, tools, StandardCopyOption.ATOMIC_MOVE);
                }
            } finally { erase(stage); }
        } finally { Files.deleteIfExists(artifact); }
    }

    private static void extract(ZipFile release, Path stage) throws IOException {
        try (ZipOutputStream compiler = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(stage.resolve("compiler.jar"))))) {
            compiler.setLevel(1);
            Enumeration<? extends ZipEntry> entries = release.entries();
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.canceled));
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !(name.matches("classes(?:[0-9]+)?\\.dex") || name.startsWith("META-INF/") ||
                    name.startsWith("messages/") || name.startsWith("org/jetbrains/") || name.startsWith("kotlin/") ||
                    name.equals("pluginsCompatibleWithK2Mode.txt"))) continue;
                compiler.putNextEntry(new ZipEntry(name));
                try (InputStream in = release.getInputStream(entry)) { copy(in, compiler); }
                compiler.closeEntry();
            }
        }
        for (String name : Arrays.asList("kotlin-stdlib.jar", "assets/r8.dex.zip", "assets/predexed/kotlin-stdlib.dex.zip"))
            try (InputStream in = release.getInputStream(release.getEntry(name))) {
                Files.copy(in, stage.resolve(name.substring(name.lastIndexOf('/') + 1)));
            }
        Path home = Files.createDirectory(stage.resolve("home"));
        try (ZipInputStream resources = new ZipInputStream(release.getInputStream(release.getEntry("assets/kotlinc-resources.zip")))) {
            ZipEntry entry;
            while ((entry = resources.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = home.resolve(entry.getName()).normalize();
                if (!target.startsWith(home) || target.equals(home)) throw new IOException(uiString(R.string.invalid_kotlin_resource_path));
                Files.createDirectories(target.getParent());
                Files.copy(resources, target);
            }
        }
        for (String file : Arrays.asList("compiler.jar", "r8.dex.zip")) {
            if (!stage.resolve(file).toFile().setReadOnly()) throw new IOException(uiString(R.string.could_not_protect_the_compiler_files));
        }
    }

    public void remove() throws IOException {
        synchronized (KotlinTools.class) { erase(tools); }
    }

    Path stdlib() { return tools.resolve("kotlin-stdlib.jar"); }

    void compile(List<String> kotlin, List<String> java, Path platform, Path classes, Consumer<String> log)
            throws Exception {
        if (!installed()) throw new IOException(uiString(R.string.install_the_kotlin_d8_compiler_tools_in_sdk));
        List<String> args = vm("compiler.jar");
        Collections.addAll(args, "-Djava.io.tmpdir=" + cache, "-Dkotlinc.art.home=" + tools.resolve("home"),
            "-Dkotlin.colors.enabled=false", "-cp", tools.resolve("compiler.jar").toString(),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-Xuse-fir-lt=false", "-no-jdk", "-no-reflect", "-no-stdlib",
            "-jvm-target", "1.8", "-classpath", platform + File.pathSeparator + stdlib(), "-d", classes.toString());
        args.addAll(kotlin);
        args.addAll(java);
        run(args, "Kotlin", log);
    }

    void dex(Path classes, Path platform, Path temp, String minSdk, Consumer<String> log) throws Exception {
        Path jar = temp.resolve("classes.jar");
        try (ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> paths = Files.walk(classes)) {
            Iterator<Path> files = paths.filter(p -> p.toString().endsWith(".class")).iterator();
            while (files.hasNext()) {
                Path file = files.next();
                target.putNextEntry(new ZipEntry(classes.relativize(file).toString()));
                Files.copy(file, target);
                target.closeEntry();
            }
        }
        Path output = Files.createDirectory(temp.resolve("dex"));
        List<String> args = vm("r8.dex.zip");
        Collections.addAll(args, "-Djava.io.tmpdir=" + cache, "-cp", tools.resolve("r8.dex.zip").toString(),
            "com.android.tools.r8.D8", "--debug", "--min-api", minSdk, "--lib", platform.toString(),
            "--classpath", stdlib().toString(), "--output", output.toString(), jar.toString());
        run(args, "D8", log);
        // The stdlib was dexed by the same upstream toolchain. Keep it as a secondary dex (minSdk >= 24).
        int next = 2;
        while (Files.exists(output.resolve("classes" + next + ".dex"))) next++;
        try (ZipFile zip = new ZipFile(tools.resolve("kotlin-stdlib.dex.zip").toFile());
             InputStream in = zip.getInputStream(zip.getEntry("classes.dex"))) {
            Files.copy(in, output.resolve("classes" + next + ".dex"));
        }
    }

    private List<String> vm(String tool) throws IOException {
        String bits = android.os.Process.is64Bit() ? "64" : "32";
        for (String launcher : Arrays.asList("/apex/com.android.art/bin/dalvikvm" + bits, "/system/bin/dalvikvm" + bits, "/system/bin/dalvikvm")) {
            if (!new File(launcher).canExecute()) continue;
            List<String> args = new ArrayList<>(Arrays.asList(launcher, "-Xmx512m"));
            if (Build.VERSION.SDK_INT <= 30 && tool.equals("compiler.jar"))
                Collections.addAll(args, "-Xcompiler-option", "--inline-max-code-units=0");
            return args;
        }
        throw new IOException(uiString(R.string.could_not_find_the_art_compiler_runtime_for));
    }

    private static void run(List<String> args, String tool, Consumer<String> log) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) log.accept(line);
            }
            int result = p.waitFor();
            if (result != 0) throw new IOException(uiString(R.string.compilation_failed_format, tool, result));
        } finally { p.destroy(); }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void erase(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            Iterator<Path> files = paths.sorted(Comparator.reverseOrder()).iterator();
            while (files.hasNext()) Files.deleteIfExists(files.next());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
