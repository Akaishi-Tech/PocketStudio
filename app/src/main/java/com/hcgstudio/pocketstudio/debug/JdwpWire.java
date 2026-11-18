package com.hcgstudio.pocketstudio.debug;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Framed request/reply transport. VM events can arrive while a command is pending. */
final class JdwpWire {
    interface Payload {
        void write(DataOutputStream output) throws IOException;
    }

    interface Receiver {
        void event(byte[] packet);
        void disconnected(IOException cause);
    }

    static final class CommandError extends IOException {
        final int code;

        CommandError(int operation, int command, int code) {
            super(text("commandFailed", operation, command, code));
            this.code = code;
        }
    }

    static final Payload EMPTY = out -> {};
    private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PACKET = 32 * 1024 * 1024;
    private static final int HEADER_SIZE = 11;

    private final Closeable transport;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Receiver receiver;
    private final CountDownLatch handshake = new CountDownLatch(1);
    private final Map<Integer, Reply> pending = new HashMap<>();
    private int nextId = 1;
    private IOException failure;

    JdwpWire(InputStream in, OutputStream out, Closeable transport, Receiver receiver) {
        this.in = new DataInputStream(in);
        this.out = new DataOutputStream(out);
        this.transport = transport;
        this.receiver = receiver;
    }

    void start() throws IOException {
        out.write(HANDSHAKE);
        out.flush();
        Thread reader = new Thread(this::readLoop, "JDWP packets");
        reader.setDaemon(true);
        reader.start();
        await(handshake, text("handshake"));
        synchronized (this) {
            if (failure != null) throw failure;
        }
    }

    private void readLoop() {
        try {
            byte[] response = new byte[HANDSHAKE.length];
            in.readFully(response);
            if (!Arrays.equals(response, HANDSHAKE)) throw new IOException(text("jdwpHandshakeInvalid"));
            handshake.countDown();

            while (true) {
                int length = in.readInt();
                if (length < HEADER_SIZE || length > MAX_PACKET) throw new IOException(text("jdwpPacketSizeInvalid"));
                int id = in.readInt();
                int flags = in.readUnsignedByte();
                int commandOrError = in.readUnsignedShort();
                byte[] payload = new byte[length - HEADER_SIZE];
                in.readFully(payload);
                if ((flags & 0x80) != 0) {
                    synchronized (this) {
                        Reply reply = pending.remove(id);
                        if (reply != null) {
                            reply.error = commandOrError;
                            reply.payload = payload;
                            reply.ready.countDown();
                        }
                    }
                } else if (commandOrError == (64 << 8 | 100)) {
                    receiver.event(payload);
                }
                // ART can also send unsolicited DDMS chunks. JDWP events require no reply.
            }
        } catch (EOFException e) {
            terminate(new IOException(text("processDisconnected"), e));
        } catch (IOException e) {
            terminate(e);
        }
    }

    DataInputStream command(int group, int command, Payload payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        payload.write(new DataOutputStream(body));
        Reply reply = new Reply();
        int id;
        synchronized (this) {
            if (failure != null) throw failure;
            id = nextId++;
            pending.put(id, reply);
        }

        try {
            // One underlying write avoids one USB transfer per JDWP header field.
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(HEADER_SIZE + body.size());
            DataOutputStream packet = new DataOutputStream(encoded);
            packet.writeInt(HEADER_SIZE + body.size());
            packet.writeInt(id);
            packet.writeByte(0);
            packet.writeByte(group);
            packet.writeByte(command);
            body.writeTo(packet);
            synchronized (out) {
                out.write(encoded.toByteArray());
                out.flush();
            }
            await(reply.ready, text("command", group, command));
        } catch (IOException e) {
            terminate(e);
            throw e;
        }

        if (reply.payload == null) {
            synchronized (this) {
                throw failure;
            }
        }
        if (reply.error != 0) throw new CommandError(group, command, reply.error);
        return new DataInputStream(new ByteArrayInputStream(reply.payload));
    }

    private static void await(CountDownLatch latch, String operation) throws IOException {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IOException(text("operationTimeout", operation));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(text("operationInterrupted", operation), e);
        }
    }

    void terminate(IOException reason) {
        synchronized (this) {
            if (failure != null) return;
            failure = reason;
            handshake.countDown();
            for (Reply reply : pending.values()) reply.ready.countDown();
            pending.clear();
        }
        try {
            transport.close();
        } catch (IOException ignored) {
            // A removed USB device may already have closed the transport.
        } finally {
            receiver.disconnected(reason);
        }
    }

    private static final class Reply {
        final CountDownLatch ready = new CountDownLatch(1);
        byte[] payload;
        int error;
    }
}
