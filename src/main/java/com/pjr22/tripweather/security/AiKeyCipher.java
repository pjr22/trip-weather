package com.pjr22.tripweather.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts user-supplied AI provider API keys for at-rest storage.
 * AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>AES-256-GCM with a fresh random 12-byte IV per value. The stored form is
 * {@code base64(iv ‖ ciphertext‖tag)} — the 12-byte IV is prepended to the
 * GCM output (which already includes the 16-byte authentication tag) and the
 * whole thing is Base64-encoded. GCM gives confidentiality <em>and</em>
 * tamper-detection: {@link #decrypt} throws if the ciphertext (or IV) was
 * altered.
 *
 * <p>The key comes from {@code trip.ai.enc-key} (env {@code TRIP_AI_ENC_KEY}),
 * a Base64-encoded 32-byte value (generate with {@code openssl rand -base64
 * 32}). {@code StartupConfigValidator} requires it whenever
 * {@code trip.ai.assist.enabled=true}, so by the time any encrypt/decrypt runs
 * the key is present. Construction tolerates a blank key (so the app still
 * boots with assist disabled) but a <em>non-blank</em> key must decode to
 * exactly 32 bytes or construction fails fast — a misconfigured key is a boot
 * error, not a per-request surprise.
 */
@Component
public class AiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    /** Null when no (or blank) key is configured — encrypt/decrypt then refuse. */
    private final SecretKeySpec keySpec;

    public AiKeyCipher(@Value("${trip.ai.enc-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.keySpec = null;
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "TRIP_AI_ENC_KEY is not valid Base64. Generate one with "
                  + "'openssl rand -base64 32'.", e);
        }
        if (keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(
                    "TRIP_AI_ENC_KEY must decode to exactly " + AES_256_KEY_BYTES
                  + " bytes (AES-256) but was " + keyBytes.length
                  + ". Generate one with 'openssl rand -base64 32'.");
        }
        this.keySpec = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    /** True when a usable key is configured (i.e. encrypt/decrypt will work). */
    public boolean isConfigured() {
        return keySpec != null;
    }

    /**
     * Encrypt a plaintext API key into the {@code base64(iv ‖ ciphertext‖tag)}
     * storage form.
     *
     * @throws IllegalStateException if no key is configured
     */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt API key", e);
        }
    }

    /**
     * Decrypt a value previously produced by {@link #encrypt}. GCM verifies the
     * authentication tag, so any tampering (or a wrong key, e.g. after rotating
     * {@code TRIP_AI_ENC_KEY}) surfaces as an exception rather than garbage.
     *
     * @throws IllegalStateException if no key is configured
     * @throws IllegalArgumentException if the stored value is malformed or fails authentication
     */
    public String decrypt(String stored) {
        requireKey();
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Stored API key is not valid Base64", e);
        }
        if (combined.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("Stored API key is too short to contain an IV and ciphertext");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException (tamper / wrong key) lands here too.
            throw new IllegalArgumentException("Failed to decrypt API key (tampered, or wrong TRIP_AI_ENC_KEY)", e);
        }
    }

    private void requireKey() {
        if (keySpec == null) {
            throw new IllegalStateException(
                    "TRIP_AI_ENC_KEY is not configured — cannot encrypt/decrypt AI provider keys. "
                  + "Set it (Base64 32 bytes) or disable the feature with TRIP_AI_ASSIST_ENABLED=false.");
        }
    }
}
