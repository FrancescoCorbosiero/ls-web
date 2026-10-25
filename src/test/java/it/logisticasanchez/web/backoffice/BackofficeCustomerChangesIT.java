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
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADM-12 (owner decision of 2026-10-25; ADR 0028): staff create a customer who asked by phone or
 * e-mail — ACTIVE at once, source BACKOFFICE, with its OWNER login, the welcome e-mail (EML-03)
 * only when chosen — with the rules of the registration form (CUS-02) and a unique e-mail; they
 * correct a customer's registration data on the version the page showed, never the login e-mail,
 * never an anonymised customer, audited; the orders already sent keep their sender (Q-13).
 */
@Tag("ADM-12")
@Tag("EML-03")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeCustomerChangesIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private CustomerService customers;

    @Autowired
    private OrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    private StaffUser operator;
    private MockHttpSession session;

    @BeforeEach
    void reset() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        session = StaffTestData.session(operator);
        outbox.deleteAll();
    }

    @Test
    void staffCreateAnActiveCustomerWithTheWelcomeEmail() throws Exception {
        assertThat(html("/gestione/clienti/")).contains("href=\"/gestione/clienti/nuovo\"");
        assertThat(html("/gestione/clienti/nuovo"))
                .contains("Nuovo cliente")
                .contains("name=\"email\"")
                .containsPattern("name=\"benvenuto\" value=\"on\" checked=\"checked\"")
                .contains("Ragione Sociale/Nominativo");
        String email = CustomerTestData.uniqueEmail("telefono");
        MvcResult created = mvc.perform(create(email.toUpperCase(), data(), true))
                .andExpect(status().isFound())
                .andReturn();
        Map<String, Object> row = jdbc.queryForMap("select * from customer where email = ?", email);
        long id = ((Number) row.get("id")).longValue();
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/gestione/clienti/" + id + "/");
        assertThat(row)
                .containsEntry("status", "ACTIVE")
                .containsEntry("source", "BACKOFFICE")
                .containsEntry("company_name", "Nuovo Cliente Telefonico S.r.l.")
                .containsEntry("postal_code", "20900")
                .containsEntry("approved_by", operator.getId());
        assertThat(row.get("approved_at")).isNotNull();
        assertThat(row.get("email_verified_at"))
                .as("verified at the first sign-in")
                .isNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer_user where customer_id = ? and email = ?",
                        Long.class,
                        id,
                        email))
                .isEqualTo(1);
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-03");
        assertThat(mails.get(0).getRecipient()).isEqualTo(email);
        assertThat(mails.get(0).getBodyText()).contains("Benvenuto in Logistica Sanchez!");
        assertThat(audits("customer.create", id)).isEqualTo(1);
        assertThat(html("/gestione/clienti/" + id + "/"))
                .contains("Creato dall&#39;ufficio")
                .contains("href=\"/gestione/ordini/nuovo?cliente=" + id + "\"");

        // its first sign-in verifies the address; it stays ACTIVE and gets no registration e-mails
        outbox.deleteAll();
        customers.emailVerified(id, java.time.Instant.now());
        assertThat(jdbc.queryForMap("select status, email_verified_at from customer where id = ?", id))
                .containsEntry("status", "ACTIVE")
                .extractingByKey("email_verified_at")
                .isNotNull();
        assertThat(outbox.findAll()).isEmpty();

        // the new customer can order at once
        OrderView order = orders.createByStaff(
                operator.getId(),
                id,
                UUID.randomUUID(),
                OrderTestData.draft("DDT-NUOVO", "Dest", "Monza", "20900").content());
        assertThat(order.sender().name()).isEqualTo("Nuovo Cliente Telefonico S.r.l.");
    }

    @Test
    void theRulesOfRegistrationApplyAndTheWelcomeEmailIsAChoice() throws Exception {
        Map<String, String> invalid = data();
        invalid.put("postalCode", "2090");
        invalid.put("companyName", "");
        assertThat(page(create("", invalid, true)))
                .contains("data-form-errors")
                .contains("Inserisci un CAP di cinque cifre.")
                .contains("Campo obbligatorio.");
        assertThat(page(create("non-una-email", data(), true))).contains("Inserisci un indirizzo e-mail valido.");
        CustomerTestData.Ids existing = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("esiste"), "ACTIVE", "Già Cliente S.r.l.");
        assertThat(page(create(existing.email(), data(), true)))
                .contains("Esiste già un cliente con questa e-mail: cercalo tra i clienti.");
        assertThat(outbox.findAll()).isEmpty();

        String email = CustomerTestData.uniqueEmail("senza-benvenuto");
        mvc.perform(create(email, data(), false)).andExpect(status().isFound());
        assertThat(jdbc.queryForObject("select status from customer where email = ?", String.class, email))
                .isEqualTo("ACTIVE");
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void staffCorrectACustomersDataOnTheVersionTheySaw() throws Exception {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("correggi"), "ACTIVE", "Ragione Vecchia S.r.l.");
        OrderView order = orders.submit(
                ids.customerId(),
                ids.customerUserId(),
                OrderTestData.draft("DDT-Q13", "Destinatario Q13", "Monza", "20900"));
        outbox.deleteAll();
        String base = "/gestione/clienti/" + ids.customerId() + "/";
        int version = version(ids.customerId());
        assertThat(html(base)).contains("href=\"" + base + "modifica\"");
        assertThat(html(base + "modifica"))
                .contains("Modifica i dati di Ragione Vecchia S.r.l.")
                .contains("name=\"versione\" value=\"" + version + "\"")
                .contains("value=\"Ragione Vecchia S.r.l.\"")
                .contains(ids.email())
                .doesNotContain("name=\"email\"");

        Map<String, String> corrected = data();
        corrected.put("companyName", "Ragione Corretta S.r.l.");
        corrected.put("email", "altro@cliente.example");
        assertThat(page(update(base, invalid(corrected), version))).contains("Inserisci un CAP di cinque cifre.");
        mvc.perform(update(base, corrected, version)).andExpect(redirectedUrl(base));
        Map<String, Object> row = jdbc.queryForMap("select * from customer where id = ?", ids.customerId());
        assertThat(row)
                .containsEntry("company_name", "Ragione Corretta S.r.l.")
                .containsEntry("city", "Monza")
                .containsEntry("email", ids.email())
                .containsEntry("status", "ACTIVE")
                .containsEntry("version", version + 1);
        assertThat(audits("customer.update", ids.customerId())).isEqualTo(1);
        assertThat(outbox.findAll()).as("no e-mail to the customer").isEmpty();
        assertThat(jdbc.queryForObject("select sender_name from shipment_order where id = ?", String.class, order.id()))
                .as("the order keeps the sender of when it was sent (Q-13)")
                .isEqualTo("Ragione Vecchia S.r.l.");

        // a stale page changes nothing and shows the current data
        corrected.put("companyName", "Ragione Stantia S.r.l.");
        assertThat(page(update(base, corrected, version)))
                .contains("I dati del cliente sono stati modificati nel frattempo")
                .contains("value=\"Ragione Corretta S.r.l.\"");
        assertThat(jdbc.queryForObject(
                        "select company_name from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("Ragione Corretta S.r.l.");

        // an anonymised customer is not edited
        customers.anonymise(ids.customerId(), operator.getId(), "anonimo-" + ids.customerId() + "@anonimo.example");
        mvc.perform(get(base + "modifica").session(session)).andExpect(redirectedUrl(base));
        mvc.perform(update(base, data(), version(ids.customerId()))).andExpect(redirectedUrl(base));
        mvc.perform(get("/gestione/clienti/999999999/modifica").session(session))
                .andExpect(status().isNotFound());
    }

    private static Map<String, String> data() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", "Nuovo Cliente Telefonico S.r.l.");
        values.put("addressLine", "Via Lecco 12");
        values.put("province", "MB");
        values.put("city", "Monza");
        values.put("postalCode", "20900");
        values.put("phone", "039 1234567");
        values.put("vatNumber", "");
        values.put("taxCode", "");
        return values;
    }

    private static Map<String, String> invalid(Map<String, String> values) {
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put("postalCode", "ABCDE");
        return copy;
    }

    private MockHttpServletRequestBuilder create(String email, Map<String, String> values, boolean welcome) {
        MockHttpServletRequestBuilder builder = post("/gestione/clienti/nuovo")
                .session(session)
                .with(csrf())
                .accept("text/html")
                .param("email", email);
        if (welcome) {
            builder.param("benvenuto", "on");
        }
        values.forEach(builder::param);
        return builder;
    }

    private MockHttpServletRequestBuilder update(String base, Map<String, String> values, int version) {
        MockHttpServletRequestBuilder builder = post(base + "modifica")
                .session(session)
                .with(csrf())
                .accept("text/html")
                .param("versione", Integer.toString(version));
        values.forEach(builder::param);
        return builder;
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path).session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private int version(long customerId) {
        return jdbc.queryForObject("select version from customer where id = ?", Integer.class, customerId);
    }

    private long audits(String action, long customerId) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and actor_id = ? and entity_id = ?",
                Long.class,
                action,
                operator.getId(),
                customerId);
    }
}
