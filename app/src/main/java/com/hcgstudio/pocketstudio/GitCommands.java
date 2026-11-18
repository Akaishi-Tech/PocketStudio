package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Read-only repository inspection shared by the project menu and command palette. */
final class GitCommands {
    static String run(Project project, String command) throws Exception {
        if (!project.resolve(".git").folder) throw new IOException(uiString(R.string.select_the_workspace_root_containing_the_git_directory));
        Path snapshot = null;
        try {
            File dir;
            if ("file".equals(project.root.uri.getScheme())) dir = new File(project.root.uri.getPath());
            else {
                snapshot = Files.createTempDirectory(project.context.getCacheDir().toPath(), "git-worktree-");
                project.copyForGit(snapshot.toFile());
                dir = snapshot.toFile();
            }
            try (Git git = Git.open(dir)) {
                // SAF and ZIP imports do not retain POSIX executable bits.
                git.getRepository().getConfig().setBoolean("core", null, "filemode", false);
                switch (command) {
                    case "status":
                        return status(git);
                    case "log":
                        StringBuilder log = new StringBuilder();
                        try {
                            for (RevCommit commit : git.log().setMaxCount(30).call())
                                log.append(commit.abbreviate(8).name()).append("  ").append(commit.getShortMessage()).append('\n');
                        } catch (NoHeadException e) { return uiString(R.string.no_commits_yet); }
                        return log.toString().trim();
                    case "diff":
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        OutputStream limited = new OutputStream() {
                            @Override public void write(int data) { if (bytes.size() < 128 * 1024) bytes.write(data); }
                            @Override public void write(byte[] data, int at, int size) {
                                bytes.write(data, at, Math.min(size, 128 * 1024 - bytes.size()));
                            }
                        };
                        git.diff().setOutputStream(limited).call();
                        if (bytes.size() == 0) return uiString(R.string.no_unstaged_changes);
                        String diff = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                        return bytes.size() == 128 * 1024 ? uiString(R.string.git_output_truncated, diff) : diff;
                    default: throw new IllegalArgumentException(uiString(R.string.unknown_git_command_format, command));
                }
            }
        } finally {
            if (snapshot != null) try (Stream<Path> files = Files.walk(snapshot)) {
                Iterator<Path> entries = files.sorted(Comparator.reverseOrder()).iterator();
                while (entries.hasNext()) Files.deleteIfExists(entries.next());
            }
        }
    }

    private static String status(Git git) throws Exception {
        Status s = git.status().call();
        StringBuilder result = new StringBuilder("## ").append(git.getRepository().getBranch());
        SortedSet<String> files = new TreeSet<>(s.getUncommittedChanges());
        files.addAll(s.getUntracked());
        for (String file : files) {
            char staged = s.getAdded().contains(file) ? 'A' : s.getRemoved().contains(file) ? 'D' : s.getChanged().contains(file) ? 'M' : ' ';
            char worktree = s.getMissing().contains(file) ? 'D' : s.getModified().contains(file) ? 'M' : ' ';
            String flags = s.getUntracked().contains(file) ? "??" : s.getConflicting().contains(file) ? "UU" : "" + staged + worktree;
            result.append('\n').append(flags).append(' ').append(file);
        }
        if (s.isClean()) result.append(uiString(R.string.nworking_tree_clean));
        return result.toString();
    }
}
