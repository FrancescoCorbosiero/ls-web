package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.FormValidator;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-06 with {@code leads.inbox} off (the default, ADR 0025): no «Richieste» in the navigation,
 * every inbox route is a 404, and «Nuovo cliente» / «Nuovo ordine» ignore {@code ?richiesta=}:
 * the requests are stored and e-mailed to the office as in M1 (PUB-11), nothing more.
 */
@Tag("ENH-06")
@SpringBootTest
@AutoConfigureMockMvc
class LeadsInboxOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FormValidator forms;

    @Autowired
    private LeadService leads;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private FeatureFlags flags;

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theBackOfficeIsTheM1One() throws Exception {
        assertThat(flags.isEnabled(FlagKey.LEADS_INBOX)).isFalse();
        MockHttpSession staff = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "spento-" + tag + "@cliente.example";
        Map<String, String[]> fields = new LinkedHashMap<>();
        fields.put("nome", new String[] {"Lucia Esempio"});
        fields.put("email", new String[] {email});
        fields.put("messaggio", new String[] {"Vorrei sapere se offrite lo stoccaggio."});
        fields.put("privacy", new String[] {"accettata"});
        long id = leads.store(forms.validate(FormSubmission.Form.CONTATTI, fields), "192.0.2.11", "LeadsInboxOffIT");

        mvc.perform(get("/gestione/richieste/").session(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/gestione/richieste/" + id + "/").session(staff)).andExpect(status().isNotFound());
        mvc.perform(post("/gestione/richieste/" + id + "/stato")
                        .session(staff)
                        .with(csrf())
                        .param("stato", "CONTACTED"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/gestione/richieste/" + id + "/collega")
                        .session(staff)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(page(staff, "/gestione/")).doesNotContain("href=\"/gestione/richieste/\"");
        assertThat(page(staff, "/gestione/clienti/nuovo?richiesta=" + id))
                .doesNotContain("data-from-lead")
                .doesNotContain("name=\"richiesta\"")
                .doesNotContain(email);
        assertThat(page(staff, "/gestione/ordini/nuovo?richiesta=" + id)).doesNotContain("name=\"richiesta\"");

        mvc.perform(post("/gestione/clienti/nuovo")
                        .session(staff)
                        .with(csrf())
                        .param("richiesta", Long.toString(id))
                        .param("email", email)
                        .param("companyName", "Cliente Spento " + tag)
                        .param("addressLine", "Via Milano 1")
                        .param("province", "MB")
                        .param("city", "Seregno")
                        .param("postalCode", "20831")
                        .param("phone", "039 1234567")
                        .param("vatNumber", "")
                        .param("taxCode", ""))
                .andExpect(redirectedUrlPattern("/gestione/clienti/*/"));
        assertThat(jdbc.queryForMap("select status, customer_id from lead where id = ?", id))
                .containsEntry("status", "NEW")
                .containsEntry("customer_id", null);
    }

    private String page(MockHttpSession staff, String path) throws Exception {
        return mvc.perform(get(path).session(staff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
