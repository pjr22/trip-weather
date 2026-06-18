package com.pjr22.tripweather.security;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiKeyCipher}. AI_ASSIST_PLAN.md, Phase 1. Covers the
 * encrypt/decrypt round-trip, tamper / wrong-key detection (GCM auth tag), the
 * unconfigured-key behaviour (so the app boots with assist disabled), and
 * construction-time validation of a misconfigured key.
 */
class AiKeyCipherTest {

    private static String key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    /** A second, distinct 32-byte key for the wrong-key test. */
    private static String otherKey() {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void roundTrip_returnsOriginalPlaintext() {
        AiKeyCipher cipher = new AiKeyCipher(key(32));
        String plaintext = "sk-proj-abc123-SECRET";

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotBlank();
        assertThat(encrypted).doesNotContain(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_usesFreshIv_soSamePlaintextYieldsDifferentCiphertext() {
        AiKeyCipher cipher = new AiKeyCipher(key(32));

        String a = cipher.encrypt("same");
        String b = cipher.encrypt("same");

        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("same");
        assertThat(cipher.decrypt(b)).isEqualTo("same");
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        AiKeyCipher cipher = new AiKeyCipher(key(32));
        String encrypted = cipher.encrypt("secret");

        // Flip the last byte (inside the GCM tag) and re-encode.
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_withWrongKey_throws() {
        AiKeyCipher writer = new AiKeyCipher(key(32));
        AiKeyCipher reader = new AiKeyCipher(otherKey());

        String encrypted = writer.encrypt("secret");

        assertThatThrownBy(() -> reader.decrypt(encrypted))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_garbageInput_throws() {
        AiKeyCipher cipher = new AiKeyCipher(key(32));
        assertThatThrownBy(() -> cipher.decrypt("not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankKey_isNotConfigured_andEncryptDecryptThrow() {
        AiKeyCipher cipher = new AiKeyCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullKey_isNotConfigured() {
        assertThat(new AiKeyCipher(null).isConfigured()).isFalse();
    }

    @Test
    void validKey_isConfigured() {
        assertThat(new AiKeyCipher(key(32)).isConfigured()).isTrue();
    }

    @Test
    void wrongLengthKey_failsConstruction() {
        assertThatThrownBy(() -> new AiKeyCipher(key(16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void nonBase64Key_failsConstruction() {
        assertThatThrownBy(() -> new AiKeyCipher("!!! not base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
