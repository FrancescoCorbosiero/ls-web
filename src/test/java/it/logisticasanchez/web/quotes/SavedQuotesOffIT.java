package it.logisticasanchez.web.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.FormValidator;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-20 with {@code quotes.saved} off (the default, ADR 0025), the inbox of ENH-06 on: no
 * «Prepara preventivo», every quote route is a 404 — the link's page and PDF, the customer's order
 * form, the staff's routes — and «I miei ordini» lists no quote, even when one is stored.
 */
@Tag("ENH-20")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.flags.override.[leads.inbox]=true")
class SavedQuotesOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FormValidator forms;

    @Autowired
    private LeadService leads;

    @Autowired
    private QuoteService quotes;

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
    void everyQuoteScreenIsAbsent() throws Exception {
        assertThat(flags.isEnabled(FlagKey.QUOTES_SAVED)).isFalse();
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        MockHttpSession staff = StaffTestData.session(operator);
        CustomerTestData.Ids customer =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("spento"), "ACTIVE");
        MockHttpSession customerSession = CustomerTestData.session(customer);
        Map<String, String[]> fields = new LinkedHashMap<>();
        fields.put("nome", new String[] {"Lucia Esempio"});
        fields.put("email", new String[] {customer.email()});
        fields.put("messaggio", new String[] {"Vorrei un preventivo per due pallet."});
        fields.put("privacy", new String[] {"accettata"});
        long lead = leads.store(forms.validate(FormSubmission.Form.CONTATTI, fields), "192.0.2.21", "SavedQuotesOffIT");
        leads.linkCustomer(lead, operator.getId(), customer.customerId());
        // a quote stored meanwhile (the service does not read the flag: the screens do)
        QuoteView quote = quotes.create(
                lead,
                operator.getId(),
                new QuoteService.Draft(
                        List.of(new OrderDraft.Line(1, LineKind.GENERIC, 100, 120, 80, new BigDecimal("200"), 1)),
                        "90",
                        quotes.today().plusDays(3),
                        null,
                        null,
                        null,
                        null));

        assertThat(mvc.perform(get("/gestione/richieste/" + lead + "/").session(staff))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-create-quote")
                .doesNotContain("data-section=\"quotes\"");
        mvc.perform(get("/gestione/richieste/" + lead + "/preventivo").session(staff))
                .andExpect(status().isNotFound());
        mvc.perform(post("/gestione/richieste/" + lead + "/preventivo")
                        .session(staff)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/gestione/preventivi/" + quote.id() + "/ritira")
                        .session(staff)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/gestione/preventivi/" + quote.id() + "/pdf").session(staff))
                .andExpect(status().isNotFound());
        mvc.perform(get("/preventivi/" + QuoteTokens.newToken())).andExpect(status().isNotFound());
        mvc.perform(get("/area-clienti/preventivi/" + quote.id() + "/ordina").session(customerSession))
                .andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/area-clienti/ordini/").session(customerSession))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-open-quotes");
    }
}
