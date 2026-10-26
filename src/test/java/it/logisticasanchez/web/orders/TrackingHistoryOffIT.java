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
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import it.logisticasanchez.web.tracking.TrackingState;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ENH-05 with its flags off (the default, ADR 0025): the public answer is the M1 one —
 * {@code status} and {@code updatedAt} only, whatever happened to the shipment —, the customer's
 * order detail shows no timeline nor delivery day, and the tracking changes e-mail the customer
 * only at CONSEGNATO (EML-09), as in M1.
 */
@Tag("ENH-05")
@SpringBootTest
@AutoConfigureMockMvc
class TrackingHistoryOffIT extends AbstractPostgresIT {

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
    private FeatureFlags flags;

    @Autowired
    private ObjectMapper json;

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theTrackingIsTheM1One() throws Exception {
        assertThat(flags.isEnabled(FlagKey.TRACKING_HISTORY)).isFalse();
        assertThat(flags.isEnabled(FlagKey.TRACKING_NOTIFY_CUSTOMER)).isFalse();
        rateLimiter.reset();
        CustomerTestData.Ids ids = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("m1"), "ACTIVE");
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        OrderView order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        String code = "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        orders.approve(order.id(), operator.getId(), code, "90");
        outbox.deleteAll();

        orders.setTrackingState(order.id(), operator.getId(), 2);
        orders.setTrackingState(order.id(), operator.getId(), 3);
        orders.setTrackingState(order.id(), operator.getId(), 4);
        assertThat(outbox.findAll()).as("no tracking update e-mail").isEmpty();

        String body = mvc.perform(post("/mono/api/order/trace/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codice\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode answer = json.readTree(body);
        assertThat(new TreeSet<>(answer.propertyNames())).containsExactly("status", "updatedAt");
        assertThat(answer.get("status").asString()).isEqualTo("Assegnato");

        assertThat(mvc.perform(get("/area-clienti/ordini/" + code + "/").session(CustomerTestData.session(ids)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-tracking-timeline")
                .doesNotContain("data-delivery-day");

        orders.setTrackingState(order.id(), operator.getId(), TrackingState.CONSEGNATO);
        assertThat(outbox.findAll()).extracting(EmailOutbox::getKind).containsExactly("EML-09");
    }
}
