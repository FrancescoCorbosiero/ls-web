package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.MutableClock;
import it.logisticasanchez.web.support.StaffTestData;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-13 with {@code admin.audit} on (ADR 0040): an ADMIN reads the audit log newest first under
 * «Impostazioni», filters it by who, action, object and Europe/Rome days, sees ids, references and
 * the changed fields with their non-personal values, and never the personal values recorded
 * apart; an OPERATOR is refused.
 */
@Tag("ENH-13")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
@TestPropertySource(properties = "ls.flags.override.[admin.audit]=true")
class AuditViewerIT extends AbstractPostgresIT {

    private static final String LOG = "/gestione/impostazioni/registro/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuditService audit;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private StaffUser admin;
    private MockHttpSession session;
    private long customerId;
    private long orderId;
    private String secretStreet;

    @BeforeEach
    void entries() {
        admin = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        session = StaffTestData.session(admin);
        customerId = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("registro"), "ACTIVE")
                .customerId();
        orderId = 900_000_000L + (long) (Math.random() * 99_999_999);
        secretStreet = "Via Riservata " + UUID.randomUUID().toString().substring(0, 6);

        // 23:30 in Rome on 10 March, then 00:30 in Rome on 11 March (CET, UTC+1)
        clock.set(Instant.parse("2026-03-10T22:30:00Z"));
        audit.record(AuditEntry.of(AuditActorType.STAFF, admin.getId(), "order.approve")
                .entity("shipment_order", orderId, "TEST-0901")
                .change(Map.of("status", "PENDING"), Map.of("status", "APPROVED", "amount", "180.50")));
        clock.set(Instant.parse("2026-03-10T23:30:00Z"));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, 4242L, "customer.update")
                .entity("customer", customerId, null)
                .personal(Map.of("addressLine", secretStreet), Map.of("addressLine", "Via Nuova 88")));
        clock.set(Instant.parse("2026-03-12T09:00:00Z"));
        audit.record(AuditEntry.system("customer.email.verified")
                .entity("customer", customerId, null)
                .change(Map.of("status", "PENDING_VERIFICATION"), Map.of("status", "PENDING_APPROVAL")));
    }

    @Test
    void anAdminReadsTheLogOfAnObjectNewestFirstWithoutPersonalValues() throws Exception {
        String page = page(LOG + "?oggetto=customer&id=" + customerId);
        assertThat(page)
                .contains("data-action=\"customer.email.verified\"")
                .contains("data-action=\"customer.update\"")
                .doesNotContain("data-action=\"order.approve\"")
                .contains("Cliente · accesso 4242")
                .contains("Sistema")
                .contains("PENDING_VERIFICATION")
                .contains("PENDING_APPROVAL")
                .contains("data-personal-detail")
                .doesNotContain(secretStreet)
                .doesNotContain("Via Nuova 88")
                .contains("href=\"/gestione/clienti/" + customerId + "/\"");
        assertThat(page.indexOf("data-action=\"customer.email.verified\""))
                .as("newest first")
                .isLessThan(page.indexOf("data-action=\"customer.update\""));
    }

    @Test
    void theFiltersNarrowByWhoActionAndRomeDays() throws Exception {
        String byStaff = page(LOG + "?chi=staff-" + admin.getId() + "&azione=order.approve");
        assertThat(byStaff)
                .contains("data-action=\"order.approve\"")
                .contains(admin.getUsername())
                .contains("TEST-0901")
                .contains("href=\"/gestione/ordini/" + orderId + "/\"")
                .contains("APPROVED")
                .contains("180.50")
                .doesNotContain("data-action=\"customer.update\"");

        String customers = page(LOG + "?chi=CUSTOMER&oggetto=customer&id=" + customerId);
        assertThat(customers)
                .contains("data-action=\"customer.update\"")
                .doesNotContain("data-action=\"customer.email.verified\"");

        String eleventh = page(LOG + "?dal=2026-03-11&al=2026-03-11&oggetto=customer&id=" + customerId);
        assertThat(eleventh)
                .as("00:30 in Rome on 11 March belongs to the 11th")
                .contains("data-action=\"customer.update\"")
                .doesNotContain("data-action=\"customer.email.verified\"");
        String tenth = page(LOG + "?dal=2026-03-10&al=2026-03-10&chi=staff-" + admin.getId());
        assertThat(tenth).as("23:30 in Rome on 10 March belongs to the 10th").contains("TEST-0901");

        assertThat(page(LOG + "?azione=nessuna.azione&chi=staff-" + admin.getId()))
                .as("an unknown action is ignored")
                .contains("data-action=\"order.approve\"");
        assertThat(page(LOG + "?dal=2026-03-13&chi=staff-" + admin.getId())).contains("data-empty");
    }

    @Test
    void onlyAnAdminOpensItFromTheSettings() throws Exception {
        assertThat(page("/gestione/impostazioni/")).contains("data-audit-link");
        MockHttpSession operator = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR));
        mvc.perform(get(LOG).session(operator).accept("text/html")).andExpect(status().isForbidden());
        mvc.perform(get(LOG).accept("text/html")).andExpect(status().isFound());
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
