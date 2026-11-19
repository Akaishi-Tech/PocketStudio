package com.hcgstudio.pocketstudio.testing;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.List;
import com.hcgstudio.pocketstudio.AndroidEnvironments;
import com.hcgstudio.pocketstudio.adb.Target;

/** Connects the production USB transport to the explicitly named emulator-only fixture. */
final class UsbGadgetTarget {
    static Target connect(Context context) throws IOException {
        UsbManager manager = context.getSystemService(UsbManager.class);
        List<UsbDevice> devices = AndroidEnvironments.require(context).usbDevices();
        if (devices.size() != 1) throw new IOException("Expected one emulated USB ADB interface");
        UsbDevice device = devices.get(0);
        if (device.getVendorId() != 0x18d1 || device.getProductId() != 0x4ee7)
            throw new IOException("Unexpected emulated USB device");
        if (!manager.hasPermission(device)) {
            Intent result = new Intent("studio.test.USB_PERMISSION").setPackage(context.getPackageName());
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 2, result,
                    PendingIntent.FLAG_IMMUTABLE));
            long deadline = SystemClock.uptimeMillis() + 60000;
            while (!manager.hasPermission(device) && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(100);
        }
        if (!manager.hasPermission(device)) throw new IOException("System USB permission was not granted");
        Target adb = AndroidEnvironments.require(context).connectUsb(device);
        try {
            if (!"studio_usb_target".equals(adb.shell("getprop ro.boot.qemu.avd_name").trim()))
                throw new IOException("USB bridge did not reach the expected AVD");
            return adb;
        } catch (IOException e) {
            adb.close();
            throw e;
        }
    }

    private UsbGadgetTarget() {}
}
