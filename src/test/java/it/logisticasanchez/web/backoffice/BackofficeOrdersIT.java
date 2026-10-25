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
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.time.LocalDate;
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
 * The order screens of the back office (ADM-04…ADM-08; chapter 04 §3.4–3.5, §4.4–4.8): pending list
 * with the 13 legacy columns and «-» for a temporary code; «Approva ordine» with code and Italian
 * amount, a duplicate code refused on the form; decline; registered list with more than one page,
 * filters and search by code; tracking from the row and by code with one event per change and
 * EML-09 only when the order becomes CONSEGNATO; the internal note apart from the customer's;
 * the detail with the three legacy sections and the lines grouped with their quantity.
 */
@Tag("ADM-04")
@Tag("ADM-05")
@Tag("ADM-06")
@Tag("ADM-07")
@Tag("ADM-08")
@Tag("EML-09")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeOrdersIT extends AbstractPostgresIT {

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
    private CustomerTestData.Ids customer;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        session = StaffTestData.session(operator);
        customer = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("ordini"), "ACTIVE", "Mittente Ordini S.r.l.");
    }

    private OrderView pending(String ddt, String recipient, String city) {
        OrderView order = orders.submit(
                customer.customerId(), customer.customerUserId(), OrderTestData.draft(ddt, recipient, city, "20121"));
        outbox.deleteAll();
        return order;
    }

    private String code() {
        return "LS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String html(String path, String... params) throws Exception {
        var request = get(path).session(session);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void pendingOrdersShowTheLegacyColumnsWithADashForTheTemporaryCodeAndApprovalAssignsCodeAndAmount()
            throws Exception {
        OrderView order = pending("DDT-BO-1", "Magazzino <i>Nord</i>", "Milano");
        String page = html("/gestione/ordini/in-approvazione/");
        for (String column : List.of(
                "ID",
                "Codice",
                "Cliente mittente",
                "Codice Doc. Trasporto",
                "Destinatario",
                "Indirizzo",
                "Città (Prov)",
                "Cap",
                "Telefono",
                "Importo",
                "Ser. Accessorio",
                "Stato",
                "Tracking")) {
            assertThat(page).contains(">" + column + "<");
        }
        assertThat(page)
                .contains("data-order=\"" + order.tempCode() + "\"")
                .contains("/gestione/ordini/" + order.id() + "/\">-</a>")
                .contains("Magazzino &lt;i&gt;Nord&lt;/i&gt;")
                .contains("Sponda idraulica")
                .contains("Approva ordine")
                .doesNotContain("Nessun ordine da approvare");

        String form = html("/gestione/ordini/" + order.id() + "/approva");
        assertThat(form).contains("Importo Totale (per cliente)").contains(">Codice<");

        String code = code();
        mvc.perform(post("/gestione/ordini/" + order.id() + "/approva")
                        .session(session)
                        .with(csrf())
                        .param("codice", " " + code + " ")
                        .param("importo", "1.180,50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/gestione/ordini/in-approvazione/"));
        OrderView approved = orders.find(order.id()).orElseThrow();
        assertThat(approved.code()).isEqualTo(code);
        assertThat(approved.amount()).isEqualByComparingTo("1180.50");
        assertThat(approved.trackingLabel()).isEqualTo("Presa in carico");
        assertThat(outbox.findAll())
                .singleElement()
                .satisfies(m -> assertThat(m.getKind()).isEqualTo("EML-05"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.approve' and actor_id = ? and entity_ref = ?",
                        Long.class,
                        operator.getId(),
                        code))
                .isEqualTo(1);

        OrderView second = pending("DDT-BO-2", "Secondo", "Como");
        String refused = mvc.perform(post("/gestione/ordini/" + second.id() + "/approva")
                        .session(session)
                        .with(csrf())
                        .param("codice", code.toLowerCase())
                        .param("importo", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(refused)
                .contains("data-error")
                .contains("già stata effettuata una registrazione con questo codice")
                .contains("value=\"" + code.toLowerCase() + "\"");
        assertThat(orders.find(second.id()).orElseThrow().code()).isNull();
        String badAmount = mvc.perform(post("/gestione/ordini/" + second.id() + "/approva")
                        .session(session)
                        .with(csrf())
                        .param("codice", code())
                        .param("importo", "0"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(badAmount).contains("Inserisci un importo maggiore di zero");

        mvc.perform(post("/gestione/ordini/" + second.id() + "/rifiuta")
                        .session(session)
                        .with(csrf())
                        .param("motivo", "Merce non trasportabile"))
                .andExpect(status().is3xxRedirection());
        assertThat(orders.find(second.id()).orElseThrow().declineReason()).isEqualTo("Merce non trasportabile");
        assertThat(outbox.findAll())
                .singleElement()
                .satisfies(m -> assertThat(m.getKind()).isEqualTo("EML-05"));
        assertThat(html("/gestione/ordini/in-approvazione/"))
                .doesNotContain("data-order=\"" + second.tempCode() + "\"");
    }

    @Test
    void registeredOrdersArePaginatedFilteredAndSearchableByCode() throws Exception {
        CustomerTestData.Ids other = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE", "Altro Mittente S.r.l.");
        String marker = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String firstCode = null;
        String lastCode = null;
        for (int i = 0; i < 26; i++) {
            OrderView o = pending("DDT-PAG-" + i, "Dest " + i, "Lecco");
            String code = "PAG-" + marker + "-" + String.format("%02d", i);
            orders.approve(o.id(), operator.getId(), code, "10");
            if (i == 0) {
                firstCode = code;
            }
            lastCode = code;
        }
        OrderView otherOrder = orders.submit(
                other.customerId(),
                other.customerUserId(),
                OrderTestData.draft("DDT-ALTRO", "Dest Altro", "Como", "22100"));
        String otherCode = "ALTRO-" + marker;
        orders.approve(otherOrder.id(), operator.getId(), otherCode, "20");
        orders.setTrackingState(otherOrder.id(), operator.getId(), 5);

        String page1 = html("/gestione/ordini/", "codice", marker);
        assertThat(page1)
                .contains("27 risultati · pagina 1 di 2")
                .contains("data-order=\"" + otherCode + "\"")
                .contains("data-order=\"" + lastCode + "\"")
                .doesNotContain("data-order=\"" + firstCode + "\"")
                .contains("pagina=2");
        assertThat(page1.indexOf("data-order=\"" + otherCode + "\""))
                .as("newest first")
                .isLessThan(page1.indexOf("data-order=\"" + lastCode + "\""));
        assertThat(html("/gestione/ordini/", "codice", marker, "pagina", "2"))
                .contains("pagina 2 di 2")
                .contains("data-order=\"" + firstCode + "\"");

        assertThat(html("/gestione/ordini/", "codice", marker, "tracking", "5"))
                .contains("1 risultati")
                .contains("data-order=\"" + otherCode + "\"");
        assertThat(html("/gestione/ordini/", "codice", marker, "cliente", Long.toString(other.customerId())))
                .contains("1 risultati");
        assertThat(html("/gestione/ordini/", "codice", marker, "stato", "PENDING"))
                .contains("Nessun risultato");
        assertThat(html("/gestione/ordini/", "codice", marker, "stato", "")).contains("27 risultati");
        String today = LocalDate.now(ItalianFormats.ROME).toString();
        assertThat(html("/gestione/ordini/", "codice", marker, "da", today, "a", today))
                .contains("27 risultati");
        assertThat(html("/gestione/ordini/", "codice", marker, "a", "2024-01-01"))
                .contains("Nessun risultato");
        assertThat(html("/gestione/ordini/", "codice", firstCode.toLowerCase())).contains("1 risultati");
        assertThat(html("/gestione/ordini/", "da", "2025-13-45"))
                .contains("data-error")
                .contains("data valida");
    }

    @Test
    void trackingFromTheRowAndByCodeWritesOneEventPerChangeAndMailsOnlyAtConsegnato() throws Exception {
        OrderView order = pending("DDT-TRK", "Tracciato", "Monza");
        mvc.perform(post("/gestione/ordini/" + order.id() + "/tracking")
                        .session(session)
                        .with(csrf())
                        .param("stato", "2"))
                .andExpect(status().isOk()); // pending: refused, re-rendered with the error
        String code = code();
        orders.approve(order.id(), operator.getId(), code, "50");
        outbox.deleteAll();

        String detail = html("/gestione/ordini/" + order.id() + "/");
        assertThat(detail).contains("<option value=\"1\" selected=\"selected\">Presa in carico</option>");
        mvc.perform(post("/gestione/ordini/" + order.id() + "/tracking")
                        .session(session)
                        .with(csrf())
                        .param("stato", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/gestione/ordini/" + order.id() + "/"));
        assertThat(outbox.findAll()).isEmpty();

        String byCode = html("/gestione/tracking/", "codice", " " + code.toLowerCase() + " ");
        assertThat(byCode).contains("data-order=\"" + code + "\"").contains("data-current>Transito<");
        assertThat(html("/gestione/tracking/", "codice", "NON-ESISTE")).contains("data-not-found");
        mvc.perform(post("/gestione/tracking/")
                        .session(session)
                        .with(csrf())
                        .param("codice", code)
                        .param("stato", "5"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/gestione/tracking/")
                        .session(session)
                        .with(csrf())
                        .param("codice", code)
                        .param("stato", "5"))
                .andExpect(status().is3xxRedirection());
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-09");
        assertThat(orders.trackingHistory(order.id()))
                .extracting(OrderView.TrackingChange::stateId)
                .containsExactly((short) 1, (short) 2, (short) 5);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.tracking' and actor_id = ?",
                        Long.class,
                        operator.getId()))
                .isEqualTo(2);
        assertThat(html("/gestione/ordini/" + order.id() + "/"))
                .contains("data-history")
                .contains("Consegnato");
    }

    @Test
    void theInternalNoteIsKeptApartFromTheCustomersNoteAndTheDetailShowsTheLegacySections() throws Exception {
        OrderView order = pending("DDT-NOTE", "Annotato", "Bergamo");
        mvc.perform(post("/gestione/ordini/" + order.id() + "/note")
                        .session(session)
                        .with(csrf())
                        .param("nota", "Chiamare prima\ndel ritiro"))
                .andExpect(status().is3xxRedirection());
        String detail = html("/gestione/ordini/" + order.id() + "/");
        assertThat(detail)
                .contains("Chiamare prima\ndel ritiro</textarea>")
                .contains("data-customer-notes>Consegna al mattino<")
                .contains("Dettaglio Spedizione")
                .contains("Dettaglio Colli")
                .contains(">Pallets<")
                .contains("data-section=\"packages\"")
                .contains("data-table=\"lines\"")
                .contains("Approva ordine");
        assertThat(detail.split("<tr>\n")).as("one row per line, not per unit").hasSizeGreaterThan(1);
        assertThat(jdbc.queryForObject(
                        "select body from order_internal_note where order_id = ?", String.class, order.id()))
                .isEqualTo("Chiamare prima\ndel ritiro");
        assertThat(jdbc.queryForObject(
                        "select customer_notes from shipment_order where id = ?", String.class, order.id()))
                .isEqualTo("Consegna al mattino");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log l join audit_log_detail d on d.audit_log_id = l.id"
                                + " where l.action = 'order.note' and l.entity_id = ?",
                        Long.class,
                        order.id()))
                .isEqualTo(1);
        // the lines of the worked example are three, grouped with their quantity (2, 1, 3)
        int lines = detail.split("\">Epal</td>|\">Pallet Generico/Colli</td>").length - 1;
        assertThat(lines).isEqualTo(3);
        assertThat(detail).contains("data-label=\"Quantità\">2</td>").contains("data-label=\"Quantità\">3</td>");
        mvc.perform(post("/gestione/ordini/" + order.id() + "/note")
                        .session(session)
                        .with(csrf())
                        .param("nota", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject(
                        "select count(*) from order_internal_note where order_id = ?", Long.class, order.id()))
                .isEqualTo(0);
    }
}
