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
import java.util.LinkedHashMap;
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
 * CUS-04 (chapter 09; chapter 03 §6 with the §10 fixes; chapter 08 §2.4 "Order"; ADR 0029 for the
 * page): one form with the lines as rows, the shipment data and the recipient, with the legacy
 * labels; rows added and removed without JavaScript, everything typed kept; the totals of the
 * §6.3 example recomputed on every round trip; lines validated row by row (type required,
 * integers ≥ 1), blank rows ignored, DDT required, package count stored, service and notes,
 * recipient rules; the summary, the confirmation creating one PENDING order with a {@code TEMP-}
 * code whose stored data equal the input, the final screen with the corrected brand, EML-04 to
 * the customer and EML-06 to the office; idempotency of the draft; a non-ACTIVE customer refused
 * (CUS-03).
 */
@Tag("CUS-04")
@Tag("CUS-03")
@Tag("EML-04")
@Tag("EML-06")
@SpringBootTest
@AutoConfigureMockMvc
class OrderWizardIT extends AbstractPostgresIT {

    private static final String NEW = "/area-clienti/ordini/nuovo/";
    private static final String REVIEW = "/area-clienti/ordini/nuovo/riepilogo/";
    private static final String DONE = "/area-clienti/ordini/nuovo/inviato/";

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
    void theHappyPathThroughTheFormAndTheSummary() throws Exception {
        String blank = mvc.perform(get(NEW).session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(blank)
                .contains("Inserisci i dati dell&#39;ordine")
                .contains("Tipo Pallet")
                .contains("Altezza (cm)")
                .contains("Aggiungi riga")
                .contains("data-total-weight>0,00<")
                .contains("Numero Colli (facoltativo)")
                .contains("Documento di trasporto")
                .contains(">Nessun servizio accessorio<")
                .contains(">Sponda idraulica<")
                .contains("Pallet Generico/Colli")
                .contains(">Epal<")
                .contains("Ragione Sociale/Nominativo")
                .contains("Telefono (facoltativo)")
                .contains("Email del destinatario (facoltativo)")
                .contains("name=\"line.0.kind\"")
                .doesNotContain("name=\"line.1.kind\"");
        // Enter submits the hidden «Continua» placed before every other button, never «Elimina»
        assertThat(blank.indexOf("value=\"save\"")).isPositive().isLessThan(blank.indexOf("value=\"addLine\""));

        // «Aggiungi riga» without JavaScript: the page comes back with one more row, nothing lost
        Map<String, String> first = new LinkedHashMap<>();
        row(first, 0, "EPAL", "180", "120", "80", "450", "2");
        first.put("transportDocumentCode", "DDT-2026-0042");
        assertThat(page("addLine", first))
                .contains("name=\"line.1.kind\"")
                .contains("value=\"DDT-2026-0042\"")
                .contains("data-line-weight>900,00<")
                .contains("data-line-volume>3,46<")
                .contains("data-total-weight>900,00<")
                .contains("data-total-units>2<");

        // the §6.3 lines: per-line and grand totals, with no float artefacts
        assertThat(page("addLine", example()))
                .contains("name=\"line.3.kind\"")
                .contains("data-total-weight>1.236,00<")
                .contains("data-total-volume>4,53<")
                .contains("data-line-volume>3,46<")
                .contains("data-line-volume>0,96<")
                .contains("data-line-volume>0,11<")
                .contains("data-total-units>6<")
                .doesNotContain("0.06999");

        // «Elimina» without JavaScript: the row goes, the others are renumbered, the totals follow
        assertThat(page("removeLine.0", example()))
                .contains("data-total-weight>336,00<")
                .contains("name=\"line.0.heightCm\" value=\"100\"")
                .contains("name=\"line.1.heightCm\" value=\"40\"")
                .doesNotContain("name=\"line.2.kind\"");

        // the rules of the shipment data and of the recipient, all on the same page
        assertThat(page("save", example(Map.of("transportDocumentCode", "  "))))
                .contains("data-form-errors")
                .contains("Campo obbligatorio.")
                .contains("name=\"line.2.weightKg\" value=\"12\"");
        assertThat(page("save", example(Map.of("accessoryServiceId", "99"))))
                .contains("Scegli un servizio dall&#39;elenco.");
        assertThat(page("save", example(Map.of("packageCount", "tre"))))
                .contains("Inserisci un numero intero maggiore di zero.");
        assertThat(page("save", example(Map.of("postalCode", "20121-1234", "name", ""))))
                .contains("Inserisci un CAP di cinque cifre.")
                .contains("Campo obbligatorio.");
        assertThat(page("save", example(Map.of("phone", "telefono"))))
                .contains("Inserisci un numero di telefono valido.");

        // complete (a trailing blank row is ignored) → the summary
        Map<String, String> complete = example();
        row(complete, 3, "", "", "", "", "", "");
        mvc.perform(form("save", complete))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", REVIEW));

        mvc.perform(get(REVIEW).session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Controlla i dati inseriti")))
                .andExpect(content().string(Matchers.containsString("data-review-totals>1.236,00 kg · 4,53 m³<")))
                .andExpect(content().string(Matchers.containsString("DDT-2026-0042")))
                .andExpect(content().string(Matchers.containsString("Sponda idraulica")))
                .andExpect(content().string(Matchers.containsString("Magazzino Destinatario S.r.l.")))
                .andExpect(content().string(Matchers.containsString("Conferma l&#39;ordine")))
                .andExpect(content().string(Matchers.containsString("puoi ancora modificare o annullare")));
        // back to the form: the draft is there, as typed and normalised
        assertThat(mvc.perform(get(NEW).session(session).accept("text/html"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("value=\"DDT-2026-0042\"")
                .contains("name=\"line.0.heightCm\" value=\"180\"")
                .contains("name=\"line.2.quantity\" value=\"3\"")
                .doesNotContain("name=\"line.3.kind\"")
                .contains("value=\"Magazzino Destinatario S.r.l.\"")
                .contains("data-total-weight>1.236,00<");

        MvcResult confirmed = mvc.perform(
                        post(REVIEW).session(session).with(csrf()).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", DONE))
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
        assertThat(order.get("edited_at")).isNull();
        assertThat((BigDecimal) order.get("total_weight_kg")).isEqualByComparingTo("1236.00");
        assertThat((BigDecimal) order.get("total_volume_m3")).isEqualByComparingTo("4.53");
        List<Map<String, Object>> lines = jdbc.queryForList(
                "select line_no, kind, height_cm, length_cm, width_cm, weight_kg, quantity, line_weight_kg, line_volume_m3"
                        + " from order_line where order_id = ? order by line_no",
                order.get("id"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0))
                .containsEntry("kind", "EPAL")
                .containsEntry("height_cm", 180)
                .containsEntry("length_cm", 120)
                .containsEntry("width_cm", 80)
                .containsEntry("quantity", 2);
        assertThat((BigDecimal) lines.get(0).get("weight_kg")).isEqualByComparingTo("450");
        assertThat((BigDecimal) lines.get(0).get("line_weight_kg")).isEqualByComparingTo("900");
        assertThat((BigDecimal) lines.get(0).get("line_volume_m3")).isEqualByComparingTo("3.46");
        assertThat(lines.get(1))
                .containsEntry("kind", "GENERIC")
                .containsEntry("height_cm", 100)
                .containsEntry("quantity", 1);
        assertThat(lines.get(2))
                .containsEntry("kind", "GENERIC")
                .containsEntry("height_cm", 40)
                .containsEntry("quantity", 3);

        MockHttpSession after = (MockHttpSession) confirmed.getRequest().getSession(false);
        mvc.perform(get(DONE).session(after).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        Matchers.containsString(
                                                "Il tuo ordine è stato inoltrato correttamente! Ti preghiamo di attendere"
                                                        + " la conferma via email dal nostro centro logistico. Grazie per aver scelto Logistica Sanchez!")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Sanchez Logistica"))))
                .andExpect(content().string(Matchers.containsString(tempCode)))
                .andExpect(content().string(Matchers.containsString("Effettua un nuovo ordine")));
        // terminal: reloading the final page or confirming again creates nothing
        mvc.perform(get(DONE).session(after)).andExpect(status().isFound());
        mvc.perform(post(REVIEW).session(after).with(csrf()).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", NEW));
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
    void rowsAreValidatedOneByOneAndAnEmptyListIsRefused() throws Exception {
        Map<String, String> params = example();
        row(params, 0, "", "180", "120", "80", "450", "2");
        row(params, 1, "EPAL", "0", "120", "80", "12,5", "0");
        row(params, 2, "EPAL", "180", "", "80", "450", "2");
        assertThat(page("save", params))
                .contains("data-form-errors")
                .contains("Scegli il tipo: Pallet Generico/Colli oppure Epal.")
                .contains("Inserisci un numero intero maggiore di zero.")
                .contains("value=\"12,5\"")
                .contains("Campo obbligatorio.")
                .containsPattern("class=\"line-row has-error\"[\\s\\S]*class=\"line-row has-error\"")
                .contains("data-total-weight>0,00<");
        // only blank rows → the legacy rule, a single empty row kept to type in
        Map<String, String> none = example();
        none.keySet().removeIf(k -> k.startsWith("line."));
        row(none, 0, "", "", "", "", "", "");
        row(none, 1, "", "", "", "", "", "");
        assertThat(page("save", none))
                .contains("data-lines-error")
                .contains("Aggiungi almeno una riga di pallet o colli.")
                .contains("name=\"line.0.kind\"")
                .doesNotContain("name=\"line.1.kind\"");
        mvc.perform(get(REVIEW).session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", NEW));
        mvc.perform(post(REVIEW).session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", NEW));
    }

    @Test
    void theFormStopsAtFiftyRows() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < OrderForm.MAX_LINES + 5; i++) {
            row(params, i, "GENERIC", "100", "120", "80", "10", "1");
        }
        String page = page("addLine", params);
        assertThat(page)
                .contains("name=\"line.49.kind\"")
                .doesNotContain("name=\"line.50.kind\"")
                .containsPattern("value=\"addLine\"[^>]*disabled");
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
        assertThat(first.unitCount()).isEqualTo(6);
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
                    .andExpect(content().string(Matchers.not(Matchers.containsString("href=\"" + NEW + "\""))));
            mvc.perform(get(NEW).session(s))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/area-clienti/ordini/"));
            mvc.perform(post(NEW).session(s).with(csrf()).param("action", "save"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/area-clienti/ordini/"));
            mvc.perform(post(REVIEW).session(s).with(csrf()))
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
                .andExpect(content().string(Matchers.containsString("href=\"" + NEW + "\"")));
    }

    /** The §6.3 lines as rows 0–2, the shipment data and the recipient of the example. */
    static Map<String, String> example() {
        Map<String, String> params = new LinkedHashMap<>();
        row(params, 0, "EPAL", "180", "120", "80", "450", "2");
        row(params, 1, "GENERIC", "100", "120", "80", "300", "1");
        row(params, 2, "GENERIC", "40", "30", "30", "12", "3");
        params.put("transportDocumentCode", " DDT-2026-0042 ");
        params.put("packageCount", "3");
        params.put("accessoryServiceId", "2");
        params.put("notes", "Consegna al mattino");
        params.put("name", "Magazzino Destinatario S.r.l.");
        params.put("addressLine", "Via Roma 10");
        params.put("province", "MI");
        params.put("city", "Milano");
        params.put("postalCode", "20121");
        params.put("phone", "02 12345678");
        params.put("email", "Ricevimento@destinatario.example");
        return params;
    }

    /** The example with {@code overrides} replacing single values (MockMvc params accumulate). */
    static Map<String, String> example(Map<String, String> overrides) {
        Map<String, String> params = example();
        params.putAll(overrides);
        return params;
    }

    static void row(Map<String, String> params, int index, String... values) {
        for (int i = 0; i < OrderForm.LINE_FIELDS.size(); i++) {
            params.put("line." + index + "." + OrderForm.LINE_FIELDS.get(i), values[i]);
        }
    }

    private MockHttpServletRequestBuilder form(String action, Map<String, String> params) {
        MockHttpServletRequestBuilder builder =
                post(NEW).session(session).with(csrf()).accept("text/html").param("action", action);
        params.forEach(builder::param);
        return builder;
    }

    /** Posts the form with {@code action} and returns the page it answers with (200: the form again). */
    private String page(String action, Map<String, String> params) throws Exception {
        return mvc.perform(form(action, params))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
