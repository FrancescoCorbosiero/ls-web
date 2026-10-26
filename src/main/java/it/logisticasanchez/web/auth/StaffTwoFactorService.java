package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-factor sign-in for staff (ENH-09, flag {@code security.adminTotp}; ADR 0039). While the
 * flag is on, every staff login asks, after the password, the 6-digit code of an authenticator
 * app (RFC 6238, {@link Totp}); a user without a secret sets one up on the same page — QR code or
 * key, confirmed by a first code — and only then is it stored, encrypted ({@link TotpSecrets}).
 * Each code is accepted once. An ADMIN resets a user's secret (a lost or new phone); the
 * {@code reset-staff-totp} command does it on the server. With the flag off the login is the M1
 * one and the stored secrets are ignored.
 */
@Service
public class StaffTwoFactorService {

    private final StaffUserRepository users;
    private final TotpSecrets secrets;
    private final AuditService audit;
    private final FeatureFlags flags;
    private final BusinessProperties business;
    private final Clock clock;

    public StaffTwoFactorService(
            StaffUserRepository users,
            TotpSecrets secrets,
            AuditService audit,
            FeatureFlags flags,
            BusinessProperties business,
            Clock clock) {
        this.users = users;
        this.secrets = secrets;
        this.audit = audit;
        this.flags = flags;
        this.business = business;
        this.clock = clock;
    }

    /** True while the flag is on: every staff login asks for the code. */
    public boolean required() {
        return flags.isEnabled(FlagKey.SECURITY_ADMIN_TOTP);
    }

    /** What the set-up page shows for a new secret: the key in groups of four, the QR code. */
    record Setup(String key, TotpQr.Svg qr) {}

    Setup setup(String username, byte[] secret) {
        String key = Totp.base32(secret).replaceAll("(.{4})(?!$)", "$1 ");
        return new Setup(key, TotpQr.of(Totp.uri(business.brandName(), username, secret)));
    }

    /** The principal to sign in once the code is right: empty when the account is disabled or locked meanwhile. */
    @Transactional(readOnly = true)
    Optional<StaffPrincipal> principal(long staffUserId) {
        Instant now = clock.instant();
        return users.findById(staffUserId)
                .map(user -> new StaffPrincipal(user, now))
                .filter(p -> p.isEnabled() && p.isAccountNonLocked());
    }

    /** True when the user has confirmed a secret. */
    @Transactional(readOnly = true)
    boolean enabled(long staffUserId) {
        return users.findById(staffUserId).map(u -> u.getTotpSecret() != null).orElse(false);
    }

    /**
     * Checks a code: against the stored secret, or — for a user setting two-factor up — against
     * {@code newSecret}, which is then stored. True when the code is accepted.
     */
    @Transactional
    boolean check(long staffUserId, byte[] newSecret, String code) {
        StaffUser user = users.lockById(staffUserId).orElseThrow();
        Instant now = clock.instant();
        if (user.getTotpSecret() == null) {
            if (newSecret == null) {
                return false;
            }
            OptionalLong step = Totp.match(newSecret, code, now, null);
            if (step.isEmpty()) {
                return false;
            }
            user.enableTotp(secrets.seal(newSecret), now, step.getAsLong());
            user.setUpdatedAt(now);
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "staff.totp.enable")
                    .entity("staff_user", staffUserId, user.getUsername()));
            return true;
        }
        OptionalLong step = Totp.match(secrets.open(user.getTotpSecret()), code, now, user.getTotpLastStep());
        if (step.isEmpty()) {
            return false;
        }
        user.acceptTotpStep(step.getAsLong());
        return true;
    }

    /** An ADMIN removes a user's secret: at the next login with the flag on, the user sets it up again. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void reset(long adminId, long staffUserId) {
        StaffUser user = users.findById(staffUserId).orElseThrow();
        user.resetTotp();
        user.setUpdatedAt(clock.instant());
        audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "staff.totp.reset")
                .entity("staff_user", staffUserId, user.getUsername()));
    }

    /** The {@code reset-staff-totp} command: false when no user has this username. */
    @Transactional
    public boolean resetByCommand(String username) {
        return users.findByUsername(StaffUser.normaliseUsername(username))
                .map(user -> {
                    user.resetTotp();
                    user.setUpdatedAt(clock.instant());
                    audit.record(AuditEntry.system("staff.totp.reset")
                            .entity("staff_user", user.getId(), user.getUsername()));
                    return true;
                })
                .orElse(false);
    }
}
