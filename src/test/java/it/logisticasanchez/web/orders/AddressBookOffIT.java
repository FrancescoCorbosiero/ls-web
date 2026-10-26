package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ENH-07 with its flags off (the default, ADR 0025): the app is M1 — the address book routes and
 * «Ordina di nuovo» answer 404, the profile, the order forms and the order pages show nothing new,
 * «Usa questo destinatario» posted by hand is read as M1 reads an unknown button (save), a ticked
 * «Salva il destinatario» stores nothing, and {@code ?ripeti=} opens a blank staff form.
 */
@Tag("ENH-07")
@SpringBootTest
@AutoConfigureMockMvc
class AddressBookOffIT extends AbstractPostgresIT {

    private static final String NEW = "/area-clienti/ordini/nuovo/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private AddressBookService book;

    @Autowired
    private FeatureFlags flags;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;

    @BeforeEach
    void customer() {
        assertThat(flags.isEnabled(FlagKey.CUSTOMERS_ADDRESS_BOOK)).isFalse();
        assertThat(flags.isEnabled(FlagKey.ORDERS_REPEAT)).isFalse();
        ids = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("spento"), "ACTIVE");
        session = CustomerTestData.session(ids);
    }

    /** The orders sent here queue e-mails the worker (off in tests) never sends: leave no queue behind. */
    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theRoutesDoNotExistAndThePagesAreTheM1Ones() throws Exception {
        OrderView order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        mvc.perform(get("/area-clienti/profilo/rubrica/nuovo").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/area-clienti/profilo/rubrica/nuovo")
                        .session(session)
                        .with(csrf())
                        .param("name", "Mai Salvato"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/area-clienti/profilo/rubrica/1").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/area-clienti/profilo/rubrica/1/elimina")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/area-clienti/ordini/" + order.tempCode() + "/ripeti")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // the markup of the feature, not its words: the templates' own comments may name it
        assertThat(html("/area-clienti/profilo/", session))
                .doesNotContain("id=\"rubrica\"")
                .doesNotContain("/area-clienti/profilo/rubrica/");
        assertThat(html(NEW, session))
                .doesNotContain("data-address-book")
                .doesNotContain("name=\"saveRecipient\"")
                .doesNotContain("value=\"useAddress\"");
        assertThat(html("/area-clienti/ordini/" + order.tempCode() + "/", session))
                .doesNotContain("/ripeti\"")
                .doesNotContain("data-repeat");

        MockHttpSession staff = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(html("/gestione/ordini/" + order.id() + "/", staff))
                .doesNotContain("ripeti=")
                .doesNotContain("data-repeat");
        assertThat(html("/gestione/ordini/nuovo?cliente=" + ids.customerId(), staff))
                .doesNotContain("value=\"showAddressBook\"")
                .doesNotContain("data-address-book")
                .doesNotContain("name=\"saveRecipient\"");
        assertThat(html("/gestione/ordini/nuovo?ripeti=" + order.id(), staff))
                .as("the M1 blank form")
                .doesNotContain("value=\"Magazzino Destinatario S.r.l.\"")
                .doesNotContain("name=\"line.0.heightCm\" value=\"180\"");
    }

    @Test
    void theNewFieldsPostedByHandChangeNothing() throws Exception {
        // «Usa questo destinatario» did not exist: like any unknown button it continues to the summary
        MockHttpServletRequestBuilder use =
                post(NEW).session(session).with(csrf()).param("action", "useAddress");
        Map<String, String> example = OrderWizardIT.example();
        example.forEach(use::param);
        use.param("addressId", "1");
        use.param("saveRecipient", "1");
        mvc.perform(use).andExpect(redirectedUrl(NEW + "riepilogo/"));
        assertThat(html(NEW + "riepilogo/", session)).doesNotContain("data-save-recipient");
        mvc.perform(post(NEW + "riepilogo/").session(session).with(csrf())).andExpect(status().isFound());
        assertThat(orders.listForCustomer(ids.customerId())).hasSize(1);
        assertThat(book.list(ids.customerId()))
                .as("nothing saved while the flag is off")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer_address where customer_id = ?", Long.class, ids.customerId()))
                .isZero();
    }

    private String html(String path, MockHttpSession s) throws Exception {
        return mvc.perform(get(path).session(s).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
