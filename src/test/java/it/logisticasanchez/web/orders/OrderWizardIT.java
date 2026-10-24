package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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
 * CUS-04 (chapter 09; chapter 03 §6 with the §10 fixes; chapter 08 §2.4 "Order"): the wizard pages
 * with the legacy copy, lines validated (type required, integers ≥ 1), live totals on the §6.3
 * example, DDT required, package count stored, service and notes, recipient rules, the summary,
 * the confirmation creating one PENDING order with a {@code TEMP-} code whose stored data equal
 * the input, the final screen with the corrected brand, EML-04 to the customer and EML-06 to the
 * office; idempotency of the draft; a non-ACTIVE customer refused (CUS-03).
 */
@Tag("CUS-04")
@Tag("CUS-03")
@Tag("EML-04")
@Tag("EML-06")
@SpringBootTest
@AutoConfigureMockMvc
class OrderWizardIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OrderService orders;

    @Autowired
    private BusinessProperties business;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("ordina"), "ACTIVE", "Mittente Attivo S.r.l.");
        session = CustomerTestData.session(ids);
    }

    @Test
    void theHappyPathThroughThePages() throws Exception {
        mvc.perform(get("/area-clienti/ordini/nuovo/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(Matchers.containsString("Inserisci i dati dell&#39;ordine")))
                .andExpect(content().string(Matchers.containsString("Aggiungi Pallet/Colli")))
                .andExpect(content().string(Matchers.containsString("data-total-weight>0,00<")))
                .andExpect(content().string(Matchers.containsString("Numero Colli (facoltativo)")))
                .andExpect(content().string(Matchers.containsString("Documento di trasporto")))
                .andExpect(content().string(Matchers.containsString(">Nessun servizio accessorio<")))
                .andExpect(content().string(Matchers.containsString(">Sponda idraulica<")))
                .andExpect(content().string(Matchers.containsString("Pallet Generico/Colli")))
                .andExpect(content().string(Matchers.containsString(">Epal<")));

        // the §6.3 lines, one by one
        addLine("EPAL", "180", "120", "80", "450", "2").andExpect(status().isFound());
        addLine("GENERIC", "100", "120", "80", "300", "1").andExpect(status().isFound());
        addLine("GENERIC", "40", "30", "30", "12", "3").andExpect(status().isFound());
        String page = mvc.perform(
                        get("/area-clienti/ordini/nuovo/").session(session).accept("text/html"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("data-total-weight>1.236,00<")
                .contains("data-total-volume>4,53<")
                .contains("3,46 m³")
                .contains("0,96 m³")
                .contains("0,11 m³")
                .contains("data-line=\"1\"")
                .contains("data-line=\"3\"")
                .doesNotContain("0.06999");

        // remove and re-add the first line: the totals follow, ids are never reused
        mvc.perform(wizard("/area-clienti/ordini/nuovo/")
                        .param("action", "removeLine")
                        .param("lineId", "1"))
                .andExpect(status().isFound());
        assertThat(pageContent()).contains("data-total-weight>336,00<").doesNotContain("data-line=\"1\"");
        addLine("EPAL", "180", "120", "80", "450", "2").andExpect(status().isFound());
        assertThat(pageContent()).contains("data-total-weight>1.236,00<").contains("data-line=\"4\"");

        // the order fields: DDT missing → error; complete → recipient page
        mvc.perform(wizard("/area-clienti/ordini/nuovo/")
                        .param("action", "next")
                        .param("transportDocumentCode", "  "))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Campo obbligatorio.")));
        mvc.perform(details(Map.of("accessoryServiceId", "99")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Scegli un servizio dall&#39;elenco.")));
        mvc.perform(details(Map.of("packageCount", "tre")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un numero intero maggiore di zero.")));
        mvc.perform(details())
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/nuovo/destinatario/"));

        mvc.perform(get("/area-clienti/ordini/nuovo/destinatario/")
                        .session(session)
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci i dati del destinatario")))
                .andExpect(content().string(Matchers.containsString("Telefono (facoltativo)")))
                .andExpect(content().string(Matchers.containsString("Email del destinatario (facoltativo)")));
        mvc.perform(recipient(Map.of("postalCode", "20121-1234", "name", "")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un CAP di cinque cifre.")))
                .andExpect(content().string(Matchers.containsString("Campo obbligatorio.")));
        mvc.perform(recipient(Map.of("phone", "telefono")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un numero di telefono valido.")));
        mvc.perform(recipient())
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/nuovo/riepilogo/"));

        mvc.perform(get("/area-clienti/ordini/nuovo/riepilogo/")
                        .session(session)
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Controlla i dati inseriti")))
                .andExpect(content().string(Matchers.containsString("data-review-totals>1.236,00 kg · 4,53 m³<")))
                .andExpect(content().string(Matchers.containsString("DDT-2026-0042")))
                .andExpect(content().string(Matchers.containsString("Sponda idraulica")))
                .andExpect(content().string(Matchers.containsString("Magazzino Destinatario S.r.l.")))
                .andExpect(content().string(Matchers.containsString("Conferma l&#39;ordine")));

        MvcResult confirmed = mvc.perform(wizard("/area-clienti/ordini/nuovo/riepilogo/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/nuovo/inviato/"))
                .andReturn();
        Map<String, Object> order =
                jdbc.queryForMap("select * from shipment_order where customer_id = ?", ids.customerId());
        String tempCode = (String) order.get("temp_code");
        assertThat(tempCode).matches("TEMP-[A-Z2-9]{8}");
        assertThat(order)
                .containsEntry("status", "PENDING")
                .containsEntry("transport_document_code", "DDT-2026-0042")
                .containsEntry("package_count", 3)
                .containsEntry("accessory_service_id", 2)
                .containsEntry("customer_notes", "Consegna al mattino")
                .containsEntry("tracking_state_id", 0)
                .containsEntry("sender_name", "Mittente Attivo S.r.l.")
                .containsEntry("sender_address_line", "Via di Prova 1")
                .containsEntry("sender_postal_code", "20831")
                .containsEntry("sender_email", ids.email())
                .containsEntry("sender_phone", "+393330000000")
                .containsEntry("recipient_name", "Magazzino Destinatario S.r.l.")
                .containsEntry("recipient_address_line", "Via Roma 10")
                .containsEntry("recipient_province", "MI")
                .containsEntry("recipient_city", "Milano")
                .containsEntry("recipient_postal_code", "20121")
                .containsEntry("recipient_phone", "+390212345678")
                .containsEntry("recipient_email", "ricevimento@destinatario.example")
                .containsEntry("source", "CUSTOMER_AREA");
        assertThat(order.get("code")).isNull();
        assertThat(order.get("amount")).isNull();
        assertThat(order.get("idempotency_key")).isNotNull();
        assertThat((BigDecimal) order.get("total_weight_kg")).isEqualByComparingTo("1236.00");
        assertThat((BigDecimal) order.get("total_volume_m3")).isEqualByComparingTo("4.53");
        List<Map<String, Object>> lines = jdbc.queryForList(
                "select line_no, kind, height_cm, length_cm, width_cm, weight_kg, quantity, line_weight_kg, line_volume_m3"
                        + " from order_line where order_id = ? order by line_no",
                order.get("id"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0))
                .containsEntry("kind", "GENERIC")
                .containsEntry("height_cm", 100)
                .containsEntry("quantity", 1);
        assertThat(lines.get(1))
                .containsEntry("kind", "GENERIC")
                .containsEntry("height_cm", 40)
                .containsEntry("quantity", 3);
        assertThat(lines.get(2))
                .containsEntry("kind", "EPAL")
                .containsEntry("height_cm", 180)
                .containsEntry("length_cm", 120)
                .containsEntry("width_cm", 80)
                .containsEntry("quantity", 2);
        assertThat((BigDecimal) lines.get(2).get("weight_kg")).isEqualByComparingTo("450");
        assertThat((BigDecimal) lines.get(2).get("line_weight_kg")).isEqualByComparingTo("900");
        assertThat((BigDecimal) lines.get(2).get("line_volume_m3")).isEqualByComparingTo("3.46");

        MockHttpSession after = (MockHttpSession) confirmed.getRequest().getSession(false);
        mvc.perform(get("/area-clienti/ordini/nuovo/inviato/").session(after).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        Matchers.containsString(
                                                "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di attendere"
                                                        + " la conferma via email dal nostro centro logistico. Grazie per aver scelto Logistica Sanchez!")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Sanchez Logistica"))))
                .andExpect(content().string(Matchers.containsString(tempCode)));
        // terminal: reloading the final page or confirming again creates nothing
        mvc.perform(get("/area-clienti/ordini/nuovo/inviato/").session(after)).andExpect(status().isFound());
        mvc.perform(wizard("/area-clienti/ordini/nuovo/riepilogo/").session(after))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/nuovo/"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where customer_id = ?", Long.class, ids.customerId()))
                .isEqualTo(1);

        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).extracting(EmailOutbox::getKind).containsExactlyInAnyOrder("EML-04", "EML-06");
        EmailOutbox received = mails.stream()
                .filter(m -> m.getKind().equals("EML-04"))
                .findFirst()
                .orElseThrow();
        assertThat(received.getRecipient()).isEqualTo(ids.email());
        assertThat(received.getSubject()).isEqualTo("Logistica Sanchez - Richiesta di spedizione ricevuta");
        assertThat(received.getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(received.getBodyText())
                .contains("Abbiamo inoltrato la tua richiesta")
                .contains(tempCode)
                .contains("Controlla la tua casella postale in attesa di conferma.")
                .contains("Peso: 1.236,00 kg")
                .contains("Volume: 4,53 m³")
                .contains("Colli: 3")
                .contains("Pallet: 4")
                .contains("EPAL: 2")
                .contains("Servizio accessorio: Sponda idraulica")
                .doesNotContain("Importo");
        EmailOutbox office = mails.stream()
                .filter(m -> m.getKind().equals("EML-06"))
                .findFirst()
                .orElseThrow();
        assertThat(office.getRecipient()).isEqualTo(business.officeEmail());
        assertThat(office.getReplyTo()).isEqualTo(ids.email());
        assertThat(office.getSubject())
                .isEqualTo("Logistica Sanchez - Nuova richiesta di spedizione in attesa di conferma");
        assertThat(office.getBodyText()).contains("Cliente: " + ids.email()).contains(tempCode);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.submit' and actor_id = ?",
                        Long.class,
                        ids.customerUserId()))
                .isEqualTo(1);
    }

    @Test
    void lineValidationRefusesZeroDecimalsAndAMissingType() throws Exception {
        addLine("", "180", "120", "80", "450", "2")
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(Matchers.containsString("Scegli il tipo: Pallet Generico/Colli oppure Epal.")));
        addLine("EPAL", "0", "120", "80", "12,5", "0")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un numero intero maggiore di zero.")))
                .andExpect(content().string(Matchers.containsString("value=\"12,5\"")));
        addLine("EPAL", "180", "", "80", "450", "2")
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Campo obbligatorio.")));
        assertThat(pageContent()).doesNotContain("data-line=");
        // no line → «Avanti» refused with the legacy rule
        mvc.perform(details())
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Aggiungi almeno una riga di pallet o colli.")));
        mvc.perform(get("/area-clienti/ordini/nuovo/destinatario/").session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/nuovo/"));
        mvc.perform(get("/area-clienti/ordini/nuovo/riepilogo/").session(session))
                .andExpect(status().isFound());
    }

    @Test
    void theServiceIsIdempotentOnTheDraftKeyAndStoresTheInput() {
        OrderDraft draft = OrderTestSupport.exampleDraft();
        OrderView first = orders.submit(ids.customerId(), ids.customerUserId(), draft);
        OrderView second = orders.submit(ids.customerId(), ids.customerUserId(), draft);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject(
                        "select count(*) from shipment_order where customer_id = ?", Long.class, ids.customerId()))
                .isEqualTo(1);
        assertThat(outbox.findAll()).hasSize(2);
        assertThat(first.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(first.tempCode()).startsWith("TEMP-");
        assertThat(first.displayCode()).isEqualTo(first.tempCode());
        assertThat(first.codeOrDash()).isEqualTo("-");
        assertThat(first.totalWeightKg()).isEqualByComparingTo("1236");
        assertThat(first.totalVolumeM3()).isEqualByComparingTo("4.53");
        assertThat(first.lines()).hasSize(3);
        assertThat(first.lines().get(0).kind()).isEqualTo(LineKind.EPAL);
        assertThat(first.lines().get(0).lineVolumeM3()).isEqualByComparingTo("3.46");
        assertThat(first.packageCount()).isEqualTo(3);
        assertThat(first.accessoryServiceLabel()).isEqualTo("Sponda idraulica");
        assertThat(first.trackingLabel()).isEqualTo("Non gestito");
        assertThat(first.sender().name()).isEqualTo("Mittente Attivo S.r.l.");
        assertThat(first.recipient().email()).isEqualTo("ricevimento@destinatario.example");
        // a service of «none» and an empty package count are stored as null
        OrderDraft plain = OrderTestSupport.exampleDraft();
        plain.setDetails("DDT-1", "", null, "");
        OrderView view = orders.submit(ids.customerId(), ids.customerUserId(), plain);
        assertThat(view.packageCount()).isNull();
        assertThat(view.accessoryServiceId()).isNull();
        assertThat(view.accessoryServiceLabel()).isEqualTo("Nessun servizio accessorio");
        assertThat(view.customerNotes()).isNull();
    }

    @Test
    void pendingAndDeclinedCustomersCannotOrder() throws Exception {
        for (String status : List.of("PENDING_APPROVAL", "PENDING_VERIFICATION", "DECLINED")) {
            CustomerTestData.Ids blocked =
                    CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("bloccato"), status);
            MockHttpSession s = CustomerTestData.session(blocked);
            mvc.perform(get("/area-clienti/ordini/").session(s).accept("text/html"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("data-customer-status=")))
                    .andExpect(content()
                            .string(Matchers.not(Matchers.containsString("href=\"/area-clienti/ordini/nuovo/\""))));
            mvc.perform(get("/area-clienti/ordini/nuovo/").session(s))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/area-clienti/ordini/"));
            mvc.perform(post("/area-clienti/ordini/nuovo/riepilogo/").session(s).with(csrf()))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/area-clienti/ordini/"));
            assertThatThrownBy(() -> orders.submit(
                            blocked.customerId(), blocked.customerUserId(), OrderTestSupport.exampleDraft()))
                    .isInstanceOf(RuleViolationException.class);
            assertThat(jdbc.queryForObject(
                            "select count(*) from shipment_order where customer_id = ?",
                            Long.class,
                            blocked.customerId()))
                    .isZero();
        }
        mvc.perform(get("/area-clienti/ordini/").session(session).accept("text/html"))
                .andExpect(content().string(Matchers.containsString("href=\"/area-clienti/ordini/nuovo/\"")));
    }

    private org.springframework.test.web.servlet.ResultActions addLine(
            String kind, String h, String l, String w, String weight, String qty) throws Exception {
        return mvc.perform(wizard("/area-clienti/ordini/nuovo/")
                .param("action", "addLine")
                .param("kind", kind)
                .param("heightCm", h)
                .param("lengthCm", l)
                .param("widthCm", w)
                .param("weightKg", weight)
                .param("quantity", qty));
    }

    private MockHttpServletRequestBuilder details() {
        return details(Map.of());
    }

    /** The order fields of the example, with {@code overrides} replacing single values (MockMvc params accumulate). */
    private MockHttpServletRequestBuilder details(Map<String, String> overrides) {
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "action", "next",
                "transportDocumentCode", " DDT-2026-0042 ",
                "packageCount", "3",
                "accessoryServiceId", "2",
                "notes", "Consegna al mattino"));
        params.putAll(overrides);
        MockHttpServletRequestBuilder builder = wizard("/area-clienti/ordini/nuovo/");
        params.forEach(builder::param);
        return builder;
    }

    private MockHttpServletRequestBuilder recipient() {
        return recipient(Map.of());
    }

    private MockHttpServletRequestBuilder recipient(Map<String, String> overrides) {
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "name", "Magazzino Destinatario S.r.l.",
                "addressLine", "Via Roma 10",
                "province", "MI",
                "city", "Milano",
                "postalCode", "20121",
                "phone", "02 12345678",
                "email", "Ricevimento@destinatario.example"));
        params.putAll(overrides);
        MockHttpServletRequestBuilder builder = wizard("/area-clienti/ordini/nuovo/destinatario/");
        params.forEach(builder::param);
        return builder;
    }

    private MockHttpServletRequestBuilder wizard(String path) {
        return post(path).session(session).with(csrf()).accept("text/html");
    }

    private String pageContent() throws Exception {
        return mvc.perform(get("/area-clienti/ordini/nuovo/").session(session).accept("text/html"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
