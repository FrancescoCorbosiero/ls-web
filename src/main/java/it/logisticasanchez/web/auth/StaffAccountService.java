package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff accounts (ADM-01, ADR 0003): creation without a password plus a one-time set-password
 * link (24 hours, single use), the password policy, the lock-out counters (PD-10) and the
 * success bookkeeping. The first ADMIN comes from the {@code create-staff-user} command; the
 * other accounts from an ADMIN through {@link #createByAdmin}, which e-mails the link through
 * the outbox. No code path ever accepts or prints a password.
 */
@Service
public class StaffAccountService {

    public static final Duration SETUP_LINK_VALIDITY = Duration.ofHours(24);

    private final StaffUserRepository users;
    private final StaffSetupTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SecurityProperties security;
    private final BusinessProperties business;
    private final NotificationService notifications;
    private final AuthMails mails;
    private final AuditService audit;
    private final Clock clock;

    public StaffAccountService(
            StaffUserRepository users,
            StaffSetupTokenRepository tokens,
            PasswordEncoder encoder,
            PasswordPolicy policy,
            SecurityProperties security,
            BusinessProperties business,
            NotificationService notifications,
            AuthMails mails,
            AuditService audit,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.policy = policy;
        this.security = security;
        this.business = business;
        this.notifications = notifications;
        this.mails = mails;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Creates the account (no password) and its set-password link; used by the command. The raw
     * token is returned once and never stored.
     */
    @Transactional
    public SetupLink create(String username, String displayName, String email, StaffRole role) {
        String normalised = StaffUser.normaliseUsername(username);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        if (users.findByUsername(normalised).isPresent()) {
            throw new UsernameTakenException(normalised);
        }
        StaffUser user = new StaffUser(normalised, blankToNull(displayName) == null ? normalised : displayName, role);
        user.setEmail(blankToNull(email));
        user = users.save(user);
        return issueSetupLink(user);
    }

    /** An ADMIN creates an account; the set-password link goes to the account's e-mail through the outbox. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public StaffUser createByAdmin(long adminId, String username, String displayName, String email, StaffRole role) {
        if (blankToNull(email) == null) {
            throw new IllegalArgumentException("an e-mail is required to send the set-password link");
        }
        SetupLink link = create(username, displayName, email, role);
        StaffUser user = users.findById(link.staffUserId()).orElseThrow();
        notifications.enqueue(
                mails.staffSetPassword(user.getEmail(), user.getDisplayName(), user.getUsername(), SETUP_LINK_VALIDITY)
                        .withSecret(link.rawToken()));
        audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "staff.create")
                .entity("staff_user", user.getId(), user.getUsername())
                .change(null, Map.of("role", role.name())));
        return user;
    }

    /** Issues a fresh link for an existing account (an ADMIN re-sending it); the raw token is returned once. */
    @Transactional
    public SetupLink issueSetupLink(StaffUser user) {
        Instant now = clock.instant();
        String raw = Tokens.random();
        tokens.save(new StaffSetupToken(user.getId(), Tokens.hash(raw), now, now.plus(SETUP_LINK_VALIDITY)));
        return new SetupLink(user.getId(), user.getUsername(), raw);
    }

    /** The staff account a usable link belongs to; the link is not consumed (the GET of the page). */
    @Transactional(readOnly = true)
    public Optional<StaffUser> peekSetupLink(String rawToken) {
        Instant now = clock.instant();
        return tokens.findByTokenHash(Tokens.hash(rawToken))
                .filter(token -> token.isUsableAt(now))
                .flatMap(token -> users.findById(token.getStaffUserId()));
    }

    /**
     * Sets the password through the link (the POST of the page): the link must be usable and the
     * password must pass the policy; the link is consumed and the lock-out counters cleared.
     */
    @Transactional
    public StaffUser setPassword(String rawToken, String password) {
        Instant now = clock.instant();
        StaffSetupToken token = tokens.findByTokenHash(Tokens.hash(rawToken))
                .filter(t -> t.isUsableAt(now))
                .orElseThrow(InvalidLinkException::new);
        policy.check(password).ifPresent(violation -> {
            throw new PasswordRefusedException(violation);
        });
        StaffUser user = users.findById(token.getStaffUserId()).orElseThrow(InvalidLinkException::new);
        user.setPasswordHash(encoder.encode(password));
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);
        token.markUsed(now);
        return user;
    }

    /** A failed login (PD-10): one more failure; at the threshold the account is locked for the configured time. */
    @Transactional
    public void recordFailure(String username) {
        if (username == null) {
            return;
        }
        Instant now = clock.instant();
        users.findByUsername(StaffUser.normaliseUsername(username)).ifPresent(user -> {
            SecurityProperties.Lockout lockout = security.staff().lockout();
            int failures = user.getFailedLogins() + 1;
            if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(now)) {
                failures = 1; // the previous lock has expired: start a new count
                user.setLockedUntil(null);
            }
            user.setFailedLogins(failures);
            if (failures >= lockout.threshold()) {
                user.setLockedUntil(now.plus(lockout.duration()));
            }
            user.setUpdatedAt(now);
        });
    }

    /** A successful login: counters cleared, last login stamped. */
    @Transactional
    public void recordSuccess(long staffUserId) {
        Instant now = clock.instant();
        users.findById(staffUserId).ifPresent(user -> {
            user.setFailedLogins(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(now);
            user.setUpdatedAt(now);
        });
    }

    /** The absolute URL of a set-password link, as the command prints it and the e-mail carries it. */
    public String setupUrl(String rawToken) {
        return business.siteUrl() + AuthRoutes.STAFF_SET_PASSWORD + "?t=" + rawToken;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** What {@link #create} returns: the account and the raw token, shown once. */
    public record SetupLink(long staffUserId, String username, String rawToken) {
        public SetupLink {
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }

    public static class UsernameTakenException extends RuntimeException {
        public UsernameTakenException(String username) {
            super("username already taken: " + username);
        }
    }

    /** A set-password link that is unknown, used or expired. */
    public static class InvalidLinkException extends RuntimeException {
        public InvalidLinkException() {
            super("set-password link not usable");
        }
    }

    public static class PasswordRefusedException extends RuntimeException {
        private final PasswordPolicy.Violation violation;

        public PasswordRefusedException(PasswordPolicy.Violation violation) {
            super("password refused: " + violation);
            this.violation = violation;
        }

        public PasswordPolicy.Violation violation() {
            return violation;
        }
    }
}
