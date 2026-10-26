package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One sign-in per browser (ADR 0045), on a real server with one cookie jar per browser. Both
 * private areas share the site's one session cookie (path {@code /}, chapter 10 §2), so in one
 * browser the later sign-in ends the earlier session — staff after customer and customer after
 * staff, the old session invalidated on the server, not only replaced in the jar — and a staff
 * session hides the customer's remembered device until «Esci», after which the device opens the
 * customer area again without a new sign-in. Two browsers, as the office and the customers each
 * on their own devices in production, stay signed in side by side whatever the other does. The
 * header of every private page names its area, which gives the back office its own tint.
 */
@Tag("ADM-01")
@Tag("CUS-01")
@Tag("NFR-02")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInPerBrowserRealServerIT extends AbstractPostgresIT {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final String SESSION_COOKIE = "LS_SESSION";
    private static final String STAFF_PASSWORD = CustomerTestData.newPassword();
    private static final String CUSTOMER_PASSWORD = CustomerTestData.newPassword();

    @LocalServerPort
    private int port;

    @Autowired
    private StaffAccountService staffAccounts;

    @Autowired
    private CustomerPasswordService customerPasswords;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private JdbcTemplate jdbc;

    private String staffUsername;
    private CustomerTestData.Ids customer;

    @BeforeEach
    void accounts() {
        limiter.reset();
        staffUsername = "browser-" + UUID.randomUUID().toString().substring(0, 8);
        staffAccounts.setPassword(
                staffAccounts.create(staffUsername, null, null, StaffRole.ADMIN).rawToken(), STAFF_PASSWORD);
        customer = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("browser"), "ACTIVE");
        customerPasswords.setPassword(customer.customerUserId(), CUSTOMER_PASSWORD);
    }

    @Test
    void inOneBrowserTheCustomerSignInEndsTheStaffSessionAndTheStaffSignInEndsTheCustomerOne() {
        Browser browser = new Browser();
        staffSignsIn(browser);
        String staffSession = browser.cookie(SESSION_COOKIE).orElseThrow();
        assertThat(browser.get("/gestione/ordini/").statusCode()).isEqualTo(200);

        // a second tab: the customer signs in
        customerSignsIn(browser);
        assertThat(browser.get("/area-clienti/ordini/").statusCode()).isEqualTo(200);
        assertThat(browser.cookie(SESSION_COOKIE))
                .as("one session cookie for both areas, now the customer's")
                .hasValueSatisfying(id -> assertThat(id).isNotEqualTo(staffSession));
        // back to the first tab: the back office is refused and its entry asks for the login again
        assertThat(browser.get("/gestione/ordini/").statusCode()).isEqualTo(403);
        assertThat(browser.get("/gestione/").body()).contains("<h1>Login</h1>");
        assertThat(onlySession(staffSession, "/gestione/ordini/").statusCode())
                .as("the staff session was ended on the server: its id opens nothing any more")
                .isEqualTo(302);

        // and the other way round
        String customerSession = browser.cookie(SESSION_COOKIE).orElseThrow();
        staffSignsIn(browser);
        assertThat(browser.get("/gestione/ordini/").statusCode()).isEqualTo(200);
        assertThat(onlySession(customerSession, "/area-clienti/ordini/").statusCode())
                .as("the customer session was ended on the server")
                .isEqualTo(302);
    }

    @Test
    void aStaffSessionHidesTheRememberedCustomerOnlyUntilTheStaffLogsOut() {
        Browser browser = new Browser();
        customerSignsIn(browser); // «Resta connesso» ticked, as the page offers it
        assertThat(browser.cookie(RememberedSignInService.PLAIN_COOKIE)).isPresent();

        staffSignsIn(browser);
        assertThat(browser.get("/area-clienti/ordini/").statusCode())
                .as("the browser's session is the staff's: the customer area refuses it")
                .isEqualTo(403);
        assertThat(rememberedDevices()).as("the remembered device is kept").isEqualTo(1);

        HttpResponse<String> logout = browser.post("/gestione/logout", Map.of("_csrf", browser.csrf("/gestione/")));
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(browser.get("/gestione/ordini/").statusCode())
                .as("signed out of the back office")
                .isEqualTo(302);
        HttpResponse<String> back = browser.get("/area-clienti/ordini/");
        assertThat(back.statusCode())
                .as("the remembered device opens the customer area again, no new sign-in")
                .isEqualTo(200);
        assertThat(back.body()).contains("I miei ordini");
    }

    @Test
    void twoBrowsersTheOfficeAndTheCustomerStaySignedInWhateverTheOtherDoes() {
        Browser office = new Browser();
        Browser customerComputer = new Browser();
        staffSignsIn(office);
        customerSignsIn(customerComputer);

        // the same customer on a phone, a colleague on another office computer
        Browser customerPhone = new Browser();
        customerSignsIn(customerPhone);
        Browser colleague = new Browser();
        staffSignsIn(colleague);
        assertThat(office.get("/gestione/ordini/").statusCode()).isEqualTo(200);
        assertThat(customerComputer.get("/area-clienti/ordini/").statusCode()).isEqualTo(200);
        assertThat(customerPhone.get("/area-clienti/ordini/").statusCode()).isEqualTo(200);

        // each «Esci» ends only its own browser
        assertThat(office.post("/gestione/logout", Map.of("_csrf", office.csrf("/gestione/")))
                        .statusCode())
                .isEqualTo(302);
        assertThat(customerPhone
                        .post("/area-clienti/logout", Map.of("_csrf", customerPhone.csrf("/area-clienti/ordini/")))
                        .statusCode())
                .isEqualTo(302);
        assertThat(office.get("/gestione/ordini/").statusCode()).isEqualTo(302);
        assertThat(customerPhone.get("/area-clienti/ordini/").statusCode()).isEqualTo(302);
        assertThat(colleague.get("/gestione/ordini/").statusCode()).isEqualTo(200);
        assertThat(customerComputer.get("/area-clienti/ordini/").statusCode()).isEqualTo(200);
        assertThat(rememberedDevices()).as("the computer stays remembered").isEqualTo(1);
    }

    @Test
    void everyPrivateHeaderNamesItsAreaForTheBackOfficeTint() {
        Browser anonymous = new Browser();
        assertThat(anonymous.get("/gestione/").body())
                .contains("data-area=\"staff\"")
                .doesNotContain("data-area=\"customer\"");
        assertThat(anonymous.get("/area-clienti/").body())
                .contains("data-area=\"customer\"")
                .doesNotContain("data-area=\"staff\"");

        Browser office = new Browser();
        staffSignsIn(office);
        assertThat(office.get("/gestione/ordini/").body()).contains("data-area=\"staff\"");
        Browser customerComputer = new Browser();
        customerSignsIn(customerComputer);
        assertThat(customerComputer.get("/area-clienti/ordini/").body()).contains("data-area=\"customer\"");

        assertThat(anonymous.get("/css/app.css").body())
                .contains("body:has(.site-header[data-area=\"staff\"])")
                .contains(".site-header[data-area=\"staff\"]::before");
    }

    private void staffSignsIn(Browser browser) {
        HttpResponse<String> login = browser.post(
                "/gestione/login",
                form("username", staffUsername, "password", STAFF_PASSWORD, "_csrf", browser.csrf("/gestione/")));
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location"))
                .hasValueSatisfying(l -> assertThat(l).endsWith("/gestione/"));
    }

    /** E-mail and password with «Resta connesso» ticked, as the «Accedi» page offers it (ADR 0031, ADR 0032). */
    private void customerSignsIn(Browser browser) {
        HttpResponse<String> signIn = browser.post(
                "/area-clienti/password",
                form(
                        "email",
                        customer.email(),
                        "password",
                        CUSTOMER_PASSWORD,
                        "ricordami",
                        "1",
                        "_csrf",
                        browser.csrf("/area-clienti/")));
        assertThat(signIn.statusCode()).isEqualTo(302);
        assertThat(signIn.headers().firstValue("Location"))
                .hasValueSatisfying(l -> assertThat(l).endsWith("/area-clienti/ordini/"));
    }

    private int rememberedDevices() {
        Integer count = jdbc.queryForObject(
                "select count(*) from remembered_sign_in where customer_user_id = ?",
                Integer.class,
                customer.customerUserId());
        return count == null ? 0 : count;
    }

    /** A request carrying nothing but this session id: what a stolen or stale cookie would open. */
    private HttpResponse<String> onlySession(String sessionId, String path) {
        return send(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                HttpRequest.newBuilder(uri(path))
                        .header("Accept", "text/html")
                        .header("Cookie", SESSION_COOKIE + "=" + sessionId)
                        .GET()
                        .build());
    }

    private static Map<String, String> form(String... pairs) {
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.put(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** One browser: its own cookie jar, which keeps and sends cookies by host and path as a browser does. */
    private final class Browser {

        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpResponse<String> get(String path) {
            return send(
                    client,
                    HttpRequest.newBuilder(uri(path))
                            .header("Accept", "text/html")
                            .GET()
                            .build());
        }

        HttpResponse<String> post(String path, Map<String, String> form) {
            String body = form.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return send(
                    client,
                    HttpRequest.newBuilder(uri(path))
                            .header("Accept", "text/html")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build());
        }

        /** The CSRF token of the first form on the page, bound to this browser's session. */
        String csrf(String path) {
            HttpResponse<String> page = get(path);
            assertThat(page.statusCode()).as("page %s", path).isEqualTo(200);
            Matcher matcher = CSRF.matcher(page.body());
            assertThat(matcher.find())
                    .as("a form with a CSRF token on %s", path)
                    .isTrue();
            return matcher.group(1);
        }

        Optional<String> cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(HttpCookie::getValue)
                    .findFirst();
        }
    }
}
