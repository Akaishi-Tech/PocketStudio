package com.hcgstudio.pocketstudio.androidplugin;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cancels one Wrapper invocation, including Gradle's single-use daemon in its own Unix session. */
final class GradleProcesses {
    private static final String RUN_ID = "POCKET_STUDIO_GRADLE_RUN";
    private final String id = UUID.randomUUID().toString();
    private final String marker = "\0" + RUN_ID + "=" + id + "\0";

    void mark(ProcessBuilder builder) {
        builder.environment().put(RUN_ID, id);
    }

    void stop(Process wrapper, int group) {
        Map<Integer, Proc> stopped = new HashMap<>();
        try {
            // Keep the Wrapper alive while walking its descendants. Even a child with a cleared
            // environment can still be identified by its parent, across setsid() boundaries.
            if (group > 1 && wrapper.isAlive()) signal(-group, OsConstants.SIGSTOP);
            for (int pass = 0; pass < 10; pass++) {
                List<Proc> candidates = snapshot();
                Map<Integer, Proc> alive = new HashMap<>();
                for (Proc p : candidates) alive.put(p.pid, p);
                boolean grew;
                do {
                    grew = false;
                    for (Proc p : candidates) {
                        if (p.matches(stopped.get(p.pid))) continue;
                        Proc parent = alive.get(p.parent);
                        if (p.marked || (group > 1 && p.group == group && wrapper.isAlive()) ||
                                (parent != null && parent.matches(stopped.get(p.parent)))) {
                            if (!p.matches(read(p.directory))) continue;
                            signal(p.pid, OsConstants.SIGSTOP);
                            stopped.put(p.pid, p);
                            grew = true;
                        }
                    }
                } while (grew);
                // A process that was forking when SIGSTOP arrived may have created another child.
                boolean settled = true;
                for (Proc p : candidates) {
                    if (p.matches(stopped.get(p.pid)) && !p.stopped) { settled = false; break; }
                }
                if (settled && pass > 0) break;
                SystemClock.sleep(15);
            }
        } finally {
            for (Proc p : stopped.values()) {
                if (p.matches(read(p.directory))) signal(p.pid, OsConstants.SIGKILL);
            }
            if (group > 1 && wrapper.isAlive()) signal(-group, OsConstants.SIGKILL);
            if (wrapper.isAlive()) wrapper.destroyForcibly();
        }
    }

    private List<Proc> snapshot() {
        List<Proc> found = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return found;
        int uid = android.os.Process.myUid(), self = android.os.Process.myPid();
        for (File dir : entries) {
            if (!Character.isDigit(dir.getName().charAt(0))) continue;
            try {
                if (Os.stat(dir.getPath()).st_uid != uid) continue;
            } catch (ErrnoException e) { continue; }
            Proc p = read(dir);
            if (p == null || p.pid == self) continue;
            try {
                byte[] env = Files.readAllBytes(dir.toPath().resolve("environ"));
                p.marked = ("\0" + new String(env, StandardCharsets.ISO_8859_1)).contains(marker);
            } catch (IOException | SecurityException ignored) {
                // Some devices restrict environ. The original group and parent tree still work.
            }
            found.add(p);
        }
        return found;
    }

    private static Proc read(File dir) {
        try {
            String line = new String(Files.readAllBytes(dir.toPath().resolve("stat")), StandardCharsets.UTF_8);
            // The comm field in parentheses may contain spaces and even a closing parenthesis.
            String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
            return new Proc(dir, fields);
        } catch (IOException | SecurityException | IndexOutOfBoundsException | NumberFormatException e) {
            return null;
        }
    }

    private static final class Proc {
        final File directory;
        final int pid, parent, group;
        final long startTicks;
        final boolean stopped;
        boolean marked;

        Proc(File dir, String[] fields) {
            directory = dir;
            pid = Integer.parseInt(dir.getName());
            parent = Integer.parseInt(fields[1]);
            group = Integer.parseInt(fields[2]);
            startTicks = Long.parseLong(fields[19]);
            stopped = "TtZX".contains(fields[0]);
        }

        boolean matches(Proc other) {
            return other != null && pid == other.pid && startTicks == other.startTicks;
        }
    }

    private static void signal(int pid, int sig) {
        try { Os.kill(pid, sig); }
        catch (ErrnoException ignored) { /* A child can exit during the scan. */ }
    }
}
