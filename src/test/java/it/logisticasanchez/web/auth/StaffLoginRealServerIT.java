package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * ADM-01 on a real server (a mock request never shows the container's cookie): the session
 * cookie set at login is {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/} and carries a
 * different id from the pre-login session (rotation); the test profile uses the plain name
 * {@code LS_SESSION} without {@code Secure}, which {@link SessionCookieConfigurationTest} checks
 * for the deployed profiles.
 */
@Tag("ADM-01")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffLoginRealServerIT extends AbstractPostgresIT {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern SESSION_ID = Pattern.compile("LS_SESSION=([^;]+)");

    @LocalServerPort
    private int port;

    @Autowired
    private StaffAccountService accounts;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void theSessionCookieHasTheFlagsAndANewIdAfterLogin() throws IOException, InterruptedException {
        String username = "real-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "seregno-pallet-express-2026";
        accounts.setPassword(
                accounts.create(username, null, null, StaffRole.ADMIN).rawToken(), password);

        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/gestione/"))
                        .header("Accept", "text/html")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<h1>Login</h1>").contains("type=\"password\"");
        Matcher csrf = CSRF.matcher(page.body());
        assertThat(csrf.find()).as("the login form carries the CSRF token").isTrue();
        List<String> cookies = page.headers().allValues("Set-Cookie");
        assertThat(cookies)
                .as("the sign-in page needs a session for its CSRF token")
                .isNotEmpty();
        String before = sessionId(cookies.get(0));

        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/gestione/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", "LS_SESSION=" + before)
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location"))
                .hasValueSatisfying(l -> assertThat(l).endsWith("/gestione/"));
        List<String> loginCookies = login.headers().allValues("Set-Cookie");
        assertThat(loginCookies).hasSize(1);
        String cookie = loginCookies.get(0);
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(sessionId(cookie)).as("session id rotated on login").isNotEqualTo(before);

        HttpResponse<String> home = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/gestione/"))
                        .header("Accept", "text/html")
                        .header("Cookie", "LS_SESSION=" + sessionId(cookie))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Ciao " + username).doesNotContain("<h1>Login</h1>");
        assertThat(home.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    private static String sessionId(String setCookie) {
        Matcher matcher = SESSION_ID.matcher(setCookie);
        assertThat(matcher.find()).as("session cookie in %s", setCookie).isTrue();
        return matcher.group(1);
    }
}
