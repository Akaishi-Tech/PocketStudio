package com.hcgstudio.pocketstudio.build;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Installs host-independent platform packages from the Android SDK repository. */
public final class SdkPlatforms {
    private static final String REPOSITORY = "https://dl.google.com/android/repository/";
    private static final Pattern API = Pattern.compile("[1-9][0-9]{0,2}(?:\\.[0-9]{1,2})?");
    private static final long MAX_DOWNLOAD = 512L << 20;
    private static final long MAX_EXPANDED = 1536L << 20;
    private static final Object LOCK = new Object();
    private static final Set<Path> INSTALLING = new HashSet<>();
    private final Path platforms, cache;

    public SdkPlatforms(Context context) {
        platforms = context.getFilesDir().toPath().resolve("sdk/platforms");
        cache = context.getCacheDir().toPath();
    }

    public static final class Platform {
        public final String api, name, license, revision;
        public final long size;
        private final String file, checksum, algorithm;

        private Platform(String api, Element pkg, Element archive, String license) {
            this.api = api;
            this.name = value(pkg, "display-name");
            this.revision = value(pkg, "major");
            this.license = license;
            this.file = value(archive, "url");
            this.size = Long.parseLong(value(archive, "size"));
            Element hash = first(archive, "checksum");
            this.checksum = hash.getTextContent().trim();
            this.algorithm = "sha256".equalsIgnoreCase(hash.getAttribute("type")) ? "SHA-256" : "SHA-1";
        }
    }

    private static Element first(Element parent, String tag) {
        return (Element) parent.getElementsByTagName(tag).item(0);
    }

    private static String value(Element parent, String tag) {
        Element element = first(parent, tag);
        return element == null ? "" : element.getTextContent().trim();
    }

    public List<Platform> catalog() throws Exception {
        HttpURLConnection request = connect("repository2-3.xml");
        try (InputStream in = request.getInputStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
            parser.setEntityResolver((pub, sys) -> { throw new org.xml.sax.SAXException(uiString(R.string.the_sdk_catalog_contains_external_entities)); });
            Element root = parser.parse(in).getDocumentElement();
            Map<String,String> terms = new HashMap<>();
            NodeList licenses = root.getElementsByTagName("license");
            for (int i = 0; i < licenses.getLength(); i++) {
                Element e = (Element) licenses.item(i);
                terms.put(e.getAttribute("id"), e.getTextContent().trim());
            }
            List<Platform> result = new ArrayList<>();
            NodeList packages = root.getElementsByTagName("remotePackage");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);
                String path = pkg.getAttribute("path");
                if (!path.startsWith("platforms;android-") || "true".equals(pkg.getAttribute("obsolete"))) continue;
                String api = path.substring("platforms;android-".length());
                if (!API.matcher(api).matches() || !value(pkg, "codename").isEmpty()) continue;
                Element archive = first(pkg, "complete"), ref = first(pkg, "uses-license");
                if (archive == null || ref == null || !terms.containsKey(ref.getAttribute("ref"))) continue;
                Platform p = new Platform(api, pkg, archive, terms.get(ref.getAttribute("ref")));
                if (p.file.matches("[\\w.-]+\\.zip") && p.size > 0 && p.size <= MAX_DOWNLOAD) result.add(p);
            }
            result.sort((a,b) -> compareApi(b.api, a.api));
            if (result.isEmpty()) throw new IOException(uiString(R.string.google_s_sdk_catalog_returned_no_available_platforms));
            return result;
        } finally { request.disconnect(); }
    }

    private static int compareApi(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        int major = Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0]));
        if (major != 0) return major;
        return Integer.compare(a.length == 1 ? 0 : Integer.parseInt(a[1]), b.length == 1 ? 0 : Integer.parseInt(b[1]));
    }

    private static HttpURLConnection connect(String file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(REPOSITORY + file).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        return connection;
    }

    public List<String> installed() throws IOException {
        synchronized (LOCK) {
            prepare();
            List<String> result = new ArrayList<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(platforms, "android-*")) {
                for (Path dir : dirs) {
                    String api = dir.getFileName().toString().substring("android-".length());
                    if (API.matcher(api).matches() && Files.isRegularFile(dir.resolve("android.jar"))) result.add(api);
                }
            }
            result.sort((a,b) -> compareApi(b,a));
            return result;
        }
    }

    public String installedRevision(String api) throws IOException {
        synchronized (LOCK) {
            prepare();
            Properties metadata = properties(directory(api).resolve("source.properties"));
            return metadata.getProperty("Pkg.Revision", "");
        }
    }

    public void install(Platform platform, Consumer<String> progress) throws Exception {
        Path download = Files.createTempFile(cache, "platform-", ".zip");
        try {
            HttpURLConnection request = connect(platform.file);
            MessageDigest digest = MessageDigest.getInstance(platform.algorithm);
            try (InputStream input = request.getInputStream(); OutputStream output = Files.newOutputStream(download)) {
                long count = 0;
                int previous = -1;
                byte[] buf = new byte[65536];
                int n;
                while ((n = input.read(buf)) != -1) {
                    interrupted();
                    count += n;
                    if (count > platform.size) throw new IOException(uiString(R.string.the_sdk_download_exceeded_the_size_listed_in));
                    output.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    int percent = (int) (100L * count / platform.size);
                    if (percent != previous) { progress.accept(uiString(R.string.downloading_format, percent)); previous = percent; }
                }
                if (count != platform.size || !hex(digest.digest()).equalsIgnoreCase(platform.checksum))
                    throw new IOException(uiString(R.string.sdk_download_verification_failed_download_the_package_again));
            } finally { request.disconnect(); }
            progress.accept(uiString(R.string.installing_android_format, platform.api));
            installZip(download, platform.api);
        } finally { Files.deleteIfExists(download); }
    }

    /** Accepts an official platform zip or a zip of one installed platform directory. */
    public String importArchive(InputStream input) throws IOException {
        Path zip = Files.createTempFile(cache, "import-platform-", ".zip");
        try {
            try (OutputStream out = Files.newOutputStream(zip)) { copy(input, out, MAX_DOWNLOAD); }
            return installZip(zip, null);
        } finally { Files.deleteIfExists(zip); }
    }

    private String installZip(Path archive, String expected) throws IOException {
        Path stage;
        synchronized (LOCK) {
            prepare();
            stage = Files.createTempDirectory(platforms, ".install-");
            INSTALLING.add(stage);
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > 40000) throw new IOException(uiString(R.string.the_sdk_package_contains_too_many_files));
            String prefix = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.matches("(?:[^/]+/)?source\\.properties")) continue;
                if (prefix != null) throw new IOException(uiString(R.string.select_a_zip_containing_one_sdk_platform));
                prefix = name.substring(0, name.length() - "source.properties".length());
            }
            if (prefix == null) throw new IOException(uiString(R.string.the_sdk_zip_is_missing_source_properties));
            entries = zip.entries();
            long remaining = MAX_EXPANDED;
            while (entries.hasMoreElements()) {
                interrupted();
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix)) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.isEmpty()) continue;
                Path target = stage.resolve(name).normalize();
                if (!target.startsWith(stage) || target.equals(stage) || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException(uiString(R.string.the_sdk_package_contains_an_invalid_path));
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        remaining -= copy(in, out, remaining);
                    }
                }
            }
            Properties metadata = properties(stage.resolve("source.properties"));
            String api = metadata.getProperty("AndroidVersion.ApiLevel", "");
            if (!metadata.getProperty("AndroidVersion.CodeName", "").isEmpty() || "false".equalsIgnoreCase(metadata.getProperty("AndroidVersion.IsBaseSdk")) ||
                    !API.matcher(api).matches() || (expected != null && !expected.equals(api)))
                throw new IOException(uiString(R.string.select_a_stable_base_android_sdk_platform_package));
            try (ZipFile jar = new ZipFile(stage.resolve("android.jar").toFile())) {
                if (jar.getEntry("android/app/Activity.class") == null || jar.getEntry("resources.arsc") == null)
                    throw new IOException(uiString(R.string.android_jar_is_missing_the_android_api_or));
            }
            synchronized (LOCK) {
                prepare();
                Path target = directory(api), backup = backup(api);
                if (Files.exists(target)) Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
                try {
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException failure) {
                    if (Files.exists(backup)) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                    throw failure;
                }
                delete(backup);
            }
            return api;
        } finally {
            synchronized (LOCK) {
                INSTALLING.remove(stage);
                delete(stage);
            }
        }
    }

    private Path directory(String api) throws IOException {
        if (!API.matcher(api).matches()) throw new IOException(uiString(R.string.invalid_sdk_platform_number));
        return platforms.resolve("android-" + api);
    }

    private Path backup(String api) { return platforms.resolve(".previous-" + api); }

    private void prepare() throws IOException {
        Files.createDirectories(platforms);
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(platforms, path -> {
            String name = path.getFileName().toString();
            return name.startsWith(".removed-") || (name.startsWith(".install-") && !INSTALLING.contains(path));
        })) {
            for (Path path : stale) delete(path);
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(platforms, ".previous-*")) {
            for (Path backup : dirs) {
                Path active = directory(backup.getFileName().toString().substring(".previous-".length()));
                if (Files.exists(active)) delete(backup);
                else Files.move(backup, active, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    public void remove(String api) throws IOException {
        synchronized (LOCK) {
            prepare();
            Path target = directory(api);
            if (!Files.exists(target)) return;
            Path trash = platforms.resolve(".removed-" + UUID.randomUUID());
            Files.move(target, trash, StandardCopyOption.ATOMIC_MOVE);
            delete(trash);
        }
    }

    Path linkJar(String api, Path temp) throws IOException {
        synchronized (LOCK) {
            prepare();
            Path file = directory(api).resolve("android.jar");
            if (!Files.exists(file) && !api.contains(".")) file = directory(api + ".0").resolve("android.jar");
            if (!Files.isRegularFile(file)) throw new IOException(uiString(R.string.use_sdk_manager_to_install_android_sdk_platform_format, api));
            Path local = temp.resolve("android.jar");
            // A concurrent uninstall cannot invalidate the SDK used by this build.
            try { Files.createLink(local, file); }
            catch (IOException | UnsupportedOperationException e) { Files.copy(file, local); }
            return local;
        }
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.canceled));
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            interrupted();
            total += n;
            if (total > limit) throw new IOException(uiString(R.string.the_sdk_package_exceeds_the_size_limit));
            out.write(buf, 0, n);
        }
        return total;
    }

    private static Properties properties(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            Iterator<Path> entries = paths.sorted(Comparator.reverseOrder()).iterator();
            while (entries.hasNext()) Files.delete(entries.next());
        }
    }
}
