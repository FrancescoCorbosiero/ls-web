package it.logisticasanchez.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random one-time tokens and their stored form (chapter 10 §2 "Tokens"): 256 random bits in
 * URL-safe base64 travel in the link; only the SHA-256 hex of the raw token is stored, in
 * {@code login_token.token_hash} and {@code staff_setup_token.token_hash}.
 */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RAW_BYTES = 32;

    private Tokens() {}

    static String random() {
        byte[] bytes = new byte[RAW_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 (64 characters) of the raw token; null for a null or blank token. */
    static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
