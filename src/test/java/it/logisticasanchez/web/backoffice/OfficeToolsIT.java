package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import it.logisticasanchez.web.tracking.TrackingState;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The office tools of ADR 0035 with their flags on: one tracking state on many pasted codes
 * (ENH-15: checked first, then one event per order with the same user and time and source BULK,
 * unknown and temporary codes reported, EML-09 only for CONSEGNATO and only where the state
 * changes); «Oggi» on the home (orders to approve, goods to pick up, shipments on their way for
 * too long); the free-text order search (recipient, city, DDT, customer); the CSV exports of the
 * order and customer lists, every value a text cell, each export audited (ENH-08).
 */
@Tag("ENH-08")
@Tag("ENH-15")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "ls.flags.override.[admin.dashboard]=true",
            "ls.flags.override.[admin.exports]=true",
            "ls.flags.override.[orders.bulkTracking]=true"
        })
class OfficeToolsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private StaffUserRepository staffUsers;

    private StaffUser operator;
    private MockHttpSession staff;
    private CustomerTestData.Ids customer;
    private String tag;

    @BeforeEach
    void data() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        staff = StaffTestData.session(operator);
        tag = UUID.randomUUID().toString().substring(0, 8);
        customer = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("ufficio"), "ACTIVE", "Mittente Strumenti " + tag + " S.r.l.");
        outbox.deleteAll();
    }

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void oneStateOnManyCodesIsCheckedThenSetWithOneEventEach() throws Exception {
        OrderView first = approved("BLK1-" + tag);
        OrderView second = approved("BLK2-" + tag);
        OrderView delivered = approved("BLK3-" + tag);
        orders.setTrackingState(delivered.id(), operator.getId(), TrackingState.CONSEGNATO);
        OrderView pending = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft("DDT-BLK-" + tag, "Ricevente", "Monza", "20900"));
        outbox.deleteAll();
        String pasted = first.code() + "\n" + second.code().toLowerCase() + ", " + first.code() + "; "
                + delivered.code() + "\n" + pending.tempCode() + "\nNON-ESISTE-" + tag;

        String preview = page(post("/gestione/tracking/in-blocco")
                .param("codici", pasted)
                .param("stato", "5")
                .param("action", "preview"));
        assertThat(preview)
                .contains("data-bulk-preview")
                .contains("data-order=\"" + first.code() + "\"")
                .contains("data-order=\"" + second.code() + "\"")
                .contains("data-order=\"" + delivered.code() + "\"")
                .contains("Spedizioni trovate: 3")
                .contains("data-bulk-not-found")
                .contains(pending.tempCode())
                .contains("NON-ESISTE-" + tag)
                .contains("Già in questo stato, non cambiano: 1.")
                .contains("Imposta «Consegnato» su 2 spedizioni");
        assertThat(state(first)).as("nothing changes before the confirmation").isEqualTo(TrackingState.PRESA);

        mvc.perform(post("/gestione/tracking/in-blocco")
                        .session(staff)
                        .with(csrf())
                        .param("codici", pasted)
                        .param("stato", "5")
                        .param("action", "apply"))
                .andExpect(redirectedUrl("/gestione/tracking/"));
        assertThat(state(first)).isEqualTo(TrackingState.CONSEGNATO);
        assertThat(state(second)).isEqualTo(TrackingState.CONSEGNATO);
        List<Map<String, Object>> events = jdbc.queryForList(
                "select order_id, source, staff_user_id, occurred_at from tracking_event"
                        + " where order_id in (?, ?, ?) and state_id = 5 and source = 'BULK' order by order_id",
                first.id(),
                second.id(),
                delivered.id());
        assertThat(events)
                .extracting(e -> ((Number) e.get("order_id")).longValue())
                .containsExactly(first.id(), second.id());
        assertThat(events)
                .extracting(e -> ((Number) e.get("staff_user_id")).longValue())
                .containsOnly(operator.getId());
        assertThat(events.get(0).get("occurred_at")).isEqualTo(events.get(1).get("occurred_at"));
        assertThat(jdbc.queryForObject("select status from shipment_order where id = ?", String.class, pending.id()))
                .isEqualTo("PENDING");
        assertThat(outbox.findAll())
                .as("EML-09 for the two orders that became CONSEGNATO, none for the one already delivered")
                .extracting(EmailOutbox::getKind)
                .containsExactly("EML-09", "EML-09");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.tracking' and actor_id = ?"
                                + " and after->>'source' = 'BULK'",
                        Long.class,
                        operator.getId()))
                .isEqualTo(2);

        assertThat(page(post("/gestione/tracking/in-blocco")
                        .param("codici", first.code() + " " + delivered.code())
                        .param("stato", "5")
                        .param("action", "preview")))
                .as("nothing left to change: no confirmation button")
                .contains("Già in questo stato, non cambiano: 2.")
                .doesNotContain("value=\"apply\"");
        assertThat(page(get("/gestione/tracking/")))
                .contains("<option value=\"\">Scegli lo stato</option>")
                .doesNotContain("data-bulk-preview");

        assertThat(page(post("/gestione/tracking/in-blocco")
                        .param("codici", "NON-ESISTE-" + tag)
                        .param("stato", "2")
                        .param("action", "apply")))
                .contains("Nessun codice dell&#39;elenco è di una spedizione confermata.");
    }

    @Test
    void todayListsWhatToApproveToPickUpAndWhatTravelsForTooLong() throws Exception {
        OrderView toApprove = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft("DDT-OGGI-" + tag, "Ricevente Oggi", "Lodi", "26900"));
        OrderView toPickUp = approved("PICK-" + tag);
        OrderView stuck = approved("STUCK-" + tag);
        orders.setTrackingState(stuck.id(), operator.getId(), 2);
        OrderView recent = approved("FRESH-" + tag);
        orders.setTrackingState(recent.id(), operator.getId(), 2);
        // the oldest of each group, so they lead the lists whatever other tests left behind
        jdbc.update(
                "update shipment_order set submitted_at = ? where id = ?",
                Timestamp.from(Instant.parse("2020-01-01T08:00:00Z")),
                toApprove.id());
        jdbc.update(
                "update shipment_order set approved_at = ? where id in (?, ?)",
                Timestamp.from(Instant.parse("2020-01-02T08:00:00Z")),
                toPickUp.id(),
                stuck.id());

        String home = mvc.perform(get("/gestione/").session(staff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(home).contains("data-today").contains("In viaggio da più di 3 giorni");
        assertThat(group(home, "toApprove")).contains(toApprove.tempCode());
        assertThat(group(home, "toPickUp")).contains(toPickUp.code()).doesNotContain(stuck.code());
        assertThat(group(home, "stuck"))
                .contains(stuck.code())
                .doesNotContain(recent.code())
                .doesNotContain(toPickUp.code());
    }

    @Test
    void theOrderSearchFindsTheRecipientTheCityTheTransportDocumentAndTheCustomer() throws Exception {
        OrderView order = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft("DDT-CERCA-" + tag, "Ricevente Zeta " + tag, "Vimercate " + tag, "20871"));
        for (String text : List.of("zeta " + tag, "VIMERCATE " + tag, "ddt-cerca-" + tag, "strumenti " + tag)) {
            assertThat(page(get("/gestione/ordini/").param("stato", "").param("codice", text)))
                    .as("search «%s»", text)
                    .contains("data-order=\"" + order.tempCode() + "\"")
                    .contains("Codice, destinatario, città, DDT o cliente");
        }
        assertThat(page(get("/gestione/ordini/").param("stato", "").param("codice", "nessuno-" + tag)))
                .doesNotContain("data-order=\"" + order.tempCode() + "\"");
    }

    @Test
    void theListsExportAsCsvWithEveryValueAsText() throws Exception {
        OrderView order = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft(
                        "DDT-CSV-" + tag, "=HYPERLINK(\"http://x.example\",\"" + tag + "\")", "Como", "22100"));
        assertThat(page(get("/gestione/ordini/").param("stato", "").param("codice", tag)))
                .contains("href=\"/gestione/ordini/esporta?stato=&amp;tracking=&amp;cliente=&amp;da=&amp;a=&amp;codice="
                        + tag + "\"");

        MvcResult result = mvc.perform(get("/gestione/ordini/esporta")
                        .session(staff)
                        .param("stato", "")
                        .param("codice", "DDT-CSV-" + tag))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.matchesPattern(
                                        "attachment; filename=\"ordini-\\d{4}-\\d{2}-\\d{2}\\.csv\"")))
                .andReturn();
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).startsWith("﻿\"ID\";\"Codice\";\"Codice provvisorio\";\"Inviato il\"");
        assertThat(lines).hasSize(2);
        assertThat(lines[1])
                .contains("\"" + order.tempCode() + "\"")
                .contains("\"DDT-CSV-" + tag + "\"")
                .contains("\"'=HYPERLINK(\"\"http://x.example\"\",\"\"" + tag + "\"\")\"")
                .contains("\"In attesa di conferma\"");

        // chapter 10 §4: a company name that starts with =HYPERLINK( stays text
        String company = "=HYPERLINK(\"http://x.example\",\"Ditta " + tag + "\")";
        CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("formula"), "ACTIVE", company);
        String customers = mvc.perform(
                        get("/gestione/clienti/esporta").session(staff).param("q", "ditta " + tag))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(customers.split("\r\n")).hasSize(2);
        assertThat(customers)
                .contains("\"'" + company.replace("\"", "\"\"") + "\"")
                .contains("\"Attivo\"");
        assertThat(page(get("/gestione/clienti/").param("q", tag))).contains("/gestione/clienti/esporta?q=" + tag);

        assertThat(jdbc.queryForList(
                        "select action || ':' || (after->>'rows') from audit_log where actor_id = ?"
                                + " and action like '%.export' order by id",
                        String.class, operator.getId()))
                .containsExactly("orders.export:1", "customers.export:1");
    }

    private OrderView approved(String code) {
        OrderView order = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft("DDT-" + code, "Ricevente " + code, "Milano", "20121"));
        return orders.approve(order.id(), operator.getId(), code, "100");
    }

    private short state(OrderView order) {
        return jdbc.queryForObject(
                "select tracking_state_id from shipment_order where id = ?", Short.class, order.id());
    }

    /** The markup of one «Oggi» group of the home. */
    private static String group(String home, String name) {
        String start = "data-today-group=\"" + name + "\"";
        String rest = home.substring(home.indexOf(start));
        return rest.substring(0, rest.indexOf("</section>"));
    }

    private String page(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.session(staff).with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
