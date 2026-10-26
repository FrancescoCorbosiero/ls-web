package it.logisticasanchez.web.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.FormValidator;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * ENH-20 with {@code quotes.saved} (and {@code leads.inbox}) on (ADR 0037): staff prepare a quote
 * from a website request whose customer is linked and the customer gets EML-11 with an unguessable
 * link — a public page and a PDF, never indexed nor cached; the customer, signed in, orders it once
 * before it expires: a pending order with the quote's lines and service, the quote ACCEPTED and the
 * request WON; a quote expired or withdrawn is not ordered; another customer never sees it; the
 * customer's quotes are exported and erased with the customer (NFR-03).
 */
@Tag("ENH-20")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ls.flags.override.[leads.inbox]=true", "ls.flags.override.[quotes.saved]=true"})
class SavedQuotesIT extends AbstractPostgresIT {

    private static final Pattern LINK = Pattern.compile("/preventivi/([A-Za-z0-9_-]{43})");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FormValidator forms;

    @Autowired
    private LeadService leads;

    @Autowired
    private QuoteService quotes;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    private StaffUser operator;
    private MockHttpSession staff;
    private CustomerTestData.Ids customer;
    private MockHttpSession customerSession;
    private String tag;

    @BeforeEach
    void data() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        staff = StaffTestData.session(operator);
        tag = UUID.randomUUID().toString().substring(0, 8);
        customer = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("preventivo"), "ACTIVE", "Cliente Preventivo " + tag);
        customerSession = CustomerTestData.session(customer);
        outbox.deleteAll();
    }

    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void staffPrepareAQuoteAndTheCustomerGetsItsLinkAndPdf() throws Exception {
        long lead = linkedLead();
        String form = page(get("/gestione/richieste/" + lead + "/preventivo").session(staff));
        assertThat(form)
                .contains("name=\"line.0.heightCm\" value=\"150\"")
                .contains("name=\"line.0.quantity\" value=\"2\"")
                .contains("value=\"" + quotes.today().plusDays(30) + "\"")
                .contains("value=\"Torino\"");
        assertThat(page(get("/gestione/richieste/" + lead + "/").session(staff)))
                .contains("data-create-quote");

        mvc.perform(save(lead, "180,50", quotes.today().plusDays(30)))
                .andExpect(redirectedUrl("/gestione/richieste/" + lead + "/"))
                .andExpect(flash().attribute("flash", org.hamcrest.Matchers.startsWith("Preventivo PRV-")));
        Map<String, Object> row = jdbc.queryForMap(
                "select id, status, amount, accessory_service_id, recipient_city, customer_id from quote where lead_id = ?",
                lead);
        long id = ((Number) row.get("id")).longValue();
        assertThat(row)
                .containsEntry("status", "SENT")
                .containsEntry("amount", new BigDecimal("180.50"))
                .containsEntry("accessory_service_id", 2)
                .containsEntry("recipient_city", "Torino")
                .containsEntry("customer_id", customer.customerId());
        assertThat(jdbc.queryForObject("select count(*) from quote_line where quote_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from lead where id = ?", String.class, lead))
                .as("sending a quote is a contact")
                .isEqualTo("CONTACTED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'quote.create' and entity_id = ?",
                        Integer.class,
                        id))
                .isEqualTo(1);

        EmailOutbox mail = outbox.findAll().stream()
                .filter(m -> "EML-11".equals(m.getKind()))
                .findFirst()
                .orElseThrow();
        assertThat(mail.getRecipient()).isEqualTo(customer.email());
        assertThat(mail.getSubject()).endsWith("Il tuo preventivo " + QuoteView.code(id));
        Matcher link = LINK.matcher(mail.getBodyText());
        assertThat(link.find()).as("the link in %s", mail.getBodyText()).isTrue();
        String token = link.group(1);
        assertThat(jdbc.queryForObject("select token_hash from quote where id = ?", String.class, id))
                .isEqualTo(QuoteTokens.hash(token))
                .isNotEqualTo(token);

        String page = mvc.perform(get("/preventivi/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains(QuoteView.code(id))
                .contains("180,50")
                .contains("Sponda idraulica")
                .contains("href=\"/area-clienti/preventivi/" + id + "/ordina\"")
                .doesNotContain("data-quote-closed");
        byte[] pdf = mvc.perform(get("/preventivi/" + token + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(get("/preventivi/" + QuoteTokens.newToken())).andExpect(status().isNotFound());
        mvc.perform(get("/preventivi/non-un-token")).andExpect(status().isNotFound());
        mvc.perform(get("/gestione/preventivi/" + id + "/pdf").session(staff)).andExpect(status().isOk());
    }

    @Test
    void theCustomerOrdersTheQuoteOnceAfterSigningIn() throws Exception {
        long lead = linkedLead();
        QuoteView quote = quote(lead, quotes.today().plusDays(10));
        String order = "/area-clienti/preventivi/" + quote.id() + "/ordina";

        mvc.perform(get(order)).andExpect(status().is3xxRedirection());
        CustomerTestData.Ids other =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE");
        mvc.perform(get(order).session(CustomerTestData.session(other))).andExpect(status().isNotFound());
        assertThat(page(get("/area-clienti/ordini/").session(customerSession)))
                .contains("data-open-quotes")
                .contains("href=\"" + order + "\"");
        assertThat(page(get(order).session(customerSession)))
                .contains("Epal 120 × 80 × 150 cm")
                .contains("value=\"Torino\"")
                .contains("value=\"10121\"");

        assertThat(page(post(order).session(customerSession).with(csrf()).params(recipient(""))))
                .as("the transport document is required (Q-24)")
                .contains("data-form-errors");
        mvc.perform(post(order)
                        .session(customerSession)
                        .with(csrf())
                        .params(recipient("DDT-PRV-" + tag))
                        .param("line.0.quantity", "99"))
                .andExpect(redirectedUrlPattern("/area-clienti/ordini/TEMP-*/"));

        Map<String, Object> made = jdbc.queryForMap(
                "select o.id, o.status, o.accessory_service_id, l.quantity, l.weight_kg from shipment_order o"
                        + " join order_line l on l.order_id = o.id where o.transport_document_code = ?",
                "DDT-PRV-" + tag);
        assertThat(made)
                .containsEntry("status", "PENDING")
                .containsEntry("accessory_service_id", 2)
                .as("the quote's lines, whatever the request carries")
                .containsEntry("quantity", 2)
                .containsEntry("weight_kg", new BigDecimal("300.00"));
        long orderId = ((Number) made.get("id")).longValue();
        assertThat(jdbc.queryForMap("select status, order_id from quote where id = ?", quote.id()))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("order_id", orderId);
        assertThat(jdbc.queryForMap("select status, converted_order_id from lead where id = ?", lead))
                .containsEntry("status", "WON")
                .containsEntry("converted_order_id", orderId);
        assertThat(jdbc.queryForObject(
                        "select actor_type from audit_log where action = 'quote.accept' and entity_id = ?",
                        String.class,
                        quote.id()))
                .isEqualTo("CUSTOMER");

        assertThat(page(post(order).session(customerSession).with(csrf()).params(recipient("DDT-PRV2-" + tag))))
                .contains("Hai già trasformato il preventivo " + quote.code() + " in un ordine.");
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where transport_document_code = ?",
                        Integer.class,
                        "DDT-PRV2-" + tag))
                .isZero();
        assertThat(page(get("/area-clienti/ordini/").session(customerSession))).doesNotContain("data-open-quotes");

        assertThat(page(get("/gestione/ordini/" + orderId + "/").session(staff)))
                .contains("data-order-quote")
                .contains(quote.code());
        assertThat(page(get("/gestione/ordini/" + orderId + "/approva").session(staff)))
                .contains("name=\"importo\" type=\"text\"")
                .contains("value=\"180,50\"")
                .contains("data-quote-amount");
    }

    @Test
    void anExpiredOrWithdrawnQuoteIsNotOrdered() throws Exception {
        long lead = linkedLead();
        QuoteView expiring = quote(lead, quotes.today());
        jdbc.update(
                "update quote set valid_until = ? where id = ?",
                Date.valueOf(quotes.today().minusDays(1)),
                expiring.id());
        String token = token(expiring.id());
        assertThat(page(get("/preventivi/" + token)))
                .contains("data-quote-closed")
                .contains("è scaduto il "
                        + quotes.today()
                                .minusDays(1)
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .doesNotContain("data-order-quote");
        assertThat(page(post("/area-clienti/preventivi/" + expiring.id() + "/ordina")
                        .session(customerSession)
                        .with(csrf())
                        .params(recipient("DDT-SCADUTO-" + tag))))
                .contains("data-quote-closed")
                .doesNotContain("data-quote-order");

        QuoteView withdrawn = quote(lead, quotes.today().plusDays(5));
        mvc.perform(post("/gestione/preventivi/" + withdrawn.id() + "/ritira")
                        .session(staff)
                        .with(csrf()))
                .andExpect(redirectedUrl("/gestione/richieste/" + lead + "/"))
                .andExpect(flash().attribute("flash", org.hamcrest.Matchers.containsString("ritirato")));
        assertThat(page(get("/preventivi/" + token(withdrawn.id())))).contains("è stato ritirato");
        mvc.perform(post("/gestione/preventivi/" + withdrawn.id() + "/ritira")
                        .session(staff)
                        .with(csrf()))
                .andExpect(flash().attribute(
                                "flashError", "Il preventivo " + withdrawn.code() + " non è più ritirabile."));
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where transport_document_code like ?",
                        Integer.class,
                        "DDT-SCADUTO-" + tag + "%"))
                .isZero();
    }

    @Test
    void aQuoteNeedsALinkedActiveCustomerAndValidFields() throws Exception {
        long unlinked = lead("senza-cliente-" + tag + "@cliente.example");
        assertThat(page(get("/gestione/richieste/" + unlinked + "/").session(staff)))
                .doesNotContain("data-create-quote");
        assertThat(page(save(unlinked, "100", quotes.today().plusDays(5))))
                .contains("Collega prima la richiesta a un cliente attivo");

        long lead = linkedLead();
        assertThat(page(save(lead, "zero", quotes.today().plusDays(5))))
                .contains("Inserisci un importo maggiore di zero");
        assertThat(page(save(lead, "100", quotes.today().minusDays(1))))
                .contains("Scegli fino a quando vale il preventivo");
        assertThat(page(post("/gestione/richieste/" + lead + "/preventivo")
                        .session(staff)
                        .with(csrf())
                        .param("action", "save")
                        .param("amount", "100")
                        .param("validUntil", quotes.today().plusDays(5).toString())))
                .contains("data-lines-error");
        assertThat(jdbc.queryForObject("select count(*) from quote where lead_id = ?", Integer.class, lead))
                .isZero();
    }

    @Test
    void theCustomersQuotesAreExportedAndErasedWithThem() throws Exception {
        QuoteView quote = quote(linkedLead(), quotes.today().plusDays(10));
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(page(post("/gestione/impostazioni/privacy/esporta")
                        .session(admin)
                        .with(csrf())
                        .param("email", customer.email())))
                .contains("\"preventivi\"")
                .contains(quote.code())
                .contains("IVA esclusa.");
        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(admin)
                        .with(csrf())
                        .param("email", customer.email()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForMap("select status, note, recipient_city from quote where id = ?", quote.id()))
                .containsEntry("status", "WITHDRAWN")
                .containsEntry("note", null)
                .containsEntry("recipient_city", null);
    }

    /** A website request sent with the customer's e-mail and linked to the customer. */
    private long linkedLead() {
        long id = lead(customer.email());
        leads.linkCustomer(id, operator.getId(), customer.customerId());
        return id;
    }

    private long lead(String email) {
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
        fields.put("servizi_extra[]", "Sponda idraulica");
        fields.put("nome", "Mario Prova");
        fields.put("email", email);
        fields.put("telefono", "+39 333 000 0001");
        fields.put("contatto_preferito", "email");
        fields.put("privacy", "accettata");
        Map<String, String[]> parameters = new LinkedHashMap<>();
        fields.forEach((name, value) -> parameters.put(name, new String[] {value}));
        return leads.store(forms.validate(FormSubmission.Form.PREVENTIVO, parameters), "192.0.2.20", "SavedQuotesIT");
    }

    private QuoteView quote(long lead, LocalDate validUntil) {
        return quotes.create(
                lead,
                operator.getId(),
                new QuoteService.Draft(
                        List.of(new OrderDraft.Line(1, LineKind.EPAL, 150, 120, 80, new BigDecimal("300.00"), 2)),
                        "180,50",
                        validUntil,
                        2,
                        "Torino",
                        "10121",
                        "IVA esclusa."));
    }

    /** The token of a quote's EML-11 (the database keeps only its hash). */
    private String token(long quoteId) {
        for (EmailOutbox mail : outbox.findAll()) {
            if ("EML-11".equals(mail.getKind()) && mail.getSubject().endsWith(QuoteView.code(quoteId))) {
                Matcher link = LINK.matcher(mail.getBodyText());
                if (link.find()) {
                    return link.group(1);
                }
            }
        }
        throw new AssertionError("no EML-11 for " + QuoteView.code(quoteId));
    }

    private MockHttpServletRequestBuilder save(long lead, String amount, LocalDate validUntil) {
        return post("/gestione/richieste/" + lead + "/preventivo")
                .session(staff)
                .with(csrf())
                .param("action", "save")
                .param("line.0.kind", "EPAL")
                .param("line.0.heightCm", "150")
                .param("line.0.lengthCm", "120")
                .param("line.0.widthCm", "80")
                .param("line.0.weightKg", "300")
                .param("line.0.quantity", "2")
                .param("amount", amount)
                .param("validUntil", validUntil.toString())
                .param("accessoryServiceId", "2")
                .param("recipientCity", "Torino")
                .param("recipientPostalCode", "10121")
                .param("note", "IVA esclusa.");
    }

    private static org.springframework.util.MultiValueMap<String, String> recipient(String ddt) {
        org.springframework.util.LinkedMultiValueMap<String, String> params =
                new org.springframework.util.LinkedMultiValueMap<>();
        params.add("transportDocumentCode", ddt);
        params.add("packageCount", "");
        params.add("notes", "");
        params.add("name", "Ricevente Torino S.r.l.");
        params.add("addressLine", "Corso Francia 10");
        params.add("province", "TO");
        params.add("city", "Torino");
        params.add("postalCode", "10121");
        params.add("phone", "");
        params.add("email", "");
        return params;
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
