package com.ecut.campus;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 交院门户账号仅经用户主动保存。密钥由 Android Keystore 管理，密文不参与备份。 */
final class SdjtuCredentials {
    private static final String KEY_ALIAS = "sdjtu_portal_login_v1";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String CIPHER_SUITE = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int FILE_VERSION = 1;
    private static boolean updating;

    private SdjtuCredentials() {}

    static final class Account {
        final String username;
        final String password;

        private Account(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private static AtomicFile file(Context ctx) {
        return new AtomicFile(new File(ctx.getNoBackupFilesDir(), "sdjtu-portal-login.bin"));
    }

    private static SharedPreferences state(Context ctx) {
        // Only revision and consent are stored here, never an identifier or a password.
        return ctx.getSharedPreferences("sdjtu_portal_login_state", Context.MODE_PRIVATE);
    }

    static synchronized long generation(Context ctx) {
        return state(ctx).getLong("generation", 0);
    }

    static synchronized boolean isUpdating() {
        return updating;
    }

    static synchronized boolean hasRecord(Context ctx) {
        File base = file(ctx).getBaseFile();
        // AtomicFile on older Android versions keeps the previous record in .bak during replacement.
        return base.exists() || new File(base + ".bak").exists();
    }

    static synchronized boolean hasAnyState(Context ctx) {
        SharedPreferences prefs = state(ctx);
        return hasRecord(ctx) || prefs.getBoolean("enabled", false) || prefs.getLong("generation", 0) > 0;
    }

    static synchronized boolean isSaved(Context ctx) {
        return !updating && state(ctx).getBoolean("enabled", false)
                && hasRecord(ctx);
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        return store;
    }

    @Nullable
    static synchronized Account load(Context ctx) {
        if (!isSaved(ctx)) return null;
        byte[] plain = null;
        try {
            byte[] bytes = file(ctx).readFully();
            if (bytes.length <= 1 + IV_LENGTH + TAG_LENGTH || bytes[0] != FILE_VERSION) return null;
            SecretKey key = (SecretKey) keyStore().getKey(KEY_ALIAS, null);
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance(CIPHER_SUITE);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, bytes, 1, IV_LENGTH));
            int offset = 1 + IV_LENGTH;
            plain = cipher.doFinal(bytes, offset, bytes.length - offset);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                String username = in.readUTF();
                String password = in.readUTF();
                return username.isEmpty() || password.isEmpty() || in.available() != 0
                        ? null : new Account(username, password);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException unavailable) {
            return null;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** Main-thread entry. The new account becomes usable only after the old WebView cookies are gone. */
    static synchronized void save(Context ctx, String username, String password, ValueCallback<Boolean> done) {
        Context app = ctx.getApplicationContext();
        String name = username.trim();
        if (updating || name.isEmpty() || password.isEmpty()) {
            done.onReceiveValue(false);
            return;
        }
        Account previous = load(app);
        boolean sameAccount = previous != null && previous.username.equals(name);
        long next = generation(app) + 1;
        updating = true;
        state(app).edit().putLong("generation", next).putBoolean("enabled", false).apply();
        if (sameAccount) {
            finishSave(app, next, name, password, done);
            return;
        }
        Prefs.setPortalSession(app, false);
        Prefs.setSessionExpired(app, false);
        Prefs.setSdjtuAcademicBase(app, "");
        SdjtuStudyRoute.clear(app);
        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(removed -> {
                // false also means the cookie jar was already empty.
                try {
                    cookies.flush();
                } catch (RuntimeException unavailable) {
                    failSave(done);
                    return;
                }
                finishSave(app, next, name, password, done);
            });
        } catch (RuntimeException unavailable) {
            failSave(done);
        }
    }

    private static synchronized void failSave(ValueCallback<Boolean> done) {
        updating = false;
        done.onReceiveValue(false);
    }

    private static synchronized void finishSave(Context ctx, long revision, String name, String password,
                                               ValueCallback<Boolean> done) {
        boolean saved = false;
        if (revision == generation(ctx)) {
            try {
                encryptAndWrite(ctx, name, password);
                Prefs.setSessionExpired(ctx, false);
                Prefs.setPortalSessionVerifiedAt(ctx, 0L);
                state(ctx).edit().putBoolean("enabled", true).apply();
                saved = true;
            } catch (IOException | GeneralSecurityException | RuntimeException unavailable) {
                // Keep automatic login disabled if storage or the device key is unavailable.
            }
        }
        updating = false;
        done.onReceiveValue(saved);
    }

    private static void encryptAndWrite(Context ctx, String username, String password)
            throws IOException, GeneralSecurityException {
        SecretKey key = (SecretKey) keyStore().getKey(KEY_ALIAS, null);
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            key = generator.generateKey();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(username);
            out.writeUTF(password);
        }
        byte[] plain = buffer.toByteArray();
        byte[] encrypted;
        Cipher cipher = Cipher.getInstance(CIPHER_SUITE);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key);
            encrypted = cipher.doFinal(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        byte[] iv = cipher.getIV();
        if (iv.length != IV_LENGTH) throw new GeneralSecurityException();
        AtomicFile target = file(ctx);
        FileOutputStream output = target.startWrite();
        try {
            output.write(FILE_VERSION);
            output.write(iv);
            output.write(encrypted);
            target.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            target.failWrite(output);
            throw failure;
        }
    }

    static synchronized boolean clear(Context ctx) {
        state(ctx).edit().putLong("generation", generation(ctx) + 1).putBoolean("enabled", false).apply();
        AtomicFile target = file(ctx);
        target.delete();
        try {
            KeyStore store = keyStore();
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            return !target.getBaseFile().exists();
        } catch (IOException | GeneralSecurityException | RuntimeException unavailable) {
            return false;
        }
    }
}
