package it.logisticasanchez.web.platform.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.platform.audit.AuditLog;
import it.logisticasanchez.web.platform.audit.AuditLogRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR 0025: every ENH flag key is registered and off, the enum matches the seeded table, an
 * environment override wins, and the toggle is ADMIN-only and audited (NFR-02).
 */
@SpringBootTest
@TestPropertySource(properties = "ls.flags.override.[site.openNow]=true")
class FeatureFlagServiceIT extends AbstractPostgresIT {

    @Autowired
    private FeatureFlagService flags;

    @Autowired
    private FeatureFlagRepository repository;

    @Autowired
    private AuditLogRepository audit;

    @Autowired
    private StaffUserRepository staff;

    @AfterEach
    void flagsBackOff() {
        repository.findAll().forEach(flag -> {
            if (flag.isEnabled() || flag.getUpdatedBy() != null) {
                flag.setEnabled(false, null, java.time.Instant.now());
                repository.save(flag);
            }
        });
        staff.findAll().stream()
                .filter(user -> user.getUsername().startsWith("flag-admin-"))
                .forEach(staff::delete);
        flags.evictCache();
    }

    @Test
    void everyFlagKeyIsRegisteredAndOffExceptTheTestOverride() {
        assertThat(FlagKey.values()).hasSize(25);
        assertThat(repository.findAll().stream().map(FeatureFlag::getKey))
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(FlagKey.values())
                        .map(FlagKey::key)
                        .toList());
        for (FlagKey key : FlagKey.values()) {
            assertThat(repository.findById(key.key()).orElseThrow().getRequirementId())
                    .isEqualTo(key.requirementId());
            if (key != FlagKey.SITE_OPEN_NOW) {
                assertThat(flags.isEnabled(key)).as("%s is off", key.key()).isFalse();
            }
        }
        assertThat(flags.isEnabled(FlagKey.SITE_OPEN_NOW))
                .as("ls.flags.override wins over the table")
                .isTrue();
        assertThat(repository
                        .findById(FlagKey.SITE_OPEN_NOW.key())
                        .orElseThrow()
                        .isEnabled())
                .as("the override never touches the table")
                .isFalse();
    }

    @Test
    void anUnknownOverrideKeyIsRefused() {
        assertThatThrownBy(() -> FeatureFlagService.parseOverrides(Map.of("pricing.typo", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pricing.typo");
    }

    @Test
    @Tag("NFR-02")
    @WithMockUser(roles = "ADMIN")
    void anAdminToggleWritesTheRowTheAuditEntryAndEvictsTheCache() {
        // updated_by references staff_user: a synthetic ADMIN account stands for the actor.
        StaffUser admin = staff.save(new StaffUser("flag-admin-" + System.nanoTime(), "Admin Test", StaffRole.ADMIN));
        long actorId = admin.getId();
        assertThat(flags.isEnabled(FlagKey.TRACKING_HISTORY)).isFalse();
        long before = audit.count();

        flags.toggle(FlagKey.TRACKING_HISTORY, true, actorId);

        assertThat(flags.isEnabled(FlagKey.TRACKING_HISTORY))
                .as("visible at once, cache evicted")
                .isTrue();
        FeatureFlag row = repository.findById(FlagKey.TRACKING_HISTORY.key()).orElseThrow();
        assertThat(row.isEnabled()).isTrue();
        assertThat(row.getUpdatedBy()).isEqualTo(actorId);
        assertThat(audit.count()).isEqualTo(before + 1);
        List<AuditLog> entries = audit.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("feature_flag", null);
        AuditLog entry = audit.findAll().stream()
                .filter(e -> "flag.toggle".equals(e.getAction()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(entry.getActorType().name()).isEqualTo("STAFF");
        assertThat(entry.getActorId()).isEqualTo(actorId);
        assertThat(entry.getEntityRef()).isEqualTo("tracking.history");
        assertThat(entry.getBefore()).matches("\\{\\s*\"enabled\"\\s*:\\s*false\\s*}");
        assertThat(entry.getAfter()).matches("\\{\\s*\"enabled\"\\s*:\\s*true\\s*}");
        assertThat(entries).isNotNull();
    }

    @Test
    @Tag("NFR-02")
    @WithMockUser(roles = "OPERATOR")
    void anOperatorCannotToggle() {
        assertThatThrownBy(() -> flags.toggle(FlagKey.TRACKING_HISTORY, true, 8L))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(repository
                        .findById(FlagKey.TRACKING_HISTORY.key())
                        .orElseThrow()
                        .isEnabled())
                .isFalse();
    }

    @Test
    @Tag("NFR-02")
    void anonymousCodeCannotToggle() {
        assertThatThrownBy(() -> flags.toggle(FlagKey.TRACKING_HISTORY, true, 8L))
                .isInstanceOfAny(AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class);
    }
}
