package com.hcgstudio.pocketstudio.wireless;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one AOSP adb server. Its smart socket and pairing keys live in app-private storage. */
final class PrivateAdbServer implements Closeable {
    private final Process process;
    private final File socketPath;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Closing the Java owner's stdin (also on process death) stops the native server.
    private static final String SUPERVISE =
            "\"$1\" -L \"localfilesystem:$2\" server nodaemon </dev/null >/dev/null 2>&1 & child=$!; "
            + "trap 'kill $child 2>/dev/null; wait $child' EXIT; read -r unused";

    PrivateAdbServer(Context context) throws IOException {
        if (Build.VERSION.SDK_INT < 30) throw new IOException(uiString(R.string.wireless_pairing_requires_android_11_or_later_on));
        File home = context.getDir("wireless", Context.MODE_PRIVATE);
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libstudio_adb.so");
        if (!binary.canExecute()) throw new IOException(uiString(R.string.the_wireless_debugging_component_is_unavailable_for_this));
        socketPath = File.createTempFile("adb-", ".sock", context.getCacheDir());
        if (!socketPath.delete()) throw new IOException(uiString(R.string.could_not_create_the_app_s_private_debugging));
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", SUPERVISE,
                "studio-adb", binary.getAbsolutePath(), socketPath.getAbsolutePath());
        Map<String, String> env = builder.environment();
        env.put("HOME", home.getPath());
        env.put("ANDROID_SDK_HOME", home.getPath());
        env.put("ANDROID_USER_HOME", new File(home, ".android").getPath());
        env.put("TMPDIR", context.getCacheDir().getPath());
        env.put("ADB_MDNS", "0");
        env.put("ADB_MDNS_AUTO_CONNECT", "0");
        env.put("ADB_EMU", "0");
        env.put("ADB_USB", "0");
        env.put("ADB_LOCAL_TRANSPORT_MAX_PORT", "0");
        builder.redirectErrorStream(true);
        process = builder.start();
        long end = SystemClock.elapsedRealtime() + 5000;
        try {
            while (SystemClock.elapsedRealtime() < end && process.isAlive()) {
                try (LocalSocket ignored = socket()) { return; }
                catch (IOException waiting) { SystemClock.sleep(50); }
            }
            String message = Os.sysconf(OsConstants._SC_PAGESIZE) > 4096
                    ? uiString(R.string.the_wireless_debugging_component_could_not_start_with) : uiString(R.string.could_not_start_the_app_s_private_wireless);
            throw new IOException(message);
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    boolean isAlive() { return !closed.get() && process.isAlive(); }

    LocalSocket socket() throws IOException {
        if (!isAlive()) throw new IOException(uiString(R.string.the_wireless_debugging_service_closed));
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(socketPath.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(20000);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    String query(String service) throws IOException {
        try (LocalSocket client = socket()) {
            request(client, service);
            return readMessage(new DataInputStream(client.getInputStream()));
        }
    }

    static void request(LocalSocket client, String service) throws IOException {
        byte[] body = service.getBytes(StandardCharsets.UTF_8);
        if (body.length > 65535 || service.indexOf(0) >= 0) throw new IOException(uiString(R.string.invalid_adb_request));
        OutputStream out = client.getOutputStream();
        out.write(String.format(Locale.ROOT, "%04x", body.length).getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        DataInputStream in = new DataInputStream(client.getInputStream());
        String status = fourChars(in);
        if ("FAIL".equals(status)) throw new IOException(readMessage(in));
        if (!"OKAY".equals(status)) throw new IOException(uiString(R.string.the_wireless_adb_service_returned_an_invalid_status));
    }

    static String readMessage(DataInputStream in) throws IOException {
        int size;
        try { size = Integer.parseInt(fourChars(in), 16); }
        catch (NumberFormatException e) { throw new IOException(uiString(R.string.invalid_wireless_adb_message_length), e); }
        if (size < 0 || size > 65535) throw new IOException(uiString(R.string.invalid_wireless_adb_message_length));
        byte[] message = new byte[size];
        in.readFully(message);
        return new String(message, StandardCharsets.UTF_8);
    }

    private static String fourChars(DataInputStream in) throws IOException {
        byte[] field = new byte[4];
        try { in.readFully(field); }
        catch (EOFException e) { throw new IOException(uiString(R.string.the_wireless_adb_service_disconnected), e); }
        return new String(field, StandardCharsets.US_ASCII);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            process.getOutputStream().close();
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) process.destroy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        } catch (IOException ignored) {
            process.destroy();
        } finally {
            socketPath.delete();
        }
    }
}
