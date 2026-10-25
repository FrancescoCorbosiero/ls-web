package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA-256 of a client IP with the key {@code ls.security.ip-hash-key} (chapter 10 §2):
 * what {@code audit_log.ip_hash} and {@code login_token.requested_ip_hash} store, kept at most
 * 30 days (Q-18). The raw address is never stored or logged.
 */
@Component
public class IpHasher {

    private final SecretKeySpec key;

    public IpHasher(SecurityProperties properties) {
        String secret = properties.ipHashKey();
        this.key = secret.isBlank() ? null : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Lower-case hex of the HMAC, or null for a null address. */
    public String hash(String ip) {
        if (ip == null) {
            return null;
        }
        if (key == null) {
            throw new IllegalStateException("ls.security.ip-hash-key (LS_SECURITY_IP_HASH_KEY) is not set");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(ip.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
