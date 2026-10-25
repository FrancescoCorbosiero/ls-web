package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * PUB-05 / chapter 10 §2 "public pages stay cookie-free and cacheable": on a real server (a
 * mock request never shows the container's session cookie) no public answer — a 200, a 404
 * page, the health endpoint, the redirect of a refused anonymous request — sets a cookie,
 * whatever the {@code Accept} header, so nothing creates a session outside the private
 * areas (the deferred CSRF token and the saved request included).
 */
@Tag("PUB-05")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicPagesCookieFreeIT extends AbstractPostgresIT {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @ParameterizedTest(name = "{0} with Accept {1} sets no cookie")
    @CsvSource({
        "/actuator/health, */*",
        "/non-esiste/, */*",
        "/non-esiste/, 'text/html, application/json'",
        "/api/non-esiste, */*",
        "/area-clienti/ordini/, */*",
        "/area-clienti/ordini/, text/html",
        "/gestione/ordini/, text/html"
    })
    void publicAnswersSetNoCookie(String path, String accept) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", accept)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isLessThan(500);
        assertThat(response.headers().allValues("Set-Cookie"))
                .as("Set-Cookie on %s", path)
                .isEmpty();
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }
}
