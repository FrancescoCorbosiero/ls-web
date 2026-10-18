package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.web.HtmxConfig;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-02 (chapter 10 §4, 06 §8.5, ADR 0018): the customer area and back office send a CSP
 * without {@code unsafe-inline}/{@code unsafe-eval} with a per-request nonce that the page's
 * inline script carries, {@code frame-ancestors 'none'}, {@code Cache-Control: no-store}, and
 * the HTMX configuration meta with the CSRF token.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class PrivateHeadersIT extends AbstractPostgresIT {

    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9+/=]+)'");

    @Autowired
    private MockMvc mvc;

    @Test
    void privatePagesSendTheStrictCspWithANonceAndNoStore() throws Exception {
        MvcResult result = mvc.perform(get("/area-clienti/fixture").with(user("c").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp)
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval")
                .contains("frame-ancestors 'none'")
                .contains("base-uri 'none'")
                .contains("object-src 'none'")
                .contains("default-src 'self'");
        Matcher nonce = NONCE.matcher(csp);
        assertThat(nonce.find()).as("nonce in %s", csp).isTrue();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("nonce=\"" + nonce.group(1) + "\"");
        assertThat(result.getResponse().getHeader("ETag")).as("no ETag on private pages").isNull();

        String second = mvc.perform(get("/area-clienti/fixture").with(user("c").roles("CUSTOMER")))
                .andReturn()
                .getResponse()
                .getHeader("Content-Security-Policy");
        assertThat(second).as("a new nonce per request").isNotEqualTo(csp);
    }

    @Test
    void theHtmxFragmentConfiguresHtmxForTheCspAndCarriesTheCsrfToken() throws Exception {
        String html = mvc.perform(get("/area-clienti/fixture").with(user("c").roles("CUSTOMER")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Matcher config = Pattern.compile("<meta name=\"htmx-config\" content=\"([^\"]+)\">").matcher(html);
        assertThat(config.find()).as("htmx-config meta in %s", html).isTrue();
        JsonNode parsed = new ObjectMapper().readTree(config.group(1).replace("&quot;", "\""));
        assertThat(parsed.get("includeIndicatorStyles").asBoolean()).isFalse();
        assertThat(parsed.get("allowEval").asBoolean()).isFalse();
        assertThat(parsed.get("allowScriptTags").asBoolean()).isFalse();
        assertThat(parsed.get("selfRequestsOnly").asBoolean()).isTrue();
        assertThat(config.group(1).replace("&quot;", "\"")).isEqualTo(HtmxConfig.META_CONTENT);
        Matcher csrf = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\">").matcher(html);
        assertThat(csrf.find()).isTrue();
        assertThat(html).contains("hx-headers=\"{&quot;X-CSRF-TOKEN&quot;: &quot;" + csrf.group(1) + "&quot;}\"");
        assertThat(html).doesNotContain("style=\"");
    }
}
