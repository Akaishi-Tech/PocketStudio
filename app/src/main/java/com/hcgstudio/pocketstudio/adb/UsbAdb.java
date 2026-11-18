package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** An authenticated connection to the target's adbd. Call blocking methods off the UI thread. */
public final class UsbAdb implements Target {
    static final int OPEN = 0x4e45504f, OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    // Also fits Android's pre-P single bulk-transfer size limit.
    private static final int MAX_PAYLOAD = 16384;
    static final byte[] EMPTY = new byte[0];
    private final AdbLink link;
    private final Object sendLock = new Object();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private int payloadLimit = MAX_PAYLOAD;
    private int version = VERSION;
    private String banner;

    private UsbAdb(AdbLink link) { this.link = link; }

    public static List<UsbDevice> devices(UsbManager manager) {
        List<UsbDevice> devices = new ArrayList<>();
        if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values())
                if (AndroidUsbBulk.adbInterface(device) != null) devices.add(device);
        }
        return devices;
    }

    public static UsbAdb connect(Context context, UsbDevice device) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null || device == null) throw new IOException(text("selectUsbDevice"));
        return start(new UsbLink(new AndroidUsbBulk(manager, device)));
    }

    /** Connect to one explicitly specified legacy ADB TCP endpoint (including an emulator). */
    public static UsbAdb connectTcp(Context context, String host, int port) throws IOException {
        return start(new TcpLink(host, port));
    }

    static UsbAdb start(AdbLink link) throws IOException {
        UsbAdb adb = new UsbAdb(link);
        try {
            adb.handshake();
            Thread reader = new Thread(adb::readLoop, "ADB packet reader");
            reader.setDaemon(true);
            reader.start();
            return adb;
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    private void handshake() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        send(CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
        AdbIdentity identity = null;
        while (true) {
            Packet packet = receive(deadline);
            if (packet.command == CNXN) {
                if (packet.first < 0x01000000 || packet.second < 1)
                    throw new IOException(text("adbVersionOrSizeInvalid"));
                version = Math.min(VERSION, packet.first);
                payloadLimit = Math.min(MAX_PAYLOAD, packet.second);
                banner = new String(packet.data, StandardCharsets.UTF_8);
                return;
            }
            if (packet.command == STLS)
                throw new IOException(text("wirelessPairingRequired"));
            if (packet.command != AUTH || packet.first != 1 || packet.data.length != 20)
                throw new IOException(text("adbHandshakeInvalid"));
            if (identity == null) {
                identity = AdbIdentity.load();
                send(AUTH, 2, 0, identity.sign(packet.data));
            } else {
                send(AUTH, 3, 0, identity.publicKeyMessage());
            }
        }
    }

    public AdbStream open(String service) throws IOException {
        if (service == null || service.isEmpty() || service.indexOf(0) >= 0)
            throw new IOException(text("serviceNameInvalid"));
        byte[] address = (service + '\0').getBytes(StandardCharsets.UTF_8);
        if (address.length > payloadLimit) throw new IOException(text("serviceNameTooLong"));
        int id = nextId.getAndIncrement();
        AdbStream stream = new AdbStream(this, id, payloadLimit);
        streams.put(id, stream);
        try {
            send(OPEN, id, 0, address);
            stream.awaitOpen();
            return stream;
        } catch (IOException e) {
            stream.close();
            throw e;
        }
    }

    public String shell(String command) throws IOException {
        try (AdbStream stream = open("shell:" + command)) {
            return readText(stream.input(), 4 * 1024 * 1024);
        }
    }

    /** Install or update a single APK using Package Manager's streamed installation. */
    public void install(InputStream apk, long size) throws IOException {
        if (size <= 0) throw new IOException(text("apkSizeUnknown"));
        // Android 7 and later advertise the cmd feature. Older targets expose the pm front end.
        String manager = banner.matches("(?s).*features=([^;\\x00]*,)?cmd([,;\\x00].*|$)")
                ? "cmd package" : "pm";
        try (AdbStream stream = open("exec:" + manager + " install -r -S " + size)) {
            byte[] buffer = new byte[payloadLimit];
            long remaining = size;
            while (remaining > 0) {
                int n = apk.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (n < 0) throw new EOFException(text("apkUnexpectedEnd"));
                stream.output().write(buffer, 0, n);
                remaining -= n;
            }
            String result = readText(stream.input(), 65536).trim();
            if (!result.equals("Success"))
                throw new IOException(result.isEmpty() ? text("installationUnconfirmed") : result);
        }
    }

    private static String readText(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (result.size() + count > limit) throw new IOException(text("deviceOutputTooLong"));
            result.write(buffer, 0, count);
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    private void readLoop() {
        try {
            while (failure.get() == null) {
                Packet packet = receive(0);
                if (packet.command == OPEN) {
                    send(CLSE, 0, packet.first, EMPTY);
                    continue;
                }
                if (packet.command != OKAY && packet.command != WRTE && packet.command != CLSE)
                    throw new IOException(text("unexpectedPacket"));
                AdbStream stream = streams.get(packet.second);
                if (stream == null) {
                    // A canceled OPEN may receive its remote id after the caller has timed out.
                    if (packet.command == OKAY && packet.first != 0)
                        send(CLSE, packet.second, packet.first, EMPTY);
                    continue;
                }
                if (packet.command == OKAY) stream.acknowledge(packet.first);
                if (packet.command == WRTE) stream.receive(packet.first, packet.data);
                if (packet.command == CLSE) {
                    streams.remove(packet.second, stream);
                    stream.remoteClosed();
                }
            }
        } catch (IOException | RuntimeException e) {
            fail(e instanceof IOException ? (IOException) e : new IOException(text("adbReadFailed"), e));
        }
    }

    void forget(int id, AdbStream stream) { streams.remove(id, stream); }

    void send(int command, int first, int second, byte[] data) throws IOException {
        try {
            synchronized (sendLock) {
                IOException disconnected = failure.get();
                if (disconnected != null) throw new IOException(disconnected.getMessage(), disconnected);
                ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                header.putInt(command).putInt(first).putInt(second).putInt(data.length)
                        .putInt(checksum(data)).putInt(~command);
                link.send(header.array());
                if (data.length != 0) link.send(data);
            }
        } catch (IOException e) {
            fail(e);
            throw e;
        }
    }

    private Packet receive(long deadline) throws IOException {
        byte[] bytes = new byte[24];
        link.receive(bytes, deadline);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(), first = header.getInt(), second = header.getInt();
        int size = header.getInt(), sum = header.getInt(), magic = header.getInt();
        if (magic != ~command || size < 0 || size > MAX_PAYLOAD)
            throw new IOException(text("adbHeaderInvalid"));
        byte[] data = size == 0 ? EMPTY : new byte[size];
        long payloadDeadline = deadline == 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(15) : deadline;
        if (size > 0) link.receive(data, payloadDeadline);
        if ((sum != 0 || version < VERSION || (command == CNXN && first < VERSION)) && sum != checksum(data))
            throw new IOException(text("adbChecksumMismatch"));
        return new Packet(command, first, second, data);
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte value : data) sum += value & 0xff;
        return sum;
    }

    private static final class Packet {
        final int command, first, second;
        final byte[] data;
        Packet(int command, int first, int second, byte[] data) {
            this.command = command;
            this.first = first;
            this.second = second;
            this.data = data;
        }
    }

    private void fail(IOException error) {
        if (!failure.compareAndSet(null, error)) return;
        for (AdbStream stream : streams.values()) stream.failed(error);
        streams.clear();
        try { link.close(); } catch (IOException ignored) { }
    }

    public boolean isConnected() { return failure.get() == null; }
    @Override public void close() { fail(new IOException(text("adbClosed"))); }
}
