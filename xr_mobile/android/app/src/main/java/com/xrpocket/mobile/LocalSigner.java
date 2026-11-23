package com.xrpocket.mobile;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

/** A per-installation key: Android keeps the private key in its Keystore. */
final class LocalSigner {
    private static final String ALIAS = "bigxr-local-apk-v1";

    private static synchronized KeyStore.PrivateKeyEntry identity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=BigXR Local"))
                    .setCertificateSerialNumber(new BigInteger(120, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(1577836800000L))
                    .setCertificateNotAfter(new Date(4102444800000L))
                    .build());
            generator.generateKeyPair();
        }
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) throw new GeneralSecurityException("Chave local indisponível");
        return (KeyStore.PrivateKeyEntry) entry;
    }

    static void verifyOriginal(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(29).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
            throw new GeneralSecurityException("A assinatura do APK original não passou na verificação");
        }
    }

    static void sign(File unsigned, File output) throws Exception {
        if (output.exists() || unsigned.getCanonicalFile().equals(output.getCanonicalFile())) {
            throw new IOException("O destino da assinatura precisa ser um arquivo novo");
        }
        KeyStore.PrivateKeyEntry key = identity();
        X509Certificate certificate = (X509Certificate) key.getCertificate();
        boolean done = false;
        try {
            ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("bigxr", key.getPrivateKey(),
                    Collections.singletonList(certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(config))
                    .setInputApk(unsigned).setOutputApk(output).setMinSdkVersion(29)
                    .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
                    .setV4SigningEnabled(false).build().sign();
            ApkVerifier.Result result = new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(29).build().verify();
            if (!result.isVerified() || !(result.isVerifiedUsingV2Scheme() || result.isVerifiedUsingV3Scheme())
                    || result.getSignerCertificates().size() != 1
                    || !Arrays.equals(certificate.getEncoded(), result.getSignerCertificates().get(0).getEncoded())) {
                throw new GeneralSecurityException("A assinatura da cópia criada não passou na verificação");
            }
            done = true;
        } finally {
            if (!done) output.delete();
        }
    }
    private LocalSigner() { }
}
