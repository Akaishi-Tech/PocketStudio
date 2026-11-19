package com.hcgstudio.pocketstudio.prootplugin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class DebianRunner {
    private final Context app;
    private final DebianImage image;
    DebianRunner(Context context) {
        app = context.getApplicationContext();
        image = new DebianImage(app);
    }

    Process start(String project, String[] argv, boolean terminal) throws IOException {
        if (!project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || argv.length == 0)
            throw new IllegalArgumentException("Invalid project or command");
        Path root = image.acquire();
        try {
            Process child = spawn(root, project, argv, terminal);
            Thread reap = new Thread(() -> {
                try {
                    for (;;) {
                        try { child.waitFor(); break; } catch (InterruptedException ignored) {}
                    }
                } finally { image.release(); }
            }, "Debian process lifetime");
            reap.start();
            return child;
        } catch (IOException | RuntimeException failure) { image.release(); throw failure; }
    }

    private Process spawn(Path root, String project, String[] argv, boolean terminal) throws IOException {
        Path home = image.base.resolve("home");
        Path projects = Files.createDirectories(home.resolve("projects"));
        Path dir = Files.createDirectories(projects.resolve(project));
        if (!dir.toRealPath().equals(home.toRealPath().resolve("projects").resolve(project))) throw new IOException("Linux project directory must not be a link");

        Path lib = Paths.get(app.getApplicationInfo().nativeLibraryDir);
        Path tools = Files.createDirectories(image.base.resolve("runtime"));
        Path tmp = Files.createDirectories(tools.resolve("tmp"));
        Path links = Files.createDirectories(tools.resolve("links"));
        Path shm = Files.createDirectories(tools.resolve("shm"));
        Path resolv = prepareSystemFiles(tools, lib);
        List<String> args = new ArrayList<>(Arrays.asList(lib.resolve("libproot_runtime.so").toString(),
                "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=1000:1000",
                "--rootfs=" + root, "--cwd=/home/studio/projects/" + project,
                "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/proc/self/fd:/dev/fd",
                "--bind=" + shm + ":/dev/shm", "--bind=" + home + ":/home/studio",
                "--bind=" + resolv + ":/etc/resolv.conf!", "/usr/bin/env", "-i", "HOME=/home/studio",
                "USER=studio", "LOGNAME=studio", "SHELL=/bin/bash", "TERM=xterm-256color",
                "LANG=C.UTF-8", "TMPDIR=/tmp", "DOTNET_ROOT=/opt/dotnet",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/dotnet"));
        if (terminal) {
            // util-linux script allocates a guest PTY for the shell, without SSH.
            StringBuilder command = new StringBuilder("exec");
            for (String arg : argv) command.append(" '").append(arg.replace("'", "'\\''")).append("'");
            Collections.addAll(args, "/usr/bin/script", "-qefc", command.toString(), "/dev/null");
        } else Collections.addAll(args, argv);

        // Android's public Process API has no pid(). Read the PID before exec, then hand the
        // unbuffered stream to the caller so the preamble cannot leak into LSP or binary files.
        List<String> launcher = new ArrayList<>(Arrays.asList("/system/bin/sh", "-c",
                "echo $$; exec \"$@\"", "studio-proot"));
        launcher.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(launcher).directory(image.base.toFile()).redirectErrorStream(terminal);
        Map<String,String> env = pb.environment();
        env.clear();
        env.put("PATH", "/system/bin");
        env.put("TMPDIR", tmp.toString());
        env.put("PROOT_TMP_DIR", tmp.toString());
        // The upstream Termux shim otherwise uses an absolute directory owned by com.termux.
        env.put("PROOT_DONT_SHARE_LIBANDROID_SHMEM", "1");
        env.put("PROOT_L2S_DIR", links.toString());
        env.put("PROOT_LOADER", lib.resolve("libproot_loader.so").toString());
        env.put("LD_LIBRARY_PATH", tools + ":" + lib);
        Process child = pb.start();
        try {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = child.getInputStream().read()) >= '0' && ch <= '9' && line.length() < 10)
                line.append((char)ch);
            if (ch != '\n' || line.length() == 0) throw new IOException("Cannot identify PRoot process");
            int pid = Integer.parseInt(line.toString());
            if (pid <= 1) throw new IOException("Invalid PRoot PID");
            return new TracerProcess(child, pid);
        } catch (IOException | RuntimeException e) { child.destroyForcibly(); throw e; }
    }

    private static final class TracerProcess extends Process {
        private final Process child;
        private final int pid;
        private boolean stopping;

        TracerProcess(Process child, int pid) { this.child = child; this.pid = pid; }
        @Override public InputStream getInputStream() { return child.getInputStream(); }
        @Override public InputStream getErrorStream() { return child.getErrorStream(); }
        @Override public OutputStream getOutputStream() { return child.getOutputStream(); }
        @Override public int waitFor() throws InterruptedException { return child.waitFor(); }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return child.waitFor(timeout, unit);
        }
        @Override public int exitValue() { return child.exitValue(); }
        @Override public synchronized void destroy() {
            if (stopping || !child.isAlive()) return;
            stopping = true;
            // PRoot ignores SIGTERM. Its SIGQUIT handler kills all traced descendants,
            // including subprocesses started by a build, before leaving the event loop.
            try { Os.kill(pid, OsConstants.SIGQUIT); } catch (ErrnoException e) {
                if (e.errno != OsConstants.ESRCH) child.destroyForcibly();
            }
        }
    }

    private Path prepareSystemFiles(Path tools, Path nativeLibs) throws IOException {
        synchronized (DebianRunner.class) {
            // Android extracts lib*.so names. Expose upstream's versioned ELF SONAME to the linker.
            Path soname = tools.resolve("libtalloc.so.2");
            Path talloc = nativeLibs.resolve("libtalloc.so");
            if (!Files.isSymbolicLink(soname) || !Files.readSymbolicLink(soname).equals(talloc)) {
                Path temp = tools.resolve("libtalloc.next");
                Files.deleteIfExists(temp);
                Files.createSymbolicLink(temp, talloc);
                Files.move(temp, soname, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            StringBuilder text = new StringBuilder();
            ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
            if (manager != null) {
                LinkProperties network = manager.getLinkProperties(manager.getActiveNetwork());
                if (network != null) for (InetAddress address : network.getDnsServers())
                    text.append("nameserver ").append(address.getHostAddress()).append('\n');
            }
            Path resolv = tools.resolve("resolv.conf");
            Path temp = tools.resolve("resolv.next");
            Files.write(temp, text.toString().getBytes(StandardCharsets.US_ASCII));
            Files.move(temp, resolv, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return resolv;
        }
    }
}
