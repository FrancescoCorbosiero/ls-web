package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboundEmail;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import jakarta.servlet.http.Cookie;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CUS-01 / CUS-06 with the owner decision of 2026-10-25 (ADR 0032): the customer signs in with
 * e-mail and password; every refusal (unknown e-mail, wrong password, no password yet, disabled
 * login) answers the same page; attempts are rate-limited; only a bcrypt hash is stored. The
 * profile sets the password without the current one after an e-mailed link and asks for it
 * otherwise, applies the policy (8 characters, common passwords refused) and ends the other
 * sessions and remembered devices on a change; the home offers a password until one is set; the
 * registration summary asks for it; the anonymisation clears it.
 */
@Tag("CUS-01")
@Tag("CUS-06")
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPasswordIT extends AbstractPostgresIT {

    private static final String PASSWORD = CustomerTestData.newPassword();
    private static final String NEW_PASSWORD = CustomerTestData.newPassword();
    private static final String WRONG_PASSWORD = CustomerTestData.newPassword();
    private static final String REFUSED = "E-mail o password non corrette.";
    private static final Pattern SIGN_IN_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/accesso\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern REGISTER_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/registrazione/\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern CSRF_VALUE = Pattern.compile("name=\"_csrf\"[^>]*(value|content)=\"[^\"]*\"");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerPasswordService passwords;

    @Autowired
    private CustomerAccountService accounts;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private RateLimiter limiter;

    private CustomerTestData.Ids customer;

    @BeforeEach
    void reset() {
        limiter.reset();
        outbox.deleteAll();
        sender.clear();
        customer = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("password"), "ACTIVE");
    }

    @Test
    void aCustomerWithAPasswordSignsInWithEmailAndPasswordAndOnlyTheHashIsStored() throws Exception {
        passwords.setPassword(customer.customerUserId(), PASSWORD);

        MvcResult result = signIn("  " + customer.email().toUpperCase() + " ", PASSWORD, true)
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andReturn();

        assertThat(principalOf(result).customerUserId()).isEqualTo(customer.customerUserId());
        assertThat(rememberCookie(result.getResponse()))
                .as("«Resta connesso» ticked")
                .isNotNull();
        String hash = jdbc.queryForObject(
                "select password_hash from customer_user where id = ?", String.class, customer.customerUserId());
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject(
                        "select last_login_at is not null from customer_user where id = ?",
                        Boolean.class,
                        customer.customerUserId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'customer.password.set' and actor_id = ?",
                        Long.class,
                        customer.customerUserId()))
                .isOne();
        mvc.perform(get("/area-clienti/ordini/").session(session(result))).andExpect(status().isOk());
    }

    @Test
    void everyRefusalAnswersTheSamePage() throws Exception {
        passwords.setPassword(customer.customerUserId(), PASSWORD);
        CustomerTestData.Ids withoutPassword =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("senza-password"), "ACTIVE");
        CustomerTestData.Ids disabled =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("disattivato"), "ACTIVE");
        passwords.setPassword(disabled.customerUserId(), PASSWORD);
        jdbc.update("update customer_user set disabled_at = now() where id = ?", disabled.customerUserId());

        String wrongPassword = refusedPage(customer.email(), WRONG_PASSWORD);
        String unknownEmail = CustomerTestData.uniqueEmail("sconosciuto");
        String unknown = refusedPage(unknownEmail, PASSWORD);
        String noPassword = refusedPage(withoutPassword.email(), PASSWORD);
        String disabledLogin = refusedPage(disabled.email(), PASSWORD);

        assertThat(wrongPassword)
                .contains(REFUSED)
                .contains("Prima volta o password dimenticata?")
                .doesNotContain(WRONG_PASSWORD);
        // apart from the e-mail typed back into the form, the four pages are the same
        assertThat(blankEmail(unknown, unknownEmail)).isEqualTo(blankEmail(wrongPassword, customer.email()));
        assertThat(blankEmail(noPassword, withoutPassword.email()))
                .isEqualTo(blankEmail(wrongPassword, customer.email()));
        assertThat(blankEmail(disabledLogin, disabled.email())).isEqualTo(blankEmail(wrongPassword, customer.email()));
    }

    @Test
    void passwordAttemptsAreRateLimitedPerEmail() throws Exception {
        passwords.setPassword(customer.customerUserId(), PASSWORD);
        for (int i = 0; i < 10; i++) {
            signIn(customer.email(), WRONG_PASSWORD + i, false).andExpect(status().isOk());
        }
        signIn(customer.email(), PASSWORD, false)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void afterALinkTheProfileSetsAPasswordWithoutTheCurrentOneOtherwiseItAsksForIt() throws Exception {
        MockHttpSession byLink = signInByLink(customer.email());
        String profile = mvc.perform(get("/area-clienti/profilo/").session(byLink))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(profile).contains("id=\"newPassword\"").doesNotContain("id=\"currentPassword\"");
        mvc.perform(changePassword(byLink, null, PASSWORD, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-salvata=1#password"));
        assertThat(passwords.hasPassword(customer.customerUserId())).isTrue();

        // «password dimenticata»: a new link lets the customer replace it without the old one
        MockHttpSession again = signInByLink(customer.email());
        mvc.perform(changePassword(again, null, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-salvata=1#password"));
        assertThat(passwords.isCurrentPassword(customer.customerUserId(), NEW_PASSWORD))
                .isTrue();

        // a session opened otherwise must give the current password
        MockHttpSession other = CustomerTestData.session(customer);
        assertThat(mvc.perform(get("/area-clienti/profilo/").session(other))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("id=\"currentPassword\"");
        mvc.perform(changePassword(other, null, PASSWORD, PASSWORD))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-errore=attuale#password"));
        mvc.perform(changePassword(other, WRONG_PASSWORD, PASSWORD, PASSWORD))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-errore=attuale#password"));
        mvc.perform(changePassword(other, NEW_PASSWORD, PASSWORD, PASSWORD + 'x'))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-errore=diverse#password"));
        String tooShort = PASSWORD.substring(0, 5);
        mvc.perform(changePassword(other, NEW_PASSWORD, tooShort, tooShort))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-errore=corta#password"));
        mvc.perform(changePassword(other, NEW_PASSWORD, "password", "password"))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-errore=comune#password"));
        assertThat(mvc.perform(get("/area-clienti/profilo/")
                                .param("password-errore", "comune")
                                .session(other))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Questa password è troppo comune: scegline un&#39;altra.");
        assertThat(mvc.perform(get("/area-clienti/profilo/")
                                .param("password-errore", "corta")
                                .session(other))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("La password deve avere almeno 8 caratteri.");
        assertThat(passwords.isCurrentPassword(customer.customerUserId(), NEW_PASSWORD))
                .isTrue();
    }

    @Test
    void aChangeEndsTheOtherSessionsAndDevicesAndThisOneGoesOn() throws Exception {
        passwords.setPassword(customer.customerUserId(), PASSWORD);
        MvcResult here = signIn(customer.email(), PASSWORD, true).andReturn();
        MvcResult elsewhere = signIn(customer.email(), PASSWORD, true).andReturn();
        Cookie hereDevice = rememberCookie(here.getResponse());
        Cookie otherDevice = rememberCookie(elsewhere.getResponse());

        MvcResult changed = mvc.perform(changePassword(session(here), PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
                        .cookie(hereDevice))
                .andExpect(header().string("Location", "/area-clienti/profilo/?password-salvata=1#password"))
                .andReturn();

        MockHttpSession continued = (MockHttpSession) changed.getRequest().getSession(false);
        mvc.perform(get("/area-clienti/ordini/").session(continued)).andExpect(status().isOk());
        assertThat(rememberCookie(changed.getResponse()))
                .as("this browser stays remembered")
                .isNotNull();
        mvc.perform(get("/area-clienti/ordini/").session(session(elsewhere)).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        mvc.perform(get("/area-clienti/ordini/").cookie(otherDevice).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        signIn(customer.email(), PASSWORD, false).andExpect(status().isOk()); // the old one no longer works
        signIn(customer.email(), NEW_PASSWORD, false).andExpect(status().isFound());
    }

    @Test
    void theHomeOffersAPasswordUntilOneIsSet() throws Exception {
        MockHttpSession session = CustomerTestData.session(customer);
        mvc.perform(get("/area-clienti/ordini/").session(session))
                .andExpect(content().string(Matchers.containsString("data-password-missing")))
                .andExpect(content()
                        .string(Matchers.containsString(
                                "Scegli una password per entrare senza aspettare l&#39;e-mail.")));
        passwords.setPassword(customer.customerUserId(), PASSWORD);
        mvc.perform(get("/area-clienti/ordini/").session(session))
                .andExpect(content().string(Matchers.not(Matchers.containsString("data-password-missing"))));
    }

    @Test
    @Tag("CUS-02")
    void theRegistrationSummaryAsksForThePassword() throws Exception {
        String email = CustomerTestData.uniqueEmail("nuovo-con-password");
        String token = registerToken(email);
        mvc.perform(registration(token, email, "review", null, null))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Scegli la password")))
                .andExpect(content().string(Matchers.containsString("Almeno 8 caratteri")));
        mvc.perform(registration(token, email, "confirm", PASSWORD, PASSWORD + '!'))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Le due password non coincidono.")));
        mvc.perform(registration(token, email, "confirm", "corta", "corta"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("La password deve avere almeno 8 caratteri.")));
        assertThat(jdbc.queryForObject("select count(*) from customer_user where email = ?", Long.class, email))
                .as("nothing is created until the password is right")
                .isZero();

        mvc.perform(registration(token, email, "confirm", PASSWORD, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
        signIn(email, PASSWORD, false)
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));
    }

    @Test
    @Tag("NFR-03")
    void theAnonymisationClearsThePassword() {
        passwords.setPassword(customer.customerUserId(), PASSWORD);
        accounts.anonymise(customer.customerId(), "anonimo-" + customer.customerUserId() + "@anonimo.example");
        assertThat(jdbc.queryForObject(
                        "select password_hash is null and password_changed_at is null from customer_user where id = ?",
                        Boolean.class,
                        customer.customerUserId()))
                .isTrue();
    }

    private ResultActions signIn(String email, String password, boolean remember) throws Exception {
        MockHttpServletRequestBuilder request = post("/area-clienti/password")
                .param("email", email)
                .param("password", password)
                .with(csrf())
                .accept("text/html");
        if (remember) {
            request.param("ricordami", "1");
        }
        return mvc.perform(request);
    }

    private String refusedPage(String email, String password) throws Exception {
        String page = signIn(email, password, true)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return CSRF_VALUE.matcher(page).replaceAll("csrf");
    }

    private static String blankEmail(String page, String email) {
        return page.replace(email, "EMAIL");
    }

    private MockHttpServletRequestBuilder changePassword(
            MockHttpSession session, String current, String newPassword, String confirm) {
        MockHttpServletRequestBuilder request = post("/area-clienti/profilo/password")
                .session(session)
                .param("newPassword", newPassword)
                .param("confirmPassword", confirm)
                .with(csrf());
        if (current != null) {
            request.param("currentPassword", current);
        }
        return request;
    }

    private MockHttpSession signInByLink(String email) throws Exception {
        limiter.reset();
        mvc.perform(post("/area-clienti/").param("email", email).with(csrf())).andExpect(status().isOk());
        MvcResult result = mvc.perform(post("/area-clienti/accesso")
                        .param("t", lastLink(email, SIGN_IN_LINK))
                        .with(csrf())
                        .accept("text/html"))
                .andExpect(status().isFound())
                .andReturn();
        return session(result);
    }

    private String registerToken(String email) throws Exception {
        limiter.reset();
        mvc.perform(post("/area-clienti/").param("email", email).with(csrf())).andExpect(status().isOk());
        return lastLink(email, REGISTER_LINK);
    }

    private MockHttpServletRequestBuilder registration(
            String token, String email, String step, String password, String confirm) {
        MockHttpServletRequestBuilder builder = post("/area-clienti/registrazione/")
                .with(csrf())
                .accept("text/html")
                .param("t", token)
                .param("email", email)
                .param("step", step)
                .param("companyName", "Ditta Password S.r.l.")
                .param("addressLine", "Via Prova 2")
                .param("province", "MB")
                .param("city", "Seregno")
                .param("postalCode", "20831")
                .param("phone", "0362 000001")
                .param("vatNumber", "")
                .param("taxCode", "")
                .param("website", "");
        if (password != null) {
            builder.param("password", password).param("passwordConfirm", confirm);
        }
        return builder;
    }

    private String lastLink(String address, Pattern link) {
        worker.runOnce();
        List<OutboundEmail> sent = sender.sent();
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).recipient().equals(address)) {
                Matcher matcher = link.matcher(sent.get(i).bodyText());
                assertThat(matcher.find())
                        .as("link in the e-mail to %s", address)
                        .isTrue();
                return matcher.group(1);
            }
        }
        throw new AssertionError("no e-mail sent to " + address);
    }

    private static MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static CustomerPrincipal principalOf(MvcResult result) {
        SecurityContext context = (SecurityContext) result.getRequest()
                .getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (CustomerPrincipal) context.getAuthentication().getPrincipal();
    }

    private static Cookie rememberCookie(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(value ->
                        value.startsWith(RememberedSignInService.PLAIN_COOKIE + "=") && !value.contains("Max-Age=0"))
                .findFirst()
                .map(value -> new Cookie(
                        RememberedSignInService.PLAIN_COOKIE,
                        value.substring(RememberedSignInService.PLAIN_COOKIE.length() + 1, value.indexOf(';'))))
                .orElse(null);
    }
}
