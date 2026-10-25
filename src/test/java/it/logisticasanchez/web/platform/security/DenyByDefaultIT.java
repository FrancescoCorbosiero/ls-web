package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * NFR-02, ADR 0002: deny by default. Private-area routes without a session redirect to the
 * area's sign-in (HTMX/JSON callers get 401); a declared route with the wrong role answers 403;
 * an undeclared path answers 404 for everyone; nothing answers 500; a public route inside a
 * private area is refused at start-up.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class DenyByDefaultIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousRequestsToThePrivateAreasAreSentToTheSignInPage() throws Exception {
        mvc.perform(get("/gestione/fixture").accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/gestione/qualsiasi/cosa/").accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
        mvc.perform(get("/area-clienti/fixture").accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        mvc.perform(get("/area-clienti/ordini/").accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
    }

    @Test
    void anonymousHtmxOrJsonRequestsToThePrivateAreasGet401Json() throws Exception {
        mvc.perform(get("/gestione/fixture").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.reason").value("Non disponi delle autorizzazioni necessarie per procedere."));
        mvc.perform(get("/area-clienti/fixture").accept("application/json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void undeclaredPathsAnswer404ForEveryone() throws Exception {
        mvc.perform(get("/non-esiste/").accept("text/html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/non-esiste"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.reason").value("Impossibile recuperare i dati richiesti."));
        mvc.perform(get("/gestione/non-esiste")
                        .with(user("op").roles("OPERATOR"))
                        .accept("text/html"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/area-clienti/non-esiste")
                        .with(user("c").roles("CUSTOMER"))
                        .accept("text/html"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/non-esiste").with(user("a").roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void theWrongRoleAnswers403AndTheRightOne200() throws Exception {
        mvc.perform(get("/gestione/fixture").with(user("c").roles("CUSTOMER")).accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/gestione/fixture").with(user("op").roles("OPERATOR"))).andExpect(status().isOk());
        mvc.perform(get("/gestione/fixture/admin")
                        .with(user("op").roles("OPERATOR"))
                        .accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/gestione/fixture/admin").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/area-clienti/fixture").with(user("a").roles("ADMIN")).accept("text/html"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/area-clienti/fixture").with(user("c").roles("CUSTOMER")))
                .andExpect(status().isOk());
    }

    @Test
    void refusalsCarryNoFrameworkPageOrStackTrace() throws Exception {
        MvcResult forbidden = mvc.perform(get("/gestione/fixture")
                        .with(user("c").roles("CUSTOMER"))
                        .accept("text/html"))
                .andReturn();
        String body = forbidden.getResponse().getContentAsString();
        assertThat(body)
                .contains("Operazione non consentita")
                .doesNotContain("Whitelabel")
                .doesNotContain("Exception");
        assertThat(forbidden.getResponse().getContentType()).startsWith("text/html");
    }

    @Test
    void aPublicRouteInsideAPrivateAreaIsRefusedAtStartUp() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RouteRegistry(java.util.List.of(r -> {
                    r.permitGet("/gestione/report/");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/gestione/report/");
    }
}
