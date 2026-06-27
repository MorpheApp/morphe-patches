/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.music.patches.listenbrainz;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import app.morphe.extension.shared.Logger;

public class ListenBrainzTokenStore {
    private static final String PREFS_NAME = "listenbrainz_token";
    private static final String TOKEN_KEY = "user_token";

    private static SharedPreferences prefs;

    public static synchronized void init(Context context) {
        if (prefs != null) return;
        try {
            AesKeystore.getOrCreateKey();
            prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            Logger.printException(() -> "ListenBrainzTokenStore init failed", e);
        }
    }

    private static SharedPreferences getPrefs() {
        if (prefs == null) {
            Context context = app.morphe.extension.shared.Utils.getContext();
            if (context != null) {
                init(context);
            }
        }
        return prefs;
    }

    private static String encrypt(String value) {
        if (value == null) return null;
        try {
            return AesKeystore.encrypt(value);
        } catch (Exception e) {
            Logger.printException(() -> "ListenBrainzTokenStore: encrypt failed", e);
            return null;
        }
    }

    private static String decrypt(String value) {
        if (value == null) return null;
        try {
            return AesKeystore.decrypt(value);
        } catch (Exception e) {
            Logger.printException(() -> "ListenBrainzTokenStore: decrypt failed", e);
            return null;
        }
    }

    public static boolean store(String token) {
        SharedPreferences p = getPrefs();
        if (p == null) {
            Logger.printInfo(() -> "ListenBrainzTokenStore: cannot store, prefs not initialized");
            return false;
        }
        String encryptedToken = encrypt(token);
        if (encryptedToken == null) {
            Logger.printInfo(() -> "ListenBrainzTokenStore: encryption failed, not persisting token");
            return false;
        }
        try {
            return p.edit().putString(TOKEN_KEY, encryptedToken).commit();
        } catch (Exception e) {
            Logger.printException(() -> "ListenBrainzTokenStore: failed to store token", e);
            return false;
        }
    }

    public static String retrieve() {
        SharedPreferences p = getPrefs();
        if (p == null) return null;
        String encrypted = p.getString(TOKEN_KEY, null);
        return decrypt(encrypted);
    }

    public static boolean clear() {
        SharedPreferences p = getPrefs();
        if (p == null) {
            Logger.printInfo(() -> "ListenBrainzTokenStore: cannot clear, prefs not initialized");
            return false;
        }
        try {
            return p.edit().remove(TOKEN_KEY).commit();
        } catch (Exception e) {
            Logger.printException(() -> "ListenBrainzTokenStore: failed to clear token", e);
            return false;
        }
    }

    private static class AesKeystore {
        private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
        private static final String KEY_ALIAS = "morphe_listenbrainz_token_key";
        private static final String TRANSFORMATION = "AES/GCM/NoPadding";
        private static final int GCM_IV_SIZE = 12;
        private static final int GCM_TAG_SIZE = 128;

        private static KeyStore keyStore;

        private static synchronized KeyStore getKeyStore() throws Exception {
            if (keyStore == null) {
                keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);
            }
            return keyStore;
        }

        public static synchronized SecretKey getOrCreateKey() throws Exception {
            KeyStore ks = getKeyStore();
            if (ks.containsAlias(KEY_ALIAS)) {
                KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
                if (entry instanceof KeyStore.SecretKeyEntry) {
                    return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                }
            }
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
            keyGenerator.init(spec);
            return keyGenerator.generateKey();
        }

        public static String encrypt(String plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            if (iv.length != GCM_IV_SIZE) {
                throw new IllegalStateException("Unexpected IV size: " + iv.length);
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[GCM_IV_SIZE + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_SIZE);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_SIZE, ciphertext.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        }

        public static String decrypt(String encrypted) throws Exception {
            byte[] combined = Base64.decode(encrypted, Base64.NO_WRAP);
            if (combined.length < GCM_IV_SIZE) {
                throw new IllegalArgumentException("Encrypted data too short");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_SIZE);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_SIZE, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_SIZE, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
    }
}
