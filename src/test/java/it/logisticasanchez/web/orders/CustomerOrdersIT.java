package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CUS-05: «I miei ordini» lists only the signed-in customer's orders (temporary code while
 * pending, definitive code, tracking and amount once approved); the detail opens by code or
 * temporary code, ignoring case; another customer's code and an unknown code are 404.
 */
@Tag("CUS-05")
@SpringBootTest
@AutoConfigureMockMvc
class CustomerOrdersIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private StaffUserRepository staff;

    @Autowired
    private EmailOutboxRepository outbox;

    @Test
    void aCustomerSeesOnlyItsOwnOrders() throws Exception {
        CustomerTestData.Ids a =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("a"), "ACTIVE", "Cliente A S.r.l.");
        CustomerTestData.Ids b =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("b"), "ACTIVE", "Cliente B S.r.l.");
        OrderView pendingA = orders.submit(a.customerId(), a.customerUserId(), OrderTestSupport.exampleDraft());
        OrderView approvedA = orders.submit(a.customerId(), a.customerUserId(), OrderTestSupport.exampleDraft());
        OrderView orderB = orders.submit(b.customerId(), b.customerUserId(), OrderTestSupport.exampleDraft());
        long staffId = staff.save(new StaffUser(
                        "op-" + UUID.randomUUID().toString().substring(0, 8), "Operatore", StaffRole.OPERATOR))
                .getId();
        String code = "LS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        approvedA = orders.approve(approvedA.id(), staffId, code, "180,50");
        outbox.deleteAll();

        String list = mvc.perform(get("/area-clienti/ordini/")
                        .session(CustomerTestData.session(a))
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(list)
                .contains("data-order=\"" + pendingA.tempCode() + "\"")
                .contains("data-order=\"" + code + "\"")
                .doesNotContain(orderB.tempCode())
                .contains("In attesa di conferma")
                .contains("Confermato")
                .contains("Presa in carico")
                .contains("180,50 €")
                .contains("href=\"/area-clienti/ordini/" + code + "/\"");

        mvc.perform(get("/area-clienti/ordini/" + pendingA.tempCode().toLowerCase() + "/")
                        .session(CustomerTestData.session(a))
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Ordine " + pendingA.tempCode())))
                .andExpect(content().string(Matchers.containsString("DDT-2026-0042")))
                .andExpect(content().string(Matchers.containsString("Magazzino Destinatario S.r.l.")))
                .andExpect(content().string(Matchers.containsString("1.236,00 kg · 4,53 m³")))
                .andExpect(content().string(Matchers.containsString("in attesa di conferma")));
        mvc.perform(get("/area-clienti/ordini/" + code + "/")
                        .session(CustomerTestData.session(a))
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Presa in carico")))
                .andExpect(content().string(Matchers.containsString("180,50 €")));
        // the approved order is also reachable by its temporary code
        mvc.perform(get("/area-clienti/ordini/" + approvedA.tempCode() + "/").session(CustomerTestData.session(a)))
                .andExpect(status().isOk());

        // another customer's order and an unknown code are 404, through the error model
        mvc.perform(get("/area-clienti/ordini/" + orderB.tempCode() + "/")
                        .session(CustomerTestData.session(a))
                        .accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("Pagina non trovata")));
        mvc.perform(get("/area-clienti/ordini/" + code + "/").session(CustomerTestData.session(b)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/area-clienti/ordini/TEMP-NESSUNO/").session(CustomerTestData.session(a)))
                .andExpect(status().isNotFound());
        // the internal note never reaches the customer
        orders.setInternalNote(pendingA.id(), staffId, "Nota riservata dell'ufficio");
        mvc.perform(get("/area-clienti/ordini/" + pendingA.tempCode() + "/")
                        .session(CustomerTestData.session(a))
                        .accept("text/html"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Nota riservata"))));
    }
}
