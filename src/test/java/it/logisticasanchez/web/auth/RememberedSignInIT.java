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
import it.logisticasanchez.web.support.MutableClock;
import jakarta.servlet.http.Cookie;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CUS-01 with the owner decision of 2026-10-25 (ADR 0031): after a sign-in link the customer
 * stays signed in on the device for 60 days. «Resta connesso» is offered ticked; ticked, the
 * {@code POST} sets an {@code HttpOnly}, {@code SameSite=Lax} cookie limited to
 * {@code /area-clienti/} and stores only the token's hash with a 60-day expiry; the cookie
 * restores the session when it has ended (no session, the 12-hour limit) until the 60 days are
 * over; logout (even from a page whose session had ended), {@code endAllSessions}, a disabled
 * login, an unknown cookie, a new sign-in on the same device (ticked: the token is replaced;
 * unticked: the device is forgotten) are covered; the cookie is
 * ignored outside the customer area; the registration offers the same choice; expired devices
 * are deleted by the retention job.
 */
@Tag("CUS-01")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
class RememberedSignInIT extends AbstractPostgresIT {

    private static final Pattern SIGN_IN_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/accesso\\?t=([A-Za-z0-9_-]+)");
    private static final Pattern REGISTER_LINK =
            Pattern.compile("https://logisticasanchez\\.com/area-clienti/registrazione/\\?t=([A-Za-z0-9_-]+)");
    private static final String COOKIE = RememberedSignInService.PLAIN_COOKIE;
    private static final long SIXTY_DAYS = Duration.ofDays(60).toSeconds();

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
    private MutableClock clock;

    @Autowired
    private RememberedSignInRepository devices;

    @Autowired
    private CustomerSessionService sessions;

    @Autowired
    private ExpiredRememberedSignInRetentionJob retention;

    private String email;
    private long userId;
    private String ip;

    @BeforeEach
    void reset() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        limiter.reset();
        outbox.deleteAll();
        sender.clear();
        email = AuthTestData.uniqueEmail("ricordami");
        userId = AuthTestData.customerUser(jdbc, email, "ACTIVE");
        ip = "203.0.113." + (int) (Math.random() * 200 + 1);
    }

    @Test
    void theLinkPageOffersToStaySignedInForSixtyDaysTickedByDefault() throws Exception {
        mvc.perform(get("/area-clienti/accesso").param("t", signInToken()).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(
                        content().string(Matchers.containsString("Resta connesso su questo dispositivo per 60 giorni")))
                .andExpect(content().string(Matchers.containsString("Togli la spunta se usi un computer condiviso.")))
                .andExpect(content()
                        .string(Matchers.containsString(
                                "<input type=\"checkbox\" name=\"ricordami\" value=\"1\" checked")));
    }

    @Test
    void tickedTheDeviceIsRememberedForSixtyDaysWithOnlyTheHashStored() throws Exception {
        MvcResult result = useLink(signInToken(), true, null)
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andReturn();

        String setCookie = setCookie(result.getResponse());
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("Path=/area-clienti/", "Max-Age=" + SIXTY_DAYS, "HttpOnly", "SameSite=Lax");
        String raw = cookieValue(setCookie);
        assertThat(raw).hasSize(43); // 256 random bits, URL-safe base64

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select token_hash, expires_at, created_at from remembered_sign_in where customer_user_id = ?", userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("token_hash")).isEqualTo(Tokens.hash(raw)).isNotEqualTo(raw);
        Instant created = ((java.sql.Timestamp) rows.get(0).get("created_at")).toInstant();
        Instant expires = ((java.sql.Timestamp) rows.get(0).get("expires_at")).toInstant();
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofDays(60));
    }

    @Test
    void untickedNothingIsRemembered() throws Exception {
        MvcResult result = useLink(signInToken(), false, null)
                .andExpect(status().isFound())
                .andReturn();
        assertThat(setCookie(result.getResponse())).isNull();
        assertThat(deviceCount()).isZero();
    }

    @Test
    void theCookieSignsTheCustomerBackInWhenTheSessionHasEndedUntilTheSixtyDaysAreOver() throws Exception {
        Cookie remembered = rememberedCookie();

        // no session at all (idle timeout, restart): the area opens directly
        MvcResult restored = mvc.perform(
                        get("/area-clienti/ordini/").cookie(remembered).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(principalOf(restored).customerUserId()).isEqualTo(userId);
        assertThat(setCookie(restored.getResponse()))
                .as("the cookie is kept as it is")
                .isNull();
        assertThat(jdbc.queryForObject(
                        "select last_used_at is not null from remembered_sign_in where customer_user_id = ?",
                        Boolean.class,
                        userId))
                .isTrue();
        // the e-mail form sends a remembered customer straight to the orders
        mvc.perform(get("/area-clienti/").cookie(remembered).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"));

        // the 12-hour limit ends the session: the same request goes on with a new one
        MockHttpSession session = (MockHttpSession) restored.getRequest().getSession(false);
        clock.advance(Duration.ofHours(13));
        MvcResult afterLimit = mvc.perform(get("/area-clienti/ordini/")
                        .session(session)
                        .cookie(remembered)
                        .accept("text/html"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(session.isInvalid()).isTrue();
        assertThat(principalOf(afterLimit).customerUserId()).isEqualTo(userId);

        clock.advance(Duration.ofDays(60).minusHours(14)); // one hour before the 60 days
        mvc.perform(get("/area-clienti/ordini/").cookie(remembered).accept("text/html"))
                .andExpect(status().isOk());

        clock.advance(Duration.ofHours(2)); // past the 60 days counted from the sign-in
        MvcResult expired = mvc.perform(
                        get("/area-clienti/ordini/").cookie(remembered).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"))
                .andReturn();
        assertThat(setCookie(expired.getResponse())).startsWith(COOKIE + "=;").contains("Max-Age=0");
    }

    @Test
    void logoutForgetsTheDeviceEvenFromAPageWhoseSessionHadEnded() throws Exception {
        Cookie remembered = rememberedCookie();
        MvcResult restored = mvc.perform(get("/area-clienti/ordini/").cookie(remembered))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) restored.getRequest().getSession(false);

        MvcResult logout = mvc.perform(post("/area-clienti/logout")
                        .session(session)
                        .cookie(remembered)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"))
                .andReturn();
        assertThat(session.isInvalid()).isTrue();
        assertThat(setCookie(logout.getResponse())).startsWith(COOKIE + "=;").contains("Max-Age=0");
        assertThat(deviceCount()).isZero();
        mvc.perform(get("/area-clienti/ordini/").cookie(remembered).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));

        // «Esci» on a page left open after its session ended: the CSRF token is stale (403),
        // but the device is forgotten all the same
        Cookie another = rememberedCookie();
        assertThat(deviceCount()).isOne();
        mvc.perform(post("/area-clienti/logout").cookie(another).accept("text/html"))
                .andExpect(status().isForbidden());
        assertThat(deviceCount()).isZero();
    }

    @Test
    void endAllSessionsForgetsEveryDeviceOfTheLogin() throws Exception {
        Cookie phone = rememberedCookie();
        Cookie office = rememberedCookie();
        assertThat(deviceCount()).isEqualTo(2);

        sessions.endAllSessions(userId); // decline, e-mail change, anonymisation

        assertThat(deviceCount()).isZero();
        for (Cookie cookie : List.of(phone, office)) {
            mvc.perform(get("/area-clienti/ordini/").cookie(cookie).accept("text/html"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/area-clienti/"));
        }
    }

    @Test
    void aNewSignInOnTheSameDeviceReplacesItsToken() throws Exception {
        Cookie first = rememberedCookie();
        MvcResult again = useLink(signInToken(), true, first)
                .andExpect(status().isFound())
                .andReturn();
        String second = cookieValue(setCookie(again.getResponse()));

        assertThat(second).isNotEqualTo(first.getValue());
        assertThat(deviceCount()).isOne();
        mvc.perform(get("/area-clienti/ordini/").cookie(first).accept("text/html"))
                .andExpect(status().isFound());
        mvc.perform(get("/area-clienti/ordini/").cookie(new Cookie(COOKIE, second)))
                .andExpect(status().isOk());
    }

    @Test
    void anUntickedSignInOnARememberedDeviceForgetsTheEarlierOne() throws Exception {
        Cookie earlier = rememberedCookie(); // a shared computer remembers this customer
        String colleague = AuthTestData.uniqueEmail("collega");
        long colleagueId = AuthTestData.customerUser(jdbc, colleague, "ACTIVE");

        MvcResult result = useLink(signInToken(colleague), false, earlier)
                .andExpect(status().isFound())
                .andReturn();
        assertThat(principalOf(result).customerUserId()).isEqualTo(colleagueId);
        assertThat(setCookie(result.getResponse())).startsWith(COOKIE + "=;").contains("Max-Age=0");
        assertThat(deviceCount()).isZero();
        // once the colleague's session ends, the earlier customer's account does not reopen
        mvc.perform(get("/area-clienti/ordini/").cookie(earlier).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
    }

    @Test
    void aDisabledLoginOrAnUnknownCookieSignsNobodyInAndTheCookieIsCleared() throws Exception {
        Cookie remembered = rememberedCookie();
        jdbc.update("update customer_user set disabled_at = now() where id = ?", userId);
        MvcResult disabled = mvc.perform(
                        get("/area-clienti/ordini/").cookie(remembered).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"))
                .andReturn();
        assertThat(setCookie(disabled.getResponse())).contains("Max-Age=0");

        MvcResult unknown = mvc.perform(get("/area-clienti/ordini/")
                        .cookie(new Cookie(COOKIE, "non-un-token"))
                        .accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"))
                .andReturn();
        assertThat(setCookie(unknown.getResponse())).contains("Max-Age=0");
    }

    @Test
    void theCookieIsIgnoredOutsideTheCustomerArea() throws Exception {
        Cookie remembered = rememberedCookie();
        MvcResult publicPage = mvc.perform(get("/").cookie(remembered))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(publicPage.getResponse().getHeaders("Set-Cookie"))
                .as("public pages stay cookie-free")
                .isEmpty();
        assertThat(publicPage.getRequest().getSession(false)).isNull();

        MvcResult staff = mvc.perform(get("/gestione/").cookie(remembered).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(setCookie(staff.getResponse())).isNull();
        jakarta.servlet.http.HttpSession session = staff.getRequest().getSession(false);
        assertThat(
                        session == null
                                ? null
                                : session.getAttribute(
                                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("no customer signed in by the cookie on the staff login page")
                .isNull();
    }

    @Test
    @Tag("CUS-02")
    void theRegistrationSummaryOffersTheSameChoice() throws Exception {
        String ticked = AuthTestData.uniqueEmail("nuovo-ricordami");
        String token = registerToken(ticked);
        mvc.perform(registration(token, ticked, "review", false))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(Matchers.containsString("Resta connesso su questo dispositivo per 60 giorni")));
        MvcResult remembered = mvc.perform(registration(token, ticked, "confirm", true))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/ordini/"))
                .andReturn();
        assertThat(setCookie(remembered.getResponse())).contains("Max-Age=" + SIXTY_DAYS);

        String unticked = AuthTestData.uniqueEmail("nuovo-senza");
        MvcResult notRemembered = mvc.perform(registration(registerToken(unticked), unticked, "confirm", false))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(setCookie(notRemembered.getResponse())).isNull();
    }

    @Test
    void theRetentionJobDeletesExpiredDevicesOnly() {
        Instant now = clock.instant();
        String expired = Tokens.hash(Tokens.random());
        String valid = Tokens.hash(Tokens.random());
        devices.save(
                new RememberedSignIn(userId, expired, now.minus(Duration.ofDays(61)), now.minus(Duration.ofDays(1))));
        devices.save(new RememberedSignIn(userId, valid, now, now.plus(Duration.ofDays(60))));

        assertThat(retention.name()).isEqualTo("remembered-sign-ins");
        assertThat(retention.run(now)).isGreaterThanOrEqualTo(1);
        assertThat(devices.findByTokenHash(expired)).isEmpty();
        assertThat(devices.findByTokenHash(valid)).isPresent();
    }

    private long deviceCount() {
        return jdbc.queryForObject(
                "select count(*) from remembered_sign_in where customer_user_id = ?", Long.class, userId);
    }

    /** Signs in through a link with «Resta connesso» ticked and returns the cookie the browser keeps. */
    private Cookie rememberedCookie() throws Exception {
        MvcResult result =
                useLink(signInToken(), true, null).andExpect(status().isFound()).andReturn();
        return new Cookie(COOKIE, cookieValue(setCookie(result.getResponse())));
    }

    private org.springframework.test.web.servlet.ResultActions useLink(String raw, boolean remember, Cookie device)
            throws Exception {
        MockHttpServletRequestBuilder request =
                post("/area-clienti/accesso").param("t", raw).with(csrf()).accept("text/html");
        if (remember) {
            request.param("ricordami", "1");
        }
        if (device != null) {
            request.cookie(device);
        }
        return mvc.perform(request);
    }

    /** Requests a sign-in link for the test's customer and reads its raw token from the delivered e-mail. */
    private String signInToken() throws Exception {
        return signInToken(email);
    }

    private String signInToken(String address) throws Exception {
        limiter.reset();
        mvc.perform(post("/area-clienti/").param("email", address).with(csrf()).with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }))
                .andExpect(status().isOk());
        return lastLink(address, SIGN_IN_LINK);
    }

    private String registerToken(String address) throws Exception {
        limiter.reset();
        mvc.perform(post("/area-clienti/").param("email", address).with(csrf()).with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }))
                .andExpect(status().isOk());
        return lastLink(address, REGISTER_LINK);
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

    private MockHttpServletRequestBuilder registration(String token, String address, String step, boolean remember) {
        MockHttpServletRequestBuilder builder = post("/area-clienti/registrazione/")
                .with(csrf())
                .accept("text/html")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .param("t", token)
                .param("email", address)
                .param("step", step)
                .param("companyName", "Ditta Ricordami S.r.l.")
                .param("addressLine", "Via Prova 1")
                .param("province", "MB")
                .param("city", "Seregno")
                .param("postalCode", "20831")
                .param("phone", "0362 000000")
                .param("vatNumber", "")
                .param("taxCode", "")
                .param("website", "")
                .param("password", "Pallet-di-prova-2026")
                .param("passwordConfirm", "Pallet-di-prova-2026");
        if (remember) {
            builder.param("ricordami", "1");
        }
        return builder;
    }

    /** The remember cookie's Set-Cookie header of the response, or null. */
    private static String setCookie(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(COOKIE + "="))
                .findFirst()
                .orElse(null);
    }

    private static String cookieValue(String setCookie) {
        assertThat(setCookie).as("Set-Cookie of %s", COOKIE).isNotNull();
        return setCookie.substring(COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    private static CustomerPrincipal principalOf(MvcResult result) {
        SecurityContext context = (SecurityContext) result.getRequest()
                .getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (CustomerPrincipal) context.getAuthentication().getPrincipal();
    }
}
