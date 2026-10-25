package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import it.logisticasanchez.web.tracking.TrackingLookupService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * ADM-11 (owner decision of 2026-10-25; ADR 0028): staff enter an order for an ACTIVE customer
 * (PENDING, source BACKOFFICE, the customer as sender, one order per form even when submitted
 * twice, no e-mail), change a pending order and cancel it with a reason; an approved order only
 * as ADMIN, who may correct the definitive code (unique) and the amount; a declined or cancelled
 * order is closed; every change on the version the page showed, audited, without e-mails; a
 * cancelled order disappears from public tracking.
 */
@Tag("ADM-11")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeOrderChangesIT extends AbstractPostgresIT {

    private static final Pattern FORM_KEY = Pattern.compile("name=\"chiave\" value=\"([0-9a-f-]{36})\"");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OrderService orders;

    @Autowired
    private TrackingLookupService tracking;

    @Autowired
    private JdbcTemplate jdbc;

    private StaffUser operator;
    private StaffUser admin;
    private MockHttpSession operatorSession;
    private MockHttpSession adminSession;
    private CustomerTestData.Ids customer;

    @BeforeEach
    void reset() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        admin = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        operatorSession = StaffTestData.session(operator);
        adminSession = StaffTestData.session(admin);
        customer = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("telefono"), "ACTIVE", "Cliente Al Telefono S.r.l.");
        outbox.deleteAll();
    }

    @Test
    void staffEnterAnOrderForACustomerOnce() throws Exception {
        String page = html("/gestione/ordini/nuovo?cliente=" + customer.customerId(), operatorSession);
        assertThat(page)
                .contains("Cliente mittente")
                .containsPattern("value=\"" + customer.customerId() + "\" selected=\"selected\"")
                .contains("name=\"line.0.kind\"")
                .contains("Inserisci l&#39;ordine");
        Matcher key = FORM_KEY.matcher(page);
        assertThat(key.find()).isTrue();

        MvcResult created = mvc.perform(create(key.group(1), Long.toString(customer.customerId()), example()))
                .andExpect(status().isFound())
                .andReturn();
        Map<String, Object> row =
                jdbc.queryForMap("select * from shipment_order where customer_id = ?", customer.customerId());
        long id = ((Number) row.get("id")).longValue();
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/gestione/ordini/" + id + "/");
        assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("source", "BACKOFFICE")
                .containsEntry("tracking_state_id", 0)
                .containsEntry("sender_name", "Cliente Al Telefono S.r.l.")
                .containsEntry("sender_email", customer.email())
                .containsEntry("transport_document_code", "DDT-TEL-1")
                .containsEntry("recipient_name", "Magazzino Ricevente S.r.l.")
                .containsEntry("idempotency_key", UUID.fromString(key.group(1)));
        assertThat((String) row.get("temp_code")).matches("TEMP-[A-Z2-9]{8}");
        assertThat(row.get("code")).isNull();
        assertThat((BigDecimal) row.get("total_weight_kg")).isEqualByComparingTo("1236");
        assertThat(outbox.findAll()).as("no e-mail until approval").isEmpty();
        assertThat(audits("order.create", operator, id)).isEqualTo(1);

        // the same form sent twice is one order
        mvc.perform(create(key.group(1), Long.toString(customer.customerId()), example()))
                .andExpect(redirectedUrl("/gestione/ordini/" + id + "/"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where customer_id = ?", Long.class, customer.customerId()))
                .isEqualTo(1);

        assertThat(html("/gestione/ordini/" + id + "/", operatorSession))
                .contains("Inserito dall&#39;ufficio")
                .contains(">Cliente Al Telefono S.r.l.<");
        assertThat(mvc.perform(get("/area-clienti/ordini/").session(CustomerTestData.session(customer)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains((String) row.get("temp_code"));
        // then approved as any other order, with the customer's e-mail
        orders.approve(id, operator.getId(), "TEST-" + suffix(), "99,00");
        assertThat(outbox.findAll()).extracting(m -> m.getKind()).containsExactly("EML-05");
    }

    @Test
    void theSenderIsAnActiveCustomerAndTheFormRulesApply() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(page(create(key, "", example()))).contains("Scegli il cliente mittente.");
        CustomerTestData.Ids waiting = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("attesa"), "PENDING_APPROVAL", "In Attesa S.r.l.");
        assertThat(page(create(key, Long.toString(waiting.customerId()), example())))
                .contains("Il cliente non è attivo: approvalo prima di inserire un ordine.");
        Map<String, String> invalid = example();
        invalid.put("line.0.quantity", "0");
        invalid.put("postalCode", "CAP");
        assertThat(page(create(key, Long.toString(customer.customerId()), invalid)))
                .contains("data-form-errors")
                .contains("Inserisci un numero intero maggiore di zero.")
                .contains("Inserisci un CAP di cinque cifre.")
                .contains("name=\"chiave\" value=\"" + key + "\"");
        // the customer list offers only customers who can order
        assertThat(html("/gestione/ordini/nuovo", operatorSession))
                .contains("value=\"" + customer.customerId() + "\"")
                .doesNotContain("value=\"" + waiting.customerId() + "\"");
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where customer_id in (?, ?)",
                        Long.class,
                        customer.customerId(),
                        waiting.customerId()))
                .isZero();
    }

    @Test
    void anOperatorChangesAndCancelsAPendingOrder() throws Exception {
        OrderView order = pending("DDT-OP-1");
        outbox.deleteAll();
        String base = "/gestione/ordini/" + order.id() + "/";
        int version = version(order.id());
        assertThat(html(base, operatorSession))
                .contains("href=\"" + base + "modifica\"")
                .contains("href=\"" + base + "annulla\"");
        assertThat(html(base + "modifica", operatorSession))
                .contains("name=\"versione\" value=\"" + version + "\"")
                .contains("value=\"DDT-OP-1\"")
                .contains("name=\"line.2.quantity\" value=\"3\"")
                .doesNotContain("name=\"codice\"");

        Map<String, String> changed = example();
        changed.remove("line.2.kind");
        changed.remove("line.2.heightCm");
        changed.remove("line.2.lengthCm");
        changed.remove("line.2.widthCm");
        changed.remove("line.2.weightKg");
        changed.remove("line.2.quantity");
        changed.put("transportDocumentCode", "DDT-OP-2");
        mvc.perform(update(base, changed, version, operatorSession)).andExpect(redirectedUrl(base));
        Map<String, Object> row = jdbc.queryForMap("select * from shipment_order where id = ?", order.id());
        assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("transport_document_code", "DDT-OP-2")
                .containsEntry("edited_by_type", "STAFF")
                .containsEntry("version", version + 1);
        assertThat((BigDecimal) row.get("total_weight_kg")).isEqualByComparingTo("1200");
        assertThat(jdbc.queryForObject("select count(*) from order_line where order_id = ?", Long.class, order.id()))
                .isEqualTo(2);
        assertThat(audits("order.update", operator, order.id())).isEqualTo(1);

        // a page of before the change is stale
        changed.put("transportDocumentCode", "DDT-STALE");
        assertThat(page(update(base, changed, version, operatorSession)))
                .contains("L&#39;ordine è stato modificato nel frattempo")
                .contains("value=\"DDT-OP-2\"");

        // cancel with a reason: kept, closed, no e-mail
        assertThat(html(base + "annulla", operatorSession)).contains("name=\"motivo\"");
        mvc.perform(post(base + "annulla")
                        .session(operatorSession)
                        .with(csrf())
                        .param("versione", Integer.toString(version + 1))
                        .param("motivo", "Il cliente ha annullato per telefono"))
                .andExpect(redirectedUrl(base));
        row = jdbc.queryForMap("select * from shipment_order where id = ?", order.id());
        assertThat(row)
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancelled_by_type", "STAFF")
                .containsEntry("cancelled_by", operator.getId())
                .containsEntry("cancel_reason", "Il cliente ha annullato per telefono");
        assertThat(audits("order.cancel", operator, order.id())).isEqualTo(1);
        assertThat(outbox.findAll()).isEmpty();
        assertThat(html(base, operatorSession))
                .contains("data-cancelled")
                .contains("Il cliente ha annullato per telefono")
                .doesNotContain("href=\"" + base + "modifica\"");
        assertThat(mvc.perform(get("/area-clienti/ordini/" + order.tempCode() + "/")
                                .session(CustomerTestData.session(customer)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Il centro logistico ha annullato questo ordine il");
        // closed for everyone, ADMIN included
        mvc.perform(get(base + "modifica").session(adminSession)).andExpect(redirectedUrl(base));
        mvc.perform(get(base + "annulla").session(adminSession)).andExpect(redirectedUrl(base));

        // the reason has a limit
        OrderView other = pending("DDT-OP-3");
        mvc.perform(post("/gestione/ordini/" + other.id() + "/annulla")
                        .session(operatorSession)
                        .with(csrf())
                        .param("versione", Integer.toString(version(other.id())))
                        .param("motivo", "x".repeat(501)))
                .andExpect(status().isOk());
        assertThat(orderStatus(other.id())).isEqualTo("PENDING");
    }

    @Test
    void onlyAnAdminCorrectsAnApprovedOrder() throws Exception {
        OrderView order = pending("DDT-ADM-1");
        String code = "TEST-" + suffix();
        orders.approve(order.id(), operator.getId(), code, "180,50");
        String taken = "TEST-" + suffix();
        orders.approve(pending("DDT-ADM-2").id(), operator.getId(), taken, "50");
        outbox.deleteAll();
        String base = "/gestione/ordini/" + order.id() + "/";
        int version = version(order.id());

        // an OPERATOR cannot, through the pages or the service
        assertThat(html(base, operatorSession)).doesNotContain("href=\"" + base + "modifica\"");
        mvc.perform(get(base + "modifica").session(operatorSession)).andExpect(redirectedUrl(base));
        mvc.perform(update(base, example(), version, operatorSession)).andExpect(redirectedUrl(base));
        mvc.perform(post(base + "annulla")
                        .session(operatorSession)
                        .with(csrf())
                        .param("versione", Integer.toString(version)))
                .andExpect(redirectedUrl(base));
        assertThatThrownBy(() -> orders.cancelByStaff(order.id(), operator.getId(), false, version, ""))
                .isInstanceOf(RuleViolationException.class);
        assertThat(version(order.id())).isEqualTo(version);

        // an ADMIN sees code and amount, and they are checked as at approval
        assertThat(html(base, adminSession)).contains("href=\"" + base + "modifica\"");
        assertThat(html(base + "modifica", adminSession))
                .containsPattern("name=\"codice\"[^>]*value=\"" + code + "\"")
                .containsPattern("name=\"importo\"[^>]*value=\"180,50\"");
        Map<String, String> corrected = example();
        corrected.put("codice", taken.toLowerCase());
        corrected.put("importo", "230,50");
        assertThat(page(update(base, corrected, version, adminSession)))
                .contains("E&#39; già stata effettuata una registrazione con questo codice.");
        corrected.put("codice", "TEST-NUOVO-" + suffix());
        corrected.put("importo", "0");
        assertThat(page(update(base, corrected, version, adminSession)))
                .contains("Inserisci un importo maggiore di zero");
        assertThat(version(order.id())).isEqualTo(version);

        String newCode = corrected.get("codice");
        corrected.put("importo", "230,50");
        mvc.perform(update(base, corrected, version, adminSession)).andExpect(redirectedUrl(base));
        Map<String, Object> row = jdbc.queryForMap("select * from shipment_order where id = ?", order.id());
        assertThat(row)
                .containsEntry("status", "APPROVED")
                .containsEntry("code", newCode)
                .containsEntry("transport_document_code", "DDT-TEL-1")
                .containsEntry("edited_by_type", "STAFF");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("230.50");
        assertThat(audits("order.update", admin, order.id())).isEqualTo(1);
        assertThat(outbox.findAll()).as("corrections send no e-mail").isEmpty();
        assertThat(tracking.lookup(newCode)).isPresent();
        assertThat(tracking.lookup(code)).isEmpty();

        // an ADMIN cancels it: public tracking answers as for an unknown code
        mvc.perform(post(base + "annulla")
                        .session(adminSession)
                        .with(csrf())
                        .param("versione", Integer.toString(version + 1))
                        .param("motivo", ""))
                .andExpect(redirectedUrl(base));
        assertThat(orderStatus(order.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                        "select cancel_reason from shipment_order where id = ?", String.class, order.id()))
                .isNull();
        assertThat(tracking.lookup(newCode)).isEmpty();
        assertThat(orders.findApprovedByCode(newCode)).isEmpty();
        // the customer sees it cancelled, without the amount of a shipment that will not happen
        assertThat(mvc.perform(get("/area-clienti/ordini/" + newCode + "/").session(CustomerTestData.session(customer)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Il centro logistico ha annullato questo ordine il")
                .doesNotContain("230,50");
    }

    @Test
    void aDeclinedOrderIsClosed() throws Exception {
        OrderView order = pending("DDT-RIF-1");
        orders.decline(order.id(), operator.getId(), "Fuori zona");
        String base = "/gestione/ordini/" + order.id() + "/";
        mvc.perform(get(base + "modifica").session(adminSession)).andExpect(redirectedUrl(base));
        mvc.perform(update(base, example(), version(order.id()), adminSession)).andExpect(redirectedUrl(base));
        assertThatThrownBy(() -> orders.cancelByStaff(order.id(), admin.getId(), true, version(order.id()), ""))
                .isInstanceOf(RuleViolationException.class);
        assertThat(orderStatus(order.id())).isEqualTo("DECLINED");
    }

    private OrderView pending(String ddt) {
        return orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft(ddt, "Destinatario Ufficio", "Lecco", "23900"));
    }

    /** The chapter 03 §6.3 lines as form rows, the shipment data and a recipient. */
    private static Map<String, String> example() {
        Map<String, String> params = new LinkedHashMap<>();
        row(params, 0, "EPAL", "180", "120", "80", "450", "2");
        row(params, 1, "GENERIC", "100", "120", "80", "300", "1");
        row(params, 2, "GENERIC", "40", "30", "30", "12", "3");
        params.put("transportDocumentCode", "DDT-TEL-1");
        params.put("packageCount", "3");
        params.put("accessoryServiceId", "");
        params.put("notes", "Chiamare prima della consegna");
        params.put("name", "Magazzino Ricevente S.r.l.");
        params.put("addressLine", "Via Torino 3");
        params.put("province", "MI");
        params.put("city", "Milano");
        params.put("postalCode", "20123");
        params.put("phone", "");
        params.put("email", "");
        return params;
    }

    private static void row(Map<String, String> params, int index, String... values) {
        String[] fields = {"kind", "heightCm", "lengthCm", "widthCm", "weightKg", "quantity"};
        for (int i = 0; i < fields.length; i++) {
            params.put("line." + index + "." + fields[i], values[i]);
        }
    }

    private MockHttpServletRequestBuilder create(String key, String customerId, Map<String, String> params) {
        MockHttpServletRequestBuilder builder = post("/gestione/ordini/nuovo")
                .session(operatorSession)
                .with(csrf())
                .accept("text/html")
                .param("action", "save")
                .param("chiave", key)
                .param("cliente", customerId);
        params.forEach(builder::param);
        return builder;
    }

    private MockHttpServletRequestBuilder update(
            String base, Map<String, String> params, int version, MockHttpSession session) {
        MockHttpServletRequestBuilder builder = post(base + "modifica")
                .session(session)
                .with(csrf())
                .accept("text/html")
                .param("action", "save")
                .param("versione", Integer.toString(version));
        params.forEach(builder::param);
        return builder;
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String html(String path, MockHttpSession session) throws Exception {
        return mvc.perform(get(path).session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private int version(long orderId) {
        return jdbc.queryForObject("select version from shipment_order where id = ?", Integer.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from shipment_order where id = ?", String.class, orderId);
    }

    private long audits(String action, StaffUser actor, long orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and actor_id = ? and entity_id = ?",
                Long.class,
                action,
                actor.getId(),
                orderId);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
