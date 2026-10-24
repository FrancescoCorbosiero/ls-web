package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.SessionLimitsFilter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import it.logisticasanchez.web.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADM-01 (chapter 10 §2, §4; ADR 0003): staff log in with username and password on the «Login»
 * page, a wrong username or password answers «Username o password errate.» and nothing else,
 * the password is stored as a bcrypt hash, the session id is rotated on login and stamped for
 * the absolute limit, the idle limit is 30 minutes and the absolute limit 10 hours
 * (clock-driven), logout ends the session, an OPERATOR on an ADMIN route gets 403, the account
 * locks after the configured failures for the configured time (PD-10) and the login endpoint
 * is rate-limited per IP.
 */
@Tag("ADM-01")
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestFixtures.class, MutableClock.Configuration.class})
@TestPropertySource(
        properties = {
            "ls.security.staff.lockout.threshold=3",
            "ls.security.staff.lockout.duration=15m",
            "ls.ratelimit.staff-login.permits=4",
            "ls.ratelimit.staff-login.window=15m"
        })
class StaffLoginIT extends AbstractPostgresIT {

    private static final String PASSWORD = "pallet-espresso-seregno-2026";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffAccountService accounts;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private MutableClock clock;

    private String username;
    private String ip;

    @BeforeEach
    void createOperator() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        limiter.reset();
        username = "op-" + UUID.randomUUID().toString().substring(0, 8);
        ip = "203.0.113." + (int) (Math.random() * 200 + 1);
        StaffAccountService.SetupLink link = accounts.create(username, "Operatore Prova", null, StaffRole.OPERATOR);
        accounts.setPassword(link.rawToken(), PASSWORD);
    }

    @Test
    void thePasswordIsStoredAsABcryptHashNeverInClear() {
        StaffUser user = users.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
    }

    @Test
    void theLoginPageIsTheLegacyLoginWithARealPasswordField() throws Exception {
        mvc.perform(get("/gestione/").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>Login</h1>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"password\"")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Username o password errate."))));
        mvc.perform(get("/gestione/?errore=1").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Username o password errate.")));
    }

    @Test
    void wrongPasswordUnknownUserAndMissingPasswordAllAnswerTheSameRedirect() throws Exception {
        mvc.perform(login(username, "wrong-password-value"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));
        mvc.perform(login("nobody-" + username, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));
        mvc.perform(login(username, ""))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));
        assertThat(users.findByUsername(username).orElseThrow().getFailedLogins())
                // two failures recorded for the existing account
                .isEqualTo(2);
    }

    @Test
    void loginRotatesTheSessionStampsItAndLeadsToTheBackOffice() throws Exception {
        MockHttpSession before = new MockHttpSession();
        MvcResult result = mvc.perform(login(username, PASSWORD).session(before))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andReturn();
        MockHttpSession after = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(after).isNotNull();
        assertThat(after.getId()).as("session id rotated on login").isNotEqualTo(before.getId());
        assertThat(before.isInvalid()).isTrue();
        assertThat(after.getAttribute(SessionLimitsFilter.AUTHENTICATED_AT)).isEqualTo(clock.instant());
        SecurityContext context =
                (SecurityContext) after.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(StaffPrincipal.class);
        StaffPrincipal principal = (StaffPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.role()).isEqualTo(StaffRole.OPERATOR);
        assertThat(users.findByUsername(username).orElseThrow().getLastLoginAt())
                .isEqualTo(clock.instant());

        mvc.perform(get("/gestione/").session(after).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ciao Operatore Prova")));
        assertThat(after.getMaxInactiveInterval())
                // idle limit of staff sessions: 30 minutes
                .isEqualTo((int) Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void anOperatorMayUseStaffRoutesButNotAdminOnes() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/gestione/fixture").session(session)).andExpect(status().isOk());
        mvc.perform(get("/gestione/fixture/admin").session(session).accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/area-clienti/fixture").session(session).accept("text/html"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutIsAPostWithCsrfThatInvalidatesTheSession() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(post("/gestione/logout").session(session).accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/gestione/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/gestione/fixture").accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
    }

    @Test
    void pastTheAbsoluteLimitTheSessionEndsEvenIfActive() throws Exception {
        MockHttpSession session = signIn();
        clock.advance(Duration.ofHours(9));
        mvc.perform(get("/gestione/fixture").session(session)).andExpect(status().isOk());
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        mvc.perform(get("/gestione/fixture").session(session).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void theAccountLocksAfterTheConfiguredFailuresForTheConfiguredTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(login(username, "wrong-password-value")).andExpect(status().isFound());
        }
        StaffUser locked = users.findByUsername(username).orElseThrow();
        assertThat(locked.getLockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        mvc.perform(login(username, PASSWORD))
                // the right password is refused while locked
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));

        clock.advance(Duration.ofMinutes(16));
        MvcResult result = mvc.perform(login(username, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNotNull();
        StaffUser unlocked = users.findByUsername(username).orElseThrow();
        assertThat(unlocked.getFailedLogins()).isZero();
        assertThat(unlocked.getLockedUntil()).isNull();
    }

    @Test
    void loginAttemptsAreRateLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(login("nobody-" + i, "wrong-password-value")).andExpect(status().isFound());
        }
        mvc.perform(login("nobody-5", "wrong-password-value").accept("text/html"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(login(username, PASSWORD))
                // even the right credentials wait
                .andExpect(status().isTooManyRequests());
        mvc.perform(login(username, PASSWORD).with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                }))
                // another client is not affected
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
    }

    @Test
    void anAccountWithoutAPasswordYetCannotLogIn() throws Exception {
        String pending = "pending-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(pending, null, null, StaffRole.ADMIN);
        mvc.perform(login(pending, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));
        mvc.perform(login(pending, ""))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?errore=1"));
    }

    private MockHttpSession signIn() throws Exception {
        MvcResult result = mvc.perform(login(username, PASSWORD))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder login(String user, String password) {
        return post("/gestione/login")
                .param("username", user)
                .param("password", password)
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }
}
