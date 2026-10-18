package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for the raw one-time tokens held in {@code email_outbox.token_ciphertext}
 * (chapter 10 §2): key from {@code ls.notifications.token-encryption-key} (base64 of 32 bytes,
 * environment {@code LS_NOTIFICATIONS_TOKEN_ENCRYPTION_KEY}). Ciphertext = 12-byte IV followed
 * by the GCM output. Using it without a key is an error, not a silent plain-text fallback.
 */
@Component
public class TokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    public TokenCipher(NotificationsProperties properties) {
        this(properties.tokenEncryptionKey());
    }

    TokenCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
            if (bytes.length != 32) {
                throw new IllegalStateException(
                        "ls.notifications.token-encryption-key must be base64 of 32 bytes, got " + bytes.length);
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    public String decrypt(byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    requireKey(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(ciphertext, 0, IV_BYTES)));
            return new String(cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("token decryption failed", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "ls.notifications.token-encryption-key (LS_NOTIFICATIONS_TOKEN_ENCRYPTION_KEY) is not set");
        }
        return key;
    }
}
