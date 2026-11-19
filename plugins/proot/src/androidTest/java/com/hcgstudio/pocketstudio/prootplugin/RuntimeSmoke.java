package com.hcgstudio.pocketstudio.prootplugin;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Device-side regression: exercise the plugin UID and execute a newly compiled ELF. */
public final class RuntimeSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            new DebianImage(getTargetContext()).install();
            DebianRunner runner = new DebianRunner(getTargetContext());
            String project = "ci-" + UUID.randomUUID();
            String script = "set -eu\n"
                    + "test \"$(pwd)\" = /home/studio/projects/" + project + "\n"
                    + "test \"$(id -u)\" = 1000\n"
                    + "printf 'int main(void) { return 0; }' > test.c\n"
                    + "gcc test.c -o compiled && ./compiled\n"
                    + "cmake --version && python3 -V && node -v && go version && javac -version && dotnet --info\n"
                    + "printf 'PROOT_STDERR_OK\\n' >&2\n";
            Process tools = runner.start(project, new String[]{"/bin/sh", "-c", script}, false);
            ExecutorService readers = Executors.newFixedThreadPool(2);
            try {
                Future<String> stdout = readers.submit(() -> read(tools.getInputStream()));
                Future<String> stderr = readers.submit(() -> read(tools.getErrorStream()));
                tools.getOutputStream().close();
                if (!tools.waitFor(3, TimeUnit.MINUTES)) throw new IOException("Toolchain smoke timed out");
                String out = stdout.get(10, TimeUnit.SECONDS);
                String err = stderr.get(10, TimeUnit.SECONDS);
                if (tools.exitValue() != 0 || !err.contains("PROOT_STDERR_OK") || out.contains("PROOT_STDERR_OK"))
                    throw new IOException("Toolchain smoke failed: " + out + "\n" + err);
                result.putString("tools", out);
                Process pty = runner.start(project, new String[]{"/bin/sh", "-c", "test -t 0 && test -t 1"}, true);
                try {
                    pty.getOutputStream().close();
                    if (!pty.waitFor(20, TimeUnit.SECONDS) || pty.exitValue() != 0)
                        throw new IOException("Guest PTY failed: " + read(pty.getInputStream()));
                } finally { pty.destroy(); }
            } finally { tools.destroy(); readers.shutdownNow(); }
            Process sleep = runner.start(project,
                    new String[]{"/bin/sh", "-c", "sleep 120 & echo ready; wait"}, false);
            try (BufferedReader events = new BufferedReader(new InputStreamReader(sleep.getInputStream()))) {
                if (!"ready".equals(events.readLine())) throw new IOException("Background command did not start");
                sleep.destroy();
                if (!sleep.waitFor(10, TimeUnit.SECONDS)) throw new IOException("PRoot did not cancel the command");
            } finally { sleep.destroy(); }
            result.putString("result", "PASS PRoot toolchain, newly compiled ELF, stderr, guest PTY and cancellation");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable t) {
            result.putString("failure", Log.getStackTraceString(t));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] block = new byte[16384];
            for (int n; (n = in.read(block)) != -1;) bytes.write(block, 0, n);
        }
        return bytes.toString("UTF-8");
    }
}
