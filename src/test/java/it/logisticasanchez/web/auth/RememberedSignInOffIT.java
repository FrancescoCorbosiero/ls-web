package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboundEmail;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ADR 0031 seam: with {@code ls.security.sessions.customer.remember=0} the sign-in works as in M1
 * — no «Resta connesso», no cookie, no row — and a device remembered before is not honoured.
 */
@Tag("CUS-01")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.security.sessions.customer.remember=0")
class RememberedSignInOffIT extends AbstractPostgresIT {

    private static final Pattern SIGN_IN_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/accesso\\?t=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private RememberedSignInRepository devices;

    @Test
    void zeroDaysMeansTheSignInOfM1() throws Exception {
        limiter.reset();
        String email = AuthTestData.uniqueEmail("senza-ricordo");
        long userId = AuthTestData.customerUser(jdbc, email, "ACTIVE");
        mvc.perform(post("/area-clienti/").param("email", email).with(csrf())).andExpect(status().isOk());
        String raw = lastSignInToken(email);

        mvc.perform(get("/area-clienti/accesso").param("t", raw).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"ricordami\""))))
                .andExpect(content()
                        .string(Matchers.not(Matchers.containsString("Resta connesso su questo dispositivo"))));
        MvcResult signedIn = mvc.perform(post("/area-clienti/accesso")
                        .param("t", raw)
                        .param("ricordami", "1")
                        .with(csrf())
                        .accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andReturn();
        assertThat(signedIn.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(value -> value.startsWith(RememberedSignInService.PLAIN_COOKIE + "="));
        assertThat(jdbc.queryForObject(
                        "select count(*) from remembered_sign_in where customer_user_id = ?", Long.class, userId))
                .isZero();

        // a device remembered while the setting was on is not honoured
        Instant now = Instant.now();
        String before = Tokens.random();
        devices.save(new RememberedSignIn(userId, Tokens.hash(before), now, now.plus(Duration.ofDays(60))));
        mvc.perform(get("/area-clienti/ordini/")
                        .cookie(new Cookie(RememberedSignInService.PLAIN_COOKIE, before))
                        .accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
    }

    private String lastSignInToken(String email) {
        worker.runOnce();
        List<OutboundEmail> sent = sender.sent();
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).recipient().equals(email)) {
                Matcher matcher = SIGN_IN_LINK.matcher(sent.get(i).bodyText());
                assertThat(matcher.find()).isTrue();
                return matcher.group(1);
            }
        }
        throw new AssertionError("no e-mail sent to " + email);
    }
}
