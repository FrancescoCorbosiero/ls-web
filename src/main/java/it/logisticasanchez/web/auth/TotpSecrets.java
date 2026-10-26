package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for the staff TOTP secrets in {@code staff_user.totp_secret} (ENH-09, ADR 0039;
 * chapter 08: «bytea, encrypted»). The key is derived (HMAC-SHA256 with a purpose label) from
 * {@code ls.notifications.token-encryption-key} ({@code LS_TOKEN_ENCRYPTION_KEY}, already
 * required in production), so no new secret is needed and the two uses never share a key.
 * Ciphertext = 12-byte IV followed by the GCM output.
 */
@Component
class TotpSecrets {

    private static final byte[] PURPOSE = "ls-web staff totp secret v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    TotpSecrets(NotificationsProperties notifications) {
        String master = notifications.tokenEncryptionKey();
        if (master == null || master.isBlank()) {
            this.key = null;
            return;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(master.trim()), "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal(PURPOSE), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP key derivation failed", e);
        }
    }

    byte[] seal(byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret);
            byte[] out = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP secret encryption failed", e);
        }
    }

    byte[] open(byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    requireKey(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, IV_BYTES)));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP secret decryption failed", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "ls.notifications.token-encryption-key (LS_TOKEN_ENCRYPTION_KEY) is not set");
        }
        return key;
    }
}
