package com.hcgstudio.pocketstudio.avfplugin;

import android.content.Context;
import android.util.AtomicFile;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class AvfSsh {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 2222;
    static final String PREFS = "avf";
    static final String PIN = "host-fingerprint";

    private static synchronized byte[] identity(Context context) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "avf-client-key"));
        try { return file.readFully(); }
        catch (java.io.FileNotFoundException missing) {
            KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                pair.writePrivateKey(out);
                file.finishWrite(out);
            } catch (Exception failure) {
                file.failWrite(out);
                throw failure;
            } finally {
                pair.dispose();
            }
        }
        return file.readFully();
    }

    static byte[] authorizedKey(Context context) throws Exception {
        byte[] privateKey = identity(context);
        try {
            KeyPair pair = KeyPair.load(new JSch(), privateKey, null);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                pair.writePublicKey(out, "PocketStudio-AVF");
                return out.toByteArray();
            } finally {
                pair.dispose();
            }
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
    }

    private static Session connect(Context context, String pin) throws Exception {
        if (pin == null || !pin.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw new IOException(context.getString(R.string.avf_missing_pin));
        byte[] privateKey = identity(context);
        JSch client = new JSch();
        Session session = null;
        try {
            client.addIdentity("Pocket Studio", privateKey, null, null);
            client.setHostKeyRepository(new PinnedServer(pin));
            session = client.getSession("studio", HOST, PORT);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("server_host_key", "ecdsa-sha2-nistp256");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setConfig("max_input_buffer_size", "1048576");
            session.setDaemonThread(true);
            session.setServerAliveInterval(15000);
            session.setServerAliveCountMax(3);
            session.connect(10000);
            return session;
        } catch (Exception failure) {
            if (session != null) session.disconnect();
            throw failure;
        } finally {
            Arrays.fill(privateKey, (byte) 0);
            client.removeAllIdentity();
        }
    }

    private static final class PinnedServer implements HostKeyRepository {
        private final byte[] digest;
        PinnedServer(String pin) { digest = Base64.getDecoder().decode(pin.substring(7)); }

        @Override public int check(String host, byte[] key) {
            try {
                return MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(key))
                    ? OK : CHANGED;
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
        @Override public String getKnownHostsRepositoryID() { return "Pocket Studio AVF"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
        @Override public void add(HostKey key, UserInfo info) { throw new UnsupportedOperationException(); }
        @Override public void remove(String host, String type) { throw new UnsupportedOperationException(); }
        @Override public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException(); }
    }

    static void pair(Context context, String pin) throws Exception {
        Session session = connect(context, pin);
        try {
            if (!context.getSharedPreferences(PREFS, 0).edit().putString(PIN, pin).commit())
                throw new IOException(context.getString(R.string.avf_save_failed));
        } finally {
            session.disconnect();
        }
    }

    static Process start(Context context, String project, String[] argv, boolean terminal) throws IOException {
        if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IOException("Invalid Linux project directory");
        if (argv == null || argv.length == 0 || argv[0] == null || argv[0].isEmpty())
            throw new IOException("Expected an executable");
        StringBuilder command = new StringBuilder("umask 077; cd /home/studio && mkdir -p -- projects/")
            .append(project).append(" && cd -- projects/").append(project).append(" && exec");
        for (String arg : argv) {
            if (arg == null || arg.indexOf(0) >= 0) throw new IOException("Invalid process argument");
            command.append(" '").append(arg.replace("'", "'\\''")).append("'");
        }
        Session session = null;
        try {
            session = connect(context, context.getSharedPreferences(PREFS, 0).getString(PIN, null));
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setPty(terminal);
            if (terminal) channel.setPtyType("xterm-256color");
            channel.setCommand(command.toString());
            return new SshProcess(session, channel);
        } catch (Exception error) {
            if (session != null) session.disconnect();
            throw new IOException(context.getString(R.string.avf_connection_failed, error.getMessage()), error);
        }
    }

    private static final class SshProcess extends Process {
        private final ChannelExec channel;
        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream stdin;
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile int status;

        SshProcess(Session session, ChannelExec channel) throws Exception {
            this.channel = channel;
            stdout = channel.getInputStream();
            stderr = channel.getErrStream();
            stdin = channel.getOutputStream();
            channel.connect(10000);
            Thread watcher = new Thread(() -> {
                try {
                    while (!channel.isClosed() && session.isConnected()) Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    int exit = channel.getExitStatus();
                    status = exit < 0 ? 255 : exit;
                    channel.disconnect();
                    session.disconnect();
                    finished.countDown();
                }
            }, "AVF SSH process");
            watcher.setDaemon(true);
            watcher.start();
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException { finished.await(); return status; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (isAlive()) throw new IllegalThreadStateException();
            return status;
        }
        @Override public boolean isAlive() { return finished.getCount() != 0; }
        private void stop(String signal) {
            if (channel.isClosed()) return;
            try { channel.sendSignal(signal); }
            catch (Exception ignored) { /* A disconnected guest has already closed this job. */ }
            channel.disconnect();
        }
        @Override public void destroy() { stop("TERM"); }
        @Override public Process destroyForcibly() { stop("KILL"); return this; }
    }
}
