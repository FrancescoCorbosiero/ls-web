package it.logisticasanchez.web.orders;

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
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CUS-07 (owner decision of 2026-10-25 on Q-12; ADR 0028): while the office has not confirmed it,
 * the customer changes its own order with the form of a new one, on the version the page showed
 * (a change meanwhile brings the current data back), or withdraws it after a confirmation page
 * (CANCELLED, nothing deleted); the office gets an EML-06 notice each time and the audit log the
 * change. An order the office handled is closed; another customer's order is a 404.
 */
@Tag("CUS-07")
@Tag("EML-06")
@SpringBootTest
@AutoConfigureMockMvc
class CustomerOrderChangesIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private BusinessProperties business;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;
    private OrderView order;
    private String base;

    @BeforeEach
    void reset() {
        ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("modifica"), "ACTIVE", "Mittente Modifiche S.r.l.");
        session = CustomerTestData.session(ids);
        order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        base = "/area-clienti/ordini/" + order.tempCode() + "/";
        outbox.deleteAll();
    }

    @Test
    void theCustomerChangesItsPendingOrderWithTheSameForm() throws Exception {
        int version = version();
        assertThat(html(base)).contains("href=\"" + base + "modifica\"").contains("href=\"" + base + "annulla\"");
        assertThat(html(base + "modifica"))
                .contains("Modifica l&#39;ordine " + order.tempCode())
                .contains("name=\"versione\" value=\"" + version + "\"")
                .contains("name=\"line.0.heightCm\" value=\"180\"")
                .contains("name=\"line.2.weightKg\" value=\"12\"")
                .doesNotContain("name=\"line.3.kind\"")
                .contains("value=\"DDT-2026-0042\"")
                .contains("value=\"Magazzino Destinatario S.r.l.\"")
                .contains("data-total-weight>1.236,00<")
                .contains("Salva le modifiche");

        // «Aggiungi riga» without JavaScript keeps the version the page was built on
        assertThat(page(edit("addLine", changed(), version)))
                .contains("name=\"line.2.kind\"")
                .contains("name=\"versione\" value=\"" + version + "\"");
        // the rules of a new order apply; nothing is saved while one fails
        Map<String, String> invalid = changed();
        invalid.put("transportDocumentCode", "");
        assertThat(page(edit("save", invalid, version)))
                .contains("data-form-errors")
                .contains("Campo obbligatorio.");
        assertThat(version()).isEqualTo(version);

        mvc.perform(edit("save", changed(), version))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(base));
        Map<String, Object> row = jdbc.queryForMap("select * from shipment_order where id = ?", order.id());
        assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("temp_code", order.tempCode())
                .containsEntry("transport_document_code", "DDT-2026-0099")
                .containsEntry("recipient_name", "Nuovo Destinatario S.r.l.")
                .containsEntry("recipient_city", "Como")
                .containsEntry("recipient_postal_code", "22100")
                .containsEntry("sender_name", "Mittente Modifiche S.r.l.")
                .containsEntry("edited_by_type", "CUSTOMER")
                .containsEntry("source", "CUSTOMER_AREA");
        assertThat(row.get("package_count")).isNull();
        assertThat(row.get("accessory_service_id")).isNull();
        assertThat(row.get("customer_notes")).isNull();
        assertThat(row.get("edited_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(version + 1);
        assertThat((BigDecimal) row.get("total_weight_kg")).isEqualByComparingTo("436");
        assertThat((BigDecimal) row.get("total_volume_m3")).isEqualByComparingTo("1.55");
        assertThat(jdbc.queryForList(
                        "select line_no from order_line where order_id = ? order by line_no",
                        Integer.class,
                        order.id()))
                .containsExactly(1, 2);

        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        EmailOutbox notice = mails.get(0);
        assertThat(notice.getKind()).isEqualTo("EML-06");
        assertThat(notice.getRecipient()).isEqualTo(business.officeEmail());
        assertThat(notice.getReplyTo()).isEqualTo(ids.email());
        assertThat(notice.getSubject())
                .isEqualTo(
                        "Logistica Sanchez - Richiesta di spedizione " + order.tempCode() + " modificata dal cliente");
        assertThat(notice.getBodyText()).contains("DDT-2026-0099").contains("/gestione/ordini/" + order.id() + "/");
        assertThat(audits("order.update")).isEqualTo(1);
        assertThat(html(base)).contains("Ultima modifica fatta da te").contains("DDT-2026-0099");
        assertThat(html("/gestione/ordini/" + order.id() + "/", staffSession())).contains("data-edited");

        // the page of before the change is stale: nothing is saved, the current data come back
        Map<String, String> stale = changed();
        stale.put("transportDocumentCode", "DDT-STALE");
        assertThat(page(edit("save", stale, version)))
                .contains("L&#39;ordine è stato modificato nel frattempo")
                .contains("value=\"DDT-2026-0099\"")
                .contains("name=\"versione\" value=\"" + (version + 1) + "\"");
        assertThat(jdbc.queryForObject(
                        "select transport_document_code from shipment_order where id = ?", String.class, order.id()))
                .isEqualTo("DDT-2026-0099");
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void theCustomerWithdrawsItsPendingOrder() throws Exception {
        int version = version();
        long pendingBefore = orders.countPendingApproval();
        assertThat(html(base + "annulla"))
                .contains("Vuoi davvero annullare questo ordine?")
                .contains("name=\"versione\" value=\"" + version + "\"");
        // a stale page cancels nothing
        mvc.perform(post(base + "annulla").session(session).with(csrf()).param("versione", "99"))
                .andExpect(status().isOk());
        assertThat(orderStatus(order.id())).isEqualTo("PENDING");

        mvc.perform(post(base + "annulla").session(session).with(csrf()).param("versione", Integer.toString(version)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/area-clienti/ordini/"));
        Map<String, Object> row = jdbc.queryForMap("select * from shipment_order where id = ?", order.id());
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("cancelled_by_type", "CUSTOMER");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("cancelled_by")).isNull();
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from order_line where order_id = ?", Long.class, order.id()))
                .as("nothing is deleted")
                .isEqualTo(3);
        assertThat(orders.countPendingApproval()).isEqualTo(pendingBefore - 1);
        assertThat(orders.pendingApproval()).extracting(OrderView::id).doesNotContain(order.id());

        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).extracting(EmailOutbox::getKind).containsExactly("EML-06");
        assertThat(mails.get(0).getSubject())
                .isEqualTo(
                        "Logistica Sanchez - Richiesta di spedizione " + order.tempCode() + " annullata dal cliente");
        assertThat(audits("order.cancel")).isEqualTo(1);

        assertThat(html(base))
                .contains("data-cancelled")
                .contains("Hai annullato questo ordine oggi alle")
                .doesNotContain("href=\"" + base + "modifica\"");
        assertThat(html("/area-clienti/ordini/?stato=chiusi")).contains("data-order=\"" + order.tempCode() + "\"");
        // closed for good
        mvc.perform(get(base + "modifica").session(session)).andExpect(redirectedUrl(base));
        mvc.perform(get(base + "annulla").session(session)).andExpect(redirectedUrl(base));
        assertThatThrownBy(() ->
                        orders.cancelByCustomer(ids.customerId(), ids.customerUserId(), order.tempCode(), version + 1))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void anOrderTheOfficeHandledIsClosed() throws Exception {
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        String code = "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        orders.approve(order.id(), operator.getId(), code, "180,50");
        int version = version();
        String approved = "/area-clienti/ordini/" + code + "/";
        assertThat(html(approved)).doesNotContain("/modifica\"").doesNotContain("/annulla\"");
        mvc.perform(get(approved + "modifica").session(session)).andExpect(redirectedUrl(approved));
        mvc.perform(edit(approved + "modifica", "save", changed(), version)).andExpect(redirectedUrl(approved));
        mvc.perform(post(approved + "annulla")
                        .session(session)
                        .with(csrf())
                        .param("versione", Integer.toString(version)))
                .andExpect(redirectedUrl(approved));
        assertThat(orderStatus(order.id())).isEqualTo("APPROVED");
        assertThat(version()).isEqualTo(version);
        assertThatThrownBy(() -> orders.updateByCustomer(
                        ids.customerId(),
                        ids.customerUserId(),
                        code,
                        version,
                        OrderTestSupport.exampleDraft().content()))
                .isInstanceOf(RuleViolationException.class);

        OrderView declined = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        orders.decline(declined.id(), operator.getId(), "Fuori zona");
        String path = "/area-clienti/ordini/" + declined.tempCode() + "/";
        mvc.perform(get(path + "modifica").session(session)).andExpect(redirectedUrl(path));
        assertThat(orderStatus(declined.id())).isEqualTo("DECLINED");
    }

    @Test
    void anotherCustomersOrderIsNotFound() throws Exception {
        CustomerTestData.Ids other = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE", "Altro Cliente S.r.l.");
        MockHttpSession otherSession = CustomerTestData.session(other);
        mvc.perform(get(base + "modifica").session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get(base + "annulla").session(otherSession)).andExpect(status().isNotFound());
        MockHttpServletRequestBuilder update = post(base + "modifica")
                .session(otherSession)
                .with(csrf())
                .param("action", "save")
                .param("versione", Integer.toString(version()));
        changed().forEach(update::param);
        mvc.perform(update).andExpect(status().isNotFound());
        mvc.perform(post(base + "annulla")
                        .session(otherSession)
                        .with(csrf())
                        .param("versione", Integer.toString(version())))
                .andExpect(status().isNotFound());
        assertThat(orderStatus(order.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "select transport_document_code from shipment_order where id = ?", String.class, order.id()))
                .isEqualTo("DDT-2026-0042");
        assertThat(outbox.findAll()).isEmpty();
    }

    /** Two lines instead of three, other shipment data, another recipient. */
    private static Map<String, String> changed() {
        Map<String, String> params = new LinkedHashMap<>();
        OrderWizardIT.row(params, 0, "EPAL", "150", "120", "80", "400", "1");
        OrderWizardIT.row(params, 1, "GENERIC", "40", "30", "30", "12", "3");
        params.put("transportDocumentCode", "DDT-2026-0099");
        params.put("packageCount", "");
        params.put("accessoryServiceId", "");
        params.put("notes", "");
        params.put("name", "Nuovo Destinatario S.r.l.");
        params.put("addressLine", "Via Milano 5");
        params.put("province", "CO");
        params.put("city", "Como");
        params.put("postalCode", "22100");
        params.put("phone", "");
        params.put("email", "");
        return params;
    }

    private MockHttpServletRequestBuilder edit(String action, Map<String, String> params, int version) {
        return edit(base + "modifica", action, params, version);
    }

    private MockHttpServletRequestBuilder edit(String path, String action, Map<String, String> params, int version) {
        MockHttpServletRequestBuilder builder = post(path)
                .session(session)
                .with(csrf())
                .accept("text/html")
                .param("action", action)
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

    private String html(String path) throws Exception {
        return html(path, session);
    }

    private String html(String path, MockHttpSession s) throws Exception {
        return mvc.perform(get(path).session(s).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockHttpSession staffSession() {
        return StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR));
    }

    private int version() {
        return jdbc.queryForObject("select version from shipment_order where id = ?", Integer.class, order.id());
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from shipment_order where id = ?", String.class, orderId);
    }

    private long audits(String action) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and actor_id = ? and entity_id = ?",
                Long.class,
                action,
                ids.customerUserId(),
                order.id());
    }
}
