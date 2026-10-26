package it.logisticasanchez.web.quotes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The link of a saved quote (ENH-20, ADR 0037): 32 random bytes, Base64 URL-safe without padding
 * (43 characters); the database keeps only their SHA-256, so a copy of the table opens no quote.
 */
final class QuoteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private QuoteTokens() {}

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the text can be a token at all (anything else is not looked up). */
    static boolean wellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
