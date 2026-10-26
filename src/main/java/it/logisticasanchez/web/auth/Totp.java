package it.logisticasanchez.web.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time codes of RFC 6238 (ENH-09, ADR 0039), as every authenticator app makes
 * them: HMAC-SHA1, 30-second steps, 6 digits; the secret is 20 random bytes, shown in Base32. A
 * code is accepted for the step before and after the current one (a phone clock a little off),
 * never for a step already used.
 */
final class Totp {

    static final int SECRET_BYTES = 20;
    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;
    /** Steps accepted on each side of the current one. */
    static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {}

    static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** The 30-second step of an instant (Unix time / 30). */
    static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** The code of a step, zero-padded to {@value #DIGITS} digits. */
    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash =
                    mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    /**
     * The step the typed code belongs to, within {@link #WINDOW} steps of {@code now} and after
     * {@code lastStep} (null: no code used yet); empty when the code does not match. Spaces in the
     * typed code are ignored.
     */
    static OptionalLong match(byte[] secret, String typed, Instant now, Long lastStep) {
        String code = typed == null ? "" : typed.replaceAll("\\s", "");
        if (!code.matches("^[0-9]{" + DIGITS + "}$")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (lastStep != null && step <= lastStep) {
                continue;
            }
            if (MessageDigest.isEqual(
                    code(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** RFC 4648 Base32 without padding, as the apps take a key typed by hand. */
    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    /** The {@code otpauth://} URI an app reads from the QR code. */
    static String uri(String issuer, String account, byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + base32(secret) + "&issuer="
                + encode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
