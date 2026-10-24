package it.logisticasanchez.web.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR-02 (chapter 10 §4): behind the proxy the client IP is the last {@code X-Forwarded-For}
 * hop — never the socket address, never the value a client wrote before it — and the scheme
 * and host come from the forwarded headers; without a proxy the headers are ignored.
 */
@Tag("NFR-02")
class ForwardedHeadersIT {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestFixtures.class)
    @TestPropertySource(properties = "ls.security.proxy.trust-forwarded-headers=true")
    class BehindOneProxyHop extends AbstractPostgresIT {

        @Autowired
        private MockMvc mvc;

        @Test
        void theLastForwardedForHopIsTheClient() throws Exception {
            mvc.perform(get("/fixture/forwarded")
                            .header("X-Forwarded-For", "203.0.113.5, 198.51.100.7")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", "logisticasanchez.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clientIp").value("198.51.100.7"))
                    .andExpect(jsonPath("$.remoteAddr").value("198.51.100.7"))
                    .andExpect(jsonPath("$.scheme").value("https"))
                    .andExpect(jsonPath("$.serverName").value("logisticasanchez.com"))
                    .andExpect(jsonPath("$.url").value("https://logisticasanchez.com/fixture/forwarded"));
        }

        @Test
        void withoutTheHeaderTheSocketAddressIsAllThereIs() throws Exception {
            mvc.perform(get("/fixture/forwarded").with(request -> {
                        request.setRemoteAddr("10.0.0.2");
                        return request;
                    }))
                    .andExpect(jsonPath("$.clientIp").value("10.0.0.2"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestFixtures.class)
    class WithoutAProxy extends AbstractPostgresIT {

        @Autowired
        private MockMvc mvc;

        @Test
        void forwardedHeadersAreIgnoredAndTheSocketAddressIsTheClient() throws Exception {
            mvc.perform(get("/fixture/forwarded")
                            .header("X-Forwarded-For", "203.0.113.5, 198.51.100.7")
                            .header("X-Forwarded-Proto", "https")
                            .with(request -> {
                                request.setRemoteAddr("127.0.0.1");
                                return request;
                            }))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clientIp").value("127.0.0.1"))
                    .andExpect(jsonPath("$.remoteAddr").value("127.0.0.1"))
                    .andExpect(jsonPath("$.scheme").value("http"));
        }
    }
}
