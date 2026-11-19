package com.hcgstudio.pocketstudio.androidplugin;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;
import java.io.File;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs APKs on this device using a non-exportable key scoped to each purpose. */
public final class DeviceApkSigner {
    private DeviceApkSigner() {}

    public static byte[] certificate(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.cert.Certificate cert = store.getCertificate(alias);
        return cert == null ? null : cert.getEncoded();
    }

    public static synchronized void sign(String alias, File from, File to, int minSdk) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build());
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) store.getKey(alias, null);
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("StudioDebug", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(from).setOutputApk(to).setMinSdkVersion(minSdk)
                .setAlignmentPreserved(false).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
    }
}
