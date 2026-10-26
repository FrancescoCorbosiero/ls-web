package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * ENH-16 with {@code customers.profileEdit} on (ADR 0038): an ACTIVE customer changes its address
 * and phone at once (audited, the office told by EML-06); a different company name, VAT number or
 * tax code waits as one pending request, which a new save replaces and the customer can take
 * back; the office approves it (the values become the customer's, EML-12) or declines it with a
 * reason (EML-12, data unchanged); the rules of CUS-02, the version the page showed, the ACTIVE
 * state; the export and the erasure (NFR-03).
 */
@Tag("ENH-16")
@Tag("EML-06")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.flags.override.[customers.profileEdit]=true")
class ProfileEditIT extends AbstractPostgresIT {

    private static final String FORM = "/area-clienti/profilo/dati";
    private static final Pattern CUSTOMERS_COUNT = Pattern.compile("data-count=\"customers\">(\\d+)<");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private BusinessProperties business;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;
    private StaffUser operator;
    private MockHttpSession staff;

    @BeforeEach
    void customer() {
        outbox.deleteAll();
        ids = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("dati"), "ACTIVE");
        session = CustomerTestData.session(ids);
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        staff = StaffTestData.session(operator);
    }

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theAddressAndThePhoneChangeAtOnce() throws Exception {
        assertThat(page(get("/area-clienti/profilo/").session(session)))
                .contains("data-edit-profile")
                .contains("href=\"/area-clienti/profilo/dati\"")
                .doesNotContain("Chiedi una modifica")
                .doesNotContain("data-pending-change");
        assertThat(page(get(FORM).session(session)))
                .contains("name=\"versione\" value=\"0\"")
                .contains("value=\"Ditta Esempio S.r.l.\"")
                .contains("value=\"Via di Prova 1\"")
                .doesNotContain("data-pending-field");

        Map<String, String> data = data();
        data.put("addressLine", "Via Nuova 2");
        data.put("city", "Monza");
        data.put("postalCode", "20900");
        data.put("phone", "039 2223344");
        save(data, 0)
                .andExpect(redirectedUrl("/area-clienti/profilo/"))
                .andExpect(flash().attribute("flash", "Dati salvati: valgono per i prossimi ordini."));

        assertThat(jdbc.queryForMap(
                        "select company_name, address_line, city, postal_code, phone, version from customer where id = ?",
                        ids.customerId()))
                .containsEntry("company_name", "Ditta Esempio S.r.l.")
                .containsEntry("address_line", "Via Nuova 2")
                .containsEntry("city", "Monza")
                .containsEntry("postal_code", "20900")
                .containsEntry("phone", "+390392223344")
                .containsEntry("version", 1);
        assertThat(requests()).isZero();
        EmailOutbox notice = single("EML-06");
        assertThat(notice.getRecipient()).isEqualTo(business.officeEmail());
        assertThat(notice.getReplyTo()).isEqualTo(ids.email());
        assertThat(notice.getSubject()).isEqualTo("Logistica Sanchez - Dati del cliente aggiornati");
        assertThat(notice.getBodyText())
                .contains("Via Nuova 2 (prima: Via di Prova 1)")
                .contains("Monza (prima: Seregno)")
                .doesNotContain("Da approvare");
        assertThat(audit())
                .containsExactly(Map.of(
                        "action", "customer.update", "actor_type", "CUSTOMER", "actor_id", ids.customerUserId()));
        assertThat(page(get("/area-clienti/profilo/").session(session)))
                .contains("Via Nuova 2")
                .contains("+390392223344");
    }

    @Test
    void aChangeOfCompanyNameOrVatWaitsForTheOfficeThatApprovesIt() throws Exception {
        int queued = customersCount();
        Map<String, String> data = data();
        data.put("companyName", "Ditta Esempio Logistica S.r.l.");
        data.put("vatNumber", "IT 12345678901");
        save(data, 0)
                .andExpect(redirectedUrl("/area-clienti/profilo/"))
                .andExpect(flash().attribute("flash", Matchers.containsString("inviata al nostro ufficio")));

        assertThat(jdbc.queryForMap(
                        "select company_name, vat_number, version from customer where id = ?", ids.customerId()))
                .as("nothing changes before the approval")
                .containsEntry("company_name", "Ditta Esempio S.r.l.")
                .containsEntry("vat_number", null)
                .containsEntry("version", 0);
        Map<String, Object> request = jdbc.queryForMap(
                "select id, status, company_name, vat_number, tax_code, closed_at from customer_change_request"
                        + " where customer_id = ?",
                ids.customerId());
        assertThat(request)
                .containsEntry("status", "PENDING")
                .containsEntry("company_name", "Ditta Esempio Logistica S.r.l.")
                .containsEntry("vat_number", "12345678901")
                .containsEntry("tax_code", null)
                .containsEntry("closed_at", null);
        long requestId = ((Number) request.get("id")).longValue();
        EmailOutbox notice = single("EML-06");
        assertThat(notice.getSubject()).isEqualTo("Logistica Sanchez - Modifica dei dati da approvare");
        assertThat(notice.getBodyText())
                .contains("Ditta Esempio Logistica S.r.l. (ora: Ditta Esempio S.r.l.)")
                .contains("12345678901 (ora: -)")
                .contains("/gestione/clienti/" + ids.customerId() + "/")
                .doesNotContain("Dati aggiornati");

        assertThat(page(get("/area-clienti/profilo/").session(session)))
                .contains("data-pending-change")
                .contains("Ditta Esempio Logistica S.r.l.")
                .contains("Ritira la richiesta");
        assertThat(page(get(FORM).session(session)))
                .as("the form shows the values asked for, the present ones under them")
                .contains("value=\"Ditta Esempio Logistica S.r.l.\"")
                .contains("value=\"12345678901\"")
                .contains("In attesa di approvazione. Ora: Ditta Esempio S.r.l.");

        assertThat(customersCount()).isEqualTo(queued + 1);
        assertThat(page(get("/gestione/clienti/in-approvazione/").session(staff)))
                .contains("data-section=\"customer-changes\"")
                .contains("data-change-request=\"" + ids.customerId() + "\"")
                .contains("Ditta Esempio Logistica S.r.l.");
        assertThat(page(get("/gestione/clienti/" + ids.customerId() + "/").session(staff)))
                .contains("data-change-request")
                .contains("data-field=\"companyName\"")
                .contains("data-field=\"vatNumber\"")
                .doesNotContain("data-field=\"taxCode\"")
                .contains("Approva la modifica");

        outbox.deleteAll();
        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/approva")
                        .session(staff)
                        .with(csrf()))
                .andExpect(redirectedUrl("/gestione/clienti/" + ids.customerId() + "/"))
                .andExpect(flash().attribute("flash", Matchers.containsString("Modifica approvata")));

        assertThat(jdbc.queryForMap(
                        "select company_name, vat_number, version from customer where id = ?", ids.customerId()))
                .containsEntry("company_name", "Ditta Esempio Logistica S.r.l.")
                .containsEntry("vat_number", "12345678901")
                .containsEntry("version", 1);
        Map<String, Object> closed = jdbc.queryForMap(
                "select status, decided_by, closed_at from customer_change_request where id = ?", requestId);
        assertThat(closed).containsEntry("status", "APPROVED").containsEntry("decided_by", operator.getId());
        assertThat(closed.get("closed_at")).isNotNull();
        EmailOutbox outcome = single("EML-12");
        assertThat(outcome.getRecipient()).isEqualTo(ids.email());
        assertThat(outcome.getSubject()).isEqualTo("Logistica Sanchez - Dati aggiornati");
        assertThat(outcome.getBodyText())
                .contains("Ditta Esempio Logistica S.r.l.")
                .contains("12345678901");
        assertThat(audit())
                .containsExactly(
                        Map.of(
                                "action",
                                "customer.change.request",
                                "actor_type",
                                "CUSTOMER",
                                "actor_id",
                                ids.customerUserId()),
                        Map.of(
                                "action",
                                "customer.change.approve",
                                "actor_type",
                                "STAFF",
                                "actor_id",
                                operator.getId()));
        assertThat(page(get("/area-clienti/profilo/").session(session)))
                .doesNotContain("data-pending-change")
                .contains("Ditta Esempio Logistica S.r.l.");
        assertThat(customersCount()).isEqualTo(queued);
    }

    @Test
    void theOfficeDeclinesAChangeWithAReasonAndTheDataStay() throws Exception {
        long requestId = requestCompanyName("Ditta Rinominata S.r.l.");
        outbox.deleteAll();

        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/rifiuta")
                        .session(staff)
                        .with(csrf())
                        .param("motivo", " La ragione sociale non corrisponde alla visura. "))
                .andExpect(redirectedUrl("/gestione/clienti/" + ids.customerId() + "/"))
                .andExpect(flash().attribute("flash", Matchers.containsString("Modifica rifiutata")));

        assertThat(jdbc.queryForMap(
                        "select status, decline_reason, decided_by from customer_change_request where id = ?",
                        requestId))
                .containsEntry("status", "DECLINED")
                .containsEntry("decline_reason", "La ragione sociale non corrisponde alla visura.")
                .containsEntry("decided_by", operator.getId());
        assertThat(jdbc.queryForObject(
                        "select company_name from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("Ditta Esempio S.r.l.");
        EmailOutbox outcome = single("EML-12");
        assertThat(outcome.getSubject()).isEqualTo("Logistica Sanchez - Modifica dei dati non accettata");
        assertThat(outcome.getBodyText()).contains("Motivo: La ragione sociale non corrisponde alla visura.");

        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/approva")
                        .session(staff)
                        .with(csrf()))
                .andExpect(flash().attribute("flashError", "La richiesta di modifica non è più in attesa."));
        assertThat(jdbc.queryForObject(
                        "select company_name from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("Ditta Esempio S.r.l.");
        assertThat(page(get("/gestione/clienti/" + ids.customerId() + "/").session(staff)))
                .doesNotContain("data-change-request");
    }

    @Test
    void aNewSaveReplacesTheRequestAndTheCustomerCanTakeItBack() throws Exception {
        long first = requestCompanyName("Ditta Prima Versione S.r.l.");
        long second = requestCompanyName("Ditta Seconda Versione S.r.l.");
        assertThat(second).as("one pending request per customer, replaced").isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "select company_name from customer_change_request where id = ?", String.class, first))
                .isEqualTo("Ditta Seconda Versione S.r.l.");
        assertThat(requests()).isEqualTo(1);

        outbox.deleteAll();
        save(data(), 0).andExpect(flash().attribute("flash", "La richiesta di modifica in attesa è stata ritirata."));
        assertThat(jdbc.queryForObject("select status from customer_change_request where id = ?", String.class, first))
                .as("the present values again: the request goes away")
                .isEqualTo("WITHDRAWN");
        assertThat(outbox.findAll()).isEmpty();

        long third = requestCompanyName("Ditta Terza Versione S.r.l.");
        assertThat(third).isNotEqualTo(first);
        mvc.perform(post("/area-clienti/profilo/dati/ritira").session(session).with(csrf()))
                .andExpect(redirectedUrl("/area-clienti/profilo/"))
                .andExpect(flash().attribute("flash", Matchers.containsString("Hai ritirato la richiesta")));
        assertThat(jdbc.queryForObject("select status from customer_change_request where id = ?", String.class, third))
                .isEqualTo("WITHDRAWN");
        mvc.perform(post("/area-clienti/profilo/dati/ritira").session(session).with(csrf()))
                .andExpect(flash().attribute("flash", "Non c'è nessuna richiesta di modifica in attesa."));

        outbox.deleteAll();
        save(data(), 0).andExpect(flash().attribute("flash", "Non hai cambiato nessun dato."));
        assertThat(outbox.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("select version from customer where id = ?", Integer.class, ids.customerId()))
                .isZero();
        assertThat(audit())
                .extracting(row -> row.get("action"))
                .containsExactly(
                        "customer.change.request",
                        "customer.change.request",
                        "customer.change.withdraw",
                        "customer.change.request",
                        "customer.change.withdraw");
    }

    @Test
    void theRulesTheVersionAndTheStateAreChecked() throws Exception {
        Map<String, String> data = data();
        data.put("postalCode", "2083");
        data.put("addressLine", "Via Sbagliata 9");
        assertThat(page(post(FORM).session(session).with(csrf()).params(params(data, 0))))
                .contains("data-form-errors")
                .contains("value=\"Via Sbagliata 9\"");

        Map<String, String> stale = data();
        stale.put("addressLine", "Via Tardiva 3");
        assertThat(page(post(FORM).session(session).with(csrf()).params(params(stale, 7))))
                .contains("I tuoi dati sono stati modificati nel frattempo")
                .contains("value=\"Via di Prova 1\"");
        assertThat(jdbc.queryForObject(
                        "select address_line from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo("Via di Prova 1");
        assertThat(outbox.findAll()).isEmpty();

        CustomerTestData.Ids pending =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("attesa"), "PENDING_APPROVAL");
        MockHttpSession pendingSession = CustomerTestData.session(pending);
        mvc.perform(get(FORM).session(pendingSession)).andExpect(status().isNotFound());
        mvc.perform(post(FORM).session(pendingSession).with(csrf()).params(params(data(), 0)))
                .andExpect(status().isNotFound());
        assertThat(page(get("/area-clienti/profilo/").session(pendingSession)))
                .as("a customer that is not ACTIVE keeps «Chiedi una modifica»")
                .contains("Chiedi una modifica")
                .doesNotContain("data-edit-profile");

        long requestId = requestCompanyName("Ditta Altrui S.r.l.");
        mvc.perform(post("/gestione/clienti/" + pending.customerId() + "/modifica-dati/" + requestId + "/approva")
                        .session(staff)
                        .with(csrf()))
                .andExpect(flash().attribute("flashError", "La richiesta di modifica non è più in attesa."));
        assertThat(jdbc.queryForObject(
                        "select status from customer_change_request where id = ?", String.class, requestId))
                .as("another customer's request is not decided from this page")
                .isEqualTo("PENDING");
        mvc.perform(post("/gestione/clienti/" + ids.customerId() + "/modifica-dati/" + requestId + "/rifiuta")
                        .session(staff)
                        .with(csrf())
                        .param("motivo", "x".repeat(501)))
                .andExpect(flash().attribute("flashError", "Il motivo può avere al massimo 500 caratteri."));
        assertThat(jdbc.queryForObject(
                        "select status from customer_change_request where id = ?", String.class, requestId))
                .isEqualTo("PENDING");
    }

    @Test
    void theExportListsTheRequestsAndTheErasureDeletesThem() throws Exception {
        requestCompanyName("Ditta Da Esportare S.r.l.");
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(page(post("/gestione/impostazioni/privacy/esporta")
                        .session(admin)
                        .with(csrf())
                        .param("email", ids.email())))
                .contains("\"richiesteModificaDati\"")
                .contains("Ditta Da Esportare S.r.l.")
                .contains("\"PENDING\"");
        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(admin)
                        .with(csrf())
                        .param("email", ids.email()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", Matchers.containsString("1 richieste di modifica dei dati")));
        assertThat(requests()).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log_detail d join audit_log l on l.id = d.audit_log_id"
                                + " where l.entity_type = 'customer' and l.entity_id = ?",
                        Integer.class,
                        ids.customerId()))
                .isZero();
    }

    /** Asks for a new company name (the rest unchanged) and returns the pending request's id. */
    private long requestCompanyName(String companyName) throws Exception {
        Map<String, String> data = data();
        data.put("companyName", companyName);
        save(data, jdbc.queryForObject("select version from customer where id = ?", Integer.class, ids.customerId()))
                .andExpect(redirectedUrl("/area-clienti/profilo/"));
        return jdbc.queryForObject(
                "select id from customer_change_request where customer_id = ? and status = 'PENDING'",
                Long.class,
                ids.customerId());
    }

    private ResultActions save(Map<String, String> data, int version) throws Exception {
        return mvc.perform(post(FORM).session(session).with(csrf()).params(params(data, version)));
    }

    /** The test customer's data as the form shows them (CustomerTestData). */
    private static Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("companyName", "Ditta Esempio S.r.l.");
        data.put("addressLine", "Via di Prova 1");
        data.put("province", "MB");
        data.put("city", "Seregno");
        data.put("postalCode", "20831");
        data.put("phone", "+393330000000");
        data.put("vatNumber", "");
        data.put("taxCode", "");
        return data;
    }

    private static MultiValueMap<String, String> params(Map<String, String> data, int version) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        data.forEach(params::add);
        params.add("versione", Integer.toString(version));
        return params;
    }

    private int requests() {
        return jdbc.queryForObject(
                "select count(*) from customer_change_request where customer_id = ?", Integer.class, ids.customerId());
    }

    private EmailOutbox single(String kind) {
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().getKind()).isEqualTo(kind);
        return mails.getFirst();
    }

    private List<Map<String, Object>> audit() {
        return jdbc.queryForList(
                "select action, actor_type, actor_id from audit_log where entity_type = 'customer' and entity_id = ?"
                        + " order by id",
                ids.customerId());
    }

    /** The «In Approvazione» count of the navigation (0 when it shows none). */
    private int customersCount() throws Exception {
        Matcher m = CUSTOMERS_COUNT.matcher(page(get("/gestione/clienti/").session(staff)));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
