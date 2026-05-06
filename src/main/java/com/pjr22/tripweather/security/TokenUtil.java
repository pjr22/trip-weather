package com.pjr22.tripweather.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random-token helpers for email-verification and password-reset flows.
 *
 * The raw token leaves the server only via email; what's persisted is the
 * SHA-256 hash. That way a database leak doesn't hand attackers usable tokens
 * — they'd need the original from the user's mailbox.
 */
public final class TokenUtil {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits → 43-char base64url string

    private TokenUtil() { }

    public static String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every Java SE implementation — this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
