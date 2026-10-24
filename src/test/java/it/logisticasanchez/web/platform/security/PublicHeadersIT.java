package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PUB-05: every public route sends exactly the header set and CSP of
 * {@code legacy/ls-home/public/_headers} (a verbatim copy ships as the test resource
 * {@code security/ls-home-public_headers} so CI needs no legacy checkout; when the checkout
 * is present the copy is compared with it, so a drift fails here), with
 * {@code connect-src 'self'}; caching per PD-02: {@code /fonts/*} immutable one year,
 * {@code /img/*} and {@code /*.png} one week, HTML/CSS/JS {@code no-cache} + ETag answering 304.
 * HSTS enabled as in production.
 */
@Tag("PUB-05")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@TestPropertySource(properties = "ls.security.hsts.enabled=true")
class PublicHeadersIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "{0} carries the _headers set")
    @ValueSource(strings = {"/fixture/public", "/css/test.css", "/fonts/test.woff2", "/img/test.png", "/test.png"})
    void publicRoutesCarryTheHeaderSetOfTheStaticSite(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        var headers = legacyHeaders();
        for (var expected : headers.entrySet()) {
            String name = expected.getKey();
            String value = expected.getValue();
            if (name.equals("Content-Security-Policy")) {
                value = value.replace("connect-src 'self' https://app.logisticasanchez.com", "connect-src 'self'");
            }
            assertThat(result.getResponse().getHeader(name))
                    .as("%s on %s", name, path)
                    .isEqualTo(value);
        }
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval")
                .contains("'inline-speculation-rules'")
                .contains("frame-src https://www.google.com https://maps.google.com");
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .as("public pages are cookie-free")
                .isNull();
    }

    @Test
    void cachingRulesOfPd02() throws Exception {
        mvc.perform(get("/fonts/test.woff2"))
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
        mvc.perform(get("/img/test.png")).andExpect(header().string("Cache-Control", "public, max-age=604800"));
        mvc.perform(get("/test.png")).andExpect(header().string("Cache-Control", "public, max-age=604800"));
        mvc.perform(get("/css/test.css")).andExpect(header().string("Cache-Control", "no-cache"));
        mvc.perform(get("/fixture/public")).andExpect(header().string("Cache-Control", "no-cache"));
    }

    @ParameterizedTest(name = "{0} answers 304 on If-None-Match")
    @ValueSource(strings = {"/fixture/public", "/css/test.css"})
    void htmlCssAndJsCarryAnEtagAndAnswer304(String path) throws Exception {
        MvcResult first = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).as("ETag on %s", path).isNotBlank();
        mvc.perform(get(path).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
    }

    /** The {@code /*} block of {@code public/_headers} as name → value. */
    private static java.util.Map<String, String> legacyHeaders() throws Exception {
        List<String> lines;
        try (var in = PublicHeadersIT.class.getResourceAsStream("/security/ls-home-public_headers")) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
        Path legacy = Path.of("legacy/ls-home/public/_headers");
        if (Files.isRegularFile(legacy)) {
            assertThat(Files.readAllLines(legacy, StandardCharsets.UTF_8))
                    .as("test copy of ls-home public/_headers drifted from the checkout")
                    .isEqualTo(lines);
        }
        var headers = new java.util.LinkedHashMap<String, String>();
        boolean inGlobal = false;
        for (String line : lines) {
            if (!line.startsWith(" ")) {
                inGlobal = line.strip().equals("/*");
                continue;
            }
            if (inGlobal) {
                int colon = line.indexOf(':');
                headers.put(
                        line.substring(0, colon).strip(),
                        line.substring(colon + 1).strip());
            }
        }
        assertThat(headers).hasSize(6);
        return headers;
    }
}
