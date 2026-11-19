package com.hcgstudio.pocketstudio.protocol;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;

/** Messages shared by the Android app and the standalone JVM protocol tools. */
public final class ProtocolMessages extends ListResourceBundle {
    public static String text(String key, Object... args) {
        Locale locale = Locale.getDefault();
        String pattern = ResourceBundle.getBundle(ProtocolMessages.class.getName(), locale).getString(key);
        return args.length == 0 ? pattern : String.format(locale, pattern, args);
    }

    @Override protected Object[][] getContents() {
        return new Object[][] {
            {"usbKeyRead", "Could not read the USB debugging key"},
            {"adbChallengeInvalid", "The target device returned an invalid ADB challenge"},
            {"usbKeySign", "Could not sign the challenge with the USB debugging key"},
            {"deviceResponseTimeout", "The device timed out. Check the debugging authorization prompt on the target phone."},
            {"noAdbInterface", "The target device has no ADB interface. Enable USB debugging."},
            {"usbPermissionRequired", "Allow access to this USB device first"},
            {"usbOpenFailed", "Could not open the USB device"},
            {"usbInterfaceBusy", "The USB debugging interface is in use"},
            {"usbDisconnected", "USB disconnected"},
            {"usbReadFailed", "Could not read from the USB device. Check the connection."},
            {"usbWriteFailed", "Could not write to the USB device"},
            {"usbPacketEnd", "Could not terminate the USB packet"},
            {"targetAddressInvalid", "Enter the target device's address and a valid port"},
            {"targetDisconnected", "The target device disconnected"},
            {"streamIdInvalid", "Invalid ADB stream ID"},
            {"streamIdMismatch", "Invalid ADB stream ID (local %1$s, target %2$s, sender %3$s)"},
            {"serviceBufferFull", "Device debugging service output exceeded the buffer capacity"},
            {"serviceOpenRefused", "The device refused to open this debugging service"},
            {"serviceClosed", "The device closed this debugging service"},
            {"serviceResponseTimeout", "The device debugging service timed out"},
            {"deviceWaitInterrupted", "Interrupted while waiting for a response from the device"},
            {"selectUsbDevice", "Select a device that supports USB debugging"},
            {"adbVersionOrSizeInvalid", "The target device reported an invalid ADB protocol version or packet size"},
            {"wirelessPairingRequired", "This port requires wireless pairing. Select USB or an enabled ADB TCP port."},
            {"adbHandshakeInvalid", "The target port returned an invalid ADB handshake"},
            {"serviceNameInvalid", "Invalid ADB service name"},
            {"serviceNameTooLong", "ADB service name too long"},
            {"apkSizeUnknown", "Could not determine the APK size"},
            {"apkUnexpectedEnd", "Unexpected end of APK file"},
            {"installationUnconfirmed", "The target phone did not confirm installation"},
            {"deviceOutputTooLong", "Device output is too long"},
            {"unexpectedPacket", "Unexpected ADB packet"},
            {"adbReadFailed", "Could not read ADB data"},
            {"adbHeaderInvalid", "Invalid ADB packet header. Check that this is the target device's ADB port."},
            {"adbChecksumMismatch", "ADB data checksum mismatch"},
            {"adbClosed", "ADB connection closed"},
            {"jdwpIdSizeUnsupported", "The target process uses an unsupported JDWP ID size"},
            {"classAndLineRequired", "Enter a fully qualified class name and a line number greater than zero"},
            {"classNotLoaded", "The class has not loaded. Open the corresponding screen in the target app or check the fully qualified class name."},
            {"noCodeAtLine", "This line has no executable code, or the target APK lacks line number information."},
            {"noVariableTable", "This stack frame has no local variable debug information. Use a debug APK built with local variable tables."},
            {"variableCountMismatch", "The target returned an unexpected number of local variables"},
            {"valueTypeUnsupported", "Unsupported JDWP value type %1$s"},
            {"targetExited", "The target process exited"},
            {"unknownEvent", "Unknown JDWP event %1$s"},
            {"targetWaiting", "The target process is waiting for a debugger"},
            {"breakpointHit", "Breakpoint hit"},
            {"stepComplete", "Step complete"},
            {"debuggerDetached", "Debugger detached"},
            {"entryCountInvalid", "The target process returned an invalid JDWP entry count"},
            {"jdwpStringInvalid", "The target process returned an invalid JDWP string"},
            {"commandFailed", "JDWP %1$s/%2$s returned error %3$s"},
            {"handshake", "handshake"},
            {"jdwpHandshakeInvalid", "Invalid JDWP handshake from the target process"},
            {"jdwpPacketSizeInvalid", "Invalid JDWP packet length"},
            {"processDisconnected", "The target process disconnected"},
            {"command", "command %1$s/%2$s"},
            {"operationTimeout", "Timed out waiting for JDWP %1$s"},
            {"operationInterrupted", "JDWP %1$s was interrupted"},
        };
    }
}
