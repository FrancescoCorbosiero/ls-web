package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.auth.CustomerUser;
import it.logisticasanchez.web.auth.CustomerUserRepository;
import it.logisticasanchez.web.auth.CustomerUserRole;
import it.logisticasanchez.web.auth.LoginToken;
import it.logisticasanchez.web.auth.LoginTokenPurpose;
import it.logisticasanchez.web.auth.LoginTokenRepository;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.EmailOutboxStatus;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditLog;
import it.logisticasanchez.web.platform.audit.AuditLogDetail;
import it.logisticasanchez.web.platform.audit.AuditLogDetailRepository;
import it.logisticasanchez.web.platform.audit.AuditLogRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlag;
import it.logisticasanchez.web.platform.flags.FeatureFlagRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Phase 1 entities map their tables: every one can be written and read back through its
 * repository (jsonb, bytea, enums as varchar, timestamptz). Synthetic data only; the transaction
 * is rolled back.
 */
@SpringBootTest
@Transactional
class PhaseOneEntitiesIT extends AbstractPostgresIT {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private CustomerUserRepository customerUsers;

    @Autowired
    private LoginTokenRepository loginTokens;

    @Autowired
    private FeatureFlagRepository featureFlags;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private AuditLogDetailRepository auditLogDetails;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void staffUserRoundTrip() {
        StaffUser saved = staffUsers.save(new StaffUser("  Mario.Test ", "Mario Test", StaffRole.OPERATOR));
        saved.setEmail("Mario.Test@example");
        saved.setTotpSecret(new byte[] {1, 2, 3});
        flushAndClear();

        StaffUser found = staffUsers.findByUsername("mario.test").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmail()).isEqualTo("mario.test@example");
        assertThat(found.getRole()).isEqualTo(StaffRole.OPERATOR);
        assertThat(found.isActive()).isTrue();
        assertThat(found.getFailedLogins()).isZero();
        assertThat(found.getPasswordHash())
                .as("no password until the one-time link is used")
                .isNull();
        assertThat(found.getTotpSecret()).containsExactly(1, 2, 3);
        // The test database is shared with the auth tests, which create ADMIN accounts: assert
        // the query on the role just saved.
        assertThat(staffUsers.existsByRole(StaffRole.OPERATOR)).isTrue();
    }

    @Test
    void customerUserAndLoginTokenRoundTrip() {
        long customerId = insertSyntheticCustomer("cliente.test@example");
        CustomerUser user =
                customerUsers.save(new CustomerUser(customerId, " Cliente.Test@example ", CustomerUserRole.OWNER));
        // Tokens expire relative to their creation time (database CHECK), so real time is used.
        Instant issued = Instant.now();
        LoginToken signIn = loginTokens.save(LoginToken.forCustomerUser(
                user.getId(), LoginTokenPurpose.SIGN_IN, "a".repeat(64), issued.plus(15, ChronoUnit.MINUTES)));
        LoginToken register = loginTokens.save(
                LoginToken.forRegistration("b".repeat(64), "c".repeat(64), issued.plus(24, ChronoUnit.HOURS)));
        flushAndClear();

        CustomerUser foundUser =
                customerUsers.findByEmail("cliente.test@example").orElseThrow();
        assertThat(foundUser.getCustomerId()).isEqualTo(customerId);
        assertThat(foundUser.getRole()).isEqualTo(CustomerUserRole.OWNER);
        assertThat(customerUsers.findByCustomerId(customerId)).hasSize(1);

        LoginToken foundSignIn = loginTokens.findByTokenHash("a".repeat(64)).orElseThrow();
        assertThat(foundSignIn.getPurpose()).isEqualTo(LoginTokenPurpose.SIGN_IN);
        assertThat(foundSignIn.getCustomerUserId()).isEqualTo(user.getId());
        assertThat(foundSignIn.isUsableAt(issued)).isTrue();
        assertThat(foundSignIn.isUsableAt(issued.plus(16, ChronoUnit.MINUTES))).isFalse();
        foundSignIn.markUsed(issued);
        assertThat(foundSignIn.isUsableAt(issued)).isFalse();

        LoginToken foundRegister = loginTokens.findById(register.getId()).orElseThrow();
        assertThat(foundRegister.getCustomerUserId()).isNull();
        assertThat(foundRegister.getEmailHash()).isEqualTo("b".repeat(64));
        // Other tests leave expired tokens in the shared database: the 15-minute token goes,
        // the 24-hour one stays.
        assertThat(loginTokens.deleteExpiredBefore(issued.plus(1, ChronoUnit.HOURS)))
                .as("the 15-minute token has expired")
                .isGreaterThanOrEqualTo(1);
        assertThat(loginTokens.findByTokenHash("a".repeat(64))).isEmpty();
        assertThat(loginTokens.findById(register.getId())).isPresent();
    }

    @Test
    void featureFlagToggleRoundTrip() {
        StaffUser admin = staffUsers.save(new StaffUser("admin.test", "Admin Test", StaffRole.ADMIN));
        FeatureFlag flag = featureFlags.findById("tracking.history").orElseThrow();
        assertThat(flag.isEnabled()).isFalse();
        flag.setEnabled(true, admin.getId(), NOW);
        flushAndClear();

        FeatureFlag toggled = featureFlags.findById("tracking.history").orElseThrow();
        assertThat(toggled.isEnabled()).isTrue();
        assertThat(toggled.getUpdatedBy()).isEqualTo(admin.getId());
        assertThat(toggled.getRequirementId()).isEqualTo("ENH-05");
    }

    @Test
    void auditLogAndDetailRoundTrip() {
        // An entity id no test order reaches: the shared database holds the audit rows of the
        // order tests, and a real order 42 audited after NOW would come first in the lookup.
        long entityId = 900_000_042L;
        AuditLog entry = auditLogs.save(AuditLog.of(AuditActorType.STAFF, "order.note.update")
                .occurredAt(NOW)
                .actor(7L)
                .entity("shipment_order", entityId, "TEST-0001")
                .change("{\"body\": \"changed\"}", "{\"body\": \"changed\"}")
                .request("d".repeat(64), "test-agent/1.0", "req-1")
                .build());
        auditLogDetails.save(new AuditLogDetail(entry.getId(), "{\"body\": \"before\"}", "{\"body\": \"after\"}"));
        flushAndClear();

        AuditLog found = auditLogs
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc("shipment_order", entityId)
                .get(0);
        assertThat(found.getAction()).isEqualTo("order.note.update");
        assertThat(found.getActorType()).isEqualTo(AuditActorType.STAFF);
        assertThat(found.getOccurredAt()).isEqualTo(NOW);
        assertThat(found.getBefore()).contains("\"body\"").contains("changed");
        AuditLogDetail detail = auditLogDetails.findById(found.getId()).orElseThrow();
        assertThat(detail.getBefore()).contains("before");
        assertThat(detail.getAfter()).contains("after");
    }

    @Test
    void emailOutboxRoundTrip() {
        EmailOutbox queued = outbox.save(
                new EmailOutbox("EML-01", "cliente.test@example", "Accedi all'area clienti", "Testo", "<p>Testo</p>"));
        queued.attachToken(insertSyntheticRegisterToken(), new byte[] {9, 9});
        flushAndClear();

        assertThat(outbox.findDue(Instant.now().plus(1, ChronoUnit.MINUTES), PageRequest.of(0, 10)))
                .extracting(EmailOutbox::getId)
                .contains(queued.getId());
        EmailOutbox found = outbox.findById(queued.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(EmailOutboxStatus.QUEUED);
        assertThat(found.getTokenCiphertext()).containsExactly(9, 9);
        found.markSent(NOW, "ses-0001");
        flushAndClear();

        EmailOutbox sent = outbox.findById(queued.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(EmailOutboxStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getTokenCiphertext()).as("wiped after delivery").isNull();
        assertThat(outbox.findSentBeforeWithBodies(NOW.plus(1, ChronoUnit.DAYS), PageRequest.of(0, 10)))
                .extracting(EmailOutbox::getId)
                .contains(sent.getId());
        sent.purgeBodies(NOW.plus(30, ChronoUnit.DAYS));
        flushAndClear();
        assertThat(outbox.findById(queued.getId()).orElseThrow().getBodyText()).isNull();
        assertThat(outbox.countByStatus(EmailOutboxStatus.SENT)).isGreaterThanOrEqualTo(1);
    }

    private long insertSyntheticCustomer(String email) {
        return jdbc.queryForObject(
                "insert into customer (email, company_name, address_line, city, province, postal_code, phone, status, source)"
                        + " values (?, 'Azienda Test S.r.l.', 'Via Test 1', 'Seregno', 'MB', '20831', '+39 333 000 0000',"
                        + " 'PENDING_VERIFICATION', 'CUSTOMER_AREA') returning id",
                Long.class,
                email);
    }

    private long insertSyntheticRegisterToken() {
        Map<String, Object> row = jdbc.queryForMap(
                "insert into login_token (email_hash, purpose, token_hash, expires_at)"
                        + " values (?, 'REGISTER', ?, now() + interval '24 hours') returning id",
                "e".repeat(64),
                "f".repeat(64));
        return ((Number) row.get("id")).longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
