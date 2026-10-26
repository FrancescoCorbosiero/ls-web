package it.logisticasanchez.web.backoffice;

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
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.FormValidator;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ENH-06 with {@code leads.inbox} on (ADR 0036): «Richieste» lists the website requests by state
 * with a search and never an anonymised one; the state machine of chapter 08 §2.4 is enforced
 * and every move audited; a request becomes a customer («Crea cliente», filled from it and linked)
 * and an order («Crea ordine», filled from it; the request becomes WON and points to the order);
 * a request sent with a registered customer's e-mail is linked to that customer; a linked request is
 * exported and erased with its customer (NFR-03), whatever its e-mail.
 */
@Tag("ENH-06")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.flags.override.[leads.inbox]=true")
class LeadsInboxIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FormValidator forms;

    @Autowired
    private LeadService leads;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    private StaffUser operator;
    private MockHttpSession staff;
    private String tag;

    @BeforeEach
    void staff() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        staff = StaffTestData.session(operator);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theInboxListsTheRequestsByStateWithASearchAndNeverAnAnonymisedOne() throws Exception {
        long quote = quote("preventivo-" + tag + "@cliente.example", "Officina " + tag);
        long message = contact("contatti-" + tag + "@cliente.example");
        long erased = contact("cancellata-" + tag + "@cliente.example");
        leads.anonymiseByEmail("cancellata-" + tag + "@cliente.example");

        String inbox = page(get("/gestione/richieste/").param("q", tag));
        assertThat(inbox)
                .contains("data-lead=\"" + quote + "\"")
                .contains("data-lead=\"" + message + "\"")
                .doesNotContain("data-lead=\"" + erased + "\"")
                .contains("Spedizione pallet espresso · 20831 Seregno → 10121 Torino")
                .contains("Informazioni sul magazzino")
                .contains("href=\"/gestione/richieste/\"")
                .contains("data-count=\"leads\"");
        assertThat(page(get("/gestione/richieste/").param("stato", "CONTACTED").param("q", tag)))
                .doesNotContain("data-lead=\"" + quote + "\"");
        assertThat(page(get("/gestione/richieste/").param("q", "nessuno-" + tag)))
                .doesNotContain("data-lead=\"" + quote + "\"")
                .contains("data-empty");
        mvc.perform(get("/gestione/richieste/" + erased + "/").session(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/gestione/richieste/").session(staff).param("stato", "VINTE"))
                .andExpect(status().isNotFound());

        assertThat(page(get("/gestione/richieste/" + quote + "/")))
                .contains("Officina " + tag)
                .contains("2 × EPAL 120×80 cm")
                .contains("600 kg")
                .contains("Sponda idraulica")
                .contains("href=\"mailto:preventivo-" + tag + "@cliente.example\"")
                .contains("href=\"tel:+393330000001\"")
                .contains("data-create-customer")
                .doesNotContain("data-create-order")
                .contains("data-move=\"CONTACTED\"")
                .contains("data-move=\"SPAM\"")
                .doesNotContain("data-move=\"WON\"");
    }

    @Test
    void theStateMachineOfChapter08IsEnforcedAndEveryMoveAudited() throws Exception {
        long id = quote("stati-" + tag + "@cliente.example", "Stati " + tag);
        String base = "/gestione/richieste/" + id + "/";

        move(id, "WON")
                .andExpect(
                        flash().attribute("flashError", "Questo passaggio di stato non è previsto per la richiesta."));
        assertThat(leadStatus(id)).isEqualTo("NEW");
        move(id, "CONTACTED").andExpect(flash().attribute("flash", "Richiesta segnata come «Contattata»."));
        assertThat(leadStatus(id)).isEqualTo("CONTACTED");
        move(id, "NEW")
                .andExpect(
                        flash().attribute("flashError", "Questo passaggio di stato non è previsto per la richiesta."));
        move(id, "BOH").andExpect(flash().attribute("flashError", "Scegli il nuovo stato della richiesta."));
        move(id, "LOST");
        assertThat(leadStatus(id)).isEqualTo("LOST");
        move(id, "SPAM")
                .andExpect(
                        flash().attribute("flashError", "Questo passaggio di stato non è previsto per la richiesta."));
        assertThat(page(get(base))).doesNotContain("data-move=").doesNotContain("data-create-order");

        assertThat(jdbc.queryForList(
                        "select (before->>'status') || '>' || (after->>'status') from audit_log"
                                + " where action = 'lead.status' and entity_type = 'lead' and entity_id = ? and actor_id = ?"
                                + " order by id",
                        String.class,
                        id,
                        operator.getId()))
                .containsExactly("NEW>CONTACTED", "CONTACTED>LOST");
    }

    @Test
    void aRequestBecomesACustomerAndThenAnOrderAndIsWon() throws Exception {
        String email = "conversione-" + tag + "@cliente.example";
        long id = quote(email, "Officina Conversione " + tag);

        String customerForm = page(get("/gestione/clienti/nuovo").param("richiesta", Long.toString(id)));
        assertThat(customerForm)
                .contains("data-from-lead")
                .contains("name=\"richiesta\" value=\"" + id + "\"")
                .contains("value=\"Officina Conversione " + tag + "\"")
                .contains("value=\"" + email + "\"")
                .contains("value=\"Seregno\"")
                .contains("value=\"20831\"");
        mvc.perform(post("/gestione/clienti/nuovo")
                        .session(staff)
                        .with(csrf())
                        .param("richiesta", Long.toString(id))
                        .param("email", email)
                        .param("companyName", "Officina Conversione " + tag)
                        .param("addressLine", "Via Milano 1")
                        .param("province", "MB")
                        .param("city", "Seregno")
                        .param("postalCode", "20831")
                        .param("phone", "039 1234567")
                        .param("vatNumber", "")
                        .param("taxCode", ""))
                .andExpect(redirectedUrl("/gestione/richieste/" + id + "/"));
        Long customerId = jdbc.queryForObject("select id from customer where email = ?", Long.class, email);
        assertThat(jdbc.queryForObject("select customer_id from lead where id = ?", Long.class, id))
                .isEqualTo(customerId);

        String detail = page(get("/gestione/richieste/" + id + "/"));
        assertThat(detail)
                .contains("data-linked-customer")
                .contains("href=\"/gestione/ordini/nuovo?cliente=" + customerId + "&amp;richiesta=" + id + "\"")
                .doesNotContain("data-create-customer");

        String orderForm = page(get("/gestione/ordini/nuovo")
                .param("cliente", Long.toString(customerId))
                .param("richiesta", Long.toString(id)));
        assertThat(orderForm)
                .contains("name=\"richiesta\" value=\"" + id + "\"")
                .contains("Il modulo riporta i dati della richiesta del sito")
                .contains("name=\"line.0.heightCm\" value=\"150\"")
                .contains("name=\"line.0.lengthCm\" value=\"120\"")
                .contains("name=\"line.0.quantity\" value=\"2\"")
                .contains("name=\"line.0.weightKg\" value=\"300\"")
                .contains("value=\"Torino\"")
                .contains("value=\"10121\"");

        Map<String, String> order = new LinkedHashMap<>();
        order.put("line.0.kind", "EPAL");
        order.put("line.0.heightCm", "150");
        order.put("line.0.lengthCm", "120");
        order.put("line.0.widthCm", "80");
        order.put("line.0.weightKg", "300");
        order.put("line.0.quantity", "2");
        order.put("transportDocumentCode", "DDT-RICHIESTA-" + tag);
        order.put("packageCount", "");
        order.put("accessoryServiceId", "2");
        order.put("notes", "Ricambi meccanici");
        order.put("name", "Ricevente Torino S.r.l.");
        order.put("addressLine", "Corso Francia 10");
        order.put("province", "TO");
        order.put("city", "Torino");
        order.put("postalCode", "10121");
        order.put("phone", "");
        order.put("email", "");
        MockHttpServletRequestBuilder create = post("/gestione/ordini/nuovo")
                .session(staff)
                .with(csrf())
                .param("action", "save")
                .param("chiave", UUID.randomUUID().toString())
                .param("cliente", Long.toString(customerId))
                .param("richiesta", Long.toString(id));
        order.forEach(create::param);
        mvc.perform(create).andExpect(status().is3xxRedirection());

        Long orderId = jdbc.queryForObject(
                "select id from shipment_order where transport_document_code = ?", Long.class, "DDT-RICHIESTA-" + tag);
        assertThat(jdbc.queryForMap("select status, converted_order_id from lead where id = ?", id))
                .containsEntry("status", "WON")
                .containsEntry("converted_order_id", orderId);
        assertThat(jdbc.queryForObject(
                        "select after->>'orderId' from audit_log where action = 'lead.convert' and entity_id = ?",
                        String.class,
                        id))
                .isEqualTo(orderId.toString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'lead.customer' and entity_id = ?",
                        Integer.class,
                        id))
                .isEqualTo(1);
        assertThat(page(get("/gestione/richieste/" + id + "/")))
                .contains("data-converted-order")
                .contains("/gestione/ordini/" + orderId + "/")
                .doesNotContain("data-create-order")
                .contains("data-lead-status=\"WON\"");
    }

    @Test
    void aRequestSentWithARegisteredEmailIsLinkedToThatCustomer() throws Exception {
        String email = CustomerTestData.uniqueEmail("registrato");
        CustomerTestData.Ids customer =
                CustomerTestData.customerUser(jdbc, email, "ACTIVE", "Cliente Registrato " + tag);
        long id = contact(email);

        String detail = page(get("/gestione/richieste/" + id + "/"));
        assertThat(detail)
                .contains("data-same-email")
                .contains("Cliente Registrato " + tag)
                .doesNotContain("data-create-customer")
                .contains("data-create-order");
        mvc.perform(post("/gestione/richieste/" + id + "/collega")
                        .session(staff)
                        .with(csrf()))
                .andExpect(redirectedUrl("/gestione/richieste/" + id + "/"))
                .andExpect(
                        flash().attribute("flash", "Richiesta collegata al cliente «Cliente Registrato " + tag + "»."));
        assertThat(jdbc.queryForObject("select customer_id from lead where id = ?", Long.class, id))
                .isEqualTo(customer.customerId());
        assertThat(page(get("/gestione/richieste/" + id + "/"))).contains("data-linked-customer");

        long unknown = contact("sconosciuto-" + tag + "@cliente.example");
        mvc.perform(post("/gestione/richieste/" + unknown + "/collega")
                        .session(staff)
                        .with(csrf()))
                .andExpect(flash().attribute("flashError", "Nessun cliente registrato con l'e-mail della richiesta."));
    }

    @Test
    void aRequestLinkedToACustomerIsExportedAndErasedWithIt() throws Exception {
        String requestEmail = "richiedente-" + tag + "@cliente.example";
        String customerEmail = CustomerTestData.uniqueEmail("diverso");
        long id = contact(requestEmail);
        CustomerTestData.Ids customer = CustomerTestData.customerUser(jdbc, customerEmail, "ACTIVE", "Diverso " + tag);
        leads.linkCustomer(id, operator.getId(), customer.customerId());

        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(mvc.perform(post("/gestione/impostazioni/privacy/esporta")
                                .session(admin)
                                .with(csrf())
                                .param("email", customerEmail))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Informazioni sul magazzino " + tag);
        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(admin)
                        .with(csrf())
                        .param("email", customerEmail))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select email from lead where id = ?", String.class, id))
                .endsWith("@anonimizzato.invalid");
        mvc.perform(get("/gestione/richieste/" + id + "/").session(staff)).andExpect(status().isNotFound());
    }

    private long quote(String email, String company) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("servizio", "pallet");
        fields.put("ritiro_cap", "20831");
        fields.put("ritiro_localita", "Seregno");
        fields.put("consegna_cap", "10121");
        fields.put("consegna_localita", "Torino");
        fields.put("destinatario", "azienda");
        fields.put("tipo_pallet", "eur");
        fields.put("numero", "2");
        fields.put("peso", "600");
        fields.put("altezza", "150");
        fields.put("descrizione_merce", "Ricambi meccanici");
        fields.put("servizi_extra[]", "Sponda idraulica");
        fields.put("nome", "Mario Prova");
        fields.put("azienda", company);
        fields.put("email", email);
        fields.put("telefono", "+39 333 000 0001");
        fields.put("contatto_preferito", "telefono");
        fields.put("privacy", "accettata");
        return store(FormSubmission.Form.PREVENTIVO, fields);
    }

    private long contact(String email) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("nome", "Lucia Esempio");
        fields.put("email", email);
        fields.put("oggetto", "Informazioni sul magazzino " + tag);
        fields.put("messaggio", "Vorrei sapere se offrite lo stoccaggio di 20 pallet.");
        fields.put("privacy", "accettata");
        return store(FormSubmission.Form.CONTATTI, fields);
    }

    private long store(FormSubmission.Form form, Map<String, String> fields) {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        fields.forEach((name, value) -> parameters.put(name, new String[] {value}));
        return leads.store(forms.validate(form, parameters), "192.0.2.10", "LeadsInboxIT");
    }

    private org.springframework.test.web.servlet.ResultActions move(long id, String target) throws Exception {
        return mvc.perform(post("/gestione/richieste/" + id + "/stato")
                        .session(staff)
                        .with(csrf())
                        .param("stato", target))
                .andExpect(redirectedUrl("/gestione/richieste/" + id + "/"));
    }

    private String leadStatus(long id) {
        return jdbc.queryForObject("select status from lead where id = ?", String.class, id);
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(staff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
