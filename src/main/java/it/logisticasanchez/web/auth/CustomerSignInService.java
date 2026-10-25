package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer sign-in by one-time link (CUS-01, chapter 10 §2, ADR 0004). {@link #requestLink}
 * answers the same way for every address: a known {@code customer_user} (verified, unverified or
 * declined alike, plan D-69) gets a {@code SIGN_IN} token and the EML-01 sign-in e-mail; an
 * unknown address gets a {@code REGISTER} token (24 h, only the e-mail's HMAC stored) and the
 * registration e-mail; a disabled login gets nothing. {@link #signIn} is what only the
 * CSRF-protected {@code POST} of the link page does.
 */
@Service
public class CustomerSignInService {

    private final CustomerUserRepository users;
    private final LoginTokenService tokens;
    private final EmailHasher emailHasher;
    private final NotificationService notifications;
    private final AuthMails mails;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<CustomerSignInListener> listeners;
    private final Clock clock;

    public CustomerSignInService(
            CustomerUserRepository users,
            LoginTokenService tokens,
            EmailHasher emailHasher,
            NotificationService notifications,
            AuthMails mails,
            RateLimiter rateLimiter,
            ObjectProvider<CustomerSignInListener> listeners,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.emailHasher = emailHasher;
        this.notifications = notifications;
        this.mails = mails;
        this.rateLimiter = rateLimiter;
        this.listeners = listeners;
        this.clock = clock;
    }

    /** The e-mail as it is looked up and stored: trimmed and lower-case (OP-10, OP-11). */
    public static String normalise(String email) {
        return CustomerUser.normaliseEmail(email == null ? "" : email);
    }

    /**
     * Rate-limits (per IP, then per e-mail; 429 through the error model) and, in one transaction,
     * issues the token and queues the e-mail. Returns what was issued, for tests and logs by id
     * only; the caller shows the same page whatever happened.
     */
    public Outcome requestLink(String rawEmail, String clientIp) {
        String email = normalise(rawEmail);
        rateLimiter.require(RateLimitKeys.SIGNIN_LINK_IP, clientIp);
        rateLimiter.require(RateLimitKeys.SIGNIN_LINK_EMAIL, email);
        if (email.isEmpty() || !email.contains("@") || email.length() > 254) {
            return Outcome.NOTHING;
        }
        return issue(email, clientIp);
    }

    @Transactional
    Outcome issue(String email, String clientIp) {
        Optional<CustomerUser> known = users.findByEmail(email);
        if (known.isPresent()) {
            CustomerUser user = known.get();
            if (user.isDisabled()) {
                return Outcome.NOTHING;
            }
            LoginTokenService.IssuedToken issued = tokens.issue(LoginTokenPurpose.SIGN_IN, user.getId(), clientIp);
            notifications.enqueue(mails.signIn(email, tokens.validity(LoginTokenPurpose.SIGN_IN))
                    .withTokenLink(issued.token().getId(), issued.rawToken()));
            return Outcome.SIGN_IN_LINK;
        }
        LoginTokenService.IssuedToken issued = tokens.issueRegistration(emailHasher.hash(email), clientIp);
        notifications.enqueue(mails.register(email, tokens.validity(LoginTokenPurpose.REGISTER))
                .withTokenLink(issued.token().getId(), issued.rawToken()));
        return Outcome.REGISTER_LINK;
    }

    /** True when the raw token is a usable {@code SIGN_IN} token (the {@code GET} of the link page; nothing consumed). */
    @Transactional(readOnly = true)
    public boolean isUsableSignInToken(String rawToken) {
        return tokens.peek(rawToken, LoginTokenPurpose.SIGN_IN).isPresent();
    }

    /** True when the raw token is a usable {@code REGISTER} token (the registration page; nothing consumed). */
    @Transactional(readOnly = true)
    public boolean isUsableRegistrationToken(String rawToken) {
        return tokens.peek(rawToken, LoginTokenPurpose.REGISTER).isPresent();
    }

    /**
     * Consumes the {@code SIGN_IN} token and returns the principal to put in the session; fails
     * with {@link LoginTokenService.InvalidTokenException} when the link is used, expired or unknown.
     */
    @Transactional
    public CustomerPrincipal signIn(String rawToken) {
        LoginToken token = tokens.consume(rawToken, LoginTokenPurpose.SIGN_IN);
        CustomerUser user = users.findById(token.getCustomerUserId())
                .filter(u -> !u.isDisabled())
                .orElseThrow(LoginTokenService.InvalidTokenException::new);
        Instant now = clock.instant();
        user.setLastLoginAt(now);
        List<CustomerSignInListener> hooks = listeners.orderedStream().toList();
        for (CustomerSignInListener hook : hooks) {
            hook.onSignedIn(user.getId(), user.getCustomerId(), now);
        }
        return new CustomerPrincipal(user.getId(), user.getCustomerId(), user.getEmail());
    }

    /** What a link request produced; never shown to the requester. */
    public enum Outcome {
        SIGN_IN_LINK,
        REGISTER_LINK,
        NOTHING
    }
}
