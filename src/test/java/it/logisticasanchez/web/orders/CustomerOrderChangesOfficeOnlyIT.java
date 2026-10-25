package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
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
 * CUS-07 with the Q-12 seam set back to OFFICE_ONLY (the M1 behaviour of chapter 09 CUS-05): the
 * detail page tells how to ask the office, the change pages lead back to it and the service
 * refuses the change.
 */
@Tag("CUS-07")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.orders.customer-changes=OFFICE_ONLY")
class CustomerOrderChangesOfficeOnlyIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private EmailOutboxRepository outbox;

    @Test
    void changesStayWithTheOffice() throws Exception {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("ufficio"), "ACTIVE", "Mittente Ufficio S.r.l.");
        MockHttpSession session = CustomerTestData.session(ids);
        OrderView order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        outbox.deleteAll();
        String base = "/area-clienti/ordini/" + order.tempCode() + "/";
        assertThat(orders.customerSelfService()).isFalse();
        assertThat(mvc.perform(get(base).session(session).accept("text/html"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Per modificare o annullare un ordine in attesa scrivi a")
                .doesNotContain("href=\"" + base + "modifica\"")
                .doesNotContain("href=\"" + base + "annulla\"");
        mvc.perform(get(base + "modifica").session(session)).andExpect(redirectedUrl(base));
        mvc.perform(get(base + "annulla").session(session)).andExpect(redirectedUrl(base));
        mvc.perform(post(base + "annulla").session(session).with(csrf()).param("versione", "0"))
                .andExpect(redirectedUrl(base));
        assertThatThrownBy(() -> orders.cancelByCustomer(ids.customerId(), ids.customerUserId(), order.tempCode(), 0))
                .isInstanceOf(RuleViolationException.class);
        assertThat(jdbc.queryForObject("select status from shipment_order where id = ?", String.class, order.id()))
                .isEqualTo("PENDING");
        assertThat(outbox.findAll()).isEmpty();
    }
}
