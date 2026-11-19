package com.hcgstudio.pocketstudio.linuxipc;

import android.content.*;
import android.os.*;
import java.lang.Process;
import java.io.*;
import java.util.concurrent.*;

/** Opens one command in the selected plugin. The service stays bound until all output is delivered. */
public final class RuntimeConnection {
    private RuntimeConnection() {}

    public static Process start(Context context, ComponentName service, String project, String[] argv,
            boolean terminal) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Start Linux commands off the UI thread");
        Context app = context.getApplicationContext();
        CompletableFuture<IRuntimeService> ready = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ready.complete(IRuntimeService.Stub.asInterface(binder));
            }
            @Override public void onNullBinding(ComponentName name) { unavailable(); }
            @Override public void onBindingDied(ComponentName name) { unavailable(); }
            @Override public void onServiceDisconnected(ComponentName name) { unavailable(); }
            private void unavailable() { ready.completeExceptionally(new IOException("Linux service disconnected")); }
        };
        if (!app.bindService(new Intent().setComponent(service), connection, Context.BIND_AUTO_CREATE))
            throw new IOException("Could not bind " + service.flattenToShortString());

        ParcelFileDescriptor[][] pipes = new ParcelFileDescriptor[4][];
        try {
            IRuntimeService remote = ready.get(15, TimeUnit.SECONDS);
            for (int i = 0; i < pipes.length; i++) pipes[i] = ParcelFileDescriptor.createPipe();
            IBinder owner = new Binder();
            IRunningCommand command = remote.start(project, argv, terminal, owner,
                    pipes[0][0], pipes[1][1], pipes[2][1], pipes[3][1]);
            pipes[0][0].close(); pipes[1][1].close(); pipes[2][1].close(); pipes[3][1].close();
            return new RemoteProcess(app, connection, command, owner, pipes);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            for (ParcelFileDescriptor[] pipe : pipes) if (pipe != null)
                for (ParcelFileDescriptor end : pipe) try { end.close(); } catch (IOException ignored) {}
            app.unbindService(connection);
            throw new IOException("Cannot start Linux command", e);
        }
    }

    private static final class RemoteProcess extends Process {
        // Keep a strong reference for the duration of the process, so IBinder death represents host death.
        private final IBinder owner;
        private final IRunningCommand remote;
        private final InputStream stdout, stderr;
        private final OutputStream stdin;
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile int code = 125;

        RemoteProcess(Context app, ServiceConnection connection, IRunningCommand remote,
                IBinder owner, ParcelFileDescriptor[][] pipes) {
            this.remote = remote;
            this.owner = owner;
            stdin = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[0][1]);
            stdout = new ParcelFileDescriptor.AutoCloseInputStream(pipes[1][0]);
            stderr = new ParcelFileDescriptor.AutoCloseInputStream(pipes[2][0]);
            Thread watcher = new Thread(() -> {
                try (DataInputStream status = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pipes[3][0]))) {
                    code = status.readInt();
                } catch (IOException disconnected) { code = 125; }
                finally {
                    try { stdin.close(); } catch (IOException ignored) {}
                    try { app.unbindService(connection); } finally { finished.countDown(); }
                }
            }, "Linux command completion");
            watcher.setDaemon(true);
            watcher.start();
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException { finished.await(); return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (finished.getCount() != 0) throw new IllegalThreadStateException("Linux command is still running");
            return code;
        }
        @Override public void destroy() {
            if (finished.getCount() != 0) {
                try { remote.cancel(); } catch (RemoteException ignored) {}
                try { stdin.close(); } catch (IOException ignored) {}
                // Also unblock a pump when nobody will consume the rest of a cancelled command's output.
                try { stdout.close(); } catch (IOException ignored) {}
                try { stderr.close(); } catch (IOException ignored) {}
            }
        }
    }
}
