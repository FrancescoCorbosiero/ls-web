package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboundEmail;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.util.List;
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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CUS-06 (chapter 09; chapter 10 §2): the profile is read-only with «Chiedi una modifica»; the
 * login e-mail changes only when the new address confirms a {@code CHANGE_EMAIL} link (EML-01),
 * with a notice to the old address; the {@code GET} never consumes the link; the change updates
 * {@code customer_user} and {@code customer}, is audited and ends the user's other sessions; an
 * address already in use gets nothing and the page answers the same way.
 */
@Tag("CUS-06")
@Tag("EML-01")
@SpringBootTest
@AutoConfigureMockMvc
class CustomerProfileIT extends AbstractPostgresIT {

    private static final Pattern CHANGE_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/cambio-email\\?t=([A-Za-z0-9_-]+)");

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
    private SessionRegistry registry;

    @Autowired
    private BusinessProperties business;

    private CustomerTestData.Ids ids;
    private MockHttpSession session;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        sender.clear();
        ids = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("profilo"), "ACTIVE");
        session = CustomerTestData.session(ids);
    }

    @Test
    void theProfileIsReadOnlyWithARequestToTheOffice() throws Exception {
        mvc.perform(get("/area-clienti/profilo/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(Matchers.containsString("Ditta Esempio S.r.l.")))
                .andExpect(content().string(Matchers.containsString("Via di Prova 1")))
                .andExpect(content().string(Matchers.containsString("+393330000000")))
                .andExpect(content().string(Matchers.containsString(">Attivo<")))
                .andExpect(content().string(Matchers.containsString("Chiedi una modifica")))
                .andExpect(content().string(Matchers.containsString("mailto:" + business.officeEmail())))
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"companyName\""))))
                .andExpect(content().string(Matchers.containsString("name=\"newEmail\"")));
    }

    @Test
    void theEmailChangesOnlyWhenTheNewAddressConfirmsItsLink() throws Exception {
        String newEmail = CustomerTestData.uniqueEmail("nuova");
        registry.registerNewSession(session.getId(), ids.principal());

        mvc.perform(post("/area-clienti/profilo/email")
                        .session(session)
                        .with(csrf())
                        .param("newEmail", " " + newEmail.toUpperCase()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/profilo/?email-inviata=1"));
        mvc.perform(get("/area-clienti/profilo/")
                        .param("email-inviata", "1")
                        .session(session)
                        .accept("text/html"))
                .andExpect(content().string(Matchers.containsString("Ti abbiamo inviato un&#39;email")));

        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(2).allMatch(m -> m.getKind().equals("EML-01"));
        EmailOutbox toNew = mails.stream()
                .filter(m -> m.getRecipient().equals(newEmail))
                .findFirst()
                .orElseThrow();
        assertThat(toNew.getSubject()).isEqualTo("Conferma la nuova e-mail dell'area clienti Logistica Sanchez");
        assertThat(toNew.getLoginTokenId()).isNotNull();
        EmailOutbox notice = mails.stream()
                .filter(m -> m.getRecipient().equals(ids.email()))
                .findFirst()
                .orElseThrow();
        assertThat(notice.getSubject()).isEqualTo("Richiesta di cambio e-mail per l'area clienti Logistica Sanchez");
        assertThat(notice.getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(notice.getBodyText()).contains(newEmail);
        assertThat(jdbc.queryForObject(
                        "select pending_email from customer_user where id = ?", String.class, ids.customerUserId()))
                .isEqualTo(newEmail);
        assertThat(jdbc.queryForObject("select email from customer where id = ?", String.class, ids.customerId()))
                .as("nothing changes before the confirmation")
                .isEqualTo(ids.email());

        worker.runOnce();
        OutboundEmail delivered = sender.sent().stream()
                .filter(m -> m.recipient().equals(newEmail))
                .findFirst()
                .orElseThrow();
        Matcher matcher = CHANGE_LINK.matcher(delivered.bodyText());
        assertThat(matcher.find()).isTrue();
        String raw = matcher.group(1);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/area-clienti/cambio-email").param("t", raw).accept("text/html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(content().string(Matchers.containsString(newEmail)))
                    .andExpect(content().string(Matchers.containsString(">Conferma</button>")));
        }
        assertThat(jdbc.queryForObject(
                        "select email from customer_user where id = ?", String.class, ids.customerUserId()))
                .isEqualTo(ids.email());

        mvc.perform(post("/area-clienti/cambio-email")
                        .param("t", raw)
                        .with(csrf())
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("E-mail aggiornata")))
                .andExpect(content().string(Matchers.containsString(newEmail)));
        assertThat(jdbc.queryForMap(
                        "select email, pending_email from customer_user where id = ?", ids.customerUserId()))
                .containsEntry("email", newEmail)
                .containsEntry("pending_email", null);
        assertThat(jdbc.queryForObject("select email from customer where id = ?", String.class, ids.customerId()))
                .isEqualTo(newEmail);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'customer.email.changed' and entity_id = ?",
                        Long.class,
                        ids.customerId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log_detail d join audit_log l on l.id = d.audit_log_id"
                                + " where l.action = 'customer.email.changed' and d.after::text like ?",
                        Long.class,
                        "%" + newEmail + "%"))
                .isEqualTo(1);
        assertThat(registry.getSessionInformation(session.getId()).isExpired())
                .as("every session of the user is ended")
                .isTrue();

        // the link is spent
        mvc.perform(post("/area-clienti/cambio-email")
                        .param("t", raw)
                        .with(csrf())
                        .accept("text/html"))
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(get("/area-clienti/cambio-email").param("t", raw).accept("text/html"))
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
    }

    @Test
    void anAddressAlreadyInUseOrInvalidChangesNothing() throws Exception {
        CustomerTestData.Ids other =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("altro"), "ACTIVE");
        mvc.perform(post("/area-clienti/profilo/email")
                        .session(session)
                        .with(csrf())
                        .param("newEmail", other.email()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/profilo/?email-inviata=1"));
        assertThat(outbox.findAll()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select pending_email from customer_user where id = ?", String.class, ids.customerUserId()))
                .isNull();
        mvc.perform(post("/area-clienti/profilo/email")
                        .session(session)
                        .with(csrf())
                        .param("newEmail", "non-una-mail"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/profilo/?email-errata=1"));
        mvc.perform(post("/area-clienti/profilo/email")
                        .session(session)
                        .with(csrf())
                        .param("newEmail", ids.email()))
                .andExpect(status().isFound());
        assertThat(outbox.findAll()).isEmpty();
        // CSRF required
        mvc.perform(post("/area-clienti/profilo/email").session(session).param("newEmail", "x@cliente.example"))
                .andExpect(status().isForbidden());
    }
}
