package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboundEmail;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CUS-02 (chapter 09; chapter 03 §5.3–§5.5 corrected by §10; chapter 08 §2.4): the registration
 * form behind a {@code REGISTER} link — the {@code GET} never consumes the token, the summary page,
 * the confirmation creates the customer {@code PENDING_APPROVAL} with its login and signs it in,
 * EML-02 to the customer and EML-06 to the office once; a known e-mail gets a sign-in link and
 * the same page; validation errors; the honeypot; the per-IP limit; a declined customer registers
 * again (Q-09).
 */
@Tag("CUS-02")
@Tag("EML-01")
@Tag("EML-02")
@Tag("EML-06")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ls.ratelimit.registration.permits=3", "ls.ratelimit.registration.window=1h"})
class CustomerRegistrationIT extends AbstractPostgresIT {

    private static final Pattern REGISTER_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/registrazione/\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern SIGN_IN_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/accesso\\?t=([A-Za-z0-9_-]+)");
    private static final String SENT = "Ti abbiamo inviato un&#39;email: segui il link per continuare";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private BusinessProperties business;

    private String ip;

    @BeforeEach
    void reset() {
        limiter.reset();
        outbox.deleteAll();
        sender.clear();
        ip = "203.0.113." + (int) (Math.random() * 200 + 1);
    }

    @Test
    void registerThroughTheLinkThenSeeThePendingStatus() throws Exception {
        String email = CustomerTestData.uniqueEmail("nuovo");
        String token = registerToken(email);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/area-clienti/registrazione/").param("t", token).accept("text/html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(content().string(Matchers.containsString("Inserisci i dati dell&#39;azienda")))
                    .andExpect(content().string(Matchers.containsString("NON è necessario ripetere la registrazione")))
                    .andExpect(content().string(Matchers.containsString("href=\"/privacy/\"")))
                    .andExpect(content().string(Matchers.containsString("name=\"website\"")))
                    .andExpect(content().string(Matchers.not(Matchers.containsString(">B<"))));
        }

        mvc.perform(form(token, email, "review"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Controlla i dati inseriti")))
                .andExpect(content().string(Matchers.containsString("Ditta Nuova S.r.l.")))
                .andExpect(content().string(Matchers.containsString("+390362123456")))
                .andExpect(content().string(Matchers.containsString("value=\"confirm\"")));
        assertThat(count("customer", "email", email)).isZero();

        MockHttpSession before = new MockHttpSession();
        MvcResult confirmed = mvc.perform(form(token, email, "confirm").session(before))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) confirmed.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(before.getId());
        CustomerPrincipal principal = (CustomerPrincipal) ((SecurityContext)
                        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .getAuthentication()
                .getPrincipal();
        assertThat(principal.email()).isEqualTo(email);

        Map<String, Object> customer = jdbc.queryForMap("select * from customer where email = ?", email);
        assertThat(customer)
                .containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("company_name", "Ditta Nuova S.r.l.")
                .containsEntry("address_line", "Via Nuova 12")
                .containsEntry("province", "MB")
                .containsEntry("city", "Seregno")
                .containsEntry("postal_code", "20831")
                .containsEntry("phone", "+390362123456")
                .containsEntry("vat_number", "13113080967")
                .containsEntry("source", "CUSTOMER_AREA");
        assertThat(customer.get("email_verified_at")).isNotNull();
        assertThat(customer.get("tax_code")).isNull();
        assertThat(jdbc.queryForObject("select customer_id from customer_user where email = ?", Long.class, email))
                .isEqualTo(((Number) customer.get("id")).longValue())
                .isEqualTo(principal.customerId());

        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).extracting(EmailOutbox::getKind).containsExactlyInAnyOrder("EML-02", "EML-06");
        EmailOutbox received = mails.stream()
                .filter(m -> m.getKind().equals("EML-02"))
                .findFirst()
                .orElseThrow();
        assertThat(received.getRecipient()).isEqualTo(email);
        assertThat(received.getSubject()).isEqualTo("Logistica Sanchez - Registrazione ricevuta");
        assertThat(received.getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(received.getBodyText())
                .contains("Abbiamo verificato il tuo indirizzo email")
                .contains("a breve verrà approvata");
        assertThat(received.getBodyHtml()).contains("Ditta Nuova S.r.l.");
        EmailOutbox office = mails.stream()
                .filter(m -> m.getKind().equals("EML-06"))
                .findFirst()
                .orElseThrow();
        assertThat(office.getRecipient()).isEqualTo(business.officeEmail());
        assertThat(office.getReplyTo()).isEqualTo(email);
        assertThat(office.getSubject()).isEqualTo("Logistica Sanchez - Nuovo cliente in attesa di conferma");
        assertThat(office.getBodyText()).contains("Cliente: " + email).contains("Ditta Nuova S.r.l.");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'customer.register' and entity_id = ?",
                        Long.class,
                        principal.customerId()))
                .isEqualTo(1);

        // CUS-03: the pending customer sees its status and no «Nuovo ordine»
        mvc.perform(get("/area-clienti/ordini/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-customer-status=\"pending\"")))
                .andExpect(content()
                        .string(Matchers.containsString(
                                "La tua richiesta di registrazione è andata a buon fine, a breve verrà approvata")))
                .andExpect(content()
                        .string(Matchers.not(Matchers.containsString("href=\"/area-clienti/ordini/nuovo/\""))));

        // the link is spent: GET and POST answer «Il link non è più valido»
        mvc.perform(get("/area-clienti/registrazione/").param("t", token).accept("text/html"))
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(form(token, email, "confirm"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
    }

    @Test
    void validationErrorsAreShownTogetherAndNothingIsCreated() throws Exception {
        String email = CustomerTestData.uniqueEmail("errori");
        String token = registerToken(email);
        mvc.perform(form(token, email, "review", Map.of("postalCode", "20831-1234", "phone", "", "companyName", "  ")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un CAP di cinque cifre.")))
                .andExpect(content().string(Matchers.containsString("Campo obbligatorio.")))
                .andExpect(content().string(Matchers.containsString("value=\"20831-1234\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Controlla i dati inseriti"))));
        // a confirm with bad data is refused the same way (the summary's hidden fields are re-validated)
        mvc.perform(form(token, email, "confirm", Map.of("postalCode", "2083")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci un CAP di cinque cifre.")));
        assertThat(count("customer", "email", email)).isZero();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void theEmailMustBeTheOneTheLinkWasSentTo() throws Exception {
        String email = CustomerTestData.uniqueEmail("giusta");
        String other = CustomerTestData.uniqueEmail("altra");
        String token = registerToken(email);
        // refused as a field error on the summary step and on the confirm step alike; nothing is created
        mvc.perform(form(token, other, "review"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(Matchers.containsString(
                                "L&#39;indirizzo deve essere quello a cui hai ricevuto il link.")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Controlla i dati inseriti"))));
        mvc.perform(form(token, other, "confirm"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(Matchers.containsString(
                                "L&#39;indirizzo deve essere quello a cui hai ricevuto il link.")));
        assertThat(count("customer", "email", other)).isZero();
        assertThat(count("customer", "email", email)).isZero();
        // the typed e-mail is normalised before the comparison; the link was not consumed by the refusal
        mvc.perform(form(token, "  " + email.toUpperCase() + " ", "confirm"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
        assertThat(count("customer", "email", email)).isEqualTo(1);
    }

    @Test
    void aKnownEmailGetsASignInLinkAndTheSamePage() throws Exception {
        String email = CustomerTestData.uniqueEmail("gianota");
        String token = registerToken(email);
        // registered meanwhile (another tab, another device): nothing is created, a sign-in link goes out
        CustomerTestData.customerUser(jdbc, email, "ACTIVE");
        outbox.deleteAll();
        sender.clear();
        mvc.perform(form(token, email, "confirm"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(SENT)));
        assertThat(count("customer", "email", email)).isEqualTo(1);
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-01");
        assertThat(mails.get(0).getRecipient()).isEqualTo(email);
        worker.runOnce();
        assertThat(SIGN_IN_LINK.matcher(sender.sent().get(0).bodyText()).find()).isTrue();
    }

    @Test
    void theHoneypotAnswersLikeASuccessAndStoresNothingAndRegistrationsAreRateLimitedPerIp() throws Exception {
        String email = CustomerTestData.uniqueEmail("bot");
        String token = registerToken(email);
        outbox.deleteAll();
        for (int i = 0; i < 3; i++) {
            mvc.perform(form(token, email, "confirm", Map.of("website", "https://spam.example")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString(SENT)));
        }
        assertThat(count("customer", "email", email)).isZero();
        assertThat(outbox.findAll()).isEmpty();
        mvc.perform(form(token, email, "confirm"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(count("customer", "email", email)).isZero();
    }

    @Test
    void aDeclinedCustomerRegistersAgainFromItsSession() throws Exception {
        CustomerTestData.Ids declined =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("rifiutato"), "DECLINED");
        MockHttpSession session = CustomerTestData.session(declined);
        mvc.perform(get("/area-clienti/ordini/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-customer-status=\"declined\"")))
                .andExpect(content().string(Matchers.containsString("href=\"/area-clienti/registrazione/\"")));
        mvc.perform(get("/area-clienti/registrazione/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"Ditta Esempio S.r.l.\"")))
                .andExpect(content().string(Matchers.containsString("non è stata accettata")));
        mvc.perform(form(null, null, "confirm", Map.of("companyName", "Ditta Corretta S.r.l."))
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
        Map<String, Object> row = jdbc.queryForMap("select * from customer where id = ?", declined.customerId());
        assertThat(row)
                .containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("company_name", "Ditta Corretta S.r.l.");
        assertThat(row.get("declined_at")).isNull();
        assertThat(outbox.findAll()).extracting(EmailOutbox::getKind).containsExactlyInAnyOrder("EML-02", "EML-06");
        // now pending: the form is no longer offered
        mvc.perform(get("/area-clienti/registrazione/").session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
        CustomerTestData.Ids active =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("attivo"), "ACTIVE");
        mvc.perform(get("/area-clienti/registrazione/").session(CustomerTestData.session(active)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
    }

    private MockHttpServletRequestBuilder form(String token, String email, String step) {
        return form(token, email, step, Map.of());
    }

    /** The valid form, with {@code overrides} replacing single values (MockMvc params accumulate, so no re-param). */
    private MockHttpServletRequestBuilder form(String token, String email, String step, Map<String, String> overrides) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("step", step);
        params.put("companyName", "Ditta Nuova S.r.l.");
        params.put("addressLine", "Via Nuova 12");
        params.put("province", "MB");
        params.put("city", "Seregno");
        params.put("postalCode", "20831");
        params.put("phone", "0362 123456");
        params.put("vatNumber", "13113080967");
        params.put("taxCode", "");
        params.put("website", "");
        params.putAll(overrides);
        MockHttpServletRequestBuilder builder = post("/area-clienti/registrazione/")
                .with(csrf())
                .accept("text/html")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        params.forEach(builder::param);
        if (token != null) {
            builder.param("t", token);
        }
        if (email != null) {
            builder.param("email", email);
        }
        return builder;
    }

    /** Asks for a link for an unknown address and reads the raw REGISTER token from the delivered e-mail. */
    private String registerToken(String email) throws Exception {
        mvc.perform(post("/area-clienti/").param("email", email).with(csrf()).with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }))
                .andExpect(status().isOk());
        worker.runOnce();
        outbox.deleteAll(); // the delivered EML-01 row is not part of what the tests count
        for (OutboundEmail mail : sender.sent()) {
            if (mail.recipient().equals(email)) {
                Matcher matcher = REGISTER_LINK.matcher(mail.bodyText());
                assertThat(matcher.find()).isTrue();
                return matcher.group(1);
            }
        }
        throw new AssertionError("no registration e-mail for " + email);
    }

    private long count(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, value);
    }
}
