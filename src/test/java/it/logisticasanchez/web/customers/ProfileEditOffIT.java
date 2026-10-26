package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-16 with {@code customers.profileEdit} off (the default, ADR 0025): the app is M1 — the
 * profile is read-only with «Chiedi una modifica», the data routes answer 404, and the back office
 * neither shows nor decides a data change (a row left from a time the flag was on stays as it is).
 */
@Tag("ENH-16")
@SpringBootTest
@AutoConfigureMockMvc
class ProfileEditOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private FeatureFlags flags;

    @Test
    void theProfileAndTheBackOfficeAreTheM1Ones() throws Exception {
        assertThat(flags.isEnabled(FlagKey.CUSTOMERS_PROFILE_EDIT)).isFalse();
        CustomerTestData.Ids ids =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("dati-spento"), "ACTIVE");
        MockHttpSession session = CustomerTestData.session(ids);
        long requestId = jdbc.queryForObject(
                "insert into customer_change_request (customer_id, company_name, requested_at) values (?, ?, ?)"
                        + " returning id",
                Long.class,
                ids.customerId(),
                "Ditta Rimasta In Attesa S.r.l.",
                Timestamp.from(Instant.now()));

        assertThat(page(session, "/area-clienti/profilo/"))
                .contains("Chiedi una modifica")
                .doesNotContain("data-edit-profile")
                .doesNotContain("data-pending-change")
                .doesNotContain("Ditta Rimasta In Attesa S.r.l.");
        mvc.perform(get("/area-clienti/profilo/dati").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/area-clienti/profilo/dati")
                        .session(session)
                        .with(csrf())
                        .param("companyName", "Ditta Spenta S.r.l.")
                        .param("addressLine", "Via Spenta 1")
                        .param("province", "MB")
                        .param("city", "Seregno")
                        .param("postalCode", "20831")
                        .param("phone", "+393330000000")
                        .param("versione", "0"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/area-clienti/profilo/dati/ritira").session(session).with(csrf()))
                .andExpect(status().isNotFound());

        MockHttpSession staff = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR));
        assertThat(page(staff, "/gestione/clienti/in-approvazione/"))
                .doesNotContain("data-section=\"customer-changes\"");
        assertThat(page(staff, "/gestione/clienti/" + ids.customerId() + "/"))
                .doesNotContain("data-change-request")
                .doesNotContain("Ditta Rimasta In Attesa S.r.l.");
        assertThat(page(staff, "/gestione/")).contains("Clienti registrati in attesa della tua approvazione.");
        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/approva")
                        .session(staff)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/rifiuta")
                        .session(staff)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForMap(
                        "select company_name, address_line, version from customer where id = ?", ids.customerId()))
                .containsEntry("company_name", "Ditta Esempio S.r.l.")
                .containsEntry("address_line", "Via di Prova 1")
                .containsEntry("version", 0);
        assertThat(jdbc.queryForObject(
                        "select status from customer_change_request where id = ?", String.class, requestId))
                .isEqualTo("PENDING");
        assertThat(outbox.findAll()).noneMatch(m -> m.getRecipient().equals(ids.email()));
        jdbc.update("delete from customer_change_request where id = ?", requestId);
    }

    private String page(MockHttpSession session, String path) throws Exception {
        return mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
