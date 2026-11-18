package com.hcgstudio.pocketstudio.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Exercises the production USB packet and stream code against an in-memory bulk endpoint. */
public final class TransportCheck {
    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int HOLD = 201, SHELL = 202, SINK = 203;

    public static void main(String[] args) throws Exception {
        boolean testKeystore = args.length == 1 && args[0].equals("android");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        checkSignature(new AdbIdentity(pair.getPrivate(), (RSAPublicKey) pair.getPublic()));
        if (testKeystore) {
            AdbIdentity identity = AdbIdentity.load();
            checkSignature(identity);
            check(Arrays.equals(identity.publicKeyMessage(), AdbIdentity.load().publicKeyMessage()),
                    "Keystore did not retain the same key");
        }

        Target target = new Target(testKeystore);
        try (UsbAdb adb = UsbAdb.start(new UsbLink(target));
             AdbStream hold = adb.open("hold");
             AdbStream sink = adb.open("sink")) {
            check(adb.shell("echo ok").equals("ok\n"), "another stream blocked shell");
            check(hold.input().read() == 'h', "held stream lost data");

            byte[] out = new byte[16384 + 513];
            new SecureRandom().nextBytes(out);
            sink.output().write(out);
            sink.output().write(out, 0, 512);
            byte[] expected = Arrays.copyOf(out, out.length + 512);
            System.arraycopy(out, 0, expected, out.length, 512);
            check(Arrays.equals(expected, target.upload.toByteArray()), "fragmented write changed data");
            check(target.transfers.containsAll(Arrays.asList(24, 16384, 513, 512)), "bad ADB framing");
            check(target.zeroLengthPackets == 2, "bad ZLP count " + target.zeroLengthPackets);

            try {
                adb.open("rejected");
                throw new AssertionError("missing OPEN failure");
            } catch (IOException expectedException) { /* target refused */ }
            target.disconnect();
            try {
                sink.input().read();
                throw new AssertionError("missing disconnect failure");
            } catch (IOException expectedException) { /* reader woke on unplug */ }
            check(!adb.isConnected(), "transport stayed online after unplug");
        }
        System.out.println("PASS: USB framing, ZLP, bidirectional streams, rejection, unplug, RSA"
                + (testKeystore ? ", AndroidKeyStore, AUTH negotiation" : ""));
    }

    private static void checkSignature(AdbIdentity identity) throws Exception {
        byte[] challenge = new byte[20];
        new SecureRandom().nextBytes(challenge);
        verify(challenge, identity.sign(challenge), identity.publicKeyMessage());
    }

    private static void verify(byte[] challenge, byte[] signature, byte[] publicKey) throws Exception {
        String text = new String(publicKey, StandardCharsets.US_ASCII);
        check(text.charAt(text.length() - 1) == 0, "public key lacks terminator");
        byte[] binary = Base64.getDecoder().decode(text.substring(0, text.indexOf(' ')));
        ByteBuffer key = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        check(key.getInt() == 64 && binary.length == 524, "ADB key size");
        long n0inv = Integer.toUnsignedLong(key.getInt());
        BigInteger n = takeInteger(key), rr = takeInteger(key);
        BigInteger e = BigInteger.valueOf(key.getInt());
        BigInteger radix = BigInteger.ONE.shiftLeft(32);
        check(BigInteger.valueOf(n0inv).multiply(n).add(BigInteger.ONE).mod(radix).signum() == 0, "n0inv");
        check(rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n)), "R squared");
        byte[] signed = new BigInteger(1, signature).modPow(e, n).toByteArray();
        check(signed.length == 255 && signed[0] == 1, "RSA signature size/prefix");
        for (int i = 1; i < 219; i++) check((signed[i] & 255) == 255, "RSA padding");
        check(signed[219] == 0, "RSA separator");
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0, 0x04, 0x14};
        check(Arrays.equals(prefix, Arrays.copyOfRange(signed, 220, 235)), "DigestInfo");
        check(Arrays.equals(challenge, Arrays.copyOfRange(signed, 235, 255)), "token signed twice");
    }

    private static BigInteger takeInteger(ByteBuffer key) {
        byte[] bytes = new byte[256];
        for (int i = bytes.length - 1; i >= 0; i--) bytes[i] = key.get();
        return new BigInteger(1, bytes);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class Target implements UsbBulk {
        final ByteArrayOutputStream upload = new ByteArrayOutputStream();
        final List<Integer> transfers = new ArrayList<>();
        final ArrayDeque<Byte> inbound = new ArrayDeque<>();
        final boolean auth;
        final byte[] challenge = new byte[20];
        byte[] signature;
        int cmd, first, second, expectedBytes, expectedSum, zeroLengthPackets;
        boolean wantsZlp, detached;

        Target(boolean auth) {
            this.auth = auth;
            new SecureRandom().nextBytes(challenge);
        }

        @Override public int packetSize() { return 512; }

        @Override public synchronized int send(byte[] bytes, int timeout) {
            transfers.add(bytes.length);
            if (wantsZlp) {
                check(bytes.length == 0, "missing ZLP");
                wantsZlp = false;
                zeroLengthPackets++;
                return 0;
            }
            check(bytes.length > 0, "unexpected ZLP");
            wantsZlp = bytes.length % packetSize() == 0;
            if (expectedBytes == 0) {
                check(bytes.length == 24, "header must use separate bulk transfer");
                ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                cmd = header.getInt();
                first = header.getInt();
                second = header.getInt();
                expectedBytes = header.getInt();
                expectedSum = header.getInt();
                check(header.getInt() == ~cmd, "magic");
                if (expectedBytes == 0) dispatch(UsbAdb.EMPTY);
            } else {
                check(bytes.length == expectedBytes, "incorrect packet length");
                int sum = 0;
                for (byte b : bytes) sum += b & 255;
                check(sum == expectedSum, "checksum");
                expectedBytes = 0;
                dispatch(bytes);
            }
            return bytes.length;
        }

        private void dispatch(byte[] bytes) {
            if (cmd == CNXN) {
                if (auth) enqueue(AUTH, 1, 0, challenge);
                else connected();
            } else if (cmd == AUTH && first == 2) {
                signature = bytes;
                // The first target connection does not yet recognize this host.
                enqueue(AUTH, 1, 0, challenge);
            } else if (cmd == AUTH && first == 3) {
                try { verify(challenge, signature, bytes); }
                catch (Exception ex) { throw new AssertionError(ex); }
                connected();
            } else if (cmd == UsbAdb.OPEN) {
                String name = new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                if (name.equals("rejected")) {
                    enqueue(UsbAdb.CLSE, 0, first, UsbAdb.EMPTY);
                } else {
                    int peer = name.equals("hold") ? HOLD : name.equals("sink") ? SINK : SHELL;
                    enqueue(UsbAdb.OKAY, peer, first, UsbAdb.EMPTY);
                    if (peer == HOLD) enqueue(UsbAdb.WRTE, peer, first, new byte[]{'h', 'e', 'l', 'd'});
                    if (peer == SHELL) {
                        enqueue(UsbAdb.WRTE, peer, first, new byte[]{'o'});
                        enqueue(UsbAdb.WRTE, peer, first, new byte[]{'k', '\n'});
                        enqueue(UsbAdb.CLSE, peer, first, UsbAdb.EMPTY);
                    }
                }
            } else if (cmd == UsbAdb.WRTE && second == SINK) {
                upload.write(bytes, 0, bytes.length);
                enqueue(UsbAdb.OKAY, SINK, first, UsbAdb.EMPTY);
            }
        }

        private void connected() {
            enqueue(CNXN, 0x1000001, 16384, "device::".getBytes(StandardCharsets.US_ASCII));
        }

        private void enqueue(int cmd, int arg0, int arg1, byte[] bytes) {
            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(cmd).putInt(arg0).putInt(arg1).putInt(bytes.length).putInt(0).putInt(~cmd);
            for (byte b : header.array()) inbound.add(b);
            for (byte b : bytes) inbound.add(b);
            notifyAll();
        }

        @Override public synchronized int receive(byte[] bytes, int offset, int length, int timeout) {
            if (inbound.isEmpty() && !detached) {
                try { wait(timeout); } catch (InterruptedException ex) { throw new AssertionError(ex); }
            }
            if (detached || inbound.isEmpty()) return -1;
            int count = Math.min(7, Math.min(inbound.size(), length));
            for (int i = 0; i < count; i++) bytes[offset + i] = inbound.remove();
            return count;
        }

        synchronized void disconnect() { detached = true; notifyAll(); }
        @Override public void close() { disconnect(); }
    }
}
