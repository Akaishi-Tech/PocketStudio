package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** A native working directory for one active operation on the selected project. */
final class ProjectWorkspace implements AutoCloseable {
    private static final Set<String> active = new HashSet<>();
    final Path directory;
    final boolean direct;
    final Path gradleCache;
    private final String key;
    private final DocumentMirror mirror;
    private boolean closed;

    private ProjectWorkspace(String key, Path directory, DocumentMirror mirror, Path gradleCache) {
        this.gradleCache = gradleCache;
        this.key = key;
        this.directory = directory;
        this.mirror = mirror;
        this.direct = mirror == null;
    }

    static ProjectWorkspace open(Project project) throws IOException {
        String key = project.tree == null ? project.root.uri.toString()
                : DocumentsContract.buildDocumentUri(project.root.uri.getAuthority(),
                        DocumentsContract.getDocumentId(project.root.uri)).toString();
        synchronized (active) {
            if (!active.add(key)) throw new IOException(uiString(R.string.a_build_or_agent_operation_is_already_running));
        }
        try {
            Path direct = directDirectory(project);
            if (project.tree == null) return new ProjectWorkspace(key, direct, null, null);
            String id = hex(sha256().digest(key.getBytes(StandardCharsets.UTF_8)));
            Path privateRoot = project.context.getNoBackupFilesDir().toPath();
            Path state = privateRoot.resolve("project-workspaces").resolve(id);
            Path cache = privateRoot.resolve("gradle-project-caches").resolve(id);
            // Recover pending edits from a previous process even when direct-file access was just enabled.
            if (direct != null && !Files.exists(state)) return new ProjectWorkspace(key, direct, null, cache);
            DocumentMirror mirror = new DocumentMirror(project, state);
            mirror.sync();
            if (direct != null) {
                Path retired = state.resolveSibling(state.getFileName() + "-retired");
                if (Files.exists(retired)) Project.removeTree(retired);
                Files.move(state, retired, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Project.removeTree(retired);
                return new ProjectWorkspace(key, direct, null, cache);
            }
            return new ProjectWorkspace(key, mirror.directory, mirror, cache);
        } catch (IOException | RuntimeException | Error e) {
            synchronized (active) { active.remove(key); }
            throw e;
        }
    }

    synchronized void sync() throws IOException {
        if (closed) throw new IOException(uiString(R.string.the_project_working_directory_is_closed));
        if (mirror != null) mirror.sync();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        // A cancelled native tool may already have written files. Complete this checkpoint after it exits.
        boolean interrupted = Thread.interrupted();
        try {
            sync();
        } finally {
            closed = true;
            synchronized (active) { active.remove(key); }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static Path directDirectory(Project project) throws IOException {
        if (project.tree == null) {
            Path root = new File(project.root.uri.getPath()).toPath().toRealPath();
            Path imports = new File(project.context.getFilesDir(), "imports").toPath().toRealPath();
            if (!root.startsWith(imports) || root.equals(imports) || !Files.isDirectory(root))
                throw new IOException(uiString(R.string.the_imported_project_is_no_longer_available));
            return root;
        }
        Path selected = sharedLocation(project);
        if (selected == null || !Files.isDirectory(selected) || !Files.isReadable(selected) || !Files.isWritable(selected))
            return null;
        for (File dir : project.context.getExternalFilesDirs(null)) {
            if (dir != null && selected.startsWith(dir.getCanonicalFile().toPath())) return selected;
        }
        boolean fileAccess = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager(selected.toFile())
                : (Build.VERSION.SDK_INT < 29 || Environment.isExternalStorageLegacy()) &&
                project.context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return fileAccess ? selected : null;
    }

    private static Path sharedLocation(Project project) throws IOException {
        if (!"com.android.externalstorage.documents".equals(project.root.uri.getAuthority())) return null;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (project.context.checkUriPermission(project.root.uri, android.os.Process.myPid(),
                android.os.Process.myUid(), flags) != PackageManager.PERMISSION_GRANTED) return null;
        String id = DocumentsContract.getDocumentId(project.root.uri);
        int separator = id.indexOf(':');
        if (separator < 1) return null;
        String volumeId = id.substring(0, separator);
        String relative = id.substring(separator + 1);
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\0') >= 0) return null;
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        File volume = null;
        if ("primary".equalsIgnoreCase(volumeId)) volume = Environment.getExternalStorageDirectory();
        else if (Build.VERSION.SDK_INT >= 30) {
            StorageManager manager = project.context.getSystemService(StorageManager.class);
            for (StorageVolume entry : manager.getStorageVolumes())
                if (volumeId.equalsIgnoreCase(entry.getUuid())) volume = entry.getDirectory();
        }
        if (volume == null) return null;
        Path base = volume.getCanonicalFile().toPath();
        Path selected = base.resolve(relative);
        Path canonical = selected.toFile().getCanonicalFile().toPath();
        if (!canonical.equals(selected) || !canonical.startsWith(base)) return null;
        return canonical;
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
