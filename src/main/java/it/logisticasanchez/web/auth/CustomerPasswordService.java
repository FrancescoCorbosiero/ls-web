package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.SecurityProperties;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's own password (owner decision of 2026-10-25, ADR 0032): {@link #authenticate}
 * is the e-mail-and-password sign-in — rate-limited per IP and per e-mail, one answer for an
 * unknown e-mail, a login without a password, a disabled login and a wrong password, and the
 * same bcrypt work in every case so the timing tells nothing either. {@link #setPassword} stores
 * the bcrypt hash of a password that passes the policy ({@code
 * ls.security.customer-password.min-length}, 8, and the common-password list) and writes the
 * audit log. The e-mailed link stays for the first access and a forgotten password.
 */
@Service
public class CustomerPasswordService {

    private final CustomerUserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final Clock clock;
    private final int minLength;
    private final String unusedHash;

    public CustomerPasswordService(
            CustomerUserRepository users,
            PasswordEncoder encoder,
            PasswordPolicy policy,
            RateLimiter rateLimiter,
            AuditService audit,
            SecurityProperties security,
            Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
        this.minLength = security.customerPassword().minLength();
        // compared against when there is no hash to check, so every refusal costs the same bcrypt work
        this.unusedHash = encoder.encode(Tokens.random());
    }

    public int minLength() {
        return minLength;
    }

    /**
     * The customer for this e-mail and password, or empty for any refusal (429 through the error
     * model when the limits are spent). A successful sign-in stamps {@code last_login_at}.
     */
    @Transactional
    public Optional<CustomerPrincipal> authenticate(String rawEmail, String password, String clientIp) {
        String email = CustomerSignInService.normalise(rawEmail);
        rateLimiter.require(RateLimitKeys.SIGNIN_PASSWORD_IP, clientIp);
        rateLimiter.require(RateLimitKeys.SIGNIN_PASSWORD_EMAIL, email);
        Optional<CustomerUser> user = email.isEmpty() || email.length() > 254
                ? Optional.empty()
                : users.findByEmail(email).filter(u -> !u.isDisabled() && u.hasPassword());
        String typed = password == null ? "" : password;
        boolean matches =
                encoder.matches(typed, user.map(CustomerUser::getPasswordHash).orElse(unusedHash));
        if (user.isEmpty() || !matches) {
            return Optional.empty();
        }
        CustomerUser found = user.get();
        found.setLastLoginAt(clock.instant());
        return Optional.of(new CustomerPrincipal(found.getId(), found.getCustomerId(), found.getEmail()));
    }

    @Transactional(readOnly = true)
    public boolean hasPassword(long customerUserId) {
        return users.findById(customerUserId).map(CustomerUser::hasPassword).orElse(false);
    }

    /** True when {@code password} is the login's current password. */
    @Transactional(readOnly = true)
    public boolean isCurrentPassword(long customerUserId, String password) {
        return users.findById(customerUserId)
                .filter(CustomerUser::hasPassword)
                .map(user -> encoder.matches(password == null ? "" : password, user.getPasswordHash()))
                .orElse(false);
    }

    /** Why the policy refuses this password (too short, too common), or empty when it is acceptable. */
    public Optional<PasswordPolicy.Violation> violation(String password) {
        return policy.check(password, minLength);
    }

    /** Sets (or replaces) the password; refused with the policy's reason when it is too short or too common. */
    @Transactional
    public void setPassword(long customerUserId, String password) {
        Optional<PasswordPolicy.Violation> violation = policy.check(password, minLength);
        if (violation.isPresent()) {
            throw new PasswordRefusedException(violation.get());
        }
        CustomerUser user = users.findById(customerUserId)
                .filter(u -> !u.isDisabled())
                .orElseThrow(() -> new IllegalArgumentException("no usable customer user " + customerUserId));
        boolean replaced = user.hasPassword();
        Instant now = clock.instant();
        user.setPassword(encoder.encode(password), now);
        audit.record(AuditEntry.of(
                        AuditActorType.CUSTOMER,
                        customerUserId,
                        replaced ? "customer.password.change" : "customer.password.set")
                .entity("customer_user", customerUserId, null));
    }

    /** A password the policy refuses; {@link #violation} names the reason. */
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
