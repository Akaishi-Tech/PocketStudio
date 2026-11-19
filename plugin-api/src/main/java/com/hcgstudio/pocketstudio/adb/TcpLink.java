package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

final class TcpLink implements AdbLink {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    TcpLink(String host, int port) throws IOException {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535)
            throw new IOException(text("targetAddressInvalid"));
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host.trim(), port), 7000);
            socket.setTcpNoDelay(true);
            input = socket.getInputStream();
            output = socket.getOutputStream();
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    @Override public void receive(byte[] bytes, long deadlineNanos) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            socket.setSoTimeout(AdbLink.remainingMillis(deadlineNanos));
            int n = input.read(bytes, offset, bytes.length - offset);
            if (n < 0) throw new EOFException(text("targetDisconnected"));
            offset += n;
        }
    }

    @Override public void send(byte[] bytes) throws IOException { output.write(bytes); }
    @Override public void close() throws IOException { socket.close(); }
}
