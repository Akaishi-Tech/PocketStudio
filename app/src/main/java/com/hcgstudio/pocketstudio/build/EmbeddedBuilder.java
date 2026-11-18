package com.hcgstudio.pocketstudio.build;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;

import com.android.dx.command.dexer.DxContext;

import org.eclipse.jdt.internal.compiler.batch.Main;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Offline debug builds for the app/src/main Java project layout. */
public final class EmbeddedBuilder {
    private static final String ALIAS = "ondevice-studio-debug-v1";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private EmbeddedBuilder() {}

    public static synchronized File build(Context context, File projectRoot, Consumer<String> log) throws Exception {
        File module = new File(projectRoot, "app");
        File main = new File(module, "src/main");
        QuickProject config = new QuickProject(projectRoot);
        String namespace = config.values.get("namespace");
        String appId = config.values.get("applicationId");
        String minSdk = config.values.get("minSdk");
        String targetSdk = config.values.get("targetSdk");
        String version = config.values.get("versionName");
        String versionCode = config.values.get("versionCode");
        if (android.os.Build.VERSION.SDK_INT < 30) throw new IOException(uiString(R.string.the_built_in_aapt2_requires_android_11_or));
        File aapt = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        if (!aapt.canExecute()) throw new IOException(uiString(R.string.this_device_s_abi_has_no_executable_built));
        Path temp = Files.createTempDirectory(context.getCacheDir().toPath(), "java-build-");
        try {
            Path sdk = new SdkPlatforms(context).linkJar(config.platform(), temp);
            log.accept(uiString(R.string.aapt2_compiling_resources));
            File res = new File(main, "res");
            Path resources = temp.resolve("resources.zip");
            Path linked = temp.resolve("resources.apk");
            Path generated = Files.createDirectory(temp.resolve("generated"));
            Path manifest = temp.resolve("AndroidManifest.xml");
            debugManifest(new File(main, "AndroidManifest.xml"), manifest.toFile(), namespace);
            List<String> link = new ArrayList<>(Arrays.asList(aapt.getPath(), "link", "-I", sdk.toString(), "--manifest",
                    manifest.toString(), "--java", generated.toString(), "--custom-package", namespace,
                    "--rename-manifest-package", appId, "--min-sdk-version", minSdk, "--target-sdk-version", targetSdk,
                    "--version-name", version, "--version-code", versionCode, "--debug-mode", "-o", linked.toString()));
            if (res.isDirectory()) {
                command(Arrays.asList(aapt.getPath(), "compile", "--dir", res.getAbsolutePath(), "-o", resources.toString()));
                link.add(resources.toString());
            }
            File assets = new File(main, "assets");
            if (assets.isDirectory()) { link.add("-A"); link.add(assets.getAbsolutePath()); }
            command(link);

            List<String> java = new ArrayList<>(), kotlin = new ArrayList<>();
            listSources(main.toPath().resolve("java"), java, kotlin);
            listSources(main.toPath().resolve("kotlin"), java, kotlin);
            listSources(generated, java, kotlin);
            Path classes = Files.createDirectory(temp.resolve("classes"));
            Path dex = temp.resolve("dex");
            if (kotlin.isEmpty()) {
                compileJava(java, sdk, classes, null, log);
                Files.createDirectory(dex);
                log.accept(uiString(R.string.dx_writing_debug_information));
                legacyDex(classes, dex.resolve("classes.dex"), minSdk);
            } else {
                synchronized (KotlinTools.class) {
                    KotlinTools tools = new KotlinTools(context);
                    log.accept(uiString(R.string.kotlin_compiling_sources));
                    tools.compile(kotlin, java, sdk, classes, log);
                    compileJava(java, sdk, classes, tools.stdlib(), log);
                    log.accept(uiString(R.string.d8_writing_debug_information));
                    tools.dex(classes, sdk, temp, minSdk, log);
                }
            }
            Path unsigned = temp.resolve("unsigned.apk");
            addDex(linked, dex, unsigned);

            log.accept(uiString(R.string.apk_signing_with_the_debug_key));
            File signed = temp.resolve("debug.apk").toFile();
            DeviceApkSigner.sign(ALIAS, unsigned.toFile(), signed, Integer.parseInt(minSdk));
            Path output = Files.createDirectories(projectRoot.toPath().resolve("app/build/outputs/apk/debug")).resolve("app-debug.apk");
            Path stage = Files.createTempFile(output.getParent(), ".pending-", ".apk");
            try {
                Files.copy(signed.toPath(), stage, StandardCopyOption.REPLACE_EXISTING);
                Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(stage); }
            return output.toFile();
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void compileJava(List<String> java, Path sdk, Path classes, Path stdlib, Consumer<String> log) throws IOException {
        if (java.isEmpty()) return;
        log.accept(uiString(R.string.ecj_compiling_java));
        List<String> args = new ArrayList<>(Arrays.asList(stdlib == null ? "-1.7" : "-1.8", "-g", "-proc:none",
                "-encoding", "UTF-8", "-bootclasspath", sdk.toString(), "-d", classes.toString()));
        if (stdlib != null) Collections.addAll(args, "-classpath", classes + File.pathSeparator + stdlib);
        args.addAll(java);
        StringWriter diagnostics = new StringWriter();
        PrintWriter writer = new PrintWriter(diagnostics, true);
        if (!Main.compile(args.toArray(new String[0]), writer, writer, null)) throw new IOException(diagnostics.toString());
        if (!diagnostics.toString().isEmpty()) log.accept(diagnostics.toString().trim());
    }

    private static void legacyDex(Path classes, Path dex, String minSdk) throws IOException {
        ByteArrayOutputStream dxLog = new ByteArrayOutputStream();
        DxContext dx = new DxContext(dxLog, dxLog);
        com.android.dx.command.dexer.Main.Arguments dxArgs = new com.android.dx.command.dexer.Main.Arguments(dx);
        dxArgs.debug = true;
        dxArgs.optimize = false;
        dxArgs.localInfo = true;
        dxArgs.positionInfo = com.android.dx.dex.code.PositionList.LINES;
        dxArgs.minSdkVersion = Integer.parseInt(minSdk);
        dxArgs.outName = dex.toString();
        dxArgs.fileNames = new String[] {classes.toString()};
        if (new com.android.dx.command.dexer.Main(dx).runDx(dxArgs) != 0) throw new IOException(dxLog.toString("UTF-8"));
    }

    private static void listSources(Path dir, List<String> java, List<String> kotlin) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            Iterator<Path> files = paths.filter(Files::isRegularFile).sorted().iterator();
            while (files.hasNext()) {
                Path file = files.next();
                if (file.toString().endsWith(".java")) java.add(file.toString());
                else if (file.toString().endsWith(".kt")) kotlin.add(file.toString());
                else throw new IOException(uiString(R.string.the_source_directory_contains_build_with_gradle, file.getFileName()));
            }
        }
    }

    private static void debugManifest(File from, File to, String namespace) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder parser = f.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException(uiString(R.string.external_xml_entities_are_unsupported)); });
        Document doc = parser.parse(from);
        if (doc.getDoctype() != null) throw new IOException(uiString(R.string.the_manifest_contains_an_unsupported_doctype_declaration));
        Element root = doc.getDocumentElement();
        root.setAttribute("package", namespace);
        Element app = (Element) root.getElementsByTagName("application").item(0);
        if (app == null) throw new IOException(uiString(R.string.the_manifest_is_missing_the_application_element));
        app.setAttributeNS(ANDROID, "android:debuggable", "true");
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(to));
    }

    private static void command(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) if (output.length() < 32768) output.append(line).append('\n');
            }
            int status = p.waitFor();
            if (status != 0) throw new IOException("AAPT2 (" + status + ") " + output.toString().trim());
        } finally { p.destroy(); }
    }

    private static void addDex(Path linked, Path dex, Path out) throws IOException {
        try (ZipFile zip = new ZipFile(linked.toFile());
             ZipOutputStream apk = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry old = entries.nextElement();
                ZipEntry entry = new ZipEntry(old.getName());
                if (old.getMethod() == ZipEntry.STORED) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(old.getSize());
                    entry.setCrc(old.getCrc());
                }
                apk.putNextEntry(entry);
                try (InputStream in = zip.getInputStream(old)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) apk.write(buf, 0, n);
                }
                apk.closeEntry();
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dex, "*.dex")) {
                for (Path file : files) {
                    apk.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, apk);
                    apk.closeEntry();
                }
            }
        }
    }
}
