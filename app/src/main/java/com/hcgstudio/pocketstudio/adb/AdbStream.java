package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** An ADB service stream with independently flow-controlled input and output. */
public final class AdbStream implements Target.Stream {
    private final UsbAdb owner;
    private final int id, limit;
    private int peer;
    private boolean ready, ended, closed;
    private IOException error;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private int buffered;
    private int offset;

    AdbStream(UsbAdb owner, int id, int limit) {
        this.owner = owner;
        this.id = id;
        this.limit = limit;
    }

    synchronized void awaitOpen() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (peer == 0) {
            requireOpen();
            await(deadline);
        }
    }

    synchronized void acknowledge(int sender) throws IOException {
        if (closed) return;
        if (sender == 0 || (peer != 0 && peer != sender)) throw new IOException(text("streamIdInvalid"));
        peer = sender;
        ready = true;
        notifyAll();
    }

    void receive(int sender, byte[] bytes) throws IOException {
        boolean overflow = false;
        synchronized (this) {
            if (closed) return;
            if (peer == 0 || peer != sender)
                throw new IOException(text("streamIdMismatch", id, peer, sender));
            if (bytes.length > 0) {
                // Some adbd versions emit the final WRTE(s) while closing a service,
                // before receiving our READY for the preceding packet.
                if (buffered + bytes.length <= 1024 * 1024 && pending.size() < 256) {
                    pending.addLast(bytes);
                    buffered += bytes.length;
                    notifyAll();
                    return;
                }
                error = new IOException(text("serviceBufferFull"));
                pending.clear();
                buffered = 0;
                overflow = true;
                notifyAll();
            }
        }
        if (overflow) owner.forget(id, this);
        owner.send(overflow ? UsbAdb.CLSE : UsbAdb.OKAY, id, sender, UsbAdb.EMPTY);
    }

    synchronized void remoteClosed() { ended = true; notifyAll(); }

    synchronized void failed(IOException cause) {
        error = cause;
        notifyAll();
    }

    private void requireOpen() throws IOException {
        if (error != null) throw error;
        if (closed || ended) throw new IOException(peer == 0 ? text("serviceOpenRefused") : text("serviceClosed"));
    }

    private void await(long deadline) throws IOException {
        try {
            if (deadline == 0) {
                wait();
            } else {
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) throw new SocketTimeoutException(text("serviceResponseTimeout"));
                TimeUnit.NANOSECONDS.timedWait(this, nanos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(text("deviceWaitInterrupted"));
        }
    }

    private static void checkBounds(byte[] bytes, int start, int length) {
        if (start < 0 || length < 0 || start > bytes.length - length)
            throw new IndexOutOfBoundsException();
    }

    private final InputStream input = new InputStream() {
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] dest, int start, int length) throws IOException {
            checkBounds(dest, start, length);
            if (length == 0) return 0;
            int count, ack = 0;
            synchronized (AdbStream.this) {
                while (pending.isEmpty()) {
                    if (error != null) throw error;
                    if (ended) return -1;
                    requireOpen();
                    await(0);
                }
                byte[] head = pending.getFirst();
                count = Math.min(length, head.length - offset);
                System.arraycopy(head, offset, dest, start, count);
                offset += count;
                buffered -= count;
                if (offset == head.length) {
                    pending.removeFirst();
                    offset = 0;
                    if (!ended && !closed && error == null) ack = peer;
                }
            }
            // Acknowledge each drained packet without blocking unrelated streams.
            if (ack != 0) owner.send(UsbAdb.OKAY, id, ack, UsbAdb.EMPTY);
            return count;
        }

        @Override public void close() throws IOException { AdbStream.this.close(); }
    };

    private final OutputStream output = new OutputStream() {
        @Override public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override public synchronized void write(byte[] src, int start, int length) throws IOException {
            checkBounds(src, start, length);
            while (length > 0) {
                int remote;
                synchronized (AdbStream.this) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (!ready) {
                        requireOpen();
                        await(deadline);
                    }
                    requireOpen();
                    ready = false;
                    remote = peer;
                }
                int count = Math.min(length, limit);
                byte[] data = start == 0 && count == src.length ? src : Arrays.copyOfRange(src, start, start + count);
                owner.send(UsbAdb.WRTE, id, remote, data);
                length -= count;
                start += count;
            }
        }

        @Override public void close() throws IOException { AdbStream.this.close(); }
    };

    public InputStream input() { return input; }
    public OutputStream output() { return output; }

    @Override public void close() throws IOException {
        int remote;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
            buffered = 0;
            remote = ended || error != null ? 0 : peer;
            notifyAll();
        }
        owner.forget(id, this);
        if (remote != 0 && owner.isConnected()) owner.send(UsbAdb.CLSE, id, remote, UsbAdb.EMPTY);
    }
}
