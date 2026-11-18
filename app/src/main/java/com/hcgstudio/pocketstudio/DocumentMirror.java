package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.util.AtomicFile;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Reconciles the native worktree and SAF using the last common content hashes. */
final class DocumentMirror {
    final Path directory;
    private final Project project;
    private final Path state;
    private final AtomicFile index;
    private final AtomicFile pendingWrite;
    private static final int FORMAT = 1;

    private static final class Entry {
        final Project.Node node;
        final SortedMap<String, Entry> children;
        final String hash;
        Entry(Project.Node node, SortedMap<String, Entry> children, String hash) {
            this.node = node;
            this.children = children;
            this.hash = hash;
        }
        boolean folder() { return children != null; }
    }

    DocumentMirror(Project project, Path state) throws IOException {
        this.project = project;
        this.state = Files.createDirectories(state);
        index = new AtomicFile(state.resolve("index").toFile());
        pendingWrite = new AtomicFile(state.resolve("pending-write").toFile());
        Path files = state.resolve("files");
        if ((index.getBaseFile().exists() || Files.exists(files, LinkOption.NOFOLLOW_LINKS))
                && !Files.isDirectory(files, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(uiString(R.string.the_working_copy_is_missing_sync_paused_to));
        directory = Files.createDirectories(files);
    }

    void sync() throws IOException {
        Entry base;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(index.openRead()))) {
            if (in.readInt() != FORMAT) throw new IOException(uiString(R.string.invalid_project_sync_index_version));
            base = read(in);
        } catch (FileNotFoundException missing) {
            base = new Entry(null, new TreeMap<>(), null);
        }
        List<Path> links = new ArrayList<>();
        Entry local = local(directory, links);
        Entry remote = remote(project.root);
        List<String> conflicts = new ArrayList<>();
        recoverPendingWrite(remote, conflicts);
        if (!links.isEmpty()) {
            Project.Node recovered = project.createExact(project.root, "Studio-Recovered-" + UUID.randomUUID(), true);
            StringBuilder descriptions = new StringBuilder(uiString(R.string.the_document_provider_cannot_store_these_symbolic_links));
            for (Path link : links) {
                String relative = directory.relativize(link).toString();
                descriptions.append(relative).append(" -> ").append(lookup(local, relative).hash.substring(5)).append('\n');
            }
            project.write(project.createExact(recovered, "symlinks.txt", false),
                    descriptions.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (Path link : links) {
                String relative = directory.relativize(link).toString();
                replaceLocal(relative, lookup(local, relative), lookup(remote, relative));
            }
            conflicts.add(recovered.path);
            local = local(directory);
        }
        Entry merged = reconcile("", null, base, local, remote, conflicts);
        FileOutputStream stream = index.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream));
            out.writeInt(FORMAT);
            write(out, merged);
            out.flush();
            index.finishWrite(stream);
        } catch (IOException | RuntimeException e) { index.failWrite(stream); throw e; }
        if (!conflicts.isEmpty()) throw new IOException(uiString(R.string.sync_conflict_recovery, String.join(uiString(R.string.list_separator), conflicts)));
    }

    private Entry reconcile(String path, Project.Node parent, Entry base, Entry local,
                            Entry remote, List<String> conflicts) throws IOException {
        if (same(local, remote)) return local;
        if (local != null && local.folder() && remote != null && remote.folder()
                && (base == null || base.folder())) {
            Map<String, Entry> old = base == null ? Collections.emptyMap() : base.children;
            Set<String> names = new TreeSet<>(local.children.keySet());
            names.addAll(remote.children.keySet());
            SortedMap<String, Entry> merged = new TreeMap<>();
            for (String name : names) {
                Entry item = reconcile(path.isEmpty() ? name : path + "/" + name, remote.node,
                        old.get(name), local.children.get(name), remote.children.get(name), conflicts);
                if (item != null) merged.put(name, item);
            }
            return new Entry(remote.node, merged, null);
        }
        if (same(local, base)) {
            replaceLocal(path, local, remote);
            return remote;
        }
        if (same(remote, base)) {
            replaceRemote(path, parent, remote, local);
            return local;
        }
        // Providers cannot merge a deletion, type change or two distinct edits. Preserve both versions
        // in the granted project, where the user can inspect them without access to the app's private files.
        Project.Node recovery = project.createExact(project.root, "Studio-Recovered-" + UUID.randomUUID(), true);
        Project.Node detail = project.createExact(recovery, "conflict.txt", false);
        project.write(detail, (uiString(R.string.sync_conflict_details, path, (local == null ? uiString(R.string.the_tool_deleted_this_path) : uiString(R.string.the_tool_s_version_is_saved_in_tool)))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (local != null) export(recovery, "tool-version", directory.resolve(path), local);
        conflicts.add(recovery.path);
        replaceLocal(path, local, remote);
        return remote;
    }

    private static Entry lookup(Entry root, String relative) {
        Entry entry = root;
        for (String name : relative.split("/")) {
            if (entry == null || !entry.folder()) return null;
            entry = entry.children.get(name);
        }
        return entry;
    }

    private static boolean same(Entry a, Entry b) {
        if (a == b) return true;
        if (a == null || b == null || a.folder() != b.folder()) return false;
        if (!a.folder()) return a.hash.equals(b.hash);
        if (!a.children.keySet().equals(b.children.keySet())) return false;
        for (String name : a.children.keySet()) if (!same(a.children.get(name), b.children.get(name))) return false;
        return true;
    }

    private Entry remote(Project.Node n) throws IOException {
        if (!n.folder) {
            try (InputStream in = project.input(n)) { return new Entry(n, null, transfer(in, null)); }
        }
        SortedMap<String, Entry> children = new TreeMap<>();
        for (Project.Node child : project.list(n)) {
            checkName(child.name);
            if (children.put(child.name, remote(child)) != null)
                throw new IOException(uiString(R.string.the_storage_provider_contains_duplicate_names_format, child.path));
        }
        return new Entry(n, children, null);
    }

    private static Entry local(Path p) throws IOException { return local(p, null); }

    private static Entry local(Path p, List<Path> links) throws IOException {
        BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attr.isRegularFile()) {
            try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                return new Entry(null, null, transfer(in, null));
            }
        }
        if (attr.isSymbolicLink()) {
            if (links != null) links.add(p);
            return new Entry(null, null, "link:" + Files.readSymbolicLink(p));
        }
        if (!attr.isDirectory()) throw new IOException(uiString(R.string.the_document_provider_cannot_store_special_file_format, p.getFileName()));
        SortedMap<String, Entry> children = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(p)) {
            for (Path child : files) children.put(child.getFileName().toString(), local(child, links));
        }
        return new Entry(null, children, null);
    }

    private static String transfer(InputStream raw, OutputStream out) throws IOException {
        MessageDigest hash = ProjectWorkspace.sha256();
        DigestInputStream in = new DigestInputStream(raw, hash);
        byte[] buf = new byte[65536];
        for (int size; (size = in.read(buf)) != -1; ) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(uiString(R.string.project_sync_interrupted));
            if (out != null) out.write(buf, 0, size);
        }
        return ProjectWorkspace.hex(hash.digest());
    }

    private static void checkName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.indexOf('\0') >= 0)
            throw new IOException(uiString(R.string.the_document_provider_returned_an_invalid_file_name));
    }

    private Entry currentRemote(Project.Node parent, String name) throws IOException {
        for (Project.Node child : project.list(parent)) if (name.equals(child.name)) return remote(child);
        return null;
    }

    private void replaceRemote(String path, Project.Node parent, Entry before, Entry after) throws IOException {
        Path source = directory.resolve(path);
        String name = source.getFileName().toString();
        Entry actual = currentRemote(parent, name);
        if (!same(actual, before)) throw changed(path);
        if (after == null) {
            if (actual != null) project.delete(actual.node);
            return;
        }
        if (!same(local(source), after)) throw changed(path);
        if (actual == null) { export(parent, name, source, after); return; }
        if (!actual.folder() && !after.folder()) {
            // Keep a restorable copy until the provider has acknowledged the complete replacement.
            Path backup = Files.createTempFile(state, "original-", ".bak");
            boolean keepBackup = false;
            try {
                try (InputStream in = project.input(actual.node); OutputStream out = Files.newOutputStream(backup)) {
                    if (!transfer(in, out).equals(actual.hash)) throw changed(path);
                }
                rememberWrite(path, backup, actual.hash, after.hash);
                try {
                    writeDocument(actual.node, source, after.hash);
                } catch (IOException | RuntimeException failure) {
                    boolean interrupted = Thread.interrupted();
                    try { writeDocument(actual.node, backup, actual.hash); }
                    catch (Exception restore) {
                        keepBackup = true;
                        failure.addSuppressed(restore);
                        throw new IOException(uiString(R.string.sync_recovery_failed, backup, source), failure);
                    } finally { if (interrupted) Thread.currentThread().interrupt(); }
                    throw failure;
                }
            } finally {
                if (!keepBackup) {
                    pendingWrite.delete();
                    Files.deleteIfExists(backup);
                }
            }
            return;
        }
        // The old and new entries have different types. Materialize the new data before replacing.
        String stageName = ".studio-replace-" + UUID.randomUUID();
        Project.Node stage = export(parent, stageName, source, after);
        // Keep the original visible until both provider renames have succeeded.
        Project.Node old;
        try {
            if (!same(currentRemote(parent, name), actual)) throw changed(path);
            old = project.renameExact(actual.node, ".studio-original-" + UUID.randomUUID());
        } catch (IOException | RuntimeException e) {
            project.delete(stage);
            throw e;
        }
        try { project.renameExact(stage, name); }
        catch (IOException | RuntimeException e) {
            try { project.renameExact(old, name); project.delete(stage); }
            catch (Exception restore) { e.addSuppressed(restore); }
            throw e;
        }
        project.delete(old);
    }

    // Some document providers only support truncate-and-write. Journal the backup before
    // truncation so even a killed process can recover the previous content on its next run.
    private void rememberWrite(String path, Path backup, String original, String intended) throws IOException {
        FileOutputStream stream = pendingWrite.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(stream);
            out.writeUTF(path);
            out.writeUTF(backup.getFileName().toString());
            out.writeUTF(original);
            out.writeUTF(intended);
            out.flush();
            pendingWrite.finishWrite(stream);
        } catch (IOException | RuntimeException e) { pendingWrite.failWrite(stream); throw e; }
    }

    private void recoverPendingWrite(Entry remote, List<String> conflicts) throws IOException {
        String path, name, original, intended;
        try (DataInputStream in = new DataInputStream(pendingWrite.openRead())) {
            path = in.readUTF();
            name = in.readUTF();
            original = in.readUTF();
            intended = in.readUTF();
        } catch (FileNotFoundException noPendingWrite) { return; }
        checkName(name);
        if (!name.startsWith("original-") || !name.endsWith(".bak")) throw new IOException(uiString(R.string.invalid_project_recovery_record));
        Path backup = state.resolve(name);
        Entry current = lookup(remote, path);
        if (current == null || current.folder() || !original.equals(current.hash) && !intended.equals(current.hash)) {
            // An external edit is indistinguishable from an interrupted provider write. Retain both.
            Project.Node recovered = project.createExact(project.root, "Studio-Recovered-" + UUID.randomUUID(), true);
            project.write(project.createExact(recovered, "interrupted-write.txt", false),
                    (uiString(R.string.the_last_write_was_interrupted_before_confirmation_npath) + path + uiString(R.string.nthe_content_before_the_write_is_saved_in))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            export(recovered, "before-write", backup, new Entry(null, null, original));
            conflicts.add(recovered.path);
        }
        pendingWrite.delete();
        Files.deleteIfExists(backup);
    }

    private Project.Node export(Project.Node parent, String name, Path source, Entry entry) throws IOException {
        Project.Node created = project.createExact(parent, name, entry.folder());
        try {
            if (entry.folder()) {
                for (Map.Entry<String, Entry> child : entry.children.entrySet())
                    export(created, child.getKey(), source.resolve(child.getKey()), child.getValue());
            } else writeDocument(created, source, entry.hash);
            return created;
        } catch (IOException | RuntimeException e) {
            try { project.delete(created); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private void writeDocument(Project.Node node, Path from, String expected) throws IOException {
        try (InputStream in = Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = project.context.getContentResolver().openOutputStream(node.uri, "rwt")) {
            if (out == null) throw new IOException(uiString(R.string.unable_to_write_format, node.path));
            if (!transfer(in, out).equals(expected)) throw changed(node.path);
        }
        try (InputStream in = project.input(node)) {
            if (!transfer(in, null).equals(expected)) throw new IOException(uiString(R.string.the_storage_provider_did_not_finish_writing_format, node.path));
        }
    }

    private void replaceLocal(String path, Entry before, Entry after) throws IOException {
        Path target = directory.resolve(path);
        Path stage = Files.createTempDirectory(state, "incoming-");
        boolean preserveStage = false;
        try {
            Path incoming = stage.resolve("new");
            if (after != null) fetch(after, incoming);
            Entry actual = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? local(target) : null;
            if (!same(actual, before)) throw changed(path);
            if (after != null && !after.folder() && (actual == null || !actual.folder())) {
                Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Path previous = stage.resolve("previous");
            if (actual != null) Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
            try {
                if (after != null) Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (actual != null) try {
                    Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restore) {
                    preserveStage = true;
                    e.addSuppressed(restore);
                    throw new IOException(uiString(R.string.working_copy_restore_failed, previous), e);
                }
                throw e;
            }
        } finally { if (!preserveStage) Project.removeTree(stage); }
    }

    private void fetch(Entry entry, Path target) throws IOException {
        if (entry.folder()) {
            Files.createDirectory(target);
            for (Map.Entry<String, Entry> child : entry.children.entrySet())
                fetch(child.getValue(), target.resolve(child.getKey()));
        } else try (InputStream in = project.input(entry.node); OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            if (!transfer(in, out).equals(entry.hash)) throw changed(entry.node.path);
        }
    }

    private static IOException changed(String path) { return new IOException(uiString(R.string.file_changed_during_sync_try_again_format, path)); }

    private static void write(DataOutputStream out, Entry item) throws IOException {
        out.writeBoolean(item.folder());
        if (!item.folder()) { out.writeUTF(item.hash); return; }
        out.writeInt(item.children.size());
        for (Map.Entry<String, Entry> child : item.children.entrySet()) {
            out.writeUTF(child.getKey());
            write(out, child.getValue());
        }
    }

    private static Entry read(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return new Entry(null, null, in.readUTF());
        SortedMap<String, Entry> children = new TreeMap<>();
        int size = in.readInt();
        if (size < 0) throw new IOException(uiString(R.string.corrupt_project_sync_index));
        for (int i = 0; i < size; i++) {
            String name = in.readUTF();
            checkName(name);
            children.put(name, read(in));
        }
        return new Entry(null, children, null);
    }
}
