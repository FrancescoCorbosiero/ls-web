package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA-256 of a normalised e-mail for {@code login_token.email_hash} ({@code REGISTER}
 * tokens of unknown addresses, chapter 08 §2.3): the same key as the IP hashes
 * ({@code ls.security.ip-hash-key}) with a domain separator, so no second secret is needed
 * (ADR 0004) and the address itself is never stored before the customer registers.
 */
@Component
public class EmailHasher {

    private static final byte[] DOMAIN = "email:".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    public EmailHasher(SecurityProperties properties) {
        String secret = properties.ipHashKey();
        this.key = secret.isBlank() ? null : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String normalisedEmail) {
        if (key == null) {
            throw new IllegalStateException("ls.security.ip-hash-key (LS_SECURITY_IP_HASH_KEY) is not set");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(DOMAIN);
            return HexFormat.of().formatHex(mac.doFinal(normalisedEmail.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
