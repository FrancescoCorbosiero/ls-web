package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The office tools with their flags off (the default, ADR 0025): no export, no bulk change, no
 * «Oggi»; the order list's text field searches the codes only, as in M1.
 */
@Tag("ENH-08")
@Tag("ENH-15")
@SpringBootTest
@AutoConfigureMockMvc
class OfficeToolsOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private FeatureFlags flags;

    @Test
    void theBackOfficeIsTheM1One() throws Exception {
        assertThat(flags.isEnabled(FlagKey.ADMIN_DASHBOARD)).isFalse();
        assertThat(flags.isEnabled(FlagKey.ADMIN_EXPORTS)).isFalse();
        assertThat(flags.isEnabled(FlagKey.ORDERS_BULK_TRACKING)).isFalse();
        MockHttpSession staff = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        CustomerTestData.Ids customer =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("m1-ufficio"), "ACTIVE");
        OrderView order = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft("DDT-M1-" + tag, "Ricevente M1 " + tag, "Monza", "20900"));

        mvc.perform(get("/gestione/ordini/esporta").session(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/gestione/clienti/esporta").session(staff)).andExpect(status().isNotFound());
        mvc.perform(post("/gestione/tracking/in-blocco")
                        .session(staff)
                        .with(csrf())
                        .param("codici", "TEST-0001")
                        .param("stato", "2")
                        .param("action", "apply"))
                .andExpect(status().isNotFound());

        assertThat(page(staff, "/gestione/")).doesNotContain("data-today");
        assertThat(page(staff, "/gestione/tracking/")).doesNotContain("data-bulk");
        assertThat(page(staff, "/gestione/clienti/")).doesNotContain("data-export");
        String list = mvc.perform(get("/gestione/ordini/")
                        .session(staff)
                        .param("stato", "")
                        .param("codice", "Ricevente M1 " + tag))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(list)
                .doesNotContain("data-export")
                .contains("placeholder=\"Codice definitivo o provvisorio\"")
                .doesNotContain("data-order=\"" + order.tempCode() + "\"");
        assertThat(mvc.perform(get("/gestione/ordini/")
                                .session(staff)
                                .param("stato", "")
                                .param("codice", order.tempCode()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("data-order=\"" + order.tempCode() + "\"");
    }

    private String page(MockHttpSession staff, String path) throws Exception {
        return mvc.perform(get(path).session(staff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
