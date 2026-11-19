package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

public interface AdbLink extends Closeable {
    void receive(byte[] bytes, long deadlineNanos) throws IOException;
    void send(byte[] bytes) throws IOException;

    static int remainingMillis(long deadlineNanos) throws IOException {
        if (deadlineNanos == 0) return 0;
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException(text("deviceResponseTimeout"));
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
