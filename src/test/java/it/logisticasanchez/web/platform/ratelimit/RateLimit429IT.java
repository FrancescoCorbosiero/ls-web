package it.logisticasanchez.web.platform.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** NFR-02, ADR 0002: past the limit a route answers 429 with the JSON schema and {@code Retry-After}. */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@TestPropertySource(properties = {"ls.ratelimit.form-submit.permits=2", "ls.ratelimit.form-submit.window=10m"})
class RateLimit429IT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RateLimiter limiter;

    @BeforeEach
    void reset() {
        limiter.reset();
    }

    @Test
    void theThirdRequestInTheWindowIs429() throws Exception {
        mvc.perform(get("/fixture/ratelimit")).andExpect(status().isOk());
        mvc.perform(get("/fixture/ratelimit")).andExpect(status().isOk());
        mvc.perform(get("/fixture/ratelimit").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("too_many_requests"))
                .andExpect(jsonPath("$.reason")
                        .value("Hai fatto troppe richieste in poco tempo. Riprova tra qualche minuto."))
                .andExpect(jsonPath("$.requestId").isString());
        mvc.perform(get("/fixture/ratelimit").accept(MediaType.TEXT_HTML))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Another client is not affected.
        mvc.perform(get("/fixture/ratelimit").with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                }))
                .andExpect(status().isOk());
    }
}
