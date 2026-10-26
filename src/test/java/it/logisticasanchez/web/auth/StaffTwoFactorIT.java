package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.MutableClock;
import it.logisticasanchez.web.support.StaffTestData;
import it.logisticasanchez.web.support.TestFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ENH-09 with {@code security.adminTotp} on (ADR 0039): after the right password the session
 * holds only the pending login; a user without a secret sets the app up (QR code, key) and
 * confirms it with a first code, which stores the secret encrypted; later logins ask the code,
 * each code once; wrong codes count as failed logins and five of them, or five minutes, start the
 * login again; an ADMIN resets a user's secret from «Utenti della gestione», the
 * {@code reset-staff-totp} command does it on the server.
 */
@Tag("ENH-09")
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestFixtures.class, MutableClock.Configuration.class})
@TestPropertySource(properties = "ls.flags.override.[security.adminTotp]=true")
class StaffTwoFactorIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffAccountService accounts;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private ResetStaffTotpCommand command;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private String username;
    private String password;
    private long userId;

    @BeforeEach
    void operator() {
        // the middle of a 30-second step: the codes of the steps around it are predictable
        clock.set(Instant.parse("2026-10-26T09:00:15Z"));
        limiter.reset();
        username = "due-passi-" + UUID.randomUUID().toString().substring(0, 8);
        password = CustomerTestData.newPassword();
        StaffAccountService.SetupLink link =
                accounts.create(username, "Operatore Due Passaggi", null, StaffRole.OPERATOR);
        accounts.setPassword(link.rawToken(), password);
        userId = link.staffUserId();
    }

    @Test
    void theFirstLoginSetsTheAppUpAndOnlyTheCodeOpensTheBackOffice() throws Exception {
        MockHttpSession session = passwordStep();
        assertThat(page(get("/gestione/").session(session)))
                .as("not signed in yet")
                .contains("<h1>Login</h1>");
        mvc.perform(get("/gestione/fixture").session(session).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));

        String setUp = page(get("/gestione/verifica").session(session));
        byte[] secret = pending(session).newSecret();
        assertThat(secret).hasSize(20);
        assertThat(setUp)
                .contains("data-two-factor-setup")
                .contains("<svg class=\"totp-qr\"")
                .contains(Totp.base32(secret).replaceAll("(.{4})(?!$)", "$1 "))
                .contains("autocomplete=\"one-time-code\"");
        assertThat(page(get("/gestione/verifica").session(session)))
                .as("the same secret until it is confirmed")
                .contains(Totp.base32(secret).substring(0, 4));

        String wrong = otherThan(Totp.code(secret, step()));
        assertThat(page(code(session, wrong))).contains("data-wrong-code").contains("data-two-factor-setup");
        assertThat(user().getTotpSecret())
                .as("nothing stored before a right code")
                .isNull();
        assertThat(user().getFailedLogins()).isEqualTo(1);

        MvcResult result = code(session, Totp.code(secret, step()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andReturn();
        MockHttpSession signedIn = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(signedIn.getId())
                .as("a new session id for the signed-in user")
                .isNotEqualTo(session.getId());
        assertThat(signedIn.getAttribute(PendingStaffLogin.SESSION_ATTRIBUTE)).isNull();
        assertThat(page(get("/gestione/").session(signedIn))).contains("Ciao Operatore Due Passaggi");
        mvc.perform(get("/gestione/fixture").session(signedIn)).andExpect(status().isOk());

        StaffUser stored = user();
        assertThat(stored.getTotpSecret()).as("encrypted: IV, ciphertext, tag").hasSize(12 + 20 + 16);
        assertThat(new String(stored.getTotpSecret(), StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(secret, StandardCharsets.ISO_8859_1));
        assertThat(stored.getTotpEnabledAt()).isEqualTo(clock.instant());
        assertThat(stored.getTotpLastStep()).isEqualTo(step());
        assertThat(stored.getFailedLogins()).isZero();
        assertThat(stored.getLastLoginAt()).isEqualTo(clock.instant());
        assertThat(jdbc.queryForList(
                        "select action, actor_type from audit_log where entity_type = 'staff_user' and entity_id = ?",
                        userId))
                .contains(Map.of("action", "staff.totp.enable", "actor_type", "STAFF"));
    }

    @Test
    void theNextLoginsAskTheCodeAndEachCodeWorksOnce() throws Exception {
        byte[] secret = setUpTheApp();
        clock.advance(Duration.ofSeconds(30));

        MockHttpSession session = passwordStep();
        assertThat(page(get("/gestione/verifica").session(session)))
                .doesNotContain("data-two-factor-setup")
                .contains("Inserisci il codice di 6 cifre");
        assertThat(page(code(session, Totp.code(secret, step() - 1))))
                .as("the step of the set-up code is used")
                .contains("data-wrong-code");
        code(session, Totp.code(secret, step()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));

        MockHttpSession again = passwordStep();
        assertThat(page(code(again, Totp.code(secret, step()))))
                .as("the same code twice")
                .contains("data-wrong-code");
        code(again, Totp.code(secret, step() + 1))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
        assertThat(user().getTotpLastStep()).isEqualTo(step() + 1);
    }

    @Test
    void fiveWrongCodesOrFiveMinutesStartTheLoginAgain() throws Exception {
        byte[] secret = setUpTheApp();
        clock.advance(Duration.ofMinutes(1));
        String wrong =
                otherThan(Totp.code(secret, step()), Totp.code(secret, step() - 1), Totp.code(secret, step() + 1));

        MockHttpSession session = passwordStep();
        for (int i = 0; i < 4; i++) {
            assertThat(page(code(session, wrong))).contains("data-wrong-code");
        }
        code(session, wrong)
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?verifica=1"));
        assertThat(page(get("/gestione/?verifica=1")))
                .contains("La verifica non è riuscita o è scaduta: accedi di nuovo.");
        code(session, Totp.code(secret, step())).andExpect(header().string("Location", "/gestione/?verifica=1"));
        assertThat(user().getFailedLogins())
                .as("each wrong code is a failed login (PD-10)")
                .isEqualTo(5);
        assertThat(user().getTotpLastStep()).as("nothing accepted").isEqualTo(step() - 2);

        MockHttpSession late = passwordStep();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        mvc.perform(get("/gestione/verifica").session(late))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
        code(late, Totp.code(secret, step())).andExpect(header().string("Location", "/gestione/?verifica=1"));
    }

    @Test
    void anAdminResetsTheSecretAndTheCommandToo() throws Exception {
        setUpTheApp();
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(users, StaffRole.ADMIN));
        assertThat(page(get("/gestione/impostazioni/utenti/").session(admin)))
                .contains("data-two-factor")
                .contains("Azzera la verifica in due passaggi di " + username);
        mvc.perform(post("/gestione/impostazioni/utenti/" + userId + "/doppia-verifica")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/impostazioni/utenti/"))
                .andExpect(flash().attribute("flash", Matchers.containsString("azzerata")));
        assertThat(user().getTotpSecret()).isNull();
        assertThat(user().getTotpEnabledAt()).isNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'staff.totp.reset' and entity_id = ?",
                        Integer.class,
                        userId))
                .isEqualTo(1);
        assertThat(page(get("/gestione/verifica").session(passwordStep())))
                .as("the next login sets the app up again")
                .contains("data-two-factor-setup");

        setUpTheApp();
        Output output = run("reset-staff-totp", "--username=" + username.toUpperCase());
        assertThat(output.code()).isZero();
        assertThat(output.out()).contains("Verifica in due passaggi azzerata per " + username);
        assertThat(user().getTotpSecret()).isNull();
        assertThat(run("reset-staff-totp", "--username=nessuno-" + username).code())
                .isEqualTo(1);
        assertThat(run("reset-staff-totp").code()).isEqualTo(2);

        MockHttpSession operator = StaffTestData.session(StaffTestData.staff(users, StaffRole.OPERATOR));
        mvc.perform(post("/gestione/impostazioni/utenti/" + userId + "/doppia-verifica")
                        .session(operator)
                        .with(csrf())
                        .accept("text/html"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAccountDisabledOrLockedMeanwhileIsNotSignedInByItsCode() throws Exception {
        byte[] secret = setUpTheApp();
        clock.advance(Duration.ofSeconds(30));
        MockHttpSession session = passwordStep();
        jdbc.update("update staff_user set active = false where id = ?", userId);
        code(session, Totp.code(secret, step()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?verifica=1"));

        jdbc.update("update staff_user set active = true where id = ?", userId);
        MockHttpSession locked = passwordStep();
        jdbc.update(
                "update staff_user set locked_until = ? where id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))),
                userId);
        code(locked, Totp.code(secret, step()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?verifica=1"));
        assertThat(user().getTotpLastStep()).as("no code accepted").isEqualTo(step() - 1);
    }

    /** Password, set-up page, first code: returns the secret; the clock stays where it was. */
    private byte[] setUpTheApp() throws Exception {
        MockHttpSession session = passwordStep();
        page(get("/gestione/verifica").session(session));
        byte[] secret = pending(session).newSecret();
        code(session, Totp.code(secret, step())).andExpect(header().string("Location", "/gestione/"));
        return secret;
    }

    /** The right password: the session holds the pending login and the code page is next. */
    private MockHttpSession passwordStep() throws Exception {
        MvcResult result = mvc.perform(post("/gestione/login")
                        .param("username", username)
                        .param("password", password)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/verifica"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(pending(session).staffUserId()).isEqualTo(userId);
        return session;
    }

    private ResultActions code(MockHttpSession session, String code) throws Exception {
        return mvc.perform(
                post("/gestione/verifica").session(session).with(csrf()).param("codice", code));
    }

    private static PendingStaffLogin pending(MockHttpSession session) {
        return (PendingStaffLogin) session.getAttribute(PendingStaffLogin.SESSION_ATTRIBUTE);
    }

    private long step() {
        return Totp.step(clock.instant());
    }

    private StaffUser user() {
        return users.findById(userId).orElseThrow();
    }

    /** A 6-digit code that is none of the given ones. */
    private static String otherThan(String... codes) {
        for (int n = 0; ; n++) {
            String candidate = String.format("%06d", n);
            if (!Arrays.asList(codes).contains(candidate)) {
                return candidate;
            }
        }
    }

    private Output run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = command.run(
                new DefaultApplicationArguments(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Output(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Output(int code, String out, String err) {}

    private String page(RequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String page(ResultActions actions) throws Exception {
        return actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
