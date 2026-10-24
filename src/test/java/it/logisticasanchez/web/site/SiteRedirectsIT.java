package it.logisticasanchez.web.site;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PUB-04 (ADR 0013), table-driven: the ten {@code _redirects} rules, {@code /index.html} → {@code /},
 * every page's URL without the trailing slash (chapter 06 §6.8) and its {@code index.html} form
 * answer 301 with the right {@code Location}, keeping the query string; the {@code /mono/*} proxy
 * line is not a redirect (the app answers those paths, API-03); the host rules are
 * {@code HostRoutingIT}; the legacy anchors ({@code /#tracking}) are client-side and stay on the home.
 */
@Tag("PUB-04")
@SpringBootTest
@AutoConfigureMockMvc
class SiteRedirectsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    static List<Map.Entry<String, String>> table() {
        return List.copyOf(SiteRedirectFilter.buildTable().entrySet());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("table")
    void everyRuleAnswers301ToItsTarget(Map.Entry<String, String> rule) throws Exception {
        mvc.perform(get(rule.getKey()))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", rule.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "/about.html, /chi-siamo/",
        "/service.html, /servizi/",
        "/contact.html, /contatti/",
        "/about, /chi-siamo/",
        "/service, /servizi/",
        "/contact, /contatti/",
        "/servizi.html, /servizi/",
        "/contatti.html, /contatti/",
        "/tracking.html, /tracking/",
        "/preventivo.html, /preventivo/",
        "/index.html, /",
        "/zone, /zone/",
        "/servizi/groupage, /servizi/groupage/",
        "/blog/misure-pallet-epal-industriale, /blog/misure-pallet-epal-industriale/",
        "/zone/index.html, /zone/"
    })
    void theRulesOfChapter06AreInTheTable(String from, String to) throws Exception {
        mvc.perform(get(from)).andExpect(status().isMovedPermanently()).andExpect(header().string("Location", to));
    }

    @Test
    void theQueryStringTravelsWithTheRedirect() throws Exception {
        mvc.perform(get("/tracking").queryParam("code", "LS-2024-0118"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/tracking/?code=LS-2024-0118"));
        mvc.perform(get("/zone").queryParam("cap", "20831"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/zone/?cap=20831"));
    }

    @Test
    void theMonoProxyLineIsNotARedirectAndThePagesThemselvesAreNot() throws Exception {
        mvc.perform(get("/mono/api/enumeration/statoTracking")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/zone/")).andExpect(status().isOk());
        mvc.perform(post("/about")).andExpect(status().isNotFound());
    }
}
