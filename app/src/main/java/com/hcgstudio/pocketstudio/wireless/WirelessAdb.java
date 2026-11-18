package com.hcgstudio.pocketstudio.wireless;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import android.content.Context;
import android.net.LocalSocket;
import android.os.SystemClock;

import com.hcgstudio.pocketstudio.adb.Target;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Uses the target's six-digit Wireless debugging pairing dialog and separate connection port. */
public final class WirelessAdb implements Target {
    private final String serial;
    private final PrivateAdbServer server;
    private volatile boolean connected = true;

    private WirelessAdb(String serial, PrivateAdbServer server) {
        this.serial = serial;
        this.server = server;
    }

    public static synchronized void pair(Context context, String host, int pairingPort, String code) throws IOException {
        String device = endpoint(host, pairingPort);
        if (code == null || !code.matches("[0-9]{6}")) throw new IOException(uiString(R.string.enter_the_six_digit_pairing_code_shown_on));
        try (PrivateAdbServer server = new PrivateAdbServer(context)) {
            String result = server.query("host:pair:" + code + ":" + device);
            if (!result.startsWith("Successfully paired")) throw new IOException(uiString(R.string.pairing_did_not_finish_check_the_pairing_port));
        }
    }

    public static synchronized WirelessAdb connect(Context context, String host, int connectPort) throws IOException {
        String device = endpoint(host, connectPort);
        PrivateAdbServer server = new PrivateAdbServer(context);
        try {
            String result = server.query("host:connect:" + device);
            if (!result.startsWith("connected to ") && !result.startsWith("already connected to ")) {
                throw new IOException(uiString(R.string.wireless_connection_failed_format, result));
            }
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (!"device".equals(server.query("host-serial:" + device + ":get-state"))) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException(uiString(R.string.the_target_has_not_authorized_this_device_check));
                SystemClock.sleep(100);
            }
            WirelessAdb connection = new WirelessAdb(device, server);
            connection.monitor();
            return connection;
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
    }

    private static String endpoint(String host, int port) throws IOException {
        String address = host == null ? "" : host.trim();
        if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
        if (!address.matches("[a-zA-Z0-9_.:%-]+") || port < 1 || port > 65535) {
            throw new IOException(uiString(R.string.enter_a_valid_target_address_and_port));
        }
        return (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
    }

    private void monitor() throws IOException {
        LocalSocket tracker = server.socket();
        try {
            PrivateAdbServer.request(tracker, "host:track-devices");
            tracker.setSoTimeout(0);
        } catch (IOException e) {
            tracker.close();
            throw e;
        }
        Thread thread = new Thread(() -> {
            try (LocalSocket cursor = tracker) {
                DataInputStream input = new DataInputStream(cursor.getInputStream());
                while (connected) {
                    String list = "\n" + PrivateAdbServer.readMessage(input) + "\n";
                    if (!list.contains("\n" + serial + "\tdevice\n")) break;
                }
            } catch (IOException ignored) { /* process exited or device disconnected */ }
            finally { close(); }
        }, "Wireless ADB devices");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public Stream open(String service) throws IOException {
        if (!isConnected()) throw new IOException(uiString(R.string.the_wireless_target_disconnected));
        LocalSocket socket = server.socket();
        try {
            PrivateAdbServer.request(socket, "host:transport:" + serial);
            PrivateAdbServer.request(socket, service);
            socket.setSoTimeout(0); // Logcat or a paused debugger can be idle indefinitely.
            return new SocketStream(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static final class SocketStream implements Stream {
        private final LocalSocket socket;
        private final InputStream in;
        private final OutputStream out;
        SocketStream(LocalSocket socket) throws IOException {
            this.socket = socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }
        @Override public InputStream input() { return in; }
        @Override public OutputStream output() { return out; }
        @Override public void close() throws IOException { socket.close(); }
    }

    @Override public String shell(String command) throws IOException {
        try (Stream stream = open("shell:" + command)) {
            return text(stream.input(), 4 * 1024 * 1024);
        }
    }

    @Override public void install(InputStream apk, long size) throws IOException {
        if (size <= 0) throw new IOException(uiString(R.string.could_not_determine_the_apk_size));
        try (Stream stream = open("exec:cmd package install -r -S " + size)) {
            byte[] buffer = new byte[32768];
            for (long remaining = size; remaining > 0;) {
                int count = apk.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count < 0) throw new EOFException(uiString(R.string.unexpected_end_of_apk_file));
                stream.output().write(buffer, 0, count);
                remaining -= count;
            }
            String result = text(stream.input(), 65536).trim();
            if (!"Success".equals(result)) throw new IOException(uiString(R.string.installation_failed_format, result));
        }
    }

    private static String text(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (result.size() + count > limit) throw new IOException(uiString(R.string.target_output_is_too_long));
            result.write(buffer, 0, count);
        }
        return result.toString("UTF-8");
    }

    @Override public boolean isConnected() { return connected && server.isAlive(); }
    @Override public void close() { connected = false; server.close(); }
}
