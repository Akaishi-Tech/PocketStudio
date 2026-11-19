package com.hcgstudio.pocketstudio.linuxipc;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.*;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.*;

/** The command and its stream pumps run with this plugin's UID. */
public abstract class RuntimeService extends Service {
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Command> active = ConcurrentHashMap.newKeySet();

    protected abstract Process launch(String project, String[] argv, boolean terminal) throws IOException;

    private final IRuntimeService.Stub service = new IRuntimeService.Stub() {
        @Override public IRunningCommand start(String project, String[] argv, boolean terminal, IBinder owner,
                ParcelFileDescriptor input, ParcelFileDescriptor output, ParcelFileDescriptor error,
                ParcelFileDescriptor exit) throws RemoteException {
            int uid = Binder.getCallingUid();
            String[] packages = getPackageManager().getPackagesForUid(uid);
            if (packages == null || !Arrays.asList(packages).contains("com.hcgstudio.pocketstudio")
                    || getPackageManager().checkSignatures(uid, android.os.Process.myUid())
                        != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Caller must be the signed Pocket Studio host");
            if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                    || argv == null || argv.length == 0 || argv.length > 4096 || owner == null)
                throw new IllegalArgumentException("Invalid Linux project or command");
            for (String arg : argv) if (arg == null || arg.indexOf(0) >= 0)
                throw new IllegalArgumentException("Invalid command argument");
            Command command = new Command(owner);
            active.add(command);
            try {
                owner.linkToDeath(command, 0);
                workers.execute(() -> command.run(project, argv, terminal, input, output, error, exit));
            } catch (RemoteException | RuntimeException e) {
                active.remove(command);
                closeQuietly(input); closeQuietly(output); closeQuietly(error); closeQuietly(exit);
                throw e;
            }
            return command;
        }
    };

    @Override public IBinder onBind(Intent intent) { return service; }

    @Override public void onDestroy() {
        for (Command command : active) command.cancel();
        workers.shutdown();
        super.onDestroy();
    }

    private static void closeQuietly(Closeable closeable) {
        try { closeable.close(); } catch (IOException ignored) {}
    }

    private static void copy(InputStream source, OutputStream target) {
        try (InputStream in = source; OutputStream out = target) {
            byte[] block = new byte[32 * 1024];
            for (int n; (n = in.read(block)) != -1;) {
                out.write(block, 0, n);
                out.flush(); // Interactive prompts and LSP messages must arrive immediately.
            }
        } catch (IOException ignored) { /* A closed reader or exited command ends this direction. */ }
    }

    private final class Command extends IRunningCommand.Stub implements IBinder.DeathRecipient {
        final IBinder owner;
        private boolean cancelled;
        private Process process;
        Command(IBinder owner) { this.owner = owner; }

        @Override public synchronized void cancel() {
            cancelled = true;
            if (process != null) process.destroy();
        }

        @Override public void binderDied() { cancel(); }

        void run(String project, String[] argv, boolean terminal, ParcelFileDescriptor input,
                 ParcelFileDescriptor output, ParcelFileDescriptor error, ParcelFileDescriptor exit) {
            int code = 125;
            try (DataOutputStream status = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(exit))) {
                try {
                    synchronized (this) {
                        if (cancelled) throw new IOException("Command cancelled");
                    }
                    Process child = launch(project, argv, terminal);
                    synchronized (this) {
                        process = child;
                        if (cancelled) child.destroy();
                    }
                    workers.execute(() -> copy(new ParcelFileDescriptor.AutoCloseInputStream(input), child.getOutputStream()));
                    Future<?> stdout = workers.submit(() -> copy(child.getInputStream(),
                            new ParcelFileDescriptor.AutoCloseOutputStream(output)));
                    Future<?> stderr = workers.submit(() -> copy(child.getErrorStream(),
                            new ParcelFileDescriptor.AutoCloseOutputStream(error)));
                    code = child.waitFor();
                    stdout.get();
                    stderr.get();
                } catch (Exception failure) {
                    String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                    try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(error)) {
                        stream.write((message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException ignored) {}
                } finally {
                    synchronized (this) { if (process != null) process.destroy(); }
                    closeQuietly(output);
                    closeQuietly(error);
                }
                status.writeInt(code);
            } catch (IOException ignored) { /* The host may have gone away. */ }
            finally {
                closeQuietly(input);
                active.remove(this);
                owner.unlinkToDeath(this, 0);
            }
        }
    }
}
