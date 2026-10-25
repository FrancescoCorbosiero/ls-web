package it.logisticasanchez.web.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PUB-04 host rules (plan §4.3, Q-30, Q-47): {@code www} → 301 to the apex keeping path and
 * query (the fragment stays client-side); {@code ordini.} → 301 {@code /area-clienti/};
 * {@code dashboard.} → 301 {@code /gestione/}; the apex and {@code app.} serve the same app.
 */
@Tag("PUB-04")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class HostRoutingIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void wwwRedirectsToTheApexKeepingPathAndQuery() throws Exception {
        mvc.perform(on("www.logisticasanchez.com", "/zone/lombardia/").queryParam("cap", "20831"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://logisticasanchez.com/zone/lombardia/?cap=20831"));
        mvc.perform(on("WWW.logisticasanchez.com", "/"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://logisticasanchez.com/"));
    }

    @Test
    void theOldPortalAndCmsHostsRedirectToTheNewAreas() throws Exception {
        mvc.perform(on("ordini.logisticasanchez.com", "/qualsiasi/pagina"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://logisticasanchez.com/area-clienti/"));
        mvc.perform(on("dashboard.logisticasanchez.com", "/"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://logisticasanchez.com/gestione/"));
    }

    @Test
    void theApexAndAppHostsServeTheSameApp() throws Exception {
        mvc.perform(on("logisticasanchez.com", "/fixture/public")).andExpect(status().isOk());
        mvc.perform(on("app.logisticasanchez.com", "/fixture/public")).andExpect(status().isOk());
        mvc.perform(on("localhost", "/fixture/public")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder on(String host, String path) {
        return get(path).with(request -> {
            request.setServerName(host);
            request.addHeader("Host", host);
            return request;
        });
    }
}
