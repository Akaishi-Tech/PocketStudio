package com.hcgstudio.pocketstudio;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Exercises a real writable DocumentsProvider tree, with and without direct shared storage access. */
public final class ProjectWorkspaceChecks {
    public static boolean run(Context context, String uri) throws Exception {
        Project parent = new Project(context, Uri.parse(uri));
        Project.Node root = parent.create(parent.root, "Workspace-Check-" + UUID.randomUUID(), true);
        try {
            Project project = new Project(context, root.uri);
            Project.Node source = project.create(project.root, "source.txt", false);
            project.write(source, bytes("before"));
            Project.Node removed = project.create(project.root, "remove.txt", false);
            project.write(removed, bytes("remove"));
            boolean direct;
            try (ProjectWorkspace work = ProjectWorkspace.open(project)) {
                direct = work.direct;
                if (!text(work.directory.resolve("source.txt")).equals("before")) throw new AssertionError("read selected project");
                Files.write(work.directory.resolve("source.txt"), bytes("after"));
                Files.delete(work.directory.resolve("remove.txt"));
                Path reports = Files.createDirectories(work.directory.resolve("app/build/reports"));
                Files.write(reports.resolve("report.txt"), bytes("success"));
                if (direct && !project.read(source).equals("after")) throw new AssertionError("direct write");
                boolean rejected = false;
                try (ProjectWorkspace ignored = ProjectWorkspace.open(project)) { }
                catch (IOException e) { rejected = true; }
                if (!rejected) throw new AssertionError("overlapping operations");
            }
            if (!project.read(source).equals("after") ||
                    !project.read(project.resolve("app/build/reports/report.txt")).equals("success"))
                throw new AssertionError("source and build output writeback");
            try { project.resolve("remove.txt"); throw new AssertionError("delete writeback"); }
            catch (java.io.FileNotFoundException expected) { }

            if (!direct) {
                ProjectWorkspace work = ProjectWorkspace.open(project);
                Files.write(work.directory.resolve("source.txt"), bytes("tool"));
                project.write(source, bytes("editor"));
                boolean conflict = false;
                try { work.close(); } catch (IOException e) { conflict = e.getMessage().contains("Studio-Recovered-"); }
                if (!conflict || !project.read(source).equals("editor")) throw new AssertionError("conflicting edit");
                boolean recovered = false;
                for (Project.Node entry : project.list(project.root))
                    if (entry.name.startsWith("Studio-Recovered-") &&
                            project.read(project.resolve(entry.name + "/tool-version")).equals("tool")) recovered = true;
                if (!recovered) throw new AssertionError("tool conflict copy");
                ProjectWorkspace linked = ProjectWorkspace.open(project);
                Path outside = Files.createTempFile(context.getCacheDir().toPath(), "studio-link-target", ".txt");
                try {
                    Files.write(outside, bytes("only inside private cache"));
                    Files.createSymbolicLink(linked.directory.resolve("link.txt"), outside);
                    boolean warned = false;
                    try { linked.close(); }
                    catch (IOException e) { warned = e.getMessage().contains("Studio-Recovered-"); }
                    if (!warned || !text(outside).equals("only inside private cache")) throw new AssertionError("symbolic link isolation");
                    try { project.resolve("link.txt"); throw new AssertionError("private target exposed through SAF"); }
                    catch (java.io.FileNotFoundException expected) { }
                } finally { Files.deleteIfExists(outside); }
                try (ProjectWorkspace next = ProjectWorkspace.open(project)) {
                    if (!text(next.directory.resolve("source.txt")).equals("editor")) throw new AssertionError("repeat open");
                    Files.write(next.directory.resolve("after-cancel.txt"), bytes("partial"));
                    Thread.currentThread().interrupt();
                } finally { Thread.interrupted(); }
                if (!project.read(project.resolve("after-cancel.txt")).equals("partial")) throw new AssertionError("cancelled tool changes");
                checkInterruptedProviderWrite(context, project, source);
            }

            File imports = new File(context.getFilesDir(), "imports");
            Files.createDirectories(imports.toPath());
            Path imported = Files.createTempDirectory(imports.toPath(), "workspace-check-");
            try (ProjectWorkspace work = ProjectWorkspace.open(new Project(context, Uri.fromFile(imported.toFile())))) {
                if (!work.direct || !work.directory.equals(imported.toRealPath())) throw new AssertionError("private direct root");
            } finally { Project.removeTree(imported); }
            return direct;
        } finally { parent.delete(root); }
    }

    private static void checkInterruptedProviderWrite(Context context, Project project, Project.Node source) throws Exception {
        Path state = Files.createTempDirectory(context.getCacheDir().toPath(), "interrupted-provider-");
        try {
            DocumentMirror initial = new DocumentMirror(project, state);
            initial.sync();
            String old = project.read(source);
            Files.write(initial.directory.resolve(source.path), bytes("replacement"));
            // Model a provider truncate followed by process death, before the replacement was acknowledged.
            Files.write(state.resolve("original-interrupted.bak"), bytes(old));
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(state.resolve("pending-write")))) {
                out.writeUTF(source.path);
                out.writeUTF("original-interrupted.bak");
                out.writeUTF(ProjectWorkspace.hex(ProjectWorkspace.sha256().digest(bytes(old))));
                out.writeUTF(ProjectWorkspace.hex(ProjectWorkspace.sha256().digest(bytes("replacement"))));
            }
            project.write(source, bytes("partial"));
            DocumentMirror resumed = new DocumentMirror(project, state);
            boolean warned = false;
            try { resumed.sync(); } catch (IOException e) { warned = e.getMessage().contains("Studio-Recovered-"); }
            boolean preserved = false;
            for (Project.Node entry : project.list(project.root)) if (entry.name.startsWith("Studio-Recovered-")) {
                try { preserved |= project.read(project.resolve(entry.name + "/before-write")).equals(old); }
                catch (java.io.FileNotFoundException ignore) { }
            }
            if (!warned || !preserved || !project.read(source).equals("partial")) throw new AssertionError("interrupted provider recovery");
            resumed.sync();
        } finally { Project.removeTree(state); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}
