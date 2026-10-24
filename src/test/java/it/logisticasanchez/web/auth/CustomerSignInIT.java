package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboundEmail;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CUS-01 (chapter 09; chapter 10 §2 "Customer authentication"; ADR 0004), exactly the planned
 * tests of the parity matrix: the same page and status for a known, unknown, unverified and
 * declined e-mail; the e-mail trimmed and lower-cased before lookup; known → {@code SIGN_IN}
 * token, unknown → {@code REGISTER} token with {@code email_hash} and 24 h expiry; two
 * {@code GET}s and a {@code HEAD} on the link never consume the token, the page's {@code POST}
 * signs in once and a second {@code POST} is refused; expiry 15 min; the token stored hashed;
 * {@code Cache-Control: no-store} and {@code Referrer-Policy: no-referrer} on the link page;
 * the redirect strips the token; rate limits per e-mail and per IP (429). Plus the logout and
 * {@code endAllSessions} of chapter 10 §2.
 */
@Tag("CUS-01")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
@TestPropertySource(
        properties = {
            "ls.ratelimit.signin-link-email.permits=2",
            "ls.ratelimit.signin-link-email.window=15m",
            "ls.ratelimit.signin-link-ip.permits=5",
            "ls.ratelimit.signin-link-ip.window=15m"
        })
class CustomerSignInIT extends AbstractPostgresIT {

    private static final String SENT = "Ti abbiamo inviato un&#39;email: segui il link per continuare";
    private static final Pattern SIGN_IN_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/accesso\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern REGISTER_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/registrazione/\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern CSRF_VALUE = Pattern.compile("name=\"_csrf\"[^>]*value=\"[^\"]*\"");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginTokenRepository tokens;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private CustomerSessionService sessions;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private MutableClock clock;

    private String activeEmail;
    private long activeUserId;
    private String ip;

    @BeforeEach
    void reset() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        limiter.reset();
        outbox.deleteAll();
        sender.clear();
        activeEmail = AuthTestData.uniqueEmail("attivo");
        activeUserId = AuthTestData.customerUser(jdbc, activeEmail, "ACTIVE");
        ip = "203.0.113." + (int) (Math.random() * 200 + 1);
    }

    @Test
    void thePageAndStatusAreTheSameForKnownUnknownUnverifiedAndDeclinedEmails() throws Exception {
        String unverified = AuthTestData.uniqueEmail("nonverificato");
        AuthTestData.customerUser(jdbc, unverified, "PENDING_VERIFICATION");
        String declined = AuthTestData.uniqueEmail("rifiutato");
        AuthTestData.customerUser(jdbc, declined, "DECLINED");
        String unknown = AuthTestData.uniqueEmail("sconosciuto");

        mvc.perform(get("/area-clienti/").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Inserisci la tua e-mail")))
                .andExpect(content().string(Matchers.containsString("Assicurati di aver inserito la mail corretta.")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("NON è necessario"))))
                .andExpect(content().string(Matchers.containsString("href=\"/privacy/\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(SENT))));

        String known = page(request(activeEmail));
        for (String email : List.of(unknown, unverified, declined)) {
            assertThat(page(request(email))).as("page for %s", email).isEqualTo(known);
        }
        assertThat(known).contains(SENT);

        Map<String, LoginToken> issued = Map.of(
                activeEmail, lastTokenFor(activeEmail),
                unverified, lastTokenFor(unverified),
                declined, lastTokenFor(declined));
        issued.forEach((email, token) -> assertThat(token.getPurpose())
                .as("%s is a known login (plan D-69)", email)
                .isEqualTo(LoginTokenPurpose.SIGN_IN));
        LoginToken register = tokens.findByTokenHash(hashOfLastToken(unknown)).orElseThrow();
        assertThat(register.getPurpose()).isEqualTo(LoginTokenPurpose.REGISTER);
        assertThat(register.getCustomerUserId()).isNull();
        assertThat(register.getEmailHash()).isEqualTo(emailHasher.hash(unknown)).hasSize(64);
        assertThat(register.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(jdbc.queryForObject("select count(*) from customer where email = ?", Long.class, unknown))
                // no customer row is created for an unknown e-mail
                .isZero();
    }

    @Test
    void theEmailIsTrimmedAndLowerCasedBeforeTheLookup() throws Exception {
        String typed = "  " + activeEmail.toUpperCase() + "  ";
        page(request(typed));
        LoginToken token = lastTokenFor(activeEmail);
        assertThat(token.getPurpose()).isEqualTo(LoginTokenPurpose.SIGN_IN);
        assertThat(token.getCustomerUserId()).isEqualTo(activeUserId);
        assertThat(token.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        EmailOutbox mail = outbox.findAll().get(0);
        assertThat(mail.getRecipient()).isEqualTo(activeEmail);
        assertThat(mail.getKind()).isEqualTo("EML-01");
        assertThat(mail.getBodyText()).contains("/area-clienti/accesso?t={{token}}");
    }

    @Test
    void theTokenIsStoredHashedAndTheRawValueTravelsOnlyInTheEmail() throws Exception {
        page(request(activeEmail));
        String raw = rawSignInToken(activeEmail);
        List<String> hashes = jdbc.queryForList("select token_hash from login_token", String.class);
        assertThat(hashes).doesNotContain(raw).contains(Tokens.hash(raw));
        assertThat(jdbc.queryForList("select email_hash from login_token where email_hash is not null", String.class))
                .doesNotContain(raw);
    }

    @Test
    void twoGetsAndAHeadNeverConsumeTheTokenThePostSignsInOnceAndASecondPostIsRefused() throws Exception {
        page(request(activeEmail));
        String raw = rawSignInToken(activeEmail);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(content().string(Matchers.containsString(">Accedi</button>")))
                    .andExpect(content().string(Matchers.containsString("action=\"/area-clienti/accesso\"")));
        }
        mvc.perform(head("/area-clienti/accesso").param("t", raw))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(tokens.findByTokenHash(Tokens.hash(raw)).orElseThrow().getUsedAt())
                // GET and HEAD never consume
                .isNull();

        MockHttpSession before = new MockHttpSession();
        MvcResult signedIn = mvc.perform(useLink(raw).session(before))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        assertThat(signedIn.getResponse().getHeader("Location")).doesNotContain(raw);
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session.getId()).as("session id rotated").isNotEqualTo(before.getId());
        assertThat(session.getAttribute(SessionLimitsFilter.AUTHENTICATED_AT)).isEqualTo(clock.instant());
        SecurityContext context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        CustomerPrincipal principal =
                (CustomerPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.customerUserId()).isEqualTo(activeUserId);
        assertThat(principal.email()).isEqualTo(activeEmail);
        assertThat(tokens.findByTokenHash(Tokens.hash(raw)).orElseThrow().getUsedAt())
                .isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject(
                        "select last_login_at is not null from customer_user where id = ?",
                        Boolean.class,
                        activeUserId))
                .isTrue();

        mvc.perform(get("/area-clienti/ordini/").session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(activeEmail)));
        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofMinutes(60).toSeconds());
        mvc.perform(get("/area-clienti/").session(session).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));

        mvc.perform(useLink(raw))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")))
                .andExpect(content().string(Matchers.containsString("action=\"/area-clienti/\"")));
        mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
    }

    @Test
    void anExpiredTokenIsRefusedByGetAndPost() throws Exception {
        page(request(activeEmail));
        String raw = rawSignInToken(activeEmail);
        clock.advance(Duration.ofMinutes(14));
        mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                .andExpect(content().string(Matchers.containsString(">Accedi</button>")));
        clock.advance(Duration.ofMinutes(2));
        mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        MvcResult refused = mvc.perform(useLink(raw))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")))
                .andReturn();
        assertThat(refused.getRequest().getSession(false) == null
                        || refused.getRequest()
                                        .getSession(false)
                                        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                                == null)
                // no session is opened
                .isTrue();
        mvc.perform(get("/area-clienti/accesso").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
    }

    @Test
    void theRegistrationLinkOpensTheRegistrationPageWithoutConsumingTheToken() throws Exception {
        String unknown = AuthTestData.uniqueEmail("nuovo");
        page(request(unknown));
        String raw = rawToken(unknown, REGISTER_LINK);
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/area-clienti/registrazione/").param("t", raw).accept("text/html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(content().string(Matchers.containsString("<h1>Registrazione</h1>")));
        }
        assertThat(tokens.findByTokenHash(Tokens.hash(raw)).orElseThrow().getUsedAt())
                .isNull();
        mvc.perform(get("/area-clienti/registrazione/")
                        .param("t", "not-a-token")
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                // a REGISTER token cannot sign in
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(useLink(raw)).andExpect(content().string(Matchers.containsString("Il link non è più valido")));
    }

    @Test
    void requestsAreRateLimitedPerEmailAndPerIp() throws Exception {
        mvc.perform(request(activeEmail)).andExpect(status().isOk());
        mvc.perform(request(activeEmail)).andExpect(status().isOk());
        mvc.perform(request(activeEmail).accept("text/html"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(request(" " + activeEmail.toUpperCase()))
                // the limit keys on the normalised e-mail
                .andExpect(status().isTooManyRequests());
        assertThat(outbox.findAll()).hasSize(2);

        // Per IP: the same client with different e-mails (5 permits; 4 used so far, incl. the refused ones).
        mvc.perform(request(AuthTestData.uniqueEmail("ip"))).andExpect(status().isOk());
        mvc.perform(request(AuthTestData.uniqueEmail("ip"))).andExpect(status().isTooManyRequests());
        mvc.perform(request(AuthTestData.uniqueEmail("ip")).with(r -> {
                    r.setRemoteAddr("198.51.100.9");
                    return r;
                }))
                // another client is not affected
                .andExpect(status().isOk());
    }

    @Test
    void logoutAndEndAllSessionsEndTheCustomerSession() throws Exception {
        page(request(activeEmail));
        MockHttpSession first = (MockHttpSession) mvc.perform(useLink(rawSignInToken(activeEmail)))
                .andExpect(status().isFound())
                .andReturn()
                .getRequest()
                .getSession(false);
        mvc.perform(post("/area-clienti/logout").session(first).accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/area-clienti/logout").session(first).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        assertThat(first.isInvalid()).isTrue();
        // On the real server HttpSessionEventPublisher tells the registry about the destroyed
        // session; a mock session fires no listener, so the test does it.
        registry.removeSessionInformation(first.getId());

        outbox.deleteAll();
        sender.clear();
        limiter.reset();
        page(request(activeEmail));
        MockHttpSession second = (MockHttpSession) mvc.perform(useLink(rawSignInToken(activeEmail)))
                .andExpect(status().isFound())
                .andReturn()
                .getRequest()
                .getSession(false);
        mvc.perform(get("/area-clienti/ordini/").session(second)).andExpect(status().isOk());

        assertThat(sessions.endAllSessions(activeUserId)).isEqualTo(1);
        mvc.perform(get("/area-clienti/ordini/").session(second).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        assertThat(second.isInvalid()).isTrue();
        assertThat(sessions.endAllSessions(activeUserId)).isZero();
    }

    private MockHttpServletRequestBuilder request(String email) {
        return post("/area-clienti/").param("email", email).with(csrf()).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private MockHttpServletRequestBuilder useLink(String raw) {
        return post("/area-clienti/accesso").param("t", raw).with(csrf()).accept("text/html");
    }

    /** The answer page with the per-request CSRF token blanked, so pages can be compared. */
    private String page(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return CSRF_VALUE.matcher(result.getResponse().getContentAsString()).replaceAll("csrf");
    }

    private LoginToken lastTokenFor(String email) {
        return tokens.findByTokenHash(hashOfLastToken(email)).orElseThrow();
    }

    private String hashOfLastToken(String email) {
        Pattern any = Pattern.compile("https://logisticasanchez\\.com/area-clienti/[a-z/]*\\?t=([A-Za-z0-9_-]+)");
        return Tokens.hash(rawToken(email, any));
    }

    private String rawSignInToken(String email) {
        return rawToken(email, SIGN_IN_LINK);
    }

    /** Delivers the queued e-mails through the memory sink and reads the raw token of the last one for the address. */
    private String rawToken(String email, Pattern link) {
        worker.runOnce();
        List<OutboundEmail> sent = sender.sent();
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).recipient().equals(email)) {
                Matcher matcher = link.matcher(sent.get(i).bodyText());
                assertThat(matcher.find()).as("link in the e-mail to %s", email).isTrue();
                return matcher.group(1);
            }
        }
        throw new AssertionError("no e-mail sent to " + email);
    }
}
