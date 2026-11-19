package com.hcgstudio.pocketstudio.adb;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

final class AdbIdentity {
    private static final String ALIAS = "com.hcgstudio.pocketstudio.usb.adb";
    // ADB supplies the 20-byte digest itself. PKCS#1 v1.5 signs a SHA-1 DigestInfo.
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    AdbIdentity(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    static synchronized AdbIdentity load() throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build());
                generator.generateKeyPair();
            }
            return new AdbIdentity((PrivateKey) store.getKey(ALIAS, null),
                    (RSAPublicKey) store.getCertificate(ALIAS).getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new IOException(text("usbKeyRead"), e);
        }
    }

    byte[] sign(byte[] token) throws IOException {
        if (token.length != 20) throw new IOException(text("adbChallengeInvalid"));
        try {
            Signature signature = Signature.getInstance("NONEwithRSA");
            signature.initSign(privateKey);
            signature.update(SHA1_DIGEST_INFO);
            signature.update(token);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException(text("usbKeySign"), e);
        }
    }

    byte[] publicKeyMessage() {
        // Android encodes a 2048-bit RSA public key as an RSAPublicKey struct
        // containing two fixed-width little-endian Montgomery integers.
        BigInteger modulus = publicKey.getModulus();
        BigInteger word = BigInteger.ONE.shiftLeft(32);
        ByteBuffer key = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        key.putInt(64).putInt(modulus.modInverse(word).negate().intValue());
        putLittleEndian256(key, modulus);
        putLittleEndian256(key, BigInteger.ONE.shiftLeft(4096).mod(modulus));
        key.putInt(publicKey.getPublicExponent().intValue());
        String body = Base64.getEncoder().encodeToString(key.array()) + " studio@android\0";
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    private static void putLittleEndian256(ByteBuffer out, BigInteger value) {
        byte[] bytes = value.toByteArray();
        for (int i = 0; i < 256; i++) {
            int index = bytes.length - 1 - i;
            out.put(index >= 0 ? bytes[index] : (byte) 0);
        }
    }
}
