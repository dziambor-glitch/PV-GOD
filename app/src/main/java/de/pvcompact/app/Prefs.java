package de.pvcompact.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Prefs {
    private static final String PREFS = "pvcompact_clean";
    private static final String KEY_ALIAS = "pvcompact_clean_key_v1";
    private final SharedPreferences p;

    public Prefs(Context context) {
        p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String get(String key, String def) {
        return p.getString(key, def);
    }

    public void put(String key, String value) {
        p.edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    public boolean getBool(String key, boolean def) {
        return p.getBoolean(key, def);
    }

    public void putBool(String key, boolean value) {
        p.edit().putBoolean(key, value).apply();
    }

    public void putSecret(String key, String value) {
        String v = value == null ? "" : value.trim();
        SharedPreferences.Editor e = p.edit();
        if (v.isEmpty()) {
            e.remove(key + "_iv").remove(key + "_data").apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(v.getBytes(StandardCharsets.UTF_8));
            e.putString(key + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            e.putString(key + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            e.apply();
        } catch (Exception ex) {
            throw new IllegalStateException("Secret konnte nicht gespeichert werden", ex);
        }
    }

    public String getSecret(String key) {
        String iv = p.getString(key + "_iv", "");
        String data = p.getString(key + "_data", "");
        if (iv == null || data == null || iv.isEmpty() || data.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    public SharedPreferences raw() {
        return p;
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key existing = ks.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
