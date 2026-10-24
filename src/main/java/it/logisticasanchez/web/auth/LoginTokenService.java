package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import it.logisticasanchez.web.platform.security.IpHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time links of the customer area (chapter 10 §2, ADR 0004) for every purpose:
 * {@code SIGN_IN} (15 min), {@code VERIFY_EMAIL} and {@code CHANGE_EMAIL} (15 min, for the
 * Phase 2 {@code customers} module) and {@code REGISTER} (24 h, unknown e-mail). A token is
 * random (256 bits), stored only as its SHA-256, single use. {@link #peek} is what a {@code GET}
 * may do (look, never consume); {@link #consume} is what only the CSRF-protected {@code POST}
 * does.
 */
@Service
public class LoginTokenService {

    private final LoginTokenRepository tokens;
    private final SecurityProperties security;
    private final IpHasher ipHasher;
    private final Clock clock;

    public LoginTokenService(LoginTokenRepository tokens, SecurityProperties security, IpHasher ipHasher, Clock clock) {
        this.tokens = tokens;
        this.security = security;
        this.ipHasher = ipHasher;
        this.clock = clock;
    }

    /** Issues a token for a known customer user (any purpose but {@code REGISTER}). */
    @Transactional
    public IssuedToken issue(LoginTokenPurpose purpose, long customerUserId, String clientIp) {
        Instant now = clock.instant();
        String raw = Tokens.random();
        LoginToken token =
                LoginToken.forCustomerUser(customerUserId, purpose, Tokens.hash(raw), now.plus(validity(purpose)), now);
        token.setRequestedIpHash(clientIp == null ? null : ipHasher.hash(clientIp));
        return new IssuedToken(tokens.save(token), raw);
    }

    /** Issues a {@code REGISTER} token for an unknown e-mail, identified only by its HMAC. */
    @Transactional
    public IssuedToken issueRegistration(String emailHash, String clientIp) {
        Instant now = clock.instant();
        String raw = Tokens.random();
        LoginToken token = LoginToken.forRegistration(
                emailHash, Tokens.hash(raw), now.plus(validity(LoginTokenPurpose.REGISTER)), now);
        token.setRequestedIpHash(clientIp == null ? null : ipHasher.hash(clientIp));
        return new IssuedToken(tokens.save(token), raw);
    }

    /** The usable token of that purpose behind a raw value, without consuming it (a {@code GET}). */
    @Transactional(readOnly = true)
    public Optional<LoginToken> peek(String rawToken, LoginTokenPurpose purpose) {
        String hash = Tokens.hash(rawToken);
        if (hash == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByTokenHash(hash).filter(t -> t.getPurpose() == purpose && t.isUsableAt(now));
    }

    /** Consumes the token (the {@code POST}): marks it used, or fails when unknown, used, expired or of another purpose. */
    @Transactional
    public LoginToken consume(String rawToken, LoginTokenPurpose purpose) {
        LoginToken token = peek(rawToken, purpose).orElseThrow(InvalidTokenException::new);
        token.markUsed(clock.instant());
        return token;
    }

    public Duration validity(LoginTokenPurpose purpose) {
        SecurityProperties.Tokens lifetimes = security.tokens();
        return switch (purpose) {
            case SIGN_IN -> lifetimes.signIn();
            case VERIFY_EMAIL -> lifetimes.verifyEmail();
            case REGISTER -> lifetimes.registration();
            case CHANGE_EMAIL -> lifetimes.changeEmail();
        };
    }

    /** The stored row and the raw token, which exists only here and in the encrypted outbox column. */
    public record IssuedToken(LoginToken token, String rawToken) {
        public IssuedToken {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }

    /** A token that is unknown, used, expired or issued for another purpose. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super("one-time token not usable");
        }
    }
}
