package it.logisticasanchez.web.platform;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-04: {@code /actuator/health} is the only management endpoint reachable on the public port
 * and it answers without details; every other management path is absent or refused.
 */
@Tag("NFR-04")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIT extends AbstractPostgresIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void healthAnswers200UpWithoutDetails() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/")
                .contains("json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.properties())
                .as("health body carries only the status, no components or details")
                .hasSize(1);
        assertThat(response.body())
                .doesNotContain("db")
                .doesNotContain("diskSpace")
                .doesNotContain("details");
    }

    @ParameterizedTest(name = "{0} is not reachable")
    @ValueSource(
            strings = {
                "/actuator",
                "/actuator/",
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/info",
                "/actuator/metrics",
                "/actuator/mappings",
                "/actuator/loggers",
                "/actuator/heapdump",
                "/actuator/threaddump",
                "/actuator/health/db",
                "/actuator/health/liveness",
                "/actuator/health/readiness"
            })
    void otherManagementPathsAreAbsentOrRefused(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).as("status of %s", path).isIn(401, 404);
        assertThat(response.body()).as("body of %s", path).doesNotContain("\"status\":\"UP\"");
        assertThat(response.body()).as("body of %s", path).doesNotContain("propertySources");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json, */*")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
