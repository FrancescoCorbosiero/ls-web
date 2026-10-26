package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import it.logisticasanchez.web.tracking.TrackingState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ENH-05 with {@code tracking.history} and {@code tracking.notifyCustomer} on (ADR 0034): the
 * public tracking answer gains the delivery day and the states with their times, newest first,
 * and still nothing personal (TRK-03 allow-list); the customer's order detail lists the states
 * with their dates and the delivery day; the customer gets one EML-10 at each change to
 * TRANSITO, TERMINAL_DEST or ASSEGNATO and none for a state set again, for PRESA (EML-05) or
 * NON_GESTITO, while CONSEGNATO keeps EML-09 only.
 */
@Tag("ENH-05")
@Tag("TRK-03")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {"ls.flags.override.[tracking.history]=true", "ls.flags.override.[tracking.notifyCustomer]=true"})
class TrackingHistoryIT extends AbstractPostgresIT {

    /** Monday 3 March 2025, 10:00 in Rome (before any run of this test): to Milano, zone 1, expected on Tuesday 4. */
    private static final Instant APPROVED = Instant.parse("2025-03-03T09:00:00Z");

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

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ObjectMapper json;

    private CustomerTestData.Ids ids;
    private StaffUser operator;
    private OrderView order;
    private String code;

    @BeforeEach
    void approvedOrder() {
        rateLimiter.reset();
        ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("tracking"), "ACTIVE", "Mittente Tracking S.r.l.");
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        code = "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        orders.approve(order.id(), operator.getId(), code, "180,50");
        jdbc.update("update shipment_order set approved_at = ? where id = ?", Timestamp.from(APPROVED), order.id());
        jdbc.update(
                "update tracking_event set occurred_at = ? where order_id = ?", Timestamp.from(APPROVED), order.id());
        outbox.deleteAll();
    }

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void publicTrackingAddsTheDeliveryDayAndTheStatesWithinTheAllowList() throws Exception {
        orders.setTrackingState(order.id(), operator.getId(), 2);
        JsonNode answer = trace(code);
        assertThat(fieldNames(answer)).containsExactlyInAnyOrder("status", "updatedAt", "eta", "events");
        assertThat(answer.get("status").asString()).isEqualTo("Transito");
        assertThat(answer.get("eta").asString()).isEqualTo("2025-03-04");
        JsonNode events = answer.get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("status").asString()).as("newest first").isEqualTo("Transito");
        assertThat(events.get(1).get("status").asString()).isEqualTo("Presa in carico");
        assertThat(events.get(1).get("time").asString()).isEqualTo("2025-03-03T09:00:00Z");
        for (JsonNode event : events) {
            assertThat(fieldNames(event)).isSubsetOf("time", "status", "location");
        }
        String raw = answer.toString();
        assertThat(raw)
                .as("nothing personal leaves the public answer (TRK-03)")
                .doesNotContain("Magazzino Destinatario")
                .doesNotContain("Via Roma")
                .doesNotContain("20121")
                .doesNotContain("Milano")
                .doesNotContain("DDT-2026-0042")
                .doesNotContain("destinatario.example")
                .doesNotContain("12345678")
                .doesNotContain("Consegna al mattino")
                .doesNotContain("Mittente Tracking");

        // delivered: the day it happened
        orders.setTrackingState(order.id(), operator.getId(), TrackingState.CONSEGNATO);
        jdbc.update(
                "update shipment_order set tracking_updated_at = ? where id = ?",
                Timestamp.from(Instant.parse("2026-10-28T15:00:00Z")),
                order.id());
        JsonNode delivered = trace(code);
        assertThat(delivered.get("status").asString()).isEqualTo("Consegnato");
        assertThat(delivered.get("eta").asString()).isEqualTo("2026-10-28");
        assertThat(delivered.get("events")).hasSize(3);

        // an unknown code still answers the legacy «Non gestito» alone
        assertThat(fieldNames(trace("NON-ESISTE-" + code))).containsExactly("status");
    }

    @Test
    void noDeliveryDayWhereTheTimesAreAgreedCaseByCase() throws Exception {
        jdbc.update("update shipment_order set recipient_postal_code = '80073' where id = ?", order.id()); // Capri
        JsonNode answer = trace(code);
        assertThat(fieldNames(answer)).containsExactlyInAnyOrder("status", "updatedAt", "events");
    }

    @Test
    void theCustomersOrderDetailListsTheStatesWithTheirDatesAndTheDeliveryDay() throws Exception {
        orders.setTrackingState(order.id(), operator.getId(), 2);
        MockHttpSession session = CustomerTestData.session(ids);
        String page = mvc.perform(get("/area-clienti/ordini/" + code + "/")
                        .session(session)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("data-tracking-timeline")
                .contains("data-delivery-day")
                .contains("Consegna prevista")
                .contains("martedì 4 marzo 2025")
                .contains("03/03/2025 10:00")
                .contains(">Presa in carico<")
                .contains(">In transito<");

        orders.setTrackingState(order.id(), operator.getId(), TrackingState.CONSEGNATO);
        assertThat(mvc.perform(get("/area-clienti/ordini/" + code + "/").session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains(">Consegnata<")
                .doesNotContain("Indicativa:");
    }

    @Test
    void theCustomerIsToldOnceAtEveryChangeTheOtherEmailsDoNotCover() {
        orders.setTrackingState(order.id(), operator.getId(), 2);
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        EmailOutbox transit = mails.get(0);
        assertThat(transit.getKind()).isEqualTo("EML-10");
        assertThat(transit.getRecipient()).isEqualTo(ids.email());
        assertThat(transit.getSubject())
                .isEqualTo("Logistica Sanchez - Aggiornamento spedizione N. " + code + ": In transito");
        assertThat(transit.getBodyText())
                .contains("in viaggio verso il terminal della zona di destinazione")
                .contains("Consegna prevista (indicativa)")
                .contains("martedì 4 marzo 2025")
                .contains("DDT-2026-0042")
                .contains("/tracking/?code=" + code);

        orders.setTrackingState(order.id(), operator.getId(), 2); // the same state again: no event, no e-mail
        assertThat(kinds()).containsExactly("EML-10");
        orders.setTrackingState(order.id(), operator.getId(), 3);
        orders.setTrackingState(order.id(), operator.getId(), 4);
        assertThat(kinds()).containsExactly("EML-10", "EML-10", "EML-10");
        assertThat(outbox.findAll())
                .extracting(EmailOutbox::getSubject)
                .anyMatch(s -> s.endsWith(": Arrivata al terminal di destinazione"))
                .anyMatch(s -> s.endsWith(": Affidata al mezzo per la consegna"));

        orders.setTrackingState(order.id(), operator.getId(), TrackingState.CONSEGNATO);
        assertThat(kinds()).containsExactly("EML-10", "EML-10", "EML-10", "EML-09");
        // corrections backwards tell nothing new
        orders.setTrackingState(order.id(), operator.getId(), TrackingState.PRESA);
        orders.setTrackingState(order.id(), operator.getId(), TrackingState.NON_GESTITO);
        assertThat(kinds()).hasSize(4);
    }

    private List<String> kinds() {
        return outbox.findAll().stream()
                .sorted(java.util.Comparator.comparing(EmailOutbox::getId))
                .map(EmailOutbox::getKind)
                .toList();
    }

    private JsonNode trace(String codice) throws Exception {
        String body = mvc.perform(post("/mono/api/order/trace/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("codice", codice))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }
}
