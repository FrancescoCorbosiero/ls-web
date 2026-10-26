package it.logisticasanchez.web.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.LoginTokenPurpose;
import it.logisticasanchez.web.auth.LoginTokenService;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.OutboxBodiesRetentionJob;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.reports.ReportService;
import it.logisticasanchez.web.site.forms.LeadRetentionJob;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR-03 (chapter 10 §5, ADR 0024): the export tool returns what the app holds about an e-mail;
 * after the erasure tool no table holds the address — nor the company name — except the
 * {@code shipment_order} snapshots, the orders stay, the login is disabled and its links are
 * gone, notes, outbox bodies and audit details are removed; both tools are ADMIN-only and
 * audited by id; the retention jobs anonymise expired leads and purge sent outbox bodies.
 */
@Tag("NFR-03")
@SpringBootTest
@AutoConfigureMockMvc
class PrivacyIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderService orders;

    @Autowired
    private ReportService reports;

    @Autowired
    private LoginTokenService tokens;

    @Autowired
    private AuditService audit;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private LeadRetentionJob leadRetention;

    @Autowired
    private OutboxBodiesRetentionJob outboxRetention;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportReturnsTheDataAndAnonymisationLeavesTheAddressOnlyInTheOrderSnapshots() throws Exception {
        StaffUser admin = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        String email = CustomerTestData.uniqueEmail("cancellami");
        String company =
                "Da Cancellare " + java.util.UUID.randomUUID().toString().substring(0, 8) + " S.r.l.";
        CustomerTestData.Ids ids = CustomerTestData.customerUser(jdbc, email, "ACTIVE", company);
        OrderView order = orders.submit(
                ids.customerId(), ids.customerUserId(), OrderTestData.draft("DDT-GDPR", "Dest GDPR", "Monza", "20900"));
        orders.approve(
                order.id(),
                admin.getId(),
                "GDPR-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "10");
        orders.setInternalNote(order.id(), admin.getId(), "Nota su " + company);
        tokens.issue(LoginTokenPurpose.SIGN_IN, ids.customerUserId(), "203.0.113.5");
        insertLead(email, LocalDate.now().plusYears(1));
        reports.request(
                admin.getId(),
                new ReportService.Request(
                        ids.customerId(),
                        LocalDate.now(ItalianFormats.ROME).toString(),
                        LocalDate.now(ItalianFormats.ROME).toString(),
                        false,
                        true,
                        ""));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, ids.customerUserId(), "customer.register")
                .entity("customer", ids.customerId(), null)
                .personal(null, Map.of("email", email, "companyName", company)));
        assertThat(count(email))
                .as("the address appears in several tables before the erasure")
                .hasSizeGreaterThan(4);

        String export = mvc.perform(post("/gestione/impostazioni/privacy/esporta")
                        .session(StaffTestData.session(admin))
                        .with(csrf())
                        .param("email", email))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(export)
                .contains("\"email\" : \"" + email + "\"")
                .contains(company)
                .contains("DDT-GDPR")
                .contains("richiesteDalSito")
                .contains("Vorrei informazioni")
                .doesNotContain("Nota su");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'privacy.export' and actor_id = ? and entity_id = ?",
                        Long.class,
                        admin.getId(),
                        ids.customerId()))
                .isEqualTo(1);

        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR)))
                        .with(csrf())
                        .param("email", email))
                .andExpect(status().isForbidden());
        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(StaffTestData.session(admin))
                        .with(csrf())
                        .param("email", email))
                .andExpect(status().is3xxRedirection());

        assertThat(count(email)).as("tables still holding the address").containsExactly("shipment_order.sender_email");
        assertThat(count(company))
                .as("tables still holding the company name")
                .containsExactly("shipment_order.sender_name");
        assertThat(jdbc.queryForMap(
                        "select email, company_name, status, anonymised_at from customer where id = ?",
                        ids.customerId()))
                .containsEntry("email", "cliente-" + ids.customerId() + "@anonimizzato.invalid")
                .containsEntry("company_name", "Cliente anonimizzato")
                .containsEntry("status", "DECLINED")
                .doesNotContainEntry("anonymised_at", null);
        assertThat(jdbc.queryForObject(
                        "select disabled_at is not null from customer_user where id = ?",
                        Boolean.class,
                        ids.customerUserId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from login_token where customer_user_id = ?",
                        Long.class,
                        ids.customerUserId()))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where customer_id = ?", Long.class, ids.customerId()))
                .as("orders are kept")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from order_internal_note where order_id = ?", Long.class, order.id()))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from email_outbox where recipient like 'cliente-%@anonimizzato.invalid'"
                                + " and body_text is null and body_html is null and token_ciphertext is null",
                        Long.class))
                .isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "select count(*) from email_outbox where status = 'QUEUED' and recipient like '%anonimizzato.invalid'",
                        Long.class))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log_detail d join audit_log l on l.id = d.audit_log_id"
                                + " where l.entity_type = 'customer' and l.entity_id = ?",
                        Long.class,
                        ids.customerId()))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where entity_type = 'customer' and entity_id = ?",
                        Long.class,
                        ids.customerId()))
                .as("the audit rows themselves (ids only) stay")
                .isGreaterThan(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'privacy.anonymise' and actor_id = ? and entity_id = ?",
                        Long.class,
                        admin.getId(),
                        ids.customerId()))
                .isEqualTo(1);
        // idempotent and the customer no longer appears in the back-office lists
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer where id = ? and anonymised_at is not null",
                        Long.class,
                        ids.customerId()))
                .isEqualTo(1);
    }

    @Test
    void retentionJobsAnonymiseExpiredLeadsAndPurgeSentBodies() {
        String fresh = CustomerTestData.uniqueEmail("lead-fresco");
        String old = CustomerTestData.uniqueEmail("lead-vecchio");
        // retain_until is a UTC day, as LeadService computes it: not the JVM's day (CI runs in Europe/Rome)
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long freshId = insertLead(fresh, today.plusMonths(1));
        long oldId = insertLead(old, today.minusDays(1));
        int affected = leadRetention.run(Instant.now());
        assertThat(affected).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "select name, email, phone, message, payload::text as payload, ip_hash from lead where id = ?",
                        oldId))
                .containsEntry("name", "-")
                .containsEntry("email", "lead-" + oldId + "@anonimizzato.invalid")
                .containsEntry("message", null)
                .containsEntry("payload", "{}")
                .containsEntry("ip_hash", null);
        assertThat(jdbc.queryForObject("select email from lead where id = ?", String.class, freshId))
                .isEqualTo(fresh);
        assertThat(leadRetention.run(Instant.now())).as("idempotent").isEqualTo(0);

        Instant now = Instant.now();
        long oldMail = jdbc.queryForObject(
                "insert into email_outbox (kind, recipient, subject, body_text, body_html, status, sent_at, created_at)"
                        + " values ('EML-08', 'ufficio@ufficio.example', 'vecchia', 'corpo', '<p>corpo</p>', 'SENT', ?, ?) returning id",
                Long.class,
                Timestamp.from(now.minus(Duration.ofDays(40))),
                Timestamp.from(now.minus(Duration.ofDays(40))));
        long recentMail = jdbc.queryForObject(
                "insert into email_outbox (kind, recipient, subject, body_text, body_html, status, sent_at)"
                        + " values ('EML-08', 'ufficio@ufficio.example', 'recente', 'corpo', '<p>corpo</p>', 'SENT', ?) returning id",
                Long.class,
                Timestamp.from(now.minus(Duration.ofDays(2))));
        assertThat(outboxRetention.run(now)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "select body_text, body_html, bodies_purged_at, subject from email_outbox where id = ?",
                        oldMail))
                .containsEntry("body_text", null)
                .containsEntry("body_html", null)
                .containsEntry("subject", "vecchia")
                .doesNotContainEntry("bodies_purged_at", null);
        assertThat(jdbc.queryForObject("select body_text from email_outbox where id = ?", String.class, recentMail))
                .isEqualTo("corpo");
    }

    private long insertLead(String email, LocalDate retainUntil) {
        return jdbc.queryForObject(
                "insert into lead (form, name, email, phone, message, payload, privacy_accepted_at, ip_hash, retain_until)"
                        + " values ('CONTATTI', 'Mario Rossi', ?, '+393330000000', 'Vorrei informazioni',"
                        + " ('{\"nome\":\"Mario Rossi\",\"email\":\"' || ? || '\"}')::jsonb, now(), 'abc123', ?) returning id",
                Long.class,
                email,
                email,
                retainUntil);
    }

    /** {@code table.column} of every text-like column (varchar, text, text[], jsonb) holding the value, in any table. */
    private List<String> count(String value) {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "select table_name, column_name from information_schema.columns where table_schema = current_schema()"
                        + " and (data_type in ('character varying', 'text', 'jsonb') or udt_name = '_text')"
                        + " and table_name not like 'flyway%' order by table_name, column_name");
        List<String> holding = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            Long hits = jdbc.queryForObject(
                    "select count(*) from \"" + table + "\" where \"" + name + "\"::text ilike ?",
                    Long.class,
                    "%" + value + "%");
            if (hits != null && hits > 0) {
                holding.add(table + "." + name);
            }
        }
        return holding;
    }
}
