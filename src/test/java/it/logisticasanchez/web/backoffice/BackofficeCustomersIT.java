package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The customer screens of the back office (ADM-02, ADM-03; chapter 04 §3.2–3.3 columns and copy):
 * «In Approvazione» with the nine legacy columns and «SI»/«NO», approve and decline behind a named
 * confirmation (EML-03 queued, audit row), an unverified customer refused with the reason on the
 * page (Q-28), «Registrati» newest first with search and pagination, the customer page with its
 * orders; an OPERATOR sees every customer screen.
 */
@Tag("ADM-02")
@Tag("ADM-03")
@Tag("EML-03")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeCustomersIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    private StaffUser operator;
    private MockHttpSession session;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        session = StaffTestData.session(operator);
    }

    @Test
    void pendingListShowsTheLegacyColumnsAndApprovalGoesThroughANamedConfirmation() throws Exception {
        String email = CustomerTestData.uniqueEmail("attesa");
        CustomerTestData.Ids ids = CustomerTestData.customerUser(jdbc, email, "PENDING_APPROVAL", "Pending <b>Srl</b>");

        String page = mvc.perform(get("/gestione/clienti/in-approvazione/").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (String column : List.of(
                "ID",
                "Ragione sociale",
                "Email",
                "Indirizzo",
                "Provincia",
                "Città",
                "Cap",
                "Telefono",
                "Email confermata")) {
            assertThat(page).contains(">" + column + "<");
        }
        assertThat(page)
                .contains("data-customer=\"" + ids.customerId() + "\"")
                .contains(email)
                .contains("Pending &lt;b&gt;Srl&lt;/b&gt;")
                .doesNotContain("Pending <b>Srl</b>")
                .contains(">SI<")
                .doesNotContain("Nessun utente da approvare");

        String confirm = mvc.perform(get("/gestione/clienti/" + ids.customerId() + "/approva")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(confirm).contains("Sei sicuro di voler procedere?").contains("Pending &lt;b&gt;Srl&lt;/b&gt;");

        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/approva")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/gestione/clienti/in-approvazione/"));
        assertThat(jdbc.queryForObject("select status from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select approved_by from customer where id = ?", Long.class, ids.customerId()))
                .isEqualTo(operator.getId());
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-03");
        assertThat(mails.get(0).getRecipient()).isEqualTo(email);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'customer.approve' and actor_id = ? and entity_id = ?",
                        Long.class,
                        operator.getId(),
                        ids.customerId()))
                .isEqualTo(1);
        assertThat(mvc.perform(get("/gestione/clienti/in-approvazione/").session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-customer=\"" + ids.customerId() + "\"");
    }

    @Test
    void anUnverifiedCustomerIsNotListedAndCannotBeApproved() throws Exception {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("nonverificato"), "PENDING_VERIFICATION", "Non Verificata S.r.l.");
        assertThat(mvc.perform(get("/gestione/clienti/in-approvazione/").session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-customer=\"" + ids.customerId() + "\"");
        String page = mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/approva")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).contains("data-error").contains("non è in attesa di approvazione");
        assertThat(jdbc.queryForObject("select status from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void declineSendsTheReasonAndTheDeclinedCustomerStaysVisibleAmongTheRegistered() throws Exception {
        String email = CustomerTestData.uniqueEmail("rifiuto");
        CustomerTestData.Ids ids =
                CustomerTestData.customerUser(jdbc, email, "PENDING_APPROVAL", "Da Rifiutare S.r.l.");
        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/rifiuta")
                        .session(session)
                        .with(csrf())
                        .param("motivo", "Dati non verificabili"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select status from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("DECLINED");
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-03");
        assertThat(mails.get(0).getBodyText()).contains("Dati non verificabili");
        String registered = mvc.perform(
                        get("/gestione/clienti/").param("q", email).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(registered)
                .contains("data-customer=\"" + ids.customerId() + "\"")
                .contains("Non accettato");
    }

    @Test
    void registeredCustomersAreNewestFirstSearchableAndPaginated() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        long first = 0;
        long last = 0;
        for (int i = 0; i < 27; i++) {
            long id = CustomerTestData.customerUser(
                            jdbc,
                            CustomerTestData.uniqueEmail("pag" + marker),
                            "ACTIVE",
                            "Paginata " + marker + " " + i)
                    .customerId();
            if (i == 0) {
                first = id;
            }
            last = id;
        }
        String page1 = mvc.perform(get("/gestione/clienti/").param("q", marker).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page1)
                .contains("27 risultati · pagina 1 di 2")
                .contains("data-customer=\"" + last + "\"")
                .doesNotContain("data-customer=\"" + first + "\"")
                .contains("pagina=2");
        assertThat(page1.indexOf("data-customer=\"" + last + "\""))
                .as("newest first")
                .isLessThan(page1.indexOf("data-customer=\"" + (last - 1) + "\""));
        String page2 = mvc.perform(get("/gestione/clienti/")
                        .param("q", marker)
                        .param("pagina", "2")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page2).contains("data-customer=\"" + first + "\"").contains("pagina 2 di 2");
        assertThat(mvc.perform(get("/gestione/clienti/")
                                .param("q", "Paginata " + marker + " 3")
                                .session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("1 risultati");
    }

    @Test
    void theCustomerPageListsItsOrders() throws Exception {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("pagina"), "ACTIVE", "Con Ordini S.r.l.");
        var order = orders.submit(
                ids.customerId(),
                ids.customerUserId(),
                OrderTestData.draft("DDT-PAGE-1", "Destinatario Pagina", "Monza", "20900"));
        String page = mvc.perform(
                        get("/gestione/clienti/" + ids.customerId() + "/").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("Con Ordini S.r.l.")
                .contains("data-order=\"" + order.tempCode() + "\"")
                .contains(">Destinatario Pagina<")
                .contains(">Monza<")
                .contains("/gestione/ordini/" + order.id() + "/");
        mvc.perform(get("/gestione/clienti/999999999/").session(session)).andExpect(status().isNotFound());
    }
}
