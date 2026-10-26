package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.AddressView;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ENH-07 with {@code customers.addressBook} and {@code orders.repeat} on (ADR 0033): the customer
 * keeps saved recipients in the profile (add, change, delete; same rules as an order's recipient;
 * no duplicates; another customer's entry is a 404), picks one in the order form («Usa questo
 * destinatario» fills the recipient, without JavaScript) and saves a recipient when the order is
 * sent; staff entering an order by phone (ADM-11) use and fill the customer's book; «Ordina di
 * nuovo» fills the form from an order with a new transport document to type, for the customer
 * and in the back office; the export and the erasure (NFR-03) cover the book. Every change is
 * audited on the customer.
 */
@Tag("ENH-07")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {"ls.flags.override.[customers.addressBook]=true", "ls.flags.override.[orders.repeat]=true"})
class AddressBookIT extends AbstractPostgresIT {

    private static final String PROFILE = "/area-clienti/profilo/";
    private static final String BOOK = "/area-clienti/profilo/rubrica/";
    private static final String NEW = "/area-clienti/ordini/nuovo/";
    private static final String REVIEW = "/area-clienti/ordini/nuovo/riepilogo/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AddressBookService book;

    @Autowired
    private OrderService orders;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;

    @BeforeEach
    void customer() {
        ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("rubrica"), "ACTIVE", "Mittente Rubrica S.r.l.");
        session = CustomerTestData.session(ids);
    }

    /** The orders sent here queue e-mails the worker (off in tests) never sends: leave no queue behind. */
    @AfterEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void theCustomerManagesTheAddressBookInTheProfile() throws Exception {
        assertThat(html(PROFILE, session))
                .contains("id=\"rubrica\"")
                .contains("La rubrica è vuota")
                .contains("href=\"/area-clienti/profilo/rubrica/nuovo\"");
        assertThat(html(BOOK + "nuovo", session)).contains("Nuovo destinatario").contains("name=\"postalCode\"");

        Map<String, String> invalid = entry("Cantiere Nord", "Via dei Mille 4", "", "MB", "2084");
        assertThat(page(post(BOOK + "nuovo"), invalid))
                .contains("data-form-errors")
                .contains("Campo obbligatorio.")
                .contains("Inserisci un CAP di cinque cifre.")
                .contains("value=\"Cantiere Nord\"");
        assertThat(rows()).isZero();

        Map<String, String> valid = entry("Cantiere Nord", "Via dei Mille 4", "Lissone", "MB", "20851");
        valid.put("phone", "039 1234567");
        valid.put("email", "Magazzino@Cantiere.example");
        mvc.perform(form(post(BOOK + "nuovo"), valid))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(PROFILE + "#rubrica"));
        Map<String, Object> row =
                jdbc.queryForMap("select * from customer_address where customer_id = ?", ids.customerId());
        assertThat(row)
                .containsEntry("name", "Cantiere Nord")
                .containsEntry("city", "Lissone")
                .containsEntry("postal_code", "20851")
                .containsEntry("phone", "+390391234567")
                .containsEntry("email", "magazzino@cantiere.example");
        long id = ((Number) row.get("id")).longValue();
        assertThat(audits("address.create")).isEqualTo(1);

        // the same name, street and CAP (case and spaces aside) is the same entry
        assertThat(page(post(BOOK + "nuovo"), entry(" cantiere nord ", "VIA DEI MILLE 4", "Lissone", "MB", "20851")))
                .contains("Questo destinatario è già nella rubrica.");
        assertThat(rows()).isEqualTo(1);

        assertThat(html(PROFILE, session))
                .contains("data-address=\"" + id + "\"")
                .contains("Via dei Mille 4, 20851 Lissone (MB)")
                .doesNotContain("La rubrica è vuota");
        assertThat(html(BOOK + id, session))
                .contains("Modifica il destinatario")
                .contains("value=\"+390391234567\"");
        mvc.perform(form(post(BOOK + id), entry("Cantiere Nord", "Via dei Mille 6", "Lissone", "MB", "20851")))
                .andExpect(redirectedUrl(PROFILE + "#rubrica"));
        assertThat(jdbc.queryForObject("select address_line from customer_address where id = ?", String.class, id))
                .isEqualTo("Via dei Mille 6");
        assertThat(audits("address.update")).isEqualTo(1);

        // another customer's entry does not exist for this one
        CustomerTestData.Ids other =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE");
        MockHttpSession otherSession = CustomerTestData.session(other);
        mvc.perform(get(BOOK + id).session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(post(BOOK + id + "/elimina").session(otherSession).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(form(post(BOOK + id), entry("Preso", "Via Altra 1", "Como", "CO", "22100"), otherSession))
                .andExpect(status().isNotFound());
        assertThat(html(PROFILE, otherSession)).doesNotContain("Cantiere Nord");
        assertThat(rows()).isEqualTo(1);

        mvc.perform(post(BOOK + id + "/elimina").session(session).with(csrf()))
                .andExpect(redirectedUrl(PROFILE + "#rubrica"));
        assertThat(rows()).isZero();
        assertThat(audits("address.delete")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action like 'address.%' and entity_type = 'customer'"
                                + " and entity_id = ? and actor_type = 'CUSTOMER' and actor_id = ?",
                        Long.class, ids.customerId(), ids.customerUserId()))
                .isEqualTo(3);
    }

    @Test
    void theOrderFormPicksASavedRecipientAndSavesANewOne() throws Exception {
        AddressView saved = book.create(
                        ids.customerId(),
                        AddressBookService.Actor.customer(ids.customerUserId()),
                        entry("Deposito Sud", "Via Napoli 3", "Bari", "BA", "70121"))
                .address();
        String blank = html(NEW, session);
        assertThat(blank)
                .contains("data-address-book")
                .contains("value=\"" + saved.id() + "\"")
                .contains("Deposito Sud – Bari (BA)")
                .contains("data-postal-code=\"70121\"")
                .contains("name=\"saveRecipient\"")
                .contains("Salva il destinatario nella rubrica");

        // «Usa questo destinatario» without JavaScript: the recipient is filled, everything else kept
        Map<String, String> typed = OrderWizardIT.example(Map.of("name", "", "addressLine", "", "city", ""));
        typed.put("addressId", Long.toString(saved.id()));
        assertThat(page(post(NEW).param("action", "useAddress"), typed))
                .contains("value=\"Deposito Sud\"")
                .contains("value=\"Via Napoli 3\"")
                .contains("value=\"Bari\"")
                .contains("value=\"70121\"")
                .contains("name=\"line.2.weightKg\" value=\"12\"")
                .doesNotContain("data-form-errors");
        // never another customer's entry
        CustomerTestData.Ids other =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE");
        AddressView foreign = book.create(
                        other.customerId(),
                        AddressBookService.Actor.customer(other.customerUserId()),
                        entry("Segreto Altrui", "Via Nascosta 9", "Torino", "TO", "10121"))
                .address();
        typed.put("addressId", Long.toString(foreign.id()));
        assertThat(page(post(NEW).param("action", "useAddress"), typed))
                .contains("Scegli un destinatario della rubrica.")
                .doesNotContain("Segreto Altrui");

        // «Salva il destinatario nella rubrica»: saved when the order is sent
        Map<String, String> fresh = OrderWizardIT.example(Map.of("name", "Nuovo Cliente Finale", "city", "Monza"));
        fresh.put("saveRecipient", "1");
        mvc.perform(form(post(NEW).param("action", "save"), fresh)).andExpect(redirectedUrl(REVIEW));
        assertThat(html(REVIEW, session)).contains("data-save-recipient");
        assertThat(rows()).isEqualTo(1);
        mvc.perform(post(REVIEW).session(session).with(csrf())).andExpect(status().isFound());
        assertThat(book.list(ids.customerId()))
                .extracting(AddressView::name)
                .containsExactly("Deposito Sud", "Nuovo Cliente Finale");
        // the same recipient again updates its entry instead of adding one
        Map<String, String> again =
                OrderWizardIT.example(Map.of("name", "Nuovo Cliente Finale", "city", "Monza", "phone", "039 7654321"));
        again.put("saveRecipient", "1");
        mvc.perform(form(post(NEW).param("action", "save"), again)).andExpect(redirectedUrl(REVIEW));
        mvc.perform(post(REVIEW).session(session).with(csrf())).andExpect(status().isFound());
        assertThat(book.list(ids.customerId())).hasSize(2);
        assertThat(jdbc.queryForObject(
                        "select phone from customer_address where customer_id = ? and name = 'Nuovo Cliente Finale'",
                        String.class,
                        ids.customerId()))
                .isEqualTo("+390397654321");
        // a recipient not to keep is not kept
        mvc.perform(form(post(NEW).param("action", "save"), OrderWizardIT.example(Map.of("name", "Una Tantum"))))
                .andExpect(redirectedUrl(REVIEW));
        mvc.perform(post(REVIEW).session(session).with(csrf())).andExpect(status().isFound());
        assertThat(book.list(ids.customerId())).hasSize(2);
    }

    @Test
    void staffUseTheCustomersAddressBookForAnOrderByPhone() throws Exception {
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        MockHttpSession staff = StaffTestData.session(operator);
        AddressView saved = book.create(
                        ids.customerId(),
                        AddressBookService.Actor.customer(ids.customerUserId()),
                        entry("Punto Vendita Est", "Corso Venezia 1", "Verona", "VR", "37121"))
                .address();
        assertThat(html("/gestione/ordini/nuovo", staff))
                .contains("value=\"showAddressBook\"")
                .doesNotContain("data-address-book");
        assertThat(html("/gestione/ordini/nuovo?cliente=" + ids.customerId(), staff))
                .contains("data-address-book")
                .contains("Punto Vendita Est – Verona (VR)")
                .contains("Salva il destinatario nella rubrica del cliente");

        // the customer chosen in the list, then «Mostra la rubrica del cliente»: the page again with its book
        Map<String, String> typed = OrderWizardIT.example(Map.of("name", ""));
        typed.put("cliente", Long.toString(ids.customerId()));
        typed.put("chiave", "4f1d3c1e-7a51-4d5e-9b5e-0c6d2c8a9b10");
        assertThat(page(post("/gestione/ordini/nuovo").param("action", "showAddressBook"), typed, staff))
                .contains("Punto Vendita Est – Verona (VR)")
                .doesNotContain("data-form-errors");
        typed.put("addressId", Long.toString(saved.id()));
        assertThat(page(post("/gestione/ordini/nuovo").param("action", "useAddress"), typed, staff))
                .contains("value=\"Punto Vendita Est\"")
                .contains("value=\"Corso Venezia 1\"");

        // a new recipient saved in the customer's book by staff: audited as theirs
        Map<String, String> phoned = OrderWizardIT.example(Map.of("name", "Filiale Telefonica", "city", "Padova"));
        phoned.put("cliente", Long.toString(ids.customerId()));
        phoned.put("chiave", "0b7c9a52-3d3e-4f36-8d1e-2b8c4b0d6e21");
        phoned.put("saveRecipient", "1");
        mvc.perform(form(post("/gestione/ordini/nuovo").param("action", "save"), phoned, staff))
                .andExpect(status().isFound());
        assertThat(book.list(ids.customerId()))
                .extracting(AddressView::name)
                .containsExactly("Filiale Telefonica", "Punto Vendita Est");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'address.create' and entity_id = ?"
                                + " and actor_type = 'STAFF' and actor_id = ?",
                        Long.class,
                        ids.customerId(),
                        operator.getId()))
                .isEqualTo(1);
    }

    @Test
    void orderAgainFillsTheFormWithANewTransportDocumentToType() throws Exception {
        OrderView first = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        String detail = "/area-clienti/ordini/" + first.tempCode() + "/";
        assertThat(html(detail, session))
                .contains("action=\"" + detail + "ripeti\"")
                .contains("Ordina di nuovo");

        mvc.perform(post(detail + "ripeti").session(session).with(csrf())).andExpect(redirectedUrl(NEW));
        String form = html(NEW, session);
        assertThat(form)
                .contains("Il modulo riporta i dati dell&#39;ordine " + first.tempCode())
                .contains("name=\"line.0.heightCm\" value=\"180\"")
                .contains("name=\"line.2.weightKg\" value=\"12\"")
                .contains("value=\"Magazzino Destinatario S.r.l.\"")
                .contains("Consegna al mattino</textarea>")
                .contains("<option value=\"2\" selected=\"selected\">")
                .contains("id=\"transportDocumentCode\" name=\"transportDocumentCode\" type=\"text\" maxlength=\"100\""
                        + " autocomplete=\"off\" value=\"\"")
                .contains(
                        "id=\"packageCount\" name=\"packageCount\" type=\"text\" inputmode=\"numeric\" maxlength=\"6\""
                                + " autocomplete=\"off\" value=\"\"");
        // the transport document is still required
        Map<String, String> repeated = OrderWizardIT.example(Map.of("transportDocumentCode", "", "packageCount", ""));
        assertThat(page(post(NEW).param("action", "save"), repeated)).contains("Campo obbligatorio.");
        repeated.put("transportDocumentCode", "DDT-2026-0500");
        mvc.perform(form(post(NEW).param("action", "save"), repeated)).andExpect(redirectedUrl(REVIEW));
        mvc.perform(post(REVIEW).session(session).with(csrf())).andExpect(status().isFound());
        List<OrderView> mine = orders.listForCustomer(ids.customerId());
        assertThat(mine).hasSize(2);
        OrderView second = mine.get(0);
        assertThat(second.tempCode()).isNotEqualTo(first.tempCode());
        assertThat(second.isPending()).isTrue();
        assertThat(second.transportDocumentCode()).isEqualTo("DDT-2026-0500");
        assertThat(second.lines()).hasSameSizeAs(first.lines());
        assertThat(second.totalWeightKg()).isEqualByComparingTo(first.totalWeightKg());

        // only the customer's own orders, and only while it can order
        CustomerTestData.Ids other =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE");
        mvc.perform(post(detail + "ripeti")
                        .session(CustomerTestData.session(other))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        jdbc.update("update customer set status = 'DECLINED', declined_at = now() where id = ?", ids.customerId());
        assertThat(html(detail, session)).doesNotContain("ripeti\"");
        mvc.perform(post(detail + "ripeti").session(session).with(csrf()))
                .andExpect(redirectedUrl("/area-clienti/ordini/"));
    }

    @Test
    void staffRepeatAnOrderFromItsPage() throws Exception {
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        MockHttpSession staff = StaffTestData.session(operator);
        OrderView first = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        assertThat(html("/gestione/ordini/" + first.id() + "/", staff))
                .contains("href=\"/gestione/ordini/nuovo?ripeti=" + first.id() + "\"");
        assertThat(html("/gestione/ordini/nuovo?ripeti=" + first.id(), staff))
                .contains("Il modulo riporta i dati dell&#39;ordine " + first.tempCode())
                .contains("<option value=\"" + ids.customerId() + "\" selected=\"selected\">")
                .contains("name=\"line.0.heightCm\" value=\"180\"")
                .contains("value=\"Magazzino Destinatario S.r.l.\"")
                .contains("id=\"transportDocumentCode\" name=\"transportDocumentCode\" type=\"text\" maxlength=\"100\""
                        + " autocomplete=\"off\" value=\"\"");
    }

    @Test
    void theExportCarriesTheAddressBookAndTheErasureDeletesIt() throws Exception {
        StaffUser admin = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        MockHttpSession adminSession = StaffTestData.session(admin);
        book.create(
                ids.customerId(),
                AddressBookService.Actor.customer(ids.customerUserId()),
                entry("Destinatario Riservato", "Via Privata 7", "Lecco", "LC", "23900"));
        String export = mvc.perform(post("/gestione/impostazioni/privacy/esporta")
                        .session(adminSession)
                        .with(csrf())
                        .param("email", ids.email()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(export).contains("\"rubricaDestinatari\"").contains("Destinatario Riservato");

        mvc.perform(post("/gestione/impostazioni/privacy/anonimizza")
                        .session(adminSession)
                        .with(csrf())
                        .param("email", ids.email()))
                .andExpect(status().isFound());
        assertThat(rows()).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log_detail d join audit_log l on l.id = d.audit_log_id"
                                + " where l.action like 'address.%' and l.entity_type = 'customer' and l.entity_id = ?",
                        Long.class, ids.customerId()))
                .as("the recipients' values left the audit log with the customer")
                .isZero();
    }

    private static Map<String, String> entry(
            String name, String addressLine, String city, String province, String postalCode) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("addressLine", addressLine);
        values.put("city", city);
        values.put("province", province);
        values.put("postalCode", postalCode);
        return values;
    }

    private long rows() {
        return jdbc.queryForObject(
                "select count(*) from customer_address where customer_id = ?", Long.class, ids.customerId());
    }

    private long audits(String action) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and entity_type = 'customer' and entity_id = ?",
                Long.class,
                action,
                ids.customerId());
    }

    private MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder request, Map<String, String> params) {
        return form(request, params, session);
    }

    private static MockHttpServletRequestBuilder form(
            MockHttpServletRequestBuilder request, Map<String, String> params, MockHttpSession s) {
        MockHttpServletRequestBuilder builder = request.session(s).with(csrf()).accept("text/html");
        params.forEach(builder::param);
        return builder;
    }

    private String page(MockHttpServletRequestBuilder request, Map<String, String> params) throws Exception {
        return page(request, params, session);
    }

    private String page(MockHttpServletRequestBuilder request, Map<String, String> params, MockHttpSession s)
            throws Exception {
        return mvc.perform(form(request, params, s))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String html(String path, MockHttpSession s) throws Exception {
        return mvc.perform(get(path).session(s).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
