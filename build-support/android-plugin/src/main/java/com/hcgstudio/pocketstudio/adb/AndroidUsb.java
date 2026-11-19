package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class AndroidUsb {
    private AndroidUsb() {}

    public static List<UsbDevice> devices(UsbManager manager) {
        List<UsbDevice> devices = new ArrayList<>();
        if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values())
                if (AndroidUsbBulk.adbInterface(device) != null) devices.add(device);
        }
        return devices;
    }

    public static AdbLink open(Context context, UsbDevice device) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null || device == null) throw new IOException(text("selectUsbDevice"));
        return new UsbLink(new AndroidUsbBulk(manager, device));
    }
}

interface UsbBulk extends Closeable {
    int receive(byte[] bytes, int offset, int length, int timeout);
    int send(byte[] bytes, int timeout);
    int packetSize();
    @Override void close();
}

final class AndroidUsbBulk implements UsbBulk {
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint input, output;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;

    static UsbInterface adbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                    && intf.getInterfaceSubclass() == 0x42 && intf.getInterfaceProtocol() == 1
                    && bulkEndpoint(intf, UsbConstants.USB_DIR_IN) != null
                    && bulkEndpoint(intf, UsbConstants.USB_DIR_OUT) != null) return intf;
        }
        return null;
    }

    private static UsbEndpoint bulkEndpoint(UsbInterface intf, int direction) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint candidate = intf.getEndpoint(i);
            if (candidate.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && candidate.getDirection() == direction) return candidate;
        }
        return null;
    }

    AndroidUsbBulk(UsbManager manager, UsbDevice device) throws IOException {
        usbInterface = adbInterface(device);
        if (usbInterface == null) throw new IOException(text("noAdbInterface"));
        if (!manager.hasPermission(device)) throw new IOException(text("usbPermissionRequired"));
        input = bulkEndpoint(usbInterface, UsbConstants.USB_DIR_IN);
        output = bulkEndpoint(usbInterface, UsbConstants.USB_DIR_OUT);
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException(text("usbOpenFailed"));
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            throw new IOException(text("usbInterfaceBusy"));
        }
    }

    @Override public int receive(byte[] bytes, int offset, int length, int timeout) {
        lifecycle.readLock().lock();
        try { return closed ? -1 : connection.bulkTransfer(input, bytes, offset, length, timeout); }
        finally { lifecycle.readLock().unlock(); }
    }

    @Override public int send(byte[] bytes, int timeout) {
        lifecycle.readLock().lock();
        try { return closed ? -1 : connection.bulkTransfer(output, bytes, bytes.length, timeout); }
        finally { lifecycle.readLock().unlock(); }
    }

    @Override public int packetSize() { return output.getMaxPacketSize(); }

    @Override public void close() {
        // Keep the native handle alive while either direction is inside a bulk transfer.
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            connection.releaseInterface(usbInterface);
            connection.close();
        } finally { lifecycle.writeLock().unlock(); }
    }
}

final class UsbLink implements AdbLink {
    private final UsbBulk bulk;
    private volatile boolean closed;

    UsbLink(UsbBulk bulk) { this.bulk = bulk; }

    @Override public void receive(byte[] bytes, long deadlineNanos) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int remaining = AdbLink.remainingMillis(deadlineNanos);
            int timeout = remaining == 0 ? 2000 : Math.min(remaining, 2000);
            long start = System.nanoTime();
            if (closed) throw new EOFException(text("usbDisconnected"));
            int n = bulk.receive(bytes, offset, bytes.length - offset, timeout);
            if (n > 0) {
                offset += n;
            } else if (n < 0 && (closed || System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(timeout - 10))) {
                // Android returns a negative result for both ETIMEDOUT and device removal.
                // Idle reads periodically return so that closing the connection interrupts them.
                throw new EOFException(text("usbReadFailed"));
            }
        }
    }

    @Override public void send(byte[] bytes) throws IOException {
        if (closed || bulk.send(bytes, 5000) != bytes.length)
            throw new IOException(text("usbWriteFailed"));
        // FunctionFS reads at fixed size. A transfer ending at a packet boundary needs a ZLP.
        if (bytes.length % bulk.packetSize() == 0 && bulk.send(new byte[0], 5000) != 0)
            throw new IOException(text("usbPacketEnd"));
    }

    @Override public void close() {
        closed = true;
        bulk.close();
    }
}
